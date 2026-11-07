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
    if (document != null) QuickNoteReader(vm, document, reader, ai)
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

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("quick-notes-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("速记", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "阅读你整理的 Markdown 知识文档，与刷题记录互不影响",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                        Icon(AppIcons.MoreVert, contentDescription = "速记备份")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("导出速记备份") },
                            onClick = {
                                menu = false
                                exporter.launch("速记备份-${LocalDate.now()}.zip")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("恢复速记备份") },
                            onClick = {
                                menu = false
                                restorer.launch(BACKUP_TYPES)
                            },
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = { importer.launch(IMPORT_TYPES) },
                enabled = !busy,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(AppIcons.Import, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    "导入 Markdown 或配套 ZIP",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        when {
            state.loading -> item { MutedText("正在读取速记…") }
            state.working -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        if (state.error.isNotBlank())
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ErrorNotice(state.error)
                    SecondaryAction("重新读取列表", !busy) { notes.reload() }
                }
            }
        if (notes.positionSaveError.isNotBlank())
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ErrorNotice("阅读位置未保存：${notes.positionSaveError}")
                    SecondaryAction("重试保存阅读位置") { notes.retryPositionWrites() }
                }
            }
        // Never claim an empty library while the index is still loading or failed to load.
        if (!state.loading && state.error.isBlank() && state.documents.isEmpty())
            item { EmptyLibrary() }
        items(state.documents, key = { it.id }) { doc ->
            DocumentCard(doc, enabled = !busy, onOpen = { notes.openDocument(doc.id) }) { deleting = doc }
        }
        item {
            MutedText(
                "速记单独存放：刷题备份不包含速记，恢复刷题备份也不会删除速记。请用右上角菜单单独导出速记备份。"
            )
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

@Composable
private fun DocumentCard(doc: QuickNoteSummary, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onOpen,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.QuickNotes,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${doc.sourceName} · 导入于 ${Instant.ofEpochMilli(doc.importedAt).atZone(ZoneId.systemDefault()).toLocalDate()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val read = doc.position.fraction.toFloat().coerceIn(0f, 1f)
                if (read > 0f)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { read },
                            modifier = Modifier.weight(1f).height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                        Text(
                            "读到 ${(read * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else
                    Text(
                        "尚未阅读",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(
                    AppIcons.Trash,
                    contentDescription = "删除《${doc.title}》",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    SectionCard(title = "还没有速记文档") {
        Text("• 单个 .md 文件：直接导入。图片不会随单个文件带入。")
        Text("• 文档里有本地图片：把 .md 和图片按原来的相对目录一起打成 ZIP（配套 ZIP），再导入。")
        Text(
            "导入时会复制到应用内，原文件不会被修改，也不会变成题目。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
