package com.bol.app.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bol.app.BuildConfig
import com.bol.app.ai.model.ModelState
import com.bol.app.data.preferences.ThemeMode
import com.bol.app.ui.components.BolMood
import com.bol.app.ui.components.BolSwitch
import com.bol.app.ui.components.CalloutTone
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedCallout
import com.bol.app.ui.components.HeedCard
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.PageHeader
import com.bol.app.ui.components.PanelDivider
import com.bol.app.ui.components.PanelRow
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.components.Segmented
import com.bol.app.ui.theme.HeedTheme
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

// Public URL where the privacy policy is hosted. Pointed at the
// current Vercel deployment until/unless a custom domain is attached.
// The same URL is referenced from the Play Console store-listing
// privacy policy field, so update both in lockstep if it changes.
private const val LOW_RAM_NOTICE_MB = 6000
private const val PRIVACY_POLICY_URL = "https://heed-xi.vercel.app/privacy"

@Composable
fun SettingsScreen(
    onNavigateToLogs: () -> Unit = {},
    onNavigateToScheduledReminders: () -> Unit = {},
    onNavigateToRestore: () -> Unit = {},
    onNavigateToOssLicenses: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel()
) {
    val modelState by viewModel.modelState.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val timeFormat by viewModel.timeFormat.collectAsState(initial = com.bol.app.data.preferences.PreferencesManager.TIME_FORMAT_12H)
    val format24h = timeFormat == com.bol.app.data.preferences.PreferencesManager.TIME_FORMAT_24H
    val devModeEnabled by viewModel.devModeEnabled.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // SAF picker for the export destination. We hand the resulting
    // OutputStream straight to the export use-case so the codec can
    // stream the ZIP directly to the user-picked location — no
    // temp file, no in-memory full-bundle copy. Errors surface as a
    // Toast; the SAF document is left in place if write fails partway
    // (the user can re-export to overwrite it).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val manifest = context.contentResolver.openOutputStream(uri)?.use { out ->
                    viewModel.exportTo(out)
                } ?: error("Couldn't open output stream for backup file")
                val items = manifest.counts.items
                val recordings = manifest.counts.recordings
                val msg = "Backup saved · $items item${if (items == 1) "" else "s"}, " +
                    "$recordings recording${if (recordings == 1) "" else "s"}"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "Backup failed: ${e.message ?: "unknown error"}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HeedLayout.Gutter)
    ) {
        PageHeader(eyebrow = "Settings", title = "Your Heed.", accent = "make it yours.", mood = BolMood.Sleeping)

        // ---- Appearance ----
        SectionLabel("Appearance")
        HeedCard {
            PanelRow(label = "Theme", right = {
                ThemeSegmented(
                    value = themeMode,
                    onChange = { viewModel.setThemeMode(it) },
                )
            })
            // Language row was removed — the app is English-only, so a
            // single-option chip pair was dead UI. The VM's language flow
            // is kept intact in case multilingual support returns.
            PanelDivider()
            PanelRow(label = "Time format", right = {
                Segmented(
                    options = listOf("12-hour", "24-hour"),
                    selectedIndex = if (format24h) 1 else 0,
                    onSelect = { i ->
                        viewModel.setTimeFormat(
                            if (i == 1) com.bol.app.data.preferences.PreferencesManager.TIME_FORMAT_24H
                            else com.bol.app.data.preferences.PreferencesManager.TIME_FORMAT_12H
                        )
                    },
                )
            })
        }

        // ---- Notifications ----
        SectionLabel("Notifications")
        val digestEnabled by viewModel.digestEnabled.collectAsState(initial = false)
        val digestTime by viewModel.digestTime.collectAsState(initial = "08:00")
        val notifyOnWorkDone by viewModel.notifyOnWorkDone.collectAsState(initial = false)
        val defaultReminderHour by viewModel.defaultReminderHour.collectAsState(
            initial = com.bol.app.data.preferences.PreferencesManager.DEFAULT_REMINDER_HOUR_VALUE,
        )
        var showTimePicker by remember { mutableStateOf(false) }
        var showReminderHourPicker by remember { mutableStateOf(false) }
        // Android 13+ asks at run time, and nothing else in Settings did, so a digest switched on with notifications off
        // never showed. Re-checked on resume, like the exact-alarm row.
        var notificationsOn by remember { mutableStateOf(notificationsAllowed(context)) }
        val notificationsLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(notificationsLifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) notificationsOn = notificationsAllowed(context)
            }
            notificationsLifecycle.lifecycle.addObserver(observer)
            onDispose { notificationsLifecycle.lifecycle.removeObserver(observer) }
        }
        // Only the Notifications row sends the user on to Android's page after a refusal; a switch just asks.
        var openPageIfRefused by remember { mutableStateOf(false) }
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsOn = notificationsAllowed(context)
            if (!granted && openPageIfRefused) openNotificationSettings(context)
        }
        val turnOnNotifications: (Boolean) -> Unit = { fromRow ->
            openPageIfRefused = fromRow
            val canAsk = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (canAsk) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else if (fromRow) openNotificationSettings(context)
        }
        HeedCard {
            PanelRow(
                label = "Notifications",
                sub = if (notificationsOn) "Allowed — reminders and the digest can reach you"
                      else "Off — reminders and the digest can't reach you",
                right = {
                    if (notificationsOn) {
                        Text("On", style = MaterialTheme.typography.bodyMedium.copy(color = HeedTheme.colors.textMuted))
                    } else {
                        HeedButton("Allow", onClick = { turnOnNotifications(true) }, style = HeedButtonStyle.Text)
                    }
                },
            )
            PanelDivider()
            PanelRow(
                label = "Notify when work is done",
                sub = "A ping when a voice note's tasks are saved. Off = quiet — only reminders and the digest interrupt you.",
                right = {
                    BolSwitch(
                        checked = notifyOnWorkDone,
                        onToggle = {
                            if (!notifyOnWorkDone && !notificationsOn) turnOnNotifications(false)
                            viewModel.setNotifyOnWorkDone(!notifyOnWorkDone)
                        },
                    )
                },
            )
            PanelDivider()
            PanelRow(
                label = "Morning digest",
                sub = "A quick summary of today's reminders + open items",
                right = {
                    BolSwitch(
                        checked = digestEnabled,
                        onToggle = {
                            if (!digestEnabled && !notificationsOn) turnOnNotifications(false)
                            viewModel.setDigestEnabled(!digestEnabled)
                        },
                    )
                },
            )
            if (digestEnabled) {
                PanelDivider()
                PanelRow(
                    label = "Digest time",
                    right = {
                        Text(
                            text = formatHhMmForDisplay(digestTime, format24h),
                            style = MaterialTheme.typography.bodyMedium.copy(color = HeedTheme.colors.textMuted),
                        )
                    },
                    onClick = { showTimePicker = true },
                )
            }
            PanelDivider()
            PanelRow(
                label = "Morning reminder time",
                sub = "Used by the Tomorrow morning and Next Monday choices",
                right = {
                    Text(
                        text = formatHourForDisplay(defaultReminderHour, format24h),
                        style = MaterialTheme.typography.bodyMedium.copy(color = HeedTheme.colors.textMuted),
                    )
                },
                onClick = { showReminderHourPicker = true },
            )
            PanelDivider()
            ExactAlarmRow()
            PanelDivider()
            PanelRow(
                label = "Scheduled reminders",
                sub = "See every upcoming reminder across all categories",
                onClick = onNavigateToScheduledReminders,
            )
        }
        if (showTimePicker) {
            DigestTimePickerDialog(
                initial = digestTime,
                format24h = format24h,
                onDismiss = { showTimePicker = false },
                onConfirm = { newTime ->
                    viewModel.setDigestTime(newTime)
                    showTimePicker = false
                },
            )
        }
        if (showReminderHourPicker) {
            ReminderHourPickerDialog(
                initialHour = defaultReminderHour,
                format24h = format24h,
                onDismiss = { showReminderHourPicker = false },
                onConfirm = { newHour ->
                    viewModel.setDefaultReminderHour(newHour)
                    showReminderHourPicker = false
                },
            )
        }

        // ---- Capture & background ----
        SectionLabel("Capture & background")
        HeedCard {
            // Background-activity whitelist. On heavy-OEM phones (Oppo,
            // Xiaomi, Vivo, OnePlus, Huawei, Realme), the OS aggressively
            // throttles foreground services — even with PARTIAL_WAKE_LOCK
            // and PerformanceHintManager, the CPU governor drops to a
            // crawl when the screen is off. Locked-phone Pass-1 stretches
            // from ~12s (foreground) to >180s without this whitelist.
            // Status re-checked on every resume so a user who flips it
            // outside the app sees the new state on return.
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            var batteryOptIgnored by remember(context) {
                mutableStateOf(checkBatteryOptimizationStatus(context))
            }
            DisposableEffect(lifecycleOwner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        batteryOptIgnored = checkBatteryOptimizationStatus(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }
            val isHeavyOem = remember { isHeavyThrottleOem() }
            PanelRow(
                label = "Allow background processing",
                sub = if (batteryOptIgnored) {
                    "✓ Heed can run unthrottled when the screen is off."
                } else if (isHeavyOem) {
                    "Tap to allow — Oppo / Xiaomi / Vivo throttle background apps hard. Lock-screen voice notes can take 10× longer without this."
                } else {
                    "Tap to allow Heed to keep the CPU at clock when the screen is off."
                },
                right = {
                    Text(
                        if (batteryOptIgnored) "Allowed" else "Restricted",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (batteryOptIgnored) HeedTheme.colors.brand else HeedTheme.colors.textMuted,
                        ),
                    )
                },
                onClick = {
                    val opened = openBatteryOptimizationSettings(context)
                    if (!opened) {
                        Toast.makeText(
                            context,
                            "Couldn't open settings — look for Settings → Battery → Battery optimization.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else if (isHeavyOem) {
                        // ColorOS / MIUI ignore the standard whitelist alone — push
                        // a guidance toast about the secondary "Background activity"
                        // toggle that lives under App info.
                        Toast.makeText(
                            context,
                            "After whitelisting: also open App info → Battery → set to ‘Don't optimize / No restrictions / Allow background activity’.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            )
            PanelDivider()
            // The headline feature: Heed-as-Assistant. Long-press power
            // anywhere → record → done. Routes the user to the system
            // settings page where they pick the default Digital Assistant;
            // we can't flip it programmatically (rightly), but we can save
            // them three menu taps with a direct link.
            PanelRow(
                label = "Make Heed your assistant",
                sub = "Hold power and capture from any screen — even when locked.",
                onClick = {
                    val opened = openAssistantSettings(context)
                    if (!opened) {
                        Toast.makeText(
                            context,
                            "Couldn't open settings — look for Default apps → Digital assistant.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            )
            PanelDivider()
            // Hidden affordance hint — the only place we tell users about
            // the long-press-to-type gesture (besides the one-shot tooltip
            // on Home). Power users find it; the brand stays voice-first.
            PanelRow(
                label = "Tip: long-press the mic to type",
                sub = "Useful in meetings, libraries, or whenever you can't speak.",
            )
        }

        // ---- Intelligence (on-device models) ----
        SectionLabel("Intelligence")
        HeedCard {
            PanelRow(label = "Task model", sub = "Runs on this phone", right = {
                Text("Qwen 0.6B", style = MaterialTheme.typography.bodyMedium.copy(color = HeedTheme.colors.textMuted))
            })
            PanelDivider()
            PanelRow(label = "Voice to text", sub = "Runs on this phone", right = {
                Text("Granite 470M", style = MaterialTheme.typography.bodyMedium.copy(color = HeedTheme.colors.textMuted))
            })
            PanelDivider()
            PanelRow(label = "Search", sub = "Runs on this phone", right = {
                Text("EmbeddingGemma 300M", style = MaterialTheme.typography.bodyMedium.copy(color = HeedTheme.colors.textMuted))
            })
            if (viewModel.deviceTotalRamMb < LOW_RAM_NOTICE_MB) {
                PanelDivider()
                PanelRow(label = "Lower memory phone", sub = "Heed works here, but notes take longer to process.")
            }
        }

        // ---- Data ----
        SectionLabel("Data")
        HeedCard {
            PanelRow(
                label = "Export backup",
                sub = "Tasks, voice-note transcripts, categories and settings. Audio isn't included.",
                onClick = {
                    // Local-time stamp in the filename so users sorting by
                    // name in their file manager get a sensible order.
                    val stamp = java.text.SimpleDateFormat(
                        "yyyy-MM-dd-HHmm",
                        java.util.Locale.US,
                    ).format(java.util.Date())
                    exportLauncher.launch("heed-backup-$stamp.heedbackup")
                },
            )
            PanelDivider()
            PanelRow(
                label = "Restore from backup",
                onClick = onNavigateToRestore,
            )
        }

        // ---- Privacy callout ----
        SectionLabel("Privacy")
        HeedCallout(
            tone = CalloutTone.Brand,
            icon = Icons.Rounded.Lock,
            title = "Nothing leaves your phone.",
            body = "Your voice, transcripts, and items are stored only in Heed's private folder on this device. No accounts, no cloud.",
        )

        // ---- About ----
        SectionLabel("About")
        HeedCard {
            // Hidden 7-tap unlock for developer mode — same gesture
            // Android uses on its own Build number row. Enabled in
            // release too so closed-testing users can surface the log
            // viewer for crash reports. The Developer/Spike panel below
            // stays debug-only — only the View logs row is exposed in
            // release.
            var devTapCount by remember { mutableStateOf(0) }
            PanelRow(
                label = "Version",
                right = {
                    Text("${BuildConfig.VERSION_NAME} · beta", style = MaterialTheme.typography.bodyMedium.copy(color = HeedTheme.colors.textMuted))
                },
                onClick = if (devModeEnabled) null else {
                    {
                        val next = devTapCount + 1
                        devTapCount = next
                        when {
                            next >= 7 -> {
                                viewModel.setDevModeEnabled(true)
                                Toast.makeText(context, "Developer mode enabled", Toast.LENGTH_SHORT).show()
                                devTapCount = 0
                            }
                            next in 4..6 -> {
                                val remaining = 7 - next
                                Toast.makeText(
                                    context,
                                    "$remaining step${if (remaining == 1) "" else "s"} to developer mode",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                },
            )
            PanelDivider()
            PanelRow(
                label = "Open source licenses",
                sub = "Credits + license texts",
                onClick = onNavigateToOssLicenses,
            )
            PanelDivider()
            PanelRow(
                label = "Privacy policy",
                sub = "How Heed handles your data (it doesn't leave your phone)",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri()).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                },
            )
            if (devModeEnabled) {
                PanelDivider()
                PanelRow(label = "View logs", onClick = onNavigateToLogs)
            }
        }

        // ---- Developer ---- (dev-mode only, debug builds only)
        if (BuildConfig.DEBUG && devModeEnabled) {
            SectionLabel("Developer")
            HeedCard {
                PanelRow(
                    label = "Disable developer mode",
                    sub = "Hides this section. Tap Version 7× again to re-enable.",
                    right = {
                        Text("off", style = MaterialTheme.typography.bodyMedium.copy(color = HeedTheme.colors.textMuted))
                    },
                    onClick = {
                        viewModel.setDevModeEnabled(false)
                        Toast.makeText(context, "Developer mode disabled", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        // ---- Danger zone ----
        SectionLabel("Danger zone")
        val clearRunning by viewModel.clearRunning.collectAsState()
        val clearMessage by viewModel.clearMessage.collectAsState()
        var showClearDialog by remember { mutableStateOf(false) }
        HeedCard {
            PanelRow(
                label = if (clearRunning) "Clearing…" else "Clear all data",
                sub = if (clearRunning) null else "Deletes your tasks, notes and searches.",
                destructive = !clearRunning,
                onClick = { if (!clearRunning) showClearDialog = true },
            )
        }
        clearMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            HeedCallout(body = msg)
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("Clear all data?") },
                text = {
                    Column {
                        Text(
                            text = "This permanently deletes:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "• All tasks, and their reminders\n" +
                                "• All voice notes: transcripts and audio\n" +
                                "• Categories you added\n" +
                                "• Search history\n" +
                                "• Notes still waiting to be processed\n" +
                                "• Heed's logs",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Kept:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "• The on-device models\n" +
                                "• Your settings\n" +
                                "• The default categories (reset to how they started)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            viewModel.clearAllData()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = HeedTheme.colors.danger,
                        ),
                    ) { Text("Clear everything") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "made with a warm cup of chai ·",
            style = MaterialTheme.typography.bodySmall,
            color = HeedTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(80.dp))
    }
}

/**
 * Tries to land the user on the system page where they pick the default
 * Digital Assistant. The exact path varies by OEM and Android version, so
 * we try the most-direct intents first and fall through to broader pages.
 *
 * Returns true if SOME settings page opened, false if every fallback
 * threw (so the caller can surface a Toast with manual instructions).
 */
private fun openAssistantSettings(context: Context): Boolean {
    val candidates = listOf(
        // Direct: Voice Input settings page (assistant picker lives here on
        // Pixel + most stock Android variants).
        Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
        // Fallback: the Default Apps screen (Android 10+). One tap from
        // here to reach Digital assistant.
        Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        // Last resort: the top-level Settings page. User finds it manually.
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            return true
        } catch (_: Exception) {
            // try the next fallback
        }
    }
    return false
}

/**
 * True if the OS is currently treating Heed as exempt from battery
 * optimization (i.e. the user has tapped "Allow" in the system whitelist).
 * Wrapped in try/catch because PowerManager.isIgnoringBatteryOptimizations
 * can throw on weird OEM ROMs that omit the API.
 */
private fun checkBatteryOptimizationStatus(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }
}

/**
 * Tries to land the user on the battery-optimization whitelist screen for
 * Heed. Path varies wildly by OEM, so we walk a fallback chain from the
 * most-direct intent to the broadest. Returns true if any intent resolved.
 *
 * Note: ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is technically
 * Play-policy-restricted, but is the only intent that lands directly on
 * Heed's row with a single Allow/Deny prompt — without it the user has to
 * dig 4-5 menus deep on Oppo/Xiaomi. We deliberately use it here because
 * the app legitimately needs unthrottled background processing for
 * on-device AI inference (which Play exempts).
 */
private fun openBatteryOptimizationSettings(context: Context): Boolean {
    val candidates = listOf(
        // Direct, single-row prompt for THIS app. ColorOS / MIUI / Stock
        // all honor this intent on Android 6+.
        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        },
        // List of every app's optimization state. User finds Heed manually.
        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        // App info page — user navigates Battery → Battery optimization.
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        },
        // Last resort: top-level Settings.
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            return true
        } catch (_: Exception) {
            // try the next fallback
        }
    }
    return false
}

/**
 * True for OEMs whose firmware aggressively throttles background apps even
 * with a foreground service + PARTIAL_WAKE_LOCK held. On these devices the
 * battery whitelist is the single biggest factor in lock-screen inference
 * speed — without it, on-device AI Pass-1 stretches from ~12s in foreground
 * to >180s on lock. Sources: vendor docs (dontkillmyapp.com tier list) and
 * empirical lock-screen testing on Oppo CPH2717 (ColorOS 14).
 */
private fun isHeavyThrottleOem(): Boolean {
    val mfr = android.os.Build.MANUFACTURER.lowercase()
    return mfr in listOf("oppo", "xiaomi", "vivo", "oneplus", "huawei", "realme", "honor")
}

@Composable
private fun ThemeSegmented(
    value: ThemeMode,
    onChange: (ThemeMode) -> Unit,
) {
    val modes = ThemeMode.entries
    Segmented(
        options = modes.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
        selectedIndex = modes.indexOf(value),
        onSelect = { onChange(modes[it]) },
    )
}

// (sttEngineSub removed with the Voice recognition panel.)

/**
 * Minimal HH:mm picker. Uses Material3's TimePicker when available; falls back to
 * two number fields otherwise. The value is stored as a zero-padded 5-char string.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DigestTimePickerDialog(
    initial: String,
    format24h: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val parts = initial.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    val state = androidx.compose.material3.rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = format24h,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Digest time") },
        text = {
            androidx.compose.material3.TimePicker(state = state)
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val h = state.hour.toString().padStart(2, '0')
                val m = state.minute.toString().padStart(2, '0')
                onConfirm("$h:$m")
            }) { Text("Set") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Hour-only picker for the morning reminder time. We store just the hour
 * (0–23) in prefs, but the Material TimePicker always offers minutes — we
 * simply ignore the minute dial and snap the result to :00.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReminderHourPickerDialog(
    initialHour: Int,
    format24h: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = androidx.compose.material3.rememberTimePickerState(
        initialHour = initialHour.coerceIn(0, 23),
        initialMinute = 0,
        is24Hour = format24h,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Morning reminder time") },
        text = {
            Column {
                Text(
                    text = "Used by the Tomorrow morning and Next Monday choices.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = HeedTheme.colors.textMuted),
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.TimePicker(state = state)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onConfirm(state.hour)
            }) { Text("Set") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Hour-only display, respecting the user's 12h/24h preference.
 * 24h: "09:00", "14:00". 12h: "9 AM", "2 PM".
 */
/**
 * "Alarms & reminders" status. Android 14+ denies exact alarms by default for
 * new installs, and nothing used to ask for them — reminders silently fell
 * back to inexact alarms that can fire minutes late. Re-checked on resume so
 * it flips as soon as the user comes back from the system page.
 */
@Composable
private fun ExactAlarmRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var allowed by remember { mutableStateOf(com.bol.app.service.ReminderScheduler.canScheduleExact(context)) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                allowed = com.bol.app.service.ReminderScheduler.canScheduleExact(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    PanelRow(
        label = "Exact reminder times",
        sub = if (allowed) "Allowed — reminders ring on time"
              else "Not allowed — reminders may ring late",
        right = {
            if (allowed) {
                Text("On", style = MaterialTheme.typography.bodyMedium.copy(color = HeedTheme.colors.textMuted))
            } else {
                HeedButton("Allow", style = HeedButtonStyle.Text, onClick = {
                    com.bol.app.service.ReminderScheduler.exactAlarmSettingsIntent(context)?.let { intent ->
                        runCatching { context.startActivity(intent) }
                    }
                })
            }
        },
    )
}

/** App-level, so it covers reminders, the digest and the work-done ping (false on Android 13+ until the permission is granted). */
private fun notificationsAllowed(context: Context): Boolean =
    androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

/** Heed's page in Android's notification settings, for after the permission was refused. */
private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

private fun formatHourForDisplay(hour: Int, format24h: Boolean): String {
    val h = hour.coerceIn(0, 23)
    if (format24h) return String.format("%02d:00", h)
    val hour12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    val suffix = if (h < 12) "AM" else "PM"
    return "$hour12 $suffix"
}

/**
 * "HH:mm" string display, respecting the user's 12h/24h preference.
 * Input is always stored as 24h "HH:mm". 24h: pass through. 12h: "8:30 AM" / "2:15 PM".
 */
private fun formatHhMmForDisplay(hhmm: String, format24h: Boolean): String {
    if (format24h) return hhmm
    val parts = hhmm.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    val hour12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    val suffix = if (h < 12) "AM" else "PM"
    return "$hour12:${m.toString().padStart(2, '0')} $suffix"
}

// (WhisperStatusRow removed with the Voice recognition panel.)

