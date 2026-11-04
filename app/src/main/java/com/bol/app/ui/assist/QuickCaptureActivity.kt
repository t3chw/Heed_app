package com.bol.app.ui.assist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.bol.app.ai.model.ModelManager
import com.bol.app.ai.model.ModelState
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.service.RecordingSignal
import com.bol.app.ui.theme.BolTheme
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.android.ext.android.inject

/**
 * The "summon Heed from anywhere" entry point. Receives one of:
 *   • `android.intent.action.ASSIST` — long-press power on Pixel 4+ etc.
 *   • `android.intent.action.VOICE_COMMAND`
 * once the user has set Heed as their default Digital Assistant in
 * Settings → Apps → Default apps → Digital assistant.
 *
 * Behaviour:
 *   1. Wakes the screen + shows over the lockscreen (no unlock needed).
 *   2. Asks for RECORD_AUDIO if missing (rare — the in-app flow has
 *      almost certainly granted it already).
 *   3. Starts capturing PCM the moment the screen is up.
 *   4. Auto-stops on silence or when the user taps "Done".
 *   5. Saves the WAV + enqueues into the same voice_queue the in-app
 *      MicPill uses, kicks BolProcessingService, finishes itself.
 *
 * The activity itself is short-lived and does no transcription —
 * Whisper + the model run later in the foreground service. The user can
 * pocket their phone immediately.
 */
class QuickCaptureActivity : ComponentActivity() {

    private val viewModel: QuickCaptureViewModel by viewModel()
    private val preferencesManager: PreferencesManager by inject()
    private val recordingSignal: RecordingSignal by inject()
    private val modelManager: ModelManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Concurrent-mic guard. If the in-app MicPill on Home is currently
        // recording, two AudioRecord instances would race for the mic and
        // emit a confusing "Couldn't initialise mic" — better to step out
        // of the way and let the in-app session continue. The user just
        // needs to finish the existing recording before re-summoning.
        if (recordingSignal.isRecording.value) {
            Toast.makeText(
                this,
                "Heed is already listening — finish that first.",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return
        }

        // Lock-screen visibility: show + wake screen. Without these the
        // assistant invocation just sends us to the unlocked launcher.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // KEEP_SCREEN_ON is its own thing — required on every API. Without
        // it the system's screen-timeout (often 10–30 s) trips while the
        // user is mid-thought, the screen blanks, and recording ends with
        // a half-thought captured. Holds only while THIS window is the
        // top window — released the moment we finish, so battery / wake
        // posture returns to normal once the user is done.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            // Read the user's app theme pref (Light/Dark/System) so the
            // capture sheet matches their normal Heed look. Falls through
            // to system on first read.
            val themeKey by preferencesManager.themeMode.collectAsState(
                initial = com.bol.app.data.preferences.ThemeMode.SYSTEM.key
            )
            val themeMode = com.bol.app.data.preferences.ThemeMode.fromKey(themeKey)

            BolTheme(themeMode = themeMode) {
                val ui by viewModel.ui.collectAsState()
                val rec by viewModel.recorderState.collectAsState()

                // Permission gate. Most of the time RECORD_AUDIO is already
                // granted (the in-app MicPill flow asks for it on first
                // tap). If somehow it isn't, we ask once; deny finishes
                // the activity immediately — no nagging.
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        viewModel.startRecording()
                    } else {
                        // Brief feedback so the user understands why the
                        // activity vanished — without it, denying mic
                        // permission feels like nothing happened.
                        Toast.makeText(
                            this@QuickCaptureActivity,
                            "Heed needs the mic to capture your thought.",
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    }
                }

                LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@QuickCaptureActivity,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        viewModel.startRecording()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                // Auto-finish once Done — but hold the "Saved" beat for
                // 1.2 s so the user (especially after auto-stop) has a
                // clear signal that the popup heard them and is closing.
                // Combined with the VM's 800 ms Saving floor, that's a
                // ~2 s feedback window between speech-end and dismissal.
                //
                // Model-not-ready toast: if the user invokes Quick
                // Capture before the LLM has finished downloading or
                // loading, the recording is still saved and queued —
                // BolProcessingService will drain it later — but on
                // the lock screen the user has no way to see that the
                // model isn't ready. Without this toast the worst case
                // is the user thinking their note was lost. Surfaced
                // only on Done so the toast doesn't pre-empt the
                // recording itself, and only when the model isn't
                // already Ready (the common path stays silent).
                LaunchedEffect(ui) {
                    if (ui is QuickCaptureUiState.Done) {
                        // ModelManager never reports Ready (it unpacks, then loads per call), so the old
                        // "!is Ready" check showed this on every capture. Only a failed unpack delays sorting.
                        if (modelManager.state.value is ModelState.Error) {
                            Toast.makeText(
                                this@QuickCaptureActivity,
                                "Saved — Heed will sort it once its models open.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        kotlinx.coroutines.delay(1200L)
                        finish()
                    }
                }

                QuickCaptureScreen(
                    state = ui,
                    recorderState = rec,
                    onStop = { viewModel.stopRecording() },
                    onCancel = {
                        viewModel.cancel()
                        finish()
                    },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Stop+save sits on `onStop`, not `onPause`, so transient events
        // (notification shade pulled down, system dialogs, brief overlays)
        // don't kill an active recording — those fire onPause but not
        // onStop. The cases where we WANT to stop+save (power-button
        // press, user navigated away, screen off, phone call) all fire
        // onStop reliably, with at most ~150 ms extra latency over onPause.
        //
        // The save itself runs in QuickCaptureSaver's app-scoped coroutine,
        // so it always completes even though the activity is finishing.
        if (viewModel.ui.value is QuickCaptureUiState.Recording) {
            viewModel.stopRecording()
            if (!isFinishing) finish()
        }
    }
}
