package com.bol.app.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bol.app.ui.theme.BolEyebrow
import com.bol.app.ui.theme.BolFonts
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme
import com.bol.app.ui.theme.PageTitle
import com.bol.app.ui.theme.Tone
import com.bol.app.ui.theme.surfaceWash

/*
 * The building blocks every screen is made of, so the whole app reads as one thing.
 *
 * - The four tab screens open with [PageHeader]; every other screen with [TopBar].
 * - Content sits on the white page. Groups go in a [HeedCard] (1 dp line border, 12 dp corners), never in a tinted
 *   fill. A note or warning is a [HeedCallout] (soft fill, no border). A [SectionLabel] sits above each group.
 * - One checkbox ([HeedCheckbox]), one bucket icon ([BucketIcon]), one button ([HeedButton]), one chip ([HeedChip]),
 *   one segmented control ([Segmented]), one list row ([ListRow]), one property row ([PropertyRow]).
 * - Colour: brand green marks actions, links and on / done state; a bucket's tone only tints its [BucketIcon];
 *   danger only for delete, failure and overdue. Everything else is text colour on white.
 */

/** Page side padding, corner radii and the two shapes. */
object HeedLayout {
    val Gutter = 20.dp
    val CardShape = RoundedCornerShape(12.dp)
    val ControlShape = RoundedCornerShape(10.dp)
}

// ── Headers ──

/**
 * The top of a tab screen: a mono eyebrow, a two-line title (the second line in brand green), an optional meta line
 * and the mascot on the right.
 */
@Composable
fun PageHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    accent: String? = null,
    mood: BolMood? = null,
    meta: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = eyebrow.uppercase(), style = BolEyebrow.copy(color = HeedTheme.colors.textMuted))
                Spacer(Modifier.height(6.dp))
                Text(text = title, style = PageTitle.copy(color = HeedTheme.colors.textPrimary))
                if (accent != null) Text(text = accent, style = PageTitle.copy(color = HeedTheme.colors.brand))
            }
            if (mood != null) {
                Spacer(Modifier.width(8.dp))
                BolMascot(mood = mood, size = 76.dp)
            }
        }
        // Full width, under the mascot too, so a status line doesn't wrap.
        if (meta != null) {
            Spacer(Modifier.height(10.dp))
            meta()
        }
    }
}

/**
 * The top of every pushed screen: back arrow, title (one line), optional mono subtitle, optional leading icon
 * (a [BucketIcon]) and actions on the right.
 */
@Composable
fun TopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = HeedTheme.colors.textPrimary,
            )
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = HeedTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle.uppercase(),
                    style = BolMono.copy(color = HeedTheme.colors.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/**
 * Mono caps label above a group, with an optional count or note on the right ([trailing]) and an optional action
 * in brand green ([action] + [onAction], e.g. "See all").
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    color: Color = HeedTheme.colors.textMuted,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text.uppercase(), style = BolEyebrow.copy(color = color), modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(text = trailing, style = BolMono.copy(color = HeedTheme.colors.textMuted))
        }
        if (action != null && onAction != null) {
            if (trailing != null) Spacer(Modifier.width(12.dp))
            Text(
                text = action,
                fontFamily = BolFonts.UI,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = HeedTheme.colors.brand,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(4.dp),
            )
        }
    }
}

// ── Containers ──

/** A group of rows or a block of content: white, 1 dp line border, 12 dp corners. */
@Composable
fun HeedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(1.dp, HeedTheme.colors.line)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = HeedLayout.CardShape,
            color = HeedTheme.colors.surface,
            border = border,
        ) { Column(content = content) }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = HeedLayout.CardShape,
            color = HeedTheme.colors.surface,
            border = border,
        ) { Column(content = content) }
    }
}

enum class CalloutTone { Neutral, Brand, Danger }

/** A note, tip or warning: soft fill, no border, optional icon, title and actions ([content]). */
@Composable
fun HeedCallout(
    body: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    tone: CalloutTone = CalloutTone.Neutral,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val c = HeedTheme.colors
    val bg = when (tone) {
        CalloutTone.Neutral -> c.surfaceSoft
        CalloutTone.Brand -> c.brandSoft
        CalloutTone.Danger -> c.dangerSoft
    }
    val accent = when (tone) {
        CalloutTone.Neutral -> c.textMuted
        CalloutTone.Brand -> c.brand
        CalloutTone.Danger -> c.danger
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(HeedLayout.CardShape)
            .background(bg)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (tone == CalloutTone.Neutral) c.textPrimary else accent,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            content()
        }
    }
}

