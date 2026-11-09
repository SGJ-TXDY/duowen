package cn.embeddedquiz

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Markdown types pickers report vary by file manager; the backend checks the real extension. */
private val IMPORT_TYPES =
    arrayOf(
        "text/markdown",
        "text/x-markdown",
        "text/plain",
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )
private val BACKUP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

@Composable
internal fun QuickNotesScreen(vm: QuizViewModel, reader: QuickNoteReaderHandle, ai: QuickNoteAiEntry? = null) {
    val notes = vm.quickNotes
    val document = notes.state.reader
    val draft = notes.draft
    // The draft shows where it belongs: a new note instead of the library, an edit over its document.
    if (draft != null && notes.draftShown()) QuickNoteEditor(notes, draft)
    else if (document != null) QuickNoteReader(vm, document, reader, ai)
    else {
        BackHandler { notes.leave { vm.navigate(Page.HOME) } }
        QuickNotesLibrary(vm)
    }
    val conflict = notes.state.conflict
    if (conflict.isNotEmpty())
        AlertDialog(
            onDismissRequest = notes::cancelImport,
            title = { Text("已有相同的文档") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本次导入与下列文档的内容或文件名相同：")
                    conflict.forEach { Text("• ${it.title}（${it.sourceName}）") }
                    Text("可以取消导入，或保留两份。保留两份不会改动已有文档和它的阅读位置。")
                }
            },
            confirmButton = {
                TextButton(onClick = notes::confirmKeepBoth, enabled = !notes.state.working) {
                    Text("保留两份")
                }
            },
            dismissButton = {
                TextButton(onClick = notes::cancelImport, enabled = !notes.state.working) {
                    Text("取消导入")
                }
            },
        )
}

