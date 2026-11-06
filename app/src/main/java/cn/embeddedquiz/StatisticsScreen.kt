package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*

private fun rate(correct: Int, total: Int) =
    if (total == 0) "无记录" else "${correct*100/total}%（$correct/$total）"

@Composable
fun History(vm: QuizViewModel) {
    val today = LocalDate.now().toEpochDay()
    val totals = vm.learning.totals
    var page by rememberSaveable { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<List<Attempt>>(emptyList()) }
    LaunchedEffect(page, totals.attempts) {
        history = withContext(Dispatchers.IO) { vm.repo.dao.historyPage(30, page * 30) }
    }
    LazyColumn(
        modifier = Modifier.testTag("statistics-list"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(
                "累计 ${totals.attempts} 次 · 学习 ${totals.studied} 题\n自动判分：${rate(totals.autoCorrect,totals.automatic)}\n简答等手动自评：${rate(totals.manualCorrect,totals.manual)}"
            )
        }
        item { Text("每日答题量按本地日期内完成的不同题目计数，与首页目标一致；正确率按每次有效判分计算。自动判分与手动自评分开统计，无判分不计正确率。") }
        item { Heading("近 30 天答题量与正确率") }
        item { DailyChart(vm.learning.daily, today) }
        item { Text("柱形：每日完成题数；实线：自动判分正确率；另一条线：手动自评正确率。无有效记录的日期不连接。") }
        item { Heading("规范分类与薄弱点") }
        item { Action("薄弱知识点一键练习") { vm.startWeakPractice() } }
        if (vm.learning.categories.isEmpty()) item { Text("完成答题后显示分类统计。") }
        items(vm.learning.categories, key = { it.category }) { category ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        category.category.ifBlank { "未分类" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (category.automatic + category.manual >= 3 && category.rate < 0.65)
                        VerificationNotice("薄弱知识点")
                    Text("自动判分：${rate(category.autoCorrect,category.automatic)}")
                    Text("手动自评：${rate(category.manualCorrect,category.manual)}")
                    LinearProgressIndicator(
                        progress = { category.rate.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("掌握度：最近一次答对 ${category.mastered} / 已学 ${category.answered} 题")
                }
            }
        }
        item { Text("按合并正确率从低到高排序。薄弱筛选需至少 3 次有效作答且低于 65%；自动与自评分项用于辨别依据。") }
        item { Heading("未来 7 天到期量（含今天）") }
        item { DueChart(vm.learning.upcoming, today) }
        item { Text("今天包含此前未完成的到期题；预测基于当前复习排程，后续作答会改变结果。") }
        item { Heading("答题历史") }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Action("上一页", page > 0) { page-- }
                Text("第 ${page+1} 页", Modifier.padding(top = 16.dp))
                Action("下一页", (page + 1) * 30 < totals.attempts) { page++ }
            }
        }
        items(history, key = { it.id }) { attempt ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        vm.questions.firstOrNull { it.id == attempt.questionId }?.stem
                            ?: attempt.questionId,
                        maxLines = 2,
                    )
                    Text(
                        "${Instant.ofEpochMilli(attempt.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()} · ${attempt.mode}"
                    )
                    Text(
                        if (attempt.assessment.isNotBlank())
                            "自评：${Assessment.valueOf(attempt.assessment).label}"
                        else if (attempt.correct == null) "未判分"
                        else if (attempt.correct) "正确" else "错误"
                    )
                    Text(
                        "答案依据：${when(attempt.answerSource) {"PERSONAL"->"个人核对"
"MANUAL"->"手动判断"
else->"原题库或旧记录"}}"
                    )
                    Text(attempt.response.ifBlank { "未填写文本回答" }, maxLines = 4)
                }
            }
        }
    }
}

