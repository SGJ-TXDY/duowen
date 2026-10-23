package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.*

@Composable
fun Home(vm: QuizViewModel, today: LocalDate) {
    val day = today.toEpochDay()
    val daily = vm.questions.filter { it.daily }
    val count = vm.learning.daily.firstOrNull { it.day == day }
    val goal = vm.learning.settings.dailyGoal
    val completed = count?.answered ?: 0
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Heading("今天，稳步积累") }
        item { Text("$today · 连续学习 ${LearningDates.streak(vm.learning.learnedDays,today)} 天") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Heading("今日目标 $completed / $goal 题")
                    LinearProgressIndicator(
                        progress = { (completed.toFloat() / goal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("按当日完成的不同题目计数，重复作答不重复增加目标进度。")
                    Text("新题学习 ${count?.newAnswered ?: 0} · 复习完成 ${count?.reviewAnswered ?: 0}")
                    Heading("${vm.due(day).size} 题待复习")
                    Action("开始今日复习") { vm.start(vm.due(day), "到期复习") }
                }
            }
        }
        item { Heading("继续学习") }
        item {
            Text(
                "未学新题 ${daily.count {vm.p(it.id).seen==0}} · 日常题库 ${daily.size} · 编程专区 ${vm.questions.count {!it.daily}}"
            )
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Action("学习新题") { vm.start(daily.filter { vm.p(it.id).seen == 0 }, "新题") }
                Action("随机练习") { vm.start(daily.shuffled(), "随机练习") }
            }
        }
        item { Action("顺序练习") { vm.start(daily, "顺序练习") } }
        if (vm.queue.isNotEmpty())
            item {
                Action(if (vm.mode == "模拟考试" && !vm.examFinished) "继续未交卷考试" else "继续上次练习") {
                    vm.page = Page.QUESTION
                }
            }
        item {
            OutlinedButton(
                onClick = vm::startWeakPractice,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("薄弱知识点一键练习")
            }
        }
        item {
            Card {
                Text(
                    "有可靠标准选项时直接判分；没有可自动核对答案时选择正确、错误或不会。个人核对答案独立保存，AI 建议需自行核实。",
                    Modifier.padding(16.dp),
                )
            }
        }
    }
}