@Composable
private fun QuickNotesLibrary(vm: QuizViewModel) {
    val notes = vm.quickNotes
    val state = notes.state
    val context = LocalContext.current
    val busy = state.loading || state.working
    var deleting by remember { mutableStateOf<QuickNoteSummary?>(null) }
    var restoreFrom by rememberSaveable { mutableStateOf<Uri?>(null) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Commands report only failures; announce success once the started operation ends cleanly.
    var success by rememberSaveable { mutableStateOf("") }
    fun run(message: String, command: () -> Unit) {
        if (busy) return
        success = message
        command()
    }
    LaunchedEffect(state.working) {
        if (!state.working && success.isNotEmpty()) {
            if (state.error.isEmpty() && state.conflict.isEmpty())
                Toast.makeText(context, success, Toast.LENGTH_SHORT).show()
            success = ""
        }
    }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) run("导入完成") { notes.importUri(uri) }
        }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) run("速记备份已导出") { notes.exportBackupUri(uri) }
        }
    val restorer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreFrom = uri }

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<QuickNoteSearchHit>?>(null) }
    LaunchedEffect(searching, query) {
        if (!searching || query.isBlank()) {
            hits = null
            return@LaunchedEffect
        }
        delay(250)
        hits = runCatching { notes.search(query) }.getOrElse { emptyList() }
    }
    if (searching) BackHandler { searching = false; query = "" }
    val pinned = state.documents.filter { it.pinned }
    val others = state.documents.filterNot { it.pinned }
    val pending = notes.draft?.takeIf { it.dirty && !notes.draftShown() }

    Box(Modifier.fillMaxSize().background(groupPageColor())) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("quick-notes-list"),
        contentPadding = PaddingValues(start = PAGE_INSET, end = PAGE_INSET, top = 8.dp, bottom = 104.dp),
    ) {
        item(key = "head") {
            if (searching)
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(24.dp), color = groupColor(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty())
                                    Text("搜索标题和正文", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it.take(160) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索速记" }.testTag("quick-notes-search-field"),
                                )
                            }
                        }
                    }
                    TextButton(onClick = { searching = false; query = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                }
            else
                Row(Modifier.padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text("速记", fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        Text(
                            if (state.loading) "正在读取…" else "${state.documents.size} 篇速记",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("quick-notes-count"),
                        )
                    }
                    IconButton(onClick = { searching = true }, enabled = state.documents.isNotEmpty(), modifier = Modifier.size(48.dp).testTag("quick-notes-search")) {
                        Icon(AppIcons.Search, contentDescription = "搜索速记")
                    }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                            Icon(AppIcons.MoreVert, contentDescription = "速记菜单")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("导入 Markdown 或配套 ZIP") },
                                leadingIcon = { Icon(AppIcons.Import, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    importer.launch(IMPORT_TYPES)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导出速记备份") },
                                leadingIcon = { Icon(AppIcons.Upload, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    exporter.launch("速记备份-${LocalDate.now()}.zip")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("恢复速记备份") },
                                leadingIcon = { Icon(AppIcons.Sync, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    restorer.launch(BACKUP_TYPES)
                                },
                            )
                        }
                    }
                }
        }
        pending?.let { d ->
            item(key = "pending") {
                Surface(shape = RoundedCornerShape(GROUP_RADIUS), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("quick-note-pending")) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "《${d.title.ifBlank { "新速记" }}》有未保存的修改",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { d.documentId?.let { notes.openDocument(it) } ?: notes.startNew() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续编辑") }
                        TextButton(onClick = { notes.discardDraft() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("放弃") }
                    }
                }
            }
        }
        when {
            state.loading -> item { MutedText("正在读取速记…") }
            state.working -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
        }
        if (state.error.isNotBlank())
            item {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ErrorNotice(state.error)
                    SecondaryAction("重新读取列表", !busy) { notes.reload() }
                }
            }
        if (notes.positionSaveError.isNotBlank())
            item {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ErrorNotice("阅读位置未保存：${notes.positionSaveError}")
                    SecondaryAction("重试保存阅读位置") { notes.retryPositionWrites() }
                }
            }
        if (searching) {
            val found = hits
            when {
                query.isBlank() -> item { GroupFootnote("在本机搜索全部速记的标题和正文。") }
                found == null -> item { GroupFootnote("正在搜索…") }
                found.isEmpty() -> item { GroupFootnote("没有找到包含「${query.trim()}」的速记。", Modifier.testTag("quick-notes-search-none")) }
                else ->
                    item(key = "hits") {
                        RoundedGroup(Modifier.testTag("quick-notes-hits")) {
                            GroupRows(found, dividerStart = 20.dp) { hit ->
                                NoteRow(hit.summary, hit.snippet, query.trim(), enabled = !busy, onOpen = { notes.openDocument(hit.summary.id) })
                            }
                        }
                    }
            }
        } else {
            // Never claim an empty library while the index is still loading or failed to load.
            if (!state.loading && state.error.isBlank() && state.documents.isEmpty()) item { EmptyLibrary() }
            if (pinned.isNotEmpty()) {
                item(key = "pinned-caption") { GroupCaption("置顶") }
                item(key = "pinned") {
                    RoundedGroup(Modifier.testTag("quick-notes-pinned")) {
                        GroupRows(pinned, dividerStart = 20.dp) { doc ->
                            NoteRow(doc, doc.excerpt, null, !busy, onOpen = { notes.openDocument(doc.id) }, onPin = { notes.setPinned(doc.id, false) }) { deleting = doc }
                        }
                    }
                }
            }
            if (others.isNotEmpty()) {
                item(key = "others-caption") { GroupCaption(if (pinned.isEmpty()) "全部速记" else "其他速记") }
                item(key = "others") {
                    RoundedGroup(Modifier.testTag("quick-notes-others")) {
                        GroupRows(others, dividerStart = 20.dp) { doc ->
                            NoteRow(doc, doc.excerpt, null, !busy, onOpen = { notes.openDocument(doc.id) }, onPin = { notes.setPinned(doc.id, true) }) { deleting = doc }
                        }
                    }
                }
            }
            item {
                GroupFootnote("速记单独存放：刷题备份不包含速记，恢复刷题备份也不会删除速记。请用右上角菜单单独导出速记备份。")
            }
        }
    }
    if (!searching)
        FloatingActionButton(
            onClick = { notes.startNew() },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp).size(60.dp).testTag("quick-note-new"),
        ) {
            Icon(AppIcons.Plus, contentDescription = "新建速记", modifier = Modifier.size(28.dp))
        }
    }

    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除《${doc.title}》？") },
            text = { Text("将删除这份文档的正文、图片和阅读位置，无法撤销。刷题数据不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        run("已删除") { notes.deleteDocument(doc.id) }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    val uri = restoreFrom
    if (uri != null && !confirmReplace)
        AlertDialog(
            onDismissRequest = { restoreFrom = null },
            title = { Text("恢复速记备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("这是速记备份 ZIP，不是要阅读的 Markdown 配套 ZIP。")
                    Text("合并（推荐）：保留现有文档，内容相同的跳过，不同的作为新文档加入。")
                    Text("替换：用备份替换整个速记库，只影响速记，不影响刷题数据。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreFrom = null
                        run("速记备份已合并恢复") { notes.restoreBackupUri(uri) }
                    }
                ) {
                    Text("合并恢复")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmReplace = true }) {
                        Text("替换…", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { restoreFrom = null }) { Text("取消") }
                }
            },
        )
    if (uri != null && confirmReplace)
        AlertDialog(
            onDismissRequest = {
                confirmReplace = false
                restoreFrom = null
            },
            title = { Text("确认替换速记库？") },
            text = {
                Text(
                    "当前全部 ${state.documents.size} 份速记文档及阅读位置将被备份内容替换，无法撤销。刷题题库和学习记录不受影响。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReplace = false
                        restoreFrom = null
                        run("速记库已替换为备份内容") { notes.restoreBackupUri(uri, replace = true) }
                    }
                ) {
                    Text("替换", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmReplace = false
                        restoreFrom = null
                    }
                ) {
                    Text("取消")
                }
            },
        )
}

