package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

@Composable
fun History(vm: QuizViewModel) {
    val graded = vm.attempts.filter { it.correct != null }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Heading("学习统计与历史") }
        item {
            Text(
                "累计 ${vm.attempts.size} 次 · 学习 ${vm.attempts.map {it.questionId}.distinct().size} 题\n已判定/自评 ${graded.size} 次，正确 ${graded.count {it.correct==true}} 次，其中不会 ${graded.count {it.assessment==Assessment.UNKNOWN.name}} 次\n未手动判断且无法自动判分的记录不计入正确率。"
            )
        }
        items(vm.attempts, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        vm.questions.find { it.id == a.questionId }?.stem ?: a.questionId,
                        maxLines = 2,
                    )
                    Text(
                        "${Instant.ofEpochMilli(a.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()} · ${a.mode}"
                    )
                    Text(
                        if (a.assessment.isNotBlank())
                            "自评：${Assessment.valueOf(a.assessment).label}"
                        else if (a.correct == null) "未判分" else if (a.correct) "正确" else "错误"
                    )
                    Text(a.response.ifBlank { "未填写文本回答" }, maxLines = 4)
                }
            }
        }
    }
}