@Composable
private fun DailyChart(values: List<DailyCount>, today: Long) {
    val days = (today - 29..today).toList()
    val map = values.associateBy { it.day }
    val bar = MaterialTheme.colorScheme.secondary
    val automatic = MaterialTheme.colorScheme.primary
    val manual = MaterialTheme.colorScheme.tertiary
    val max = (values.maxOfOrNull { it.answered } ?: 1).coerceAtLeast(1)
    val description =
        days.joinToString("；") { day ->
            val row = map[day]
            "${LocalDate.ofEpochDay(day)} 完成 ${row?.answered ?: 0} 题，自动 ${rate(row?.autoCorrect ?: 0,row?.automatic ?: 0)}，自评 ${rate(row?.manualCorrect ?: 0,row?.manual ?: 0)}"
        }
    Column {
        Text("最高 $max 题 · 正确率 0–100%")
        Canvas(
            Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }
        ) {
            val width = size.width / 30
            var previousAuto: Offset? = null
            var previousManual: Offset? = null
            days.forEachIndexed { index, day ->
                val row = map[day]
                val height = size.height * (row?.answered ?: 0) / max
                drawRect(
                    bar.copy(alpha = 0.4f),
                    Offset(index * width + width * 0.2f, size.height - height),
                    Size(width * 0.6f, height),
                )
                fun point(count: Int, correct: Int, color: Color, previous: Offset?): Offset? {
                    if (count == 0) return null
                    val value =
                        Offset(
                            (index + 0.5f) * width,
                            size.height * (1f - correct.toFloat() / count),
                        )
                    previous?.let { drawLine(color, it, value, 3.dp.toPx()) }
                    drawCircle(color, 2.5.dp.toPx(), value)
                    return value
                }
                previousAuto =
                    point(row?.automatic ?: 0, row?.autoCorrect ?: 0, automatic, previousAuto)
                previousManual =
                    point(row?.manual ?: 0, row?.manualCorrect ?: 0, manual, previousManual)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(LocalDate.ofEpochDay(today - 29).format(DateTimeFormatter.ofPattern("MM-dd")))
            Text(LocalDate.ofEpochDay(today).format(DateTimeFormatter.ofPattern("MM-dd")))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("自动判分", color = automatic)
            Text("手动自评", color = manual)
        }
    }
}

@Composable
private fun DueChart(values: List<DueCount>, today: Long) {
    val map = values.associate { it.day to it.count }
    val counts = (today..today + 6).map { map[it] ?: 0 }
    val maximum = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
    val color = MaterialTheme.colorScheme.primary
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).semantics {
                contentDescription =
                    counts
                        .mapIndexed { index, count ->
                            "${LocalDate.ofEpochDay(today+index)} 到期 $count 题"
                        }
                        .joinToString("；")
            }
        ) {
            val width = size.width / 7
            counts.forEachIndexed { index, count ->
                val height = size.height * count / maximum
                drawRect(
                    color,
                    Offset(index * width + width * 0.2f, size.height - height),
                    Size(width * 0.6f, height),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            counts.forEachIndexed { index, count ->
                Column {
                    Text("${LocalDate.ofEpochDay(today+index).dayOfMonth}日")
                    Text("$count")
                }
            }
        }
    }
}

@Composable
fun CostsScreen(vm: QuizViewModel) {
    LazyColumn(
        modifier = Modifier.testTag("costs-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(
                "按日、按模型汇总成功请求。缓存读取不新增费用。采用 ${DeepSeekClient.PRICES_VERIFIED} 核对的官方美元高峰费率作保守估算，实际以 DeepSeek 账单为准。"
            )
        }
        if (vm.learning.costs.isEmpty()) item { Text("尚无成功请求的用量记录。") }
        items(vm.learning.costs, key = { "${it.day}-${it.model}" }) { cost ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("${LocalDate.ofEpochDay(cost.day)} · ${cost.model}")
                    Text("输入 ${cost.inputTokens} / 输出 ${cost.outputTokens} 词元")
                    Text("估算费用：$${"%.6f".format(cost.estimatedUsd)} 美元")
                }
            }
        }
    }
}
