package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun Home(vm: QuizViewModel, today: LocalDate) {
    val daily = vm.questions.filter { it.daily }
    val day = today.toEpochDay()
    val done = vm.attempts.filter { it.day == day }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Heading("今天，稳步积累") }
        item { Text("$today · 每一次回忆，都让知识更牢固") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Heading("${vm.due(day).size} 题待复习")
                    Text("今日已完成 ${done.map { it.questionId }.distinct().size} 题")
                    Text(
                        "新题学习 ${done.filter { it.wasNew }.map { it.questionId }.distinct().size} · 复习完成 ${done.filter { it.mode=="到期复习" }.map { it.questionId }.distinct().size}"
                    )
                    Action("开始今日复习") { vm.start(vm.due(day), "到期复习") }
                }
            }
        }
        item { Heading("继续学习") }
        item {
            Text(
                "未学新题 ${daily.count { vm.p(it.id).seen==0 }} · 日常题库 ${daily.size} · 编程专区 ${vm.questions.count { !it.daily }}"
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Card {
                Text(
                    "有已核对标准选项的题目提交后直接判定正确或错误；其余题目自行核对后选择正确、错误或不会。答题时可随时打开便签，再继续原题。",
                    Modifier.padding(16.dp),
                )
            }
        }
    }
}
