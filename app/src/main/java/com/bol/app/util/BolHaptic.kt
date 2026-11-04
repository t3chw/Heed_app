package com.bol.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Robust haptic helper. Uses the Vibrator service directly (not
 * View.performHapticFeedback / LocalHapticFeedback) so the buzz fires
 * on every device regardless of the user's "touch vibration" system
 * toggle. That toggle gates the View-level haptic helpers but not
 * direct Vibrator calls, and we treat explicit recording-state
 * feedback as deliberate user feedback — distinct from generic touch
 * feedback.
 *
 * API-level strategy:
 * - API 31+: VibratorManager.defaultVibrator
 * - API 26-30: Vibrator service (deprecated accessor, still works)
 * - API 29+ effect: EFFECT_CLICK / EFFECT_HEAVY_CLICK predefined
 * - API 26-28 effect: createOneShot with DEFAULT_AMPLITUDE
 * - API <26: deprecated vibrate(ms) call
 *
 * On devices without a vibrator (some tablets, emulators), this
 * silently no-ops.
 */
object BolHaptic {

    /** Short crisp tap — use for mic-record start. */
    fun tap(context: Context) = vibrate(context, duration = 20L, heavy = false)

    /** Slightly stronger pulse — use for mic-record stop / confirm. */
    fun confirm(context: Context) = vibrate(context, duration = 35L, heavy = true)

    private fun vibrate(context: Context, duration: Long, heavy: Boolean) {
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                val effect = VibrationEffect.createPredefined(
                    if (heavy) VibrationEffect.EFFECT_HEAVY_CLICK
                    else VibrationEffect.EFFECT_CLICK
                )
                vibrator.vibrate(effect)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                val effect = VibrationEffect.createOneShot(
                    duration,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                )
                vibrator.vibrate(effect)
            }
            else -> {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
