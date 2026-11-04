package com.bol.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bol.app.ui.theme.HeedTheme

/**
 * The trust-building moment when Bol asks for a system permission. Shown
 * BEFORE the OS permission dialog so the user sees Bol's explanation in
 * Bol's voice — not a dry system prompt that says nothing about why.
 *
 * The dialog packs three things into one tight modal:
 *   1. The Bol mascot at a small size, in Listening mood — a friendly
 *      "I'm here" presence.
 *   2. A short title in Display font — the ask, in plain language.
 *   3. One sentence of body copy that names the privacy guarantee
 *      (audio stays on-device for mic; only Bol fires for notifications).
 *
 * Buttons are deliberately ordered Allow / Not now (primary on the right
 * per Material guidelines) — and "Not now" rather than "Deny" so the
 * user feels free to decide later without any blame framing.
 */
@Composable
fun BolPermissionDialog(
    title: String,
    body: String,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    mood: BolMood = BolMood.Listening,
    confirmLabel: String = "Allow",
    dismissLabel: String = "Not now",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BolMascot(mood = mood, size = 96.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = HeedTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeedTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(confirmLabel, color = HeedTheme.colors.brand)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, color = HeedTheme.colors.textMuted)
            }
        },
    )
}
