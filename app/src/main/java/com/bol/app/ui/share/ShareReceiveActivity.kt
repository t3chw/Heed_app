package com.bol.app.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bol.app.data.preferences.ThemeMode
import com.bol.app.data.repository.VoiceQueueRepository
import com.bol.app.service.BolProcessingService
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedCard
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.SheetHeader
import com.bol.app.ui.components.heedTextFieldColors
import com.bol.app.ui.theme.BolTheme
import com.bol.app.ui.theme.HeedTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Handles `ACTION_SEND` with `text/plain`. Shows a minimal bottom sheet:
 * preview of the shared text + optional extra note + Save.
 *
 * On Save the text is enqueued via [VoiceQueueRepository.enqueueText] (skipping
 * Whisper) and [BolProcessingService] is kicked so the LLM picks it up.
 * Activity finishes immediately so the user stays in their previous app.
 */
@OptIn(ExperimentalMaterial3Api::class)
class ShareReceiveActivity : ComponentActivity() {

    private val voiceQueueRepository: VoiceQueueRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = extractSharedText(intent)
        if (sharedText.isBlank()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            BolTheme(themeMode = ThemeMode.SYSTEM) {
                ShareSheet(
                    initialText = sharedText,
                    onDismiss = { finish() },
                    onSave = { finalText ->
                        saveAndClose(finalText)
                    },
                )
            }
        }
    }

    private fun extractSharedText(intent: Intent): String {
        if (intent.action != Intent.ACTION_SEND) return ""
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        // Subject (if any) is usually the page title for a shared URL — prepend it
        // so the LLM has a richer signal.
        return if (!subject.isNullOrBlank() && !text.contains(subject)) {
            "$subject\n\n$text".trim()
        } else text.trim()
    }

    private fun saveAndClose(text: String) {
        if (text.isBlank()) { finish(); return }
        // Enqueue happens inside lifecycleScope, and crucially we call finish()
        // *after* the suspend calls return — otherwise finish() cancels the scope
        // and the DB insert is lost silently. The enqueue is a single local DB
        // write so the extra couple of milliseconds are imperceptible.
        lifecycleScope.launch {
            try {
                voiceQueueRepository.enqueueText(text)
                BolProcessingService.startProcessing(applicationContext)
                Toast.makeText(this@ShareReceiveActivity, "Added to Heed", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this@ShareReceiveActivity, "Couldn't save", Toast.LENGTH_SHORT).show()
            } finally {
                finish()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = HeedTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            SheetHeader(title = "Add to Heed")
            HeedCard {
                Text(
                    text = "\"${initialText.take(500)}${if (initialText.length > 500) "…" else ""}\"",
                    style = MaterialTheme.typography.bodyLarge,
                    color = HeedTheme.colors.textPrimary,
                    modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Add a note (optional)") },
                singleLine = false,
                maxLines = 3,
                shape = HeedLayout.ControlShape,
                colors = heedTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                HeedButton(
                    "Cancel",
                    onClick = { scope.launch { sheetState.hide(); onDismiss() } },
                    style = HeedButtonStyle.Text,
                )
                HeedButton(
                    "Save",
                    onClick = {
                        val finalText = if (note.isBlank()) initialText
                                        else "$initialText\n\nnote: $note"
                        onSave(finalText)
                    },
                    style = HeedButtonStyle.Filled,
                )
            }
        }
    }

    // Auto-show the sheet on activity launch.
    LaunchedEffect(Unit) { sheetState.show() }
}