/** A full-width hairline between rows of a flat list. Inside a [HeedCard], use [PanelDivider] (inset 16 dp). */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(HeedTheme.colors.line))
}

// ── Rows ──

/**
 * The one list row: optional leading slot (a [HeedCheckbox] or [BucketIcon], 12 dp gap), a title (16 sp regular,
 * struck through and muted when [done]), a mono meta line ([meta] or a custom [metaContent]) and an optional
 * trailing slot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    metaContent: (@Composable () -> Unit)? = null,
    done: Boolean = false,
    titleMaxLines: Int = 2,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val c = HeedTheme.colors
    val click = when {
        onClick != null || onLongClick != null ->
            Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(click)
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (done) c.textMuted else c.textPrimary,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (metaContent != null) {
                Spacer(Modifier.height(2.dp))
                metaContent()
            } else if (meta != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta.uppercase(),
                    style = BolMono.copy(color = c.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * A Notion-style property line inside a [HeedCard]: mono label on the left (fixed width), value on the right.
 * Tappable when [onClick] is set.
 */
@Composable
fun PropertyRow(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    value: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = BolMono.copy(color = HeedTheme.colors.textMuted),
            modifier = Modifier.width(92.dp),
        )
        value()
    }
}

/** A fact in an expanded task row: mono label (72 dp), then a one-line value or custom [content]. */
@Composable
fun FactLine(label: String, value: String? = null, content: (@Composable () -> Unit)? = null) {
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.uppercase(),
            style = BolMono.copy(color = HeedTheme.colors.textMuted),
            modifier = Modifier.width(72.dp),
        )
        if (content != null) content()
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = HeedTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Actions under an expanded row (Text-style [HeedButton]s, a Danger one last): they wrap instead of running off. */
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    WrapRow(modifier = modifier, spacing = 4.dp, content = content)
}

/**
 * Lays children left to right and starts a new line when one doesn't fit. Our own instead of Compose's FlowRow:
 * the app compiles against foundation 1.7 but runs 1.9 (pulled in by a library), and 1.9 dropped the FlowRow
 * signature 1.7 compiles to (NoSuchMethodError on the phone).
 */
@Composable
fun WrapRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    lineSpacing: Dp = spacing,
    centered: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val lineGap = lineSpacing.roundToPx()
        val maxWidth = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val lines = mutableListOf<MutableList<Placeable>>(mutableListOf())
        var lineWidth = 0
        for (p in placeables) {
            val line = lines.last()
            val needed = if (line.isEmpty()) p.width else lineWidth + gap + p.width
            if (line.isNotEmpty() && needed > maxWidth) {
                lines.add(mutableListOf(p))
                lineWidth = p.width
            } else {
                line.add(p)
                lineWidth = needed
            }
        }
        val widths = lines.map { l -> l.sumOf { it.width } + gap * (l.size - 1).coerceAtLeast(0) }
        val heights = lines.map { l -> l.maxOfOrNull { it.height } ?: 0 }
        val width = if (constraints.hasBoundedWidth && centered) maxWidth else (widths.maxOrNull() ?: 0).coerceAtMost(maxWidth)
        val height = heights.sum() + lineGap * (lines.size - 1).coerceAtLeast(0)
        layout(width.coerceAtLeast(constraints.minWidth), height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            lines.forEachIndexed { i, l ->
                var x = if (centered) (width - widths[i]) / 2 else 0
                l.forEach { p ->
                    p.placeRelative(x, y + (heights[i] - p.height) / 2)
                    x += p.width + gap
                }
                y += heights[i] + lineGap
            }
        }
    }
}

// ── Controls ──

/**
 * The one checkbox: 22 dp rounded square, line border when open, brand fill with a check when done. 40 dp target,
 * announced as a checkbox, and a light haptic tick through the view (so it follows the phone's touch-feedback
 * setting, unlike the mic's [com.bol.app.util.BolHaptic]).
 */
@Composable
fun HeedCheckbox(
    checked: Boolean,
    onCheckedChange: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = HeedTheme.colors
    val view = LocalView.current
    val fill by animateColorAsState(if (checked) c.brand else Color.Transparent, label = "check-fill")
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(value = checked, role = Role.Checkbox) {
                        view.performHapticFeedback(
                            if (!checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                            else HapticFeedbackConstants.CLOCK_TICK,
                        )
                        onCheckedChange()
                    }
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(fill)
                .then(if (checked) Modifier else Modifier.border(1.5.dp, c.textMuted, RoundedCornerShape(6.dp))),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = c.onBrand, modifier = Modifier.size(16.dp))
        }
    }
}

