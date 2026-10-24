package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = text },
    ) {
        Text(text)
    }
}

/** Secondary action: same footprint as [Action] without competing for attention. */
@Composable
fun SecondaryAction(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(text)
    }
}

@Composable
fun TonalAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Text(text)
    }
}

@Composable
fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
fun Tag(
    text: String,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    bold: Boolean = false,
) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (bold) FontWeight.Bold else null,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Pending verification is common in the bank, so it reads as a caution, not an error. */
@Composable
fun VerificationNotice(text: String) {
    Surface(
        color = MaterialTheme.status.warningContainer,
        contentColor = MaterialTheme.status.onWarningContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun ErrorNotice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** White content block on the tinted page background. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (trailing != null)
                        Text(
                            trailing,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            content()
        }
    }
}

/** Collapsible row used to keep secondary tools out of the answering flow. */
@Composable
fun ExpandableSection(
    icon: ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Surface(
                onClick = onToggle,
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Icon(
                        if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                        contentDescription = if (expanded) "收起$title" else "展开$title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (expanded)
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
        }
    }
}

/** Filter chip that opens a menu; highlighted once a value other than [allLabel] is chosen. */
@Composable
fun FilterMenuChip(
    label: String,
    value: String,
    options: List<String>,
    allLabel: String = "全部",
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = value != allLabel
    Box {
        FilterChip(
            selected = active,
            onClick = { expanded = true },
            label = { Text(if (active) value else label, maxLines = 1) },
            trailingIcon = {
                Icon(AppIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            shape = MaterialTheme.shapes.small,
            colors =
                FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                    selectedTrailingIconColor = MaterialTheme.colorScheme.primary,
                ),
            modifier =
                Modifier.heightIn(min = 40.dp).semantics {
                    contentDescription = "$label：$value"
                },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            options.distinct().forEach { x ->
                DropdownMenuItem(
                    text = { Text(x) },
                    trailingIcon =
                        if (x == value) {
                            { Icon(AppIcons.Check, contentDescription = "当前选择", modifier = Modifier.size(18.dp)) }
                        } else null,
                    onClick = {
                        onChange(x)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun Detail(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        remember(text) { ReadingBlocks.parse(text) }
            .forEach { block ->
                if (block.code) CodeBlock(block.text)
                else
                    SelectionContainer {
                        Text(
                            block.text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Default,
                        )
                    }
            }
    }
}

@Composable
fun Picker(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier =
                Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label：$value" },
        ) {
            Text("$label：$value", color = MaterialTheme.colorScheme.onSurface)
            Icon(
                AppIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            options.distinct().forEach { x ->
                DropdownMenuItem(
                    text = { Text(x) },
                    onClick = {
                        onChange(x)
                        expanded = false
                    },
                )
            }
        }
    }
}
