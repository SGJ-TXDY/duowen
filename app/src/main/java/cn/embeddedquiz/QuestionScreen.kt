package cn.embeddedquiz

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.delay

@Composable
fun QuestionScreen(vm: QuizViewModel) {
    val q = vm.queue.getOrNull(vm.index)?.let { id -> vm.questions.find { it.id == id } }
    if (q == null) {
        Text("题目加载中或已被恢复操作替换")
        return
    }
    key(q.id, vm.mode) { QuestionContent(vm, q) }
}

@Composable
fun QuestionContent(vm: QuizViewModel, q: Question) {
    var selected by
        rememberSaveable(q.id) {
            mutableStateOf(
                if (vm.mode == "模拟考试") vm.examAnswers[q.id]?.toList() ?: emptyList()
                else emptyList()
            )
        }
    var response by rememberSaveable(q.id) { mutableStateOf("") }
    var revealed by rememberSaveable(q.id) { mutableStateOf(false) }
    val recorded = vm.practiceRecords.containsKey(q.id)
    val savedAssessment = vm.practiceRecords[q.id]?.takeUnless { it == "AUTO" }.orEmpty()
    var code by rememberSaveable(q.id) { mutableStateOf(vm.p(q.id).code) }
    var notes by rememberSaveable(q.id) { mutableStateOf(vm.p(q.id).notes) }
    var prompt by rememberSaveable(q.id) { mutableStateOf("") }
    var remaining by remember { mutableLongStateOf(0) }
    val exam = vm.mode == "模拟考试" && !vm.examFinished
    LaunchedEffect(exam, vm.examDeadline) {
        if (exam)
            while (!vm.examFinished) {
                remaining = ((vm.examDeadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                delay(1000)
            }
    }
    LazyColumn(
        Modifier.testTag("question-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${vm.mode} · ${vm.index+1} / ${vm.queue.size}",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = vm::openStickyNotes) { Text("打开便签") }
            }
        }
        if (exam)
            item {
                Text(
                    "剩余 ${remaining/60}:${(remaining%60).toString().padStart(2,'0')} · 已答 ${vm.examAnswers.count {it.value.isNotEmpty()}} 题"
                )
            }
        if (vm.examFinished && vm.mode == "模拟考试") item { Heading(vm.examResult) }
        item { Text("${q.kind.label} · ${q.category} · ${q.difficulty}") }
        item { Detail(q.stem) }
        items(q.images) { pic ->
            val bitmap =
                remember(pic.data) {
                    runCatching {
                            val b = Base64.decode(pic.data, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                        }
                        .getOrNull()
                }
            if (bitmap != null)
                Image(
                    bitmap,
                    contentDescription = pic.name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                )
            else Text("图片无法显示：${pic.name}")
        }
        items(q.options) { o ->
            OutlinedButton(
                onClick = {
                    selected =
                        if (q.kind == Kind.MULTIPLE) {
                            if (o.key in selected) selected - o.key else selected + o.key
                        } else listOf(o.key)
                    if (exam) vm.answerExam(q.id, selected.toSet())
                },
                enabled =
                    if (exam) remaining > 0 && !vm.busy
                    else !revealed && !recorded && !vm.examFinished,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("${if(o.key in selected)"☑" else "□"} ${o.text}")
            }
        }
        if (!q.objective && q.daily)
            item {
                OutlinedTextField(
                    value = response,
                    onValueChange = { response = it },
                    label = { Text("先写下自己的回答") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    readOnly = revealed || vm.examFinished,
                )
            }
        if (!exam) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(if (vm.p(q.id).favorite) "已收藏" else "收藏") { vm.toggleFavorite(q.id) }
                    Action(if (vm.p(q.id).flagged) "取消质疑" else "标记待核对") { vm.toggleFlag(q.id) }
                }
            }
            item {
                Text("来源：${q.source}\n${q.locator}", style = MaterialTheme.typography.labelSmall)
            }
            if (q.duplicateOf.isNotBlank()) item { Text("同题关联：${q.duplicateOf}（来源和学习记录独立）") }
            if (q.reviewRequired)
                item {
                    Text("待核对：答案缺失或需结合条件核对，不自动判分，可手动判断。", color = MaterialTheme.colorScheme.error)
                }
            if (!revealed && !recorded && !vm.examFinished)
                item {
                    Action(
                        if (q.autoGradable) "提交答案" else "查看参考答案",
                        enabled = !vm.busy && (!q.autoGradable || selected.isNotEmpty()),
                    ) {
                        if (q.autoGradable)
                            vm.submit(q, response, selected.toSet()) { revealed = true }
                        else revealed = true
                    }
                }
            if (revealed || recorded || vm.examFinished) {
                val grade = Grader.grade(q, selected.toSet())
                item {
                    Heading(
                        if (savedAssessment.isNotBlank())
                            "已记录 · 自评：${Assessment.valueOf(savedAssessment).label}"
                        else if (grade == true) "自动判分：正确"
                        else if (grade == false) "自动判分：错误" else "自行核对参考"
                    )
                }
                item {
                    Detail(
                        q.answer.ifBlank {
                            if (q.correct.isNotEmpty()) "标准选项：${q.correct.joinToString()}"
                            else "原文未提供答案"
                        }
                    )
                }
                if (q.explanation.isNotBlank()) item { Detail(q.explanation) }
                if (q.daily && !q.autoGradable && !recorded && !vm.examFinished)
                    item {
                        Column {
                            Text("手动判断本次回答（只记录你的掌握情况，不修改参考答案）")
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Assessment.entries.forEach { a ->
                                    Action(a.label, enabled = !vm.busy) {
                                        vm.assess(q, response, selected.toSet(), a) {}
                                    }
                                }
                            }
                            Text("正确会延长复习间隔；错误和不会加入错题本，次日再复习。")
                        }
                    }
                if (recorded) item { Text("已保存 · 下次复习 ${LocalDate.ofEpochDay(vm.p(q.id).due)}") }
            }
            if (!q.daily) {
                item { Heading("编程题工作区") }
                item {
                    Text("输入输出示例")
                    Detail(q.examples.ifBlank { "原文未单列，请查看完整题干" })
                }
                item {
                    Text("约束条件")
                    Detail(q.constraints.ifBlank { "原文未单列" })
                }
                if (revealed) {
                    item {
                        Text("解题思路")
                        Detail(q.approach.ifBlank { "原文未提供" })
                    }
                    item {
                        Text("参考代码")
                        Detail(q.referenceCode.ifBlank { "原文未提供" })
                    }
                }
                item {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("我的代码（手动保存）") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                    )
                }
                item { Text("当前版本不执行或在线判定代码，请在自己的开发环境验证。") }
            }
            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("个人笔记（手动保存）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            item { Action("保存代码与笔记", !vm.busy) { vm.saveNotes(q.id, code, notes) } }
            item {
                HorizontalDivider()
                Heading("AI 学习助手")
            }
            item { Text("点击后将本题、参考答案、所选选项和最近讨论发送到 DeepSeek。AI 内容独立存储，不能替代核对。") }
            item {
                Action(if (vm.aiBusy) "正在分析…" else "生成详细解析", !vm.aiBusy) {
                    vm.ask(q, "生成详细解析", selected.toSet(), AiRequestType.EXPLANATION)
                }
            }
            items(vm.aiEntries.filter { it.questionId == q.id }, key = { it.cacheKey }) { a ->
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "${a.model} · ${a.prompt}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Detail(a.response)
                        Text(
                            "输入 ${a.inputTokens} / 输出 ${a.outputTokens} tokens · 估算 $${"%.6f".format(a.estimatedUsd)}"
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("追问、错误选项分析、举例或质疑") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Action("发送追问", !vm.aiBusy && prompt.isNotBlank()) {
                    vm.ask(q, prompt, selected.toSet())
                    prompt = ""
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("上一题", vm.index > 0) {
                    vm.index--
                    vm.saveExam()
                }
                Action("下一题", vm.index < vm.queue.lastIndex) {
                    vm.index++
                    vm.saveExam()
                }
                if (exam) Action("交卷", !vm.busy) { vm.finishExam() }
            }
        }
    }
}
