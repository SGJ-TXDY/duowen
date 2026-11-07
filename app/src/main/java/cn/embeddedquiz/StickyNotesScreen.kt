package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

@Composable
internal fun StickyNotesScreen(
    vm: QuizViewModel,
    /** Sources of notes saved from AI replies (PLAN-1.7.1); null shows notes without them. */
    sources: StickyNoteSources? = null,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<StickyNote?>(null) }
    val draft = vm.noteDraft
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<NoteSourceOpen.Ready?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    var sourceNotice by remember { mutableStateOf<String?>(null) }

    /** Opens the note's question read only; a missing one only explains itself. */
    fun openSource(note: StickyNote) {
        val reader = sources ?: return
        if (opening != null) return
        opening = note.id
        sourceNotice = null
        scope.launch {
            try {
                when (val r = reader.open(note)) {
                    is NoteSourceOpen.Ready -> viewing = r
                    NoteSourceOpen.Missing -> sourceNotice = "原题已不在题库中，来源不可用；便签内容不受影响。"
                }
            } finally {
                opening = null
            }
        }
    }

    viewing?.let {
        SourceQuestionView(it) { viewing = null }
        return
    }
    deleting?.let { note ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除便签？") },
            text = { Text("将删除“${note.title.ifBlank { "未命名便签" }}”，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteStickyNote(note)
                        deleting = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Title and back live in the sub-page top bar; back closes an open note before the list.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "独立于题目，所有题目共用同一份；换题不会改变这里的内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (vm.queue.isNotEmpty())
                OutlinedButton(
                    onClick = { vm.page = Page.QUESTION },
                    modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp),
                ) {
                    Text("继续答题")
                }
        }
        if (vm.mode == "模拟考试" && !vm.examFinished && vm.queue.isNotEmpty())
            Text("考试仍在计时，作答进度已保留。", color = MaterialTheme.colorScheme.error)
        sourceNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("note-source-notice")) }
        if (vm.noteSaveError.isNotBlank()) {
            Text(vm.noteSaveError, color = MaterialTheme.colorScheme.error)
            Action("重试保存", !vm.busy && vm.pendingNoteWrites == 0) { vm.retryStickyNotes() }
        } else
            Text(
                if (vm.pendingNoteWrites > 0) "正在保存…" else "自动保存到本机",
                style = MaterialTheme.typography.labelMedium,
            )
        if (draft != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::closeStickyNote) { Text("全部便签") }
                TextButton(onClick = { deleting = draft }, enabled = !vm.busy) { Text("删除便签") }
            }
            sources?.source(draft)?.let { NoteSourceRow(it, opening == draft.id) { openSource(draft) } }
            key(draft.id) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { vm.updateStickyNote(it, draft.content) },
                    label = { Text("便签标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !vm.busy,
                )
                OutlinedTextField(
                    value = draft.content,
                    onValueChange = { vm.updateStickyNote(draft.title, it) },
                    label = { Text("记下思路、易错点或待办") },
                    modifier = Modifier.fillMaxWidth().weight(1f).imePadding(),
                    enabled = !vm.busy,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("新建便签", !vm.busy) { vm.newStickyNote() }
                Text("共 ${vm.stickyNotes.size} 条", Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("搜索标题或内容") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            val filtered =
                vm.stickyNotes.filter { note ->
                    SearchTerms.split(search).all { term ->
                        note.title.contains(term, true) || note.content.contains(term, true)
                    }
                }
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (filtered.isEmpty())
                    item { Text(if (search.isBlank()) "还没有便签，随时记下学习中的想法。" else "没有匹配的便签") }
                items(filtered, key = { it.id }) { note ->
                    Card(
                        onClick = { vm.editStickyNote(note) },
                        enabled = !vm.busy && vm.pendingNoteWrites == 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                note.title.ifBlank { "未命名便签" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                            )
                            Text(note.content.ifBlank { "空白便签" }, maxLines = 4)
                            sources?.source(note)?.let { NoteSourceRow(it, opening == note.id) { openSource(note) } }
                            Text(
                                "更新于 ${Instant.ofEpochMilli(note.updated).atZone(ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0)}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
