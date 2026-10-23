package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Browse(vm: QuizViewModel, section: String) {
    var search by rememberSaveable(section) { mutableStateOf("") }
    var company by rememberSaveable(section) { mutableStateOf("全部") }
    var category by rememberSaveable(section) { mutableStateOf("全部") }
    var kind by rememberSaveable(section) { mutableStateOf("全部") }
    var difficulty by rememberSaveable(section) { mutableStateOf("全部") }
    var group by rememberSaveable(section) { mutableStateOf("错题") }
    var source by rememberSaveable(section) { mutableStateOf("全部") }
    val base = vm.questions.filter { if (section == "编程专区") !it.daily else it.daily }
    val filtered =
        base.filter { q ->
            (company == "全部" || q.company == company) &&
                (category == "全部" || q.category == category) &&
                (kind == "全部" || q.kind.label == kind) &&
                (difficulty == "全部" || q.difficulty == difficulty) &&
                (source == "全部" || q.source == source) &&
                (search.isBlank() ||
                    ("${q.stem} ${q.answer} ${q.category}").contains(search, true)) &&
                (section != "错题收藏" ||
                    when (group) {
                        "错题" -> vm.p(q.id).wrong
                        "不会" ->
                            vm.p(q.id).wrong &&
                                vm.attempts.firstOrNull { it.questionId == q.id }?.assessment ==
                                    Assessment.UNKNOWN.name
                        "收藏" -> vm.p(q.id).favorite
                        else -> vm.p(q.id).flagged || q.reviewRequired
                    })
        }
    Column {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("搜索题干、答案、知识点") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Picker("来源", source, listOf("全部") + base.map { it.source }) { source = it }
            Picker("公司", company, listOf("全部") + base.map { it.company }) { company = it }
            Picker("知识点", category, listOf("全部") + base.map { it.category }) { category = it }
            Picker("题型", kind, listOf("全部") + base.map { it.kind.label }) { kind = it }
            Picker("难度", difficulty, listOf("全部") + base.map { it.difficulty }) { difficulty = it }
        }
        if (section == "错题收藏") Picker("查看", group, listOf("错题", "不会", "收藏", "待核对")) { group = it }
        Text("共 ${filtered.size} 题")
        if (section == "模拟考试") {
            var count by rememberSaveable { mutableStateOf("20") }
            var minutes by rememberSaveable { mutableStateOf("30") }
            Row {
                Picker("题量", count, listOf("10", "20", "50")) { count = it }
                Spacer(Modifier.width(8.dp))
                Picker("分钟", minutes, listOf("15", "30", "60")) { minutes = it }
            }
            Text("仅抽取有可靠标准答案的客观题，交卷后显示解析。考试计时和作答会保存。")
            Action("开始考试") { vm.startExam(filtered, count.toInt(), minutes.toInt()) }
        } else
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(if (section == "编程专区") "开始阅读" else "顺序练习") { vm.start(filtered, section) }
                Action("随机") { vm.start(filtered.shuffled(), section) }
            }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(filtered, key = { it.id }) { q ->
                Card(
                    onClick = {
                        if (section == "模拟考试") vm.notice = "请使用上方“开始考试”按钮，系统会筛选可判分题目"
                        else vm.start(listOf(q), section)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            "${q.kind.label} · ${q.company}",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(q.stem, maxLines = 3)
                        Text(
                            "${q.source} · ${q.difficulty}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (q.reviewRequired || vm.p(q.id).flagged)
                            Text("待核对", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
