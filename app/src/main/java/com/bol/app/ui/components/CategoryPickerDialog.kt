package com.bol.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.ui.theme.HeedTheme

/**
 * Pick a different bucket for an item: a bottom sheet like the reminder one. Shows every active bucket except
 * [currentCategoryId] (a no-op move). The first tap moves and closes; no confirm button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerDialog(
    currentCategoryId: String,
    categories: List<CategoryEntity>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = categories.filter { it.id != currentCategoryId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = HeedTheme.colors.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            SheetHeader(title = "Move to")
            if (options.isEmpty()) {
                Text(
                    "No other buckets to move to. Make one from Home.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeedTheme.colors.textMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(options, key = { it.id }) { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(HeedLayout.ControlShape)
                                .clickable { onPick(cat.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BucketIcon(emoji = cat.icon, categoryId = cat.id, name = cat.name)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = cat.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = HeedTheme.colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
