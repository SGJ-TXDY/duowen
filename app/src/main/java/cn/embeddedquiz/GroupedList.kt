package cn.embeddedquiz

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Rounded groups shared by 更多, 设置 and the 速记 library (PLAN-1.7.5): a page with a 16dp inset,
 * groups on a 24dp radius, a small caption above a group, and rows separated by hairlines that start
 * after the icon. Rows keep a 56dp minimum height and merge their texts for TalkBack and tests.
 */

internal val GROUP_RADIUS = 24.dp
internal val PAGE_INSET = 16.dp

/** The page behind the groups: a quiet tint in light mode so white groups stand out. */
@Composable
@ReadOnlyComposable
internal fun groupPageColor(): Color = MaterialTheme.more.page

@Composable
@ReadOnlyComposable
internal fun groupColor(): Color = MaterialTheme.colorScheme.surfaceContainerLowest

/** A caption above a group, e.g. 「订阅」「个性化」 in the reference. */
@Composable
internal fun GroupCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** One rounded group; rows inside are separated with [GroupDivider]. */
@Composable
internal fun RoundedGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(GROUP_RADIUS),
        color = groupColor(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** A hairline between rows, starting where the row text starts. */
@Composable
internal fun GroupDivider(start: Dp = 56.dp) {
    HorizontalDivider(
        Modifier.padding(start = start, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Rows of [items] with hairlines between them. */
@Composable
internal fun <T> GroupRows(items: List<T>, dividerStart: Dp = 56.dp, row: @Composable (T) -> Unit) {
    items.forEachIndexed { index, item ->
        if (index > 0) GroupDivider(dividerStart)
        row(item)
    }
}

/**
 * A navigation or action row: icon, title, an optional short status on the right, a chevron when it
 * leads somewhere. A null [onClick] shows a plain row. [trailing] replaces the status and chevron.
 */
@Composable
internal fun GroupRow(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    subtitle: String? = null,
    value: String? = null,
    dot: Boolean = false,
    chevron: Boolean = true,
    enabled: Boolean = true,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val titleColor =
        when {
            !enabled -> sub
            danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier.semantics(mergeDescendants = true) {})
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(24.dp), tint = if (danger) MaterialTheme.colorScheme.error else if (enabled) tint else sub)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        } else {
            value?.let {
                Text(
                    it,
                    Modifier.padding(start = 8.dp).widthIn(max = 168.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (dot)
                Box(
                    Modifier.padding(start = 6.dp).size(8.dp).background(MaterialTheme.more.dot, CircleShape).semantics {
                        contentDescription = "有待处理"
                    }
                )
            if (chevron && onClick != null)
                Icon(AppIcons.ChevronRight, contentDescription = null, Modifier.padding(start = 4.dp).size(20.dp), tint = sub)
        }
    }
}

/** One choice of a set (theme, font size, model); the whole row selects. */
@Composable
internal fun GroupChoiceRow(title: String, selected: Boolean, modifier: Modifier = Modifier, subtitle: String? = null, onSelect: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (selected)
            Icon(AppIcons.Check, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** A row whose trailing switch carries the setting; tapping anywhere on the row toggles it. */
@Composable
internal fun GroupSwitchRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    GroupRow(
        title,
        icon,
        modifier,
        subtitle = subtitle,
        enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        onClick = { onChange(!checked) },
    )
}

/** A short note under a group. */
@Composable
internal fun GroupFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
    )
}

/** A colored rounded square holding an icon, for headers of a group (e.g. the current AI model). */
@Composable
internal fun GroupBadge(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}
