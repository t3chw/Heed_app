package com.bol.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.bol.app.ui.theme.HeedTheme

/**
 * Edit the text of an existing item. A plain AlertDialog with a single
 * OutlinedTextField — same shape as CategoryPickerDialog / ReminderEditDialog
 * so all item-mutation dialogs feel like one family.
 *
 * Save is disabled when the trimmed text is blank or unchanged, so tapping
 * it always produces a real edit.
 */
@Composable
fun ItemTextEditDialog(
    initialText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialText) }
    val trimmed = value.trim()
    val canSave = trimmed.isNotBlank() && trimmed != initialText.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit item", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = HeedLayout.ControlShape,
                    colors = heedTextFieldColors(),
                    label = { Text("Text") },
                    singleLine = false,
                    maxLines = 4,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Edits update the item everywhere it shows — Home, category detail, search.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HeedTheme.colors.textMuted,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmed) },
                enabled = canSave,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
