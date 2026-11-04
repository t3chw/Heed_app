package com.bol.app.ai

import android.app.ActivityManager
import android.content.Context

/**
 * Minimum total RAM required to run Bol on this device.
 *
 * Set to 5500 MB rather than a literal 6144 because Android's
 * [android.app.ActivityManager.MemoryInfo.totalMem] reports memory after the
 * kernel and bootloader reservations are subtracted. A "6 GB" phone typically
 * reports ~5.6–5.9 GB; a 4 GB phone reports ~3.5–3.9 GB. 5500 cleanly
 * separates the two without false-rejecting borderline 6 GB devices.
 *
 * Below this, even the model's smallest variant struggles — the user gets stuck
 * on a multi-minute Pass 1 and uninstalls. Hard block at onboarding rather
 * than letting them download 2.4 GB of weights they can't use.
 */
const val MIN_REQUIRED_RAM_MB = 5500

fun deviceTotalRamMb(context: Context): Int {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    return (mi.totalMem / (1024 * 1024)).toInt()
}
