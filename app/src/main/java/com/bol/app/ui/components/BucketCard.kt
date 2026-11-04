package com.bol.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bol.app.data.local.model.CategoryWithCount
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme

/**
 * A bucket on Home: white card with a line border, the bucket's icon, its name and how many tasks are open.
 * Long-press only for user-made buckets (defaults can't be deleted).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BucketCard(
    category: CategoryWithCount,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(HeedLayout.CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = HeedLayout.CardShape,
        color = HeedTheme.colors.surface,
        border = BorderStroke(1.dp, HeedTheme.colors.line),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BucketIcon(emoji = category.icon, categoryId = category.id, name = category.name, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = HeedTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${category.itemCount} OPEN",
                    style = BolMono.copy(color = HeedTheme.colors.textMuted),
                )
            }
        }
    }
}

/** The last card in the bucket grid: opens the create dialog. */
@Composable
fun AddBucketCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = HeedLayout.CardShape,
        color = HeedTheme.colors.surface,
        border = BorderStroke(1.dp, HeedTheme.colors.line),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = Icons.Rounded.Add, size = 36.dp, tint = HeedTheme.colors.textMuted)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "New",
                style = MaterialTheme.typography.titleMedium,
                color = HeedTheme.colors.textMuted,
            )
        }
    }
}
