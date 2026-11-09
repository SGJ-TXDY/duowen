package cn.embeddedquiz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The 速记 document page in edit mode (PLAN-1.7.5), used for a new note and for an existing one.
 * Common blocks are edited in place as text; tables, quotes, images and other complex Markdown keep
 * their source and open as raw text on demand. The toolbar turns the focused block into a paragraph,
 * heading, list item or to-do, or adds a code block. Saving writes back only the changed blocks.
 */

private fun QuickNoteDraft.change(key: Long, f: (QuickNoteBlock) -> QuickNoteBlock) =
    copy(blocks = blocks.map { if (it.key == key) f(it) else it })

private fun QuickNoteDraft.insertAfter(key: Long?, block: QuickNoteBlock): QuickNoteDraft {
    val at = if (key == null) blocks.size else blocks.indexOfFirst { it.key == key } + 1
    return copy(blocks = blocks.toMutableList().apply { add(at.coerceIn(0, size), block) })
}

private val TEXT_KINDS =
    setOf(QuickNoteBlockKind.PARAGRAPH, QuickNoteBlockKind.HEADING, QuickNoteBlockKind.BULLET, QuickNoteBlockKind.ORDERED, QuickNoteBlockKind.TODO)

@Composable
internal fun QuickNoteEditor(notes: QuickNotesStateHolder, draft: QuickNoteDraft) {
    var leaving by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<Long?>(null) }
    var focusNext by remember { mutableStateOf<Long?>(null) }
    val list = rememberLazyListState()
    val visible = draft.blocks.filterNot { it.deleted }
    fun close() {
        if (draft.saving) return
        if (draft.dirty) leaving = true else notes.discardDraft()
    }
    BackHandler {
        if (draft.previewing) notes.editDraft { it.copy(previewing = false) } else close()
    }
    LaunchedEffect(draft.scrollTo) {
        val target = draft.scrollTo ?: return@LaunchedEffect
        val index = visible.indexOfFirst { it.key == target }
        if (index >= 0) list.scrollToItem(index + 1)
        // A tapped block takes the cursor once it is on screen.
        draft.focusBlock?.let { focusNext = it }
    }
    if (leaving)
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("保存修改？") },
            text = { Text(if (draft.documentId == null) "这篇新速记还没有保存。" else "《${draft.baseTitle}》的修改还没有保存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        leaving = false
                        notes.saveDraft()
                    },
                    modifier = Modifier.testTag("quick-note-leave-save"),
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            leaving = false
                            notes.discardDraft()
                        },
                        modifier = Modifier.testTag("quick-note-leave-discard"),
                    ) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { leaving = false }) { Text("继续编辑") }
                }
            },
        )

    Column(Modifier.fillMaxSize().testTag("quick-note-editor")) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { close() }, modifier = Modifier.size(48.dp)) {
                Icon(AppIcons.Back, contentDescription = "返回")
            }
            Text(
                if (draft.documentId == null) "新建速记" else "编辑速记",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(
                onClick = { notes.editDraft { it.copy(previewing = !it.previewing) } },
                modifier = Modifier.size(48.dp).testTag("quick-note-preview"),
            ) {
                Icon(
                    if (draft.previewing) AppIcons.Pencil else AppIcons.Eye,
                    contentDescription = if (draft.previewing) "继续编辑" else "预览",
                    tint = if (draft.previewing) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            Button(
                onClick = { notes.saveDraft() },
                enabled = (draft.dirty || draft.documentId == null && !draft.blank) && !draft.saving,
                contentPadding = PaddingValues(horizontal = 18.dp),
                modifier = Modifier.heightIn(min = 40.dp).testTag("quick-note-save"),
            ) { Text(if (draft.saving) "正在保存…" else "保存") }
        }
        if (draft.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (draft.error.isNotBlank())
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("quick-note-save-error")) { ErrorNotice("未保存：${draft.error}") }

        if (draft.previewing) {
            Column(Modifier.weight(1f).fillMaxWidth().testTag("quick-note-preview-body")) {
                Text(
                    draft.title.ifBlank { "未命名速记" },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                // The reader's own page: tables, code and images look as they will after saving.
                QuickNotePreview(notes, draft, Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            val titleFocus = remember { FocusRequester() }
            LaunchedEffect(draft.focusTitle) { if (draft.focusTitle) runCatching { titleFocus.requestFocus() } }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("quick-note-blocks"),
                state = list,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "title") {
                    PlainField(
                        draft.title,
                        { t -> notes.editDraft { it.copy(title = t.replace('\n', ' ')) } },
                        MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        "标题",
                        Modifier.padding(bottom = 6.dp),
                        singleLine = true,
                        fieldModifier = Modifier.focusRequester(titleFocus).testTag("quick-note-title"),
                    )
                }
                itemsIndexed(visible, key = { _, b -> b.key }) { _, block ->
                    BlockEditor(
                        block,
                        requestFocus = focusNext == block.key,
                        onFocus = {
                            focused = block.key
                            if (focusNext == block.key) focusNext = null
                        },
                    ) { change -> notes.editDraft { d -> d.change(block.key) { change(it).copy(edited = true) } } }
                }
                item(key = "end") {
                    // Tapping under the last block continues writing there.
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable {
                            val last = visible.lastOrNull()
                            if (last != null && last.kind in TEXT_KINDS && last.text.isEmpty()) focusNext = last.key
                            else {
                                val key = notes.newBlockKey()
                                notes.editDraft { it.insertAfter(null, QuickNoteBlock(key, QuickNoteBlockKind.PARAGRAPH, "")) }
                                focusNext = key
                            }
                        }.testTag("quick-note-append")
                    )
                }
            }
            EditorToolbar(
                onKind = { kind ->
                    val target = visible.firstOrNull { it.key == focused }
                    if (kind != QuickNoteBlockKind.CODE && target != null && target.kind in TEXT_KINDS) {
                        notes.editDraft { d ->
                            d.change(target.key) {
                                val level = if (kind == QuickNoteBlockKind.HEADING) (if (it.kind == QuickNoteBlockKind.HEADING) it.level % 3 + 1 else 2) else it.level
                                val marker =
                                    when (kind) {
                                        QuickNoteBlockKind.ORDERED -> if (it.kind == QuickNoteBlockKind.ORDERED) it.marker else "1."
                                        QuickNoteBlockKind.BULLET, QuickNoteBlockKind.TODO -> if (it.kind == QuickNoteBlockKind.BULLET || it.kind == QuickNoteBlockKind.TODO) it.marker else "-"
                                        else -> it.marker
                                    }
                                it.copy(kind = kind, level = level, marker = marker, edited = true)
                            }
                        }
                        focusNext = target.key
                    } else {
                        val key = notes.newBlockKey()
                        notes.editDraft { it.insertAfter(target?.key ?: visible.lastOrNull()?.key, QuickNoteBlock(key, kind, "", level = 2, marker = if (kind == QuickNoteBlockKind.ORDERED) "1." else "-")) }
                        focusNext = key
                    }
                },
                canDelete = focused != null && visible.any { it.key == focused },
                onDelete = {
                    val target = focused ?: return@EditorToolbar
                    notes.editDraft { d ->
                        val b = d.blocks.firstOrNull { it.key == target }
                        if (b?.lines == null) d.copy(blocks = d.blocks.filterNot { it.key == target })
                        else d.change(target) { it.copy(deleted = true) }
                    }
                    focused = null
                },
            )
        }
    }
}

