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
import kotlinx.coroutines.*

@Composable
fun Browse(vm: QuizViewModel, section: String) {
    val page =
        when (section) {
            "编程专区" -> Page.PROGRAMMING
            "错题收藏" -> Page.MISTAKES
            "模拟考试" -> Page.EXAM
            else -> Page.BROWSE
        }
    val filter = vm.learning.filters[page] ?: BrowseFilter()
    fun update(value: BrowseFilter) {
        vm.learning.filters[page] = value
    }
    val base = vm.questions.filter { if (page == Page.PROGRAMMING) !it.daily else it.daily }
    var filtered by remember { mutableStateOf<List<QuestionSummary>>(emptyList()) }
    var noteMatches by remember { mutableStateOf<List<StickyNote>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    LaunchedEffect(filter, vm.questions, vm.progress, vm.learning.metadata) {
        searching = true
        delay(160)
        withContext(Dispatchers.IO) {
            val matches = vm.repo.search(filter, page == Page.PROGRAMMING, page == Page.MISTAKES)
            val notes =
                if (filter.search.isBlank()) emptyList() else vm.repo.searchNotes(filter.search)
            withContext(Dispatchers.Main) {
                filtered = matches
                noteMatches = notes
                searching = false
            }
        }
    }
    Column {
        OutlinedTextField(
            value = filter.search,
            onValueChange = { update(filter.copy(search = it)) },
            label = { Text("搜索题干、选项、答案、笔记、便签") },
            supportingText = { Text("空格分隔多个关键词，全部匹配") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Picker("来源", filter.source, listOf("全部") + base.map { it.source }) {
                update(filter.copy(source = it))
            }
            Picker("公司", filter.company, listOf("全部") + base.map { it.company }) {
                update(filter.copy(company = it))
            }
            Picker("规范分类", filter.category, listOf("全部") + base.map { it.normalizedCategory }) {
                update(filter.copy(category = it))
            }
            Picker(
                "题型",
                Kind.entries.firstOrNull { it.name == filter.kind }?.label ?: "全部",
                listOf("全部") + base.map { it.kind.label },
            ) { label ->
                update(
                    filter.copy(kind = Kind.entries.firstOrNull { it.label == label }?.name ?: "全部")
                )
            }
            Picker("原题难度", filter.difficulty, listOf("全部") + base.map { it.difficulty }) {
                update(filter.copy(difficulty = it))
            }
        }
        if (page == Page.MISTAKES)
            Picker("查看", filter.group, listOf("错题", "不会", "收藏", "待核对")) {
                update(filter.copy(group = it))
            }
        Text(
            "共 ${filtered.size} 题${if(noteMatches.isNotEmpty())" · 匹配便签 ${noteMatches.size} 条" else ""}"
        )
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (page == Page.EXAM) {
            var count by rememberSaveable { mutableStateOf("20") }
            var minutes by rememberSaveable { mutableStateOf("30") }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Picker("题量", count, listOf("10", "20", "50")) { count = it }
                Picker("分钟", minutes, listOf("15", "30", "60")) { minutes = it }
            }
            Text("仅抽取原题库或个人核对答案可判分的客观题。切换便签仍计时，选择自动保存。")
            Action("开始考试", !vm.busy && !searching) {
                vm.startExam(filtered, count.toInt(), minutes.toInt())
            }
        } else
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Action(if (page == Page.PROGRAMMING) "开始阅读" else "顺序练习", !searching) {
                    vm.start(filtered, section)
                }
                Action("随机", !searching) { vm.start(filtered.shuffled(), section) }
            }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(noteMatches, key = { "note-" + it.id }) { note ->
                Card(
                    onClick = {
                        vm.editStickyNote(note)
                        vm.openStickyNotes()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("便签", color = MaterialTheme.colorScheme.primary)
                        HighlightedText(note.title.ifBlank { "未命名便签" }, filter.search, 2)
                        HighlightedText(note.content, filter.search, 4)
                    }
                }
            }
            items(filtered, key = { it.id }) { q ->
                Card(
                    onClick = {
                        if (page == Page.EXAM) vm.notice = "请使用上方开始考试，系统会筛选可判分题目"
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
                        HighlightedText(q.stem, filter.search, 3)
                        if (q.matchSnippet.isNotBlank())
                            HighlightedText(q.matchSnippet, filter.search, 3)
                        Text(
                            "${q.normalizedCategory} · ${q.source} · 原题难度：${q.difficulty}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        vm.learning.metadata[q.id]?.let { meta ->
                            if (meta.tags.isNotBlank()) Text("标签：${meta.tags}")
                            if (meta.perceivedDifficulty.isNotBlank())
                                Text("我感觉：${meta.perceivedDifficulty}")
                        }
                        if (q.reviewRequired || vm.p(q.id).flagged) VerificationNotice("待核对")
                        if (vm.learning.answers[q.id]?.questionHash == q.contentHash)
                            Text("已保存个人核对答案", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
