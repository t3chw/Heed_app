package com.bol.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bol.app.ui.theme.HeedTheme

private const val MAX_CHARS = 1000
private const val SHOW_COUNTER_AFTER = 800

/**
 * Hidden text-entry alternative to the MicPill — opened by long-pressing
 * the pill on Home. Auto-focuses the field, raises the keyboard, and
 * routes the typed text through the same processing pipeline as a voice
 * recording (so typed entries land in History the same way).
 *
 * The sheet stays minimal so it doesn't compete with the voice-first
 * brand: short heading, multiline field, Send button. No formatting
 * tools, no attachments — typing here is for "I have a quick thought
 * and can't speak right now," not for journaling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeSheet(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    // Saveable: rotating the phone used to close the sheet and lose what was typed.
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = HeedTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            SheetHeader(title = "Type your thought", subtitle = "Heed will sort it into your buckets.")
            Spacer(Modifier.heightIn(min = 8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= MAX_CHARS) text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .focusRequester(focusRequester),
                shape = HeedLayout.ControlShape,
                colors = heedTextFieldColors(),
                placeholder = {
                    Text(
                        text = "pick up groceries, dentist 4pm, …",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HeedTheme.colors.textMuted,
                    )
                },
                singleLine = false,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                keyboardActions = KeyboardActions.Default,
            )
            Spacer(Modifier.heightIn(min = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (text.length >= SHOW_COUNTER_AFTER) {
                    Text(
                        text = "${text.length} / $MAX_CHARS",
                        style = MaterialTheme.typography.labelSmall,
                        color = HeedTheme.colors.textMuted,
                    )
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeedButton("Cancel", onClick = onDismiss, style = HeedButtonStyle.Text)
                        HeedButton(
                            "Send",
                            onClick = { onSubmit(text) },
                            style = HeedButtonStyle.Filled,
                            enabled = text.isNotBlank(),
                        )
                    }
                }
            }
        }
    }
}
