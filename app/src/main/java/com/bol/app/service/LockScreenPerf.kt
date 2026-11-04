package com.bol.app.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel

/**
 * Encapsulates the three OS-level levers that keep voice categorization
 * fast on a locked phone in pocket:
 *
 *  1. **PARTIAL_WAKE_LOCK** — keeps the CPU governor from dropping clock
 *     when the screen turns off. Without this, Pass-1 inference stretches
 *     from ~8 s in foreground to 25-40 s on a locked phone because the
 *     interactive/schedutil governor sees no UI activity and clocks down.
 *
 *  2. **PerformanceHintManager session** (API 33+) — signals "this is
 *     interactive-class work" to the kernel scheduler. Modern OEMs bump
 *     the CPU governor in response. Free latency win on flagship devices,
 *     no-op on older ones.
 *
 *  3. **Thermal status sample** — log it at start so we can correlate
 *     slow runs with thermal pressure when reading user-submitted logs.
 *     Doesn't actively defer work; the OS will throttle anyway, no point
 *     adding a layer of "let me throttle on top of OS throttling."
 *
 * Lifetime is tied to [BolProcessingService] — acquire when service
 * starts, release on stop / cancel / destroy. Re-entrant-safe via the
 * `held` guard so duplicate acquire calls don't leak ref counts.
 */
class LockScreenPerf(private val context: Context) {

    private var wakeLock: PowerManager.WakeLock? = null
    private var hintSession: android.os.PerformanceHintManager.Session? = null
    private var held = false

    // Synchronized: a run ending on one thread and the next run starting on another both touch [held].
    @Synchronized
    fun acquire() {
        if (held) return
        held = true

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // Wake lock — referenceCounted false so a paired release() always
        // brings it down regardless of how many acquire() calls landed.
        runCatching {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Bol::ProcessingService",
            ).apply {
                setReferenceCounted(false)
                acquire(MAX_HOLD_MS)
            }
        }.onFailure { e ->
            BolLogger.log(LogLevel.WARN, LogCategory.QUEUE, "wake_lock_acquire_failed",
                "Could not acquire PARTIAL_WAKE_LOCK — locked-phone Pass 1 will be slower. ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }

        // Performance hint session — API 33+. Target a 50 ms work duration
        // per "frame" (a generic indicator that this is responsiveness-
        // critical work). Pixel/Samsung/OnePlus respond to this by bumping
        // the CPU governor. Older OS / non-supporting OEMs return null
        // gracefully.
        //
        // CRITICAL: Process.myTid() must return the thread that will run
        // the model inference, NOT the main thread. The kernel boost is
        // thread-scoped — register the wrong TID and it's a no-op. Caller
        // is responsible for invoking acquire() FROM the inference worker
        // thread (i.e., from inside the launched coroutine on
        // Dispatchers.Default), not from onStartCommand on the main thread.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val phm = context.getSystemService(android.os.PerformanceHintManager::class.java)
                hintSession = phm?.createHintSession(intArrayOf(android.os.Process.myTid()), 50_000_000L)
            }.onFailure { e ->
                BolLogger.log(LogLevel.DEBUG, LogCategory.QUEUE, "perf_hint_unavailable",
                    "PerformanceHintManager unavailable — ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            }
        }

        // Thermal sample — informational. Helps post-mortem slow-run reports.
        val thermalStatus = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else -1
        }.getOrDefault(-1)
        BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.QUEUE, "lockscreen_perf_acquired",
            "Acquired wake lock + perf hint for voice processing. Thermal status: ${thermalLabel(thermalStatus)} ($thermalStatus). Hint session: ${if (hintSession != null) "active" else "unavailable"}",
            extras = mapOf(
                "thermalStatus" to thermalStatus.toString(),
                "perfHintActive" to (hintSession != null).toString(),
            ))
    }

    /**
     * Feedback for the perf hint session. Without this, the kernel only
     * knows we *want* a 50 ms target — it doesn't know whether our actual
     * work is meeting that target or struggling. Calling this after each
     * unit of inference work lets the OS see "actual is way over target"
     * (a Pass-1 takes ~8 s on GPU, ~12 s on CPU vs the 50 ms target) and
     * bump CPU frequency in response.
     *
     * No-op when the session is unavailable (API < 33 or stub OEMs).
     * Caller passes nanoseconds — the API takes nanos, not millis.
     */
    fun reportWorkDuration(actualNanos: Long) {
        if (!held) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { hintSession?.reportActualWorkDuration(actualNanos) }
        }
    }

    @Synchronized
    fun release() {
        if (!held) return
        held = false
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { hintSession?.close() }
            hintSession = null
        }
        BolLogger.log(LogLevel.INFO, LogCategory.QUEUE, "lockscreen_perf_released",
            "Released wake lock + perf hint")
    }

    private fun thermalLabel(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        -1 -> "unsupported (API < Q)"
        else -> "unknown($status)"
    }

    companion object {
        /** Safety cap on the wake lock — the service should release well
         *  before this fires, but a max-hold prevents a leaked lock from
         *  draining the battery indefinitely if a code path forgets to
         *  call release(). 15 minutes covers worst-case legitimate drain
         *  (5 back-to-back recordings on a tier-3 device that's already
         *  thermally throttled, plus a search that waits 10 min for
         *  indexing to finish before its own Pass 1 + reranker). If a run
         *  legitimately exceeds this, the lock auto-releases mid-flight
         *  and the slow path returns silently — bump the cap if telemetry
         *  shows that's a real problem. */
        private const val MAX_HOLD_MS = 15L * 60 * 1000
    }
}