private val noteDate = DateTimeFormatter.ofPattern("yyyy/M/d")

/** One note in a group: title, then the date and a line of its text (or the search snippet). */
@Composable
private fun NoteRow(
    doc: QuickNoteSummary,
    excerpt: String,
    highlight: String?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onPin: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen).padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp).testTag("quick-note-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val date = Instant.ofEpochMilli(doc.updatedAt).atZone(ZoneId.systemDefault()).toLocalDate().format(noteDate)
            val sub = MaterialTheme.colorScheme.onSurfaceVariant
            val mark = MaterialTheme.colorScheme.primary
            Text(
                buildAnnotatedString {
                    append(date)
                    val line = excerpt.replace(Regex("\\s+"), " ").trim()
                    if (line.isNotEmpty()) {
                        append("  ")
                        val term = highlight.orEmpty()
                        val at = if (term.isEmpty()) -1 else line.indexOf(term, ignoreCase = true)
                        if (at < 0) append(line)
                        else {
                            append(line.substring(0, at))
                            withStyle(SpanStyle(color = mark, fontWeight = FontWeight.SemiBold)) { append(line.substring(at, at + term.length)) }
                            append(line.substring(at + term.length))
                        }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = sub,
                maxLines = if (highlight != null) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onPin != null || onDelete != null)
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(AppIcons.MoreVert, contentDescription = "更多操作：${doc.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onPin?.let { pin ->
                        DropdownMenuItem(
                            text = { Text(if (doc.pinned) "取消置顶" else "置顶") },
                            leadingIcon = { Icon(AppIcons.Pin, contentDescription = null) },
                            onClick = {
                                menu = false
                                pin()
                            },
                        )
                    }
                    onDelete?.let { delete ->
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(AppIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                delete()
                            },
                        )
                    }
                }
            }
    }
}

@Composable
private fun EmptyLibrary() {
    RoundedGroup(Modifier.padding(top = 4.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("还没有速记", style = MaterialTheme.typography.titleMedium)
            Text("点右下角 ＋ 直接写一篇，或从右上角菜单导入 Markdown。", style = MaterialTheme.typography.bodyMedium)
            Text("• 单个 .md 文件：直接导入。图片不会随单个文件带入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("• 文档里有本地图片：把 .md 和图片按原来的相对目录一起打成 ZIP（配套 ZIP），再导入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