/** A bucket's emoji on its tone, as a rounded square. The only place a bucket's colour shows. */
@Composable
fun BucketIcon(
    emoji: String,
    categoryId: String?,
    modifier: Modifier = Modifier,
    name: String? = null,
    size: Dp = 32.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Tone.forCategory(categoryId, name).surfaceWash()),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = (size.value * 0.5f).sp)
    }
}

/** A neutral icon in the same rounded square as [BucketIcon] (history rows, audio, settings). */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    tint: Color = HeedTheme.colors.textSecondary,
    background: Color = HeedTheme.colors.surfaceSofter,
) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

enum class HeedButtonStyle { Filled, Outlined, Text, Danger, DangerText }

/**
 * The one button. Filled = brand green (one per screen, the main action); Outlined = white with a line border;
 * Text = brand text, no box; Danger = white with a line border and red text (a screen's delete);
 * DangerText = red text, no box (delete among inline actions). 48 dp tall (text styles 40 dp), 10 dp corners.
 */
@Composable
fun HeedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: HeedButtonStyle = HeedButtonStyle.Filled,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = HeedTheme.colors
    val textOnly = style == HeedButtonStyle.Text || style == HeedButtonStyle.DangerText
    val bg = if (style == HeedButtonStyle.Filled) c.brand else if (textOnly) Color.Transparent else c.surface
    val fg = when (style) {
        HeedButtonStyle.Filled -> c.onBrand
        HeedButtonStyle.Outlined -> c.textPrimary
        HeedButtonStyle.Text -> c.brand
        HeedButtonStyle.Danger, HeedButtonStyle.DangerText -> c.danger
    }
    val border = if (style == HeedButtonStyle.Outlined || style == HeedButtonStyle.Danger) BorderStroke(1.dp, c.line) else null
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = if (textOnly) 40.dp else 48.dp)
            .alpha(if (enabled) 1f else 0.4f),
        shape = HeedLayout.ControlShape,
        color = bg,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (textOnly) 8.dp else 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                fontFamily = BolFonts.UI,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = fg,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The one chip (filters, suggestions, a bucket or time on a row): 32 dp, 8 dp corners. Unselected = white with a line
 * border; selected = brand soft with brand text.
 */
@Composable
fun HeedChip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = HeedTheme.colors
    val fg = if (selected) c.brand else c.textSecondary
    val shape = RoundedCornerShape(8.dp)
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(6.dp))
            }
            Text(text = label, fontFamily = BolFonts.UI, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = fg, maxLines = 1)
            if (trailingIcon != null) {
                Spacer(Modifier.width(4.dp))
                Icon(trailingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            }
        }
    }
    val border = if (selected) null else BorderStroke(1.dp, c.line)
    val bg = if (selected) c.brandSoft else c.surface
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = bg, border = border) { body() }
    } else {
        Surface(modifier = modifier, shape = shape, color = bg, border = border) { body() }
    }
}

/** The one segmented control: a soft track, the selected option white with a line border. */
@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HeedTheme.colors
    Row(
        modifier = modifier
            .clip(HeedLayout.ControlShape)
            .background(c.surfaceSofter)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, option ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .widthIn(min = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) c.surface else Color.Transparent)
                    .then(if (selected) Modifier.border(1.dp, c.line, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    fontFamily = BolFonts.UI,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = if (selected) c.textPrimary else c.textMuted,
                )
            }
        }
    }
}

/** Text-field colours: white, line border, brand border and cursor when focused. Use with [HeedLayout.ControlShape]. */
@Composable
fun heedTextFieldColors(): TextFieldColors {
    val c = HeedTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.brand,
        unfocusedBorderColor = c.line,
        focusedContainerColor = c.surface,
        unfocusedContainerColor = c.surface,
        cursorColor = c.brand,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary,
        focusedPlaceholderColor = c.textMuted,
        unfocusedPlaceholderColor = c.textMuted,
        focusedLabelColor = c.brand,
        unfocusedLabelColor = c.textMuted,
    )
}

// ── Sheets and empty states ──

/** Title and optional subtitle at the top of a bottom sheet. */
@Composable
fun SheetHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, color = HeedTheme.colors.textPrimary)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = HeedTheme.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Nothing here yet: the mascot, a title and one line of help, centred. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    mood: BolMood = BolMood.Sleeping,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BolMascot(mood = mood, size = 96.dp)
        Spacer(Modifier.height(16.dp))
        Text(text = title, style = MaterialTheme.typography.titleLarge, color = HeedTheme.colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(text = body, style = MaterialTheme.typography.bodyMedium, color = HeedTheme.colors.textMuted, textAlign = TextAlign.Center)
        content()
    }
}