@Composable
private fun EditorToolbar(onKind: (QuickNoteBlockKind) -> Unit, canDelete: Boolean, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().testTag("quick-note-toolbar")) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(AppIcons.Pilcrow, "段落") { onKind(QuickNoteBlockKind.PARAGRAPH) }
            ToolButton(AppIcons.Heading, "标题") { onKind(QuickNoteBlockKind.HEADING) }
            ToolButton(AppIcons.ListOrder, "列表") { onKind(QuickNoteBlockKind.BULLET) }
            ToolButton(AppIcons.ListChecks, "待办") { onKind(QuickNoteBlockKind.TODO) }
            ToolButton(AppIcons.Code, "代码块") { onKind(QuickNoteBlockKind.CODE) }
            Spacer(Modifier.weight(1f))
            ToolButton(AppIcons.Trash, "删除此块", enabled = canDelete, onClick = onDelete)
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    style: TextStyle,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    onFocus: () -> Unit = {},
    requestFocus: Boolean = false,
    /** On the text field itself: test tags and outside focus requesters. */
    fieldModifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestFocus) { if (requestFocus) runCatching { focus.requestFocus() } }
    val color = MaterialTheme.colorScheme.onSurface
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = style.copy(color = color),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = fieldModifier.fillMaxWidth().focusRequester(focus).onFocusChanged { if (it.isFocused) onFocus() }.semantics { contentDescription = placeholder },
        )
    }
}

