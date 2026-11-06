package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun Review(vm: QuizViewModel, today: LocalDate) {
    val due = vm.due(today.toEpochDay())
    // The page title lives in the sub-page top bar.
    LazyColumn(
        modifier = Modifier.testTag("review-list"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(
                "今日到期 ${due.size} · 连续掌握 ${vm.progress.values.count {it.repetitions>=3}} · 未学 ${vm.questions.count {it.daily&&vm.p(it.id).seen==0}}"
            )
        }
        item { Text("答错或不会后隔 1 天重学；初次答对隔 1 天、第二次隔 6 天，随后逐步延长复习间隔。") }
        item { Action("错题优先开始") { vm.start(due, "到期复习") } }
        items(
            vm.questions.filter { it.daily && vm.p(it.id).seen > 0 }.sortedBy { vm.p(it.id).due },
            key = { it.id },
        ) { q ->
            val p = vm.p(q.id)
            Card(onClick = { vm.start(listOf(q), "复习浏览") }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(q.stem, maxLines = 2)
                    Text(
                        "下次 ${LocalDate.ofEpochDay(p.due)} · 连续 ${p.repetitions} 次 · 间隔 ${p.interval} 天 · 系数 ${"%.2f".format(p.ease)}"
                    )
                }
            }
        }
    }
}
