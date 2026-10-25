package cn.embeddedquiz

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun AnswerWorkbench(vm: QuizViewModel) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm::importPersonalUri)
        }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) {
            it?.let(vm::exportPersonalUri)
        }
    var selected by remember(selectedId) { mutableStateOf<Question?>(null) }
    LaunchedEffect(selectedId) { selected = selectedId?.let { vm.loadQuestion(it) } }
    val q = selected
    if (selectedId != null && q == null) {
        Text("正在加载题目…")
        return
    }
    if (q != null) {
        key(q.id) { PersonalAnswerEditor(vm, q) { selectedId = null } }
        return
    }
    val pending =
        vm.questions.filter { it.objective && (it.reviewRequired || it.correctKeys.isBlank()) }
    LazyColumn(
        modifier = Modifier.testTag("workbench-list"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Heading("待核对工作台") }
        item { Text("列出待核对或缺少标准选项的客观题。个人核对答案存放在独立表中，不修改原始题库。") }
        item { Text("判分顺序：当前题目版本有效的个人核对答案 → 原题库已核对标准答案 → 手动判断。题目内容变化后需重新核对。") }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Action("导出个人核对答案", !vm.busy) { export.launch("个人核对答案.json") }
                Action("导入个人核对答案", !vm.busy) {
                    import.launch(arrayOf("application/json", "text/plain"))
                }
            }
        }
        item { Text("共 ${pending.size} 题 · 已保存 ${vm.learning.answers.size} 条个人核对答案") }
        items(pending, key = { it.id }) { summary ->
            Card(onClick = { selectedId = summary.id }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(summary.stem, maxLines = 4)
                    Text(
                        "${summary.kind.label} · ${summary.normalizedCategory} · ${summary.source}"
                    )
                    val answer = vm.learning.answers[summary.id]
                    if (answer != null)
                        Text(
                            if (answer.questionHash == summary.contentHash)
                                "个人核对选项：${answer.selected.sorted().joinToString()}"
                            else "题目内容已变化，请重新核对"
                        )
                    else VerificationNotice("待核对")
                }
            }
        }
    }
}

@Composable
private fun PersonalAnswerEditor(vm: QuizViewModel, q: Question, onBack: () -> Unit) {
    val previous = vm.learning.answers[q.id]
    var selected by
        rememberSaveable(q.id, previous?.updated) {
            mutableStateOf(previous?.takeIf { it.validFor(q) }?.selected?.toList().orEmpty())
        }
    var notes by
        rememberSaveable(q.id, previous?.updated) { mutableStateOf(previous?.notes.orEmpty()) }
    LazyColumn(
        modifier = Modifier.testTag("workbench-editor"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("返回待核对列表")
            }
        }
        item { Heading("个人核对答案") }
        item { Text("${q.id} · ${q.source} · ${q.kind.label}") }
        item { Detail(q.stem) }
        item {
            Text("原始参考：")
            Detail(q.answer.ifBlank { "原题未提供答案" })
        }
        if (q.options.size < 2) item { VerificationNotice("原题选项不完整，暂不允许设置可自动判分答案。请保留待核对状态。") }
        items(q.options) { option ->
            OutlinedButton(
                onClick = {
                    selected =
                        if (q.kind == Kind.MULTIPLE) {
                            if (option.key in selected) selected - option.key
                            else selected + option.key
                        } else listOf(option.key)
                },
                enabled = q.options.size >= 2,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("${if(option.key in selected)"☑" else "□"} ${option.text}")
            }
        }
        item {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("核对备注与依据") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
        }
        item { Text("保存表示你已自行确认选项；AI 建议不会自动成为个人核对答案。") }
        item {
            Action("保存个人核对答案", !vm.busy && selected.isNotEmpty() && q.options.size >= 2) {
                vm.savePersonalAnswer(q, selected.toSet(), notes)
            }
        }
        if (previous != null)
            item {
                OutlinedButton(
                    onClick = { vm.deletePersonalAnswer(q.id) },
                    enabled = !vm.busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("移除个人核对答案")
                }
            }
        // Tags are global notes now; only the per-question difficulty stays here.
        item { PerceivedDifficultyChips(vm, q) }
    }
}