@Composable
private fun BlockEditor(block: QuickNoteBlock, requestFocus: Boolean, onFocus: () -> Unit, onChange: ((QuickNoteBlock) -> QuickNoteBlock) -> Unit) {
    val body = MaterialTheme.typography.bodyLarge
    val tag = Modifier.testTag("quick-note-block")
    when (block.kind) {
        QuickNoteBlockKind.PARAGRAPH ->
            PlainField(block.text, { t -> onChange { it.copy(text = t) } }, body, "输入内容", onFocus = onFocus, requestFocus = requestFocus, fieldModifier = tag)
        QuickNoteBlockKind.HEADING -> {
            val style =
                when (block.level) {
                    1 -> MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp)
                    2 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                }
            Row(tag, verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { onChange { it.copy(level = it.level % 6 + 1) } },
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(end = 10.dp).semantics { contentDescription = "标题级别 ${block.level}，点按切换" },
                ) {
                    Text("H${block.level}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                PlainField(block.text, { t -> onChange { it.copy(text = t) } }, style.copy(fontWeight = FontWeight.Bold), "标题", Modifier.weight(1f), true, onFocus, requestFocus)
            }
        }
        QuickNoteBlockKind.BULLET,
        QuickNoteBlockKind.ORDERED -> {
            Row(tag.padding(start = (block.indent.length * 6).dp), verticalAlignment = Alignment.Top) {
                Text(if (block.kind == QuickNoteBlockKind.BULLET) "•" else block.marker, style = body, modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp))
                PlainField(block.text, { t -> onChange { it.copy(text = t) } }, body, "列表项", Modifier.weight(1f), true, onFocus, requestFocus)
            }
        }
        QuickNoteBlockKind.TODO ->
            Row(tag.padding(start = (block.indent.length * 6).dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = block.checked, onCheckedChange = { c -> onChange { it.copy(checked = c) } }, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(6.dp))
                PlainField(
                    block.text,
                    { t -> onChange { it.copy(text = t) } },
                    if (block.checked) body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant) else body,
                    "待办事项",
                    Modifier.weight(1f),
                    true,
                    onFocus,
                    requestFocus,
                )
            }
        QuickNoteBlockKind.CODE ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = tag.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlainField(block.language, { t -> onChange { it.copy(language = t.trim()) } }, MaterialTheme.typography.labelMedium, "语言（可不填）", singleLine = true, onFocus = onFocus)
                    PlainField(
                        block.text,
                        { t -> onChange { it.copy(text = t) } },
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        "代码",
                        onFocus = onFocus,
                        requestFocus = requestFocus,
                    )
                }
            }
        QuickNoteBlockKind.RAW -> RawBlock(block, requestFocus, onFocus, onChange)
    }
}

/** Tables, quotes, images and other complex Markdown: kept as written, editable as source on demand. */
@Composable
private fun RawBlock(block: QuickNoteBlock, requestFocus: Boolean, onFocus: () -> Unit, onChange: ((QuickNoteBlock) -> QuickNoteBlock) -> Unit) {
    // A tapped table, quote or image opens straight on its source.
    var open by remember(block.key) { mutableStateOf(requestFocus) }
    LaunchedEffect(requestFocus) { if (requestFocus) open = true }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("quick-note-raw"),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    block.label + if (block.edited) " · 已修改" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("quick-note-raw-toggle")) {
                    Text(if (open) "收起" else "编辑原文")
                }
            }
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            if (open)
                Box(Modifier.padding(end = 8.dp)) {
                    PlainField(block.text, { t -> onChange { it.copy(text = t) } }, mono, "原文", onFocus = onFocus, requestFocus = true, fieldModifier = Modifier.testTag("quick-note-raw-field"))
                }
            else Text(block.text, style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
        }
    }
}
