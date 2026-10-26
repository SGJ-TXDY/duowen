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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    var searchError by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    // stickyNotes is a key so editing a matched note refreshes the note results on return.
    LaunchedEffect(filter, vm.questions, vm.progress, vm.learning.metadata, vm.stickyNotes, retry) {
        searching = true
        searchError = ""
        delay(160)
        try {
            val matches = vm.searchQuestions(filter, page)
            val notes = if (filter.search.isBlank()) emptyList() else vm.searchNotes(filter.search)
            filtered = matches
            noteMatches = notes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchError = "搜索失败，请重试"
        }
        searching = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrowseHeader(page, vm)
        SearchField(filter.search) { update(filter.copy(search = it)) }
        if (page == Page.MISTAKES)
            ChoiceChips(listOf("错题", "不会", "收藏", "待核对"), filter.group) {
                update(filter.copy(group = it))
            }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterMenuChip(
                "分类",
                filter.category,
                listOf("全部") + base.map { it.normalizedCategory }.sorted(),
            ) {
                update(filter.copy(category = it))
            }
            FilterMenuChip("来源", filter.source, listOf("全部") + base.map { it.source }) {
                update(filter.copy(source = it))
            }
            FilterMenuChip("公司", filter.company, listOf("全部") + base.map { it.company }) {
                update(filter.copy(company = it))
            }
            FilterMenuChip(
                "题型",
                Kind.entries.firstOrNull { it.name == filter.kind }?.label ?: "全部",
                listOf("全部") + base.map { it.kind.label },
            ) { label ->
                update(
                    filter.copy(kind = Kind.entries.firstOrNull { it.label == label }?.name ?: "全部")
                )
            }
            FilterMenuChip("原题难度", filter.difficulty, listOf("全部") + base.map { it.difficulty }) {
                update(filter.copy(difficulty = it))
            }
        }
        if (page == Page.EXAM) ExamSetup(vm, filtered, searching)
        else
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ResultCount(filtered.size, noteMatches.size, Modifier.weight(1f))
                OutlinedButton(
                    onClick = { vm.start(filtered.shuffled(), section) },
                    enabled = !searching,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Icon(AppIcons.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("随机", modifier = Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = { vm.start(filtered, section) },
                    enabled = !searching,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        if (page == Page.PROGRAMMING) "开始阅读" else "顺序练习",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (searchError.isNotBlank())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ErrorNotice(searchError) }
                TextButton(onClick = { retry++ }) { Text("重试") }
            }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(noteMatches, key = { "note-" + it.id }) { note ->
                Surface(
                    onClick = {
                        vm.editStickyNote(note)
                        vm.openStickyNotes()
                    },
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(AppIcons.Note, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("便签", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        HighlightedText(note.title.ifBlank { "未命名便签" }, filter.search, 2)
                        HighlightedText(note.content, filter.search, 4)
                    }
                }
            }
            if (!searching && filtered.isEmpty() && noteMatches.isEmpty())
                item { EmptyResult(page, filter) }
            items(filtered, key = { it.id }) { q ->
                QuestionRow(vm, q, filter.search) {
                    if (page == Page.EXAM) vm.notice = "请使用上方开始考试，系统会筛选可判分题目"
                    else vm.start(listOf(q), section)
                }
            }
        }
    }
}

@Composable
private fun BrowseHeader(page: Page, vm: QuizViewModel) {
    val title =
        when (page) {
            Page.PROGRAMMING -> "编程专区"
            Page.MISTAKES -> "错题与收藏"
            Page.EXAM -> "模拟考试"
            else -> "题库"
        }
    val subtitle =
        when (page) {
            Page.PROGRAMMING -> "手写代码与编程实现，自动保存草稿"
            Page.MISTAKES -> "答错、不会、收藏和待核对的题"
            Page.EXAM -> "只抽取可自动判分的客观题"
            else -> "日常 ${vm.questions.count { it.daily }} · 编程 ${vm.questions.count { !it.daily }}"
        }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp, start = 12.dp),
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("搜索题干、选项、答案、笔记、便签") },
        leadingIcon = { Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon =
            if (value.isNotEmpty()) {
                {
                    IconButton(onClick = { onChange("") }) {
                        Icon(AppIcons.Close, contentDescription = "清空搜索", modifier = Modifier.size(18.dp))
                    }
                }
            } else null,
        supportingText =
            if (value.isNotBlank()) {
                { Text("空格分隔多个关键词，全部匹配") }
            } else null,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors =
            OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Single-select row of chips for short, mutually exclusive choices. */
@Composable
private fun ChoiceChips(options: List<String>, value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == value,
                onClick = { onChange(option) },
                label = { Text(option) },
                leadingIcon =
                    if (option == value) {
                        { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                shape = MaterialTheme.shapes.small,
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                    ),
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
}

@Composable
private fun ResultCount(questions: Int, notes: Int, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("共 ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$questions", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(
            " 题" + if (notes > 0) " · 便签 $notes 条" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExamSetup(vm: QuizViewModel, filtered: List<QuestionSummary>, searching: Boolean) {
    var count by rememberSaveable { mutableStateOf("20") }
    var minutes by rememberSaveable { mutableStateOf("30") }
    // An unsubmitted exam is resumed, never restarted, so its queue, answers and deadline survive.
    // Its ending status and recovery actions live here too: a missing question may never load.
    if (vm.mode == "模拟考试" && !vm.examFinished && vm.queue.isNotEmpty()) {
        val idle = vm.examOperationState == ExamOperationState.Idle
        var confirm by remember { mutableStateOf<ExamConfirm?>(null) }
        ExamConfirmDialog(vm, confirm) { confirm = null }
        // The failure card is tall; on small screens or large fonts it must scroll, not clip its actions.
        SectionCard(Modifier.verticalScroll(rememberScrollState()), title = "有未交卷的考试") {
            if (idle) {
                Text(
                    "已答 ${vm.queue.count { vm.examAnswers[it].orEmpty().isNotEmpty() }} / ${vm.queue.size} 题，倒计时仍在进行。交卷或放弃后才能开始新的考试。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { vm.page = Page.QUESTION },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("继续考试", style = MaterialTheme.typography.titleMedium)
                }
            }
            ExamEndPanel(vm, showRetry = true) { confirm = ExamConfirm.ABANDON }
            if (!idle)
                SecondaryAction("查看试卷", modifier = Modifier.fillMaxWidth()) { vm.page = Page.QUESTION }
        }
        return
    }
    SectionCard(title = "考试设置") {
        Text("题量", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(listOf("10", "20", "50"), count) { count = it }
        Text("时长（分钟）", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(listOf("15", "30", "60"), minutes) { minutes = it }
        Text(
            "当前筛选 ${filtered.size} 题，仅抽取原题库或个人核对答案可判分的客观题。切换到便签仍会计时，选择自动保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { vm.startExam(filtered, count.toInt(), minutes.toInt()) },
            enabled = !vm.busy && !searching,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("开始考试", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun QuestionRow(vm: QuizViewModel, q: QuestionSummary, search: String, onClick: () -> Unit) {
    val progress = vm.p(q.id)
    val meta = vm.learning.metadata[q.id]
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (q.objective)
                    Tag(
                        q.kind.label,
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.primary,
                        bold = true,
                    )
                else Tag(q.kind.label, bold = true)
                Text(
                    q.company,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (progress.favorite)
                    Icon(
                        AppIcons.StarFilled,
                        contentDescription = "已收藏",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                if (q.reviewRequired || progress.flagged)
                    Tag(
                        "待核对",
                        MaterialTheme.status.warningContainer,
                        MaterialTheme.status.onWarningContainer,
                    )
                else if (progress.seen > 0)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            AppIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.status.success,
                            modifier = Modifier.size(14.dp),
                        )
                        Text("已学", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.status.success)
                    }
            }
            HighlightedText(q.stem, search, 3)
            if (q.matchSnippet.isNotBlank())
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
                    Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        HighlightedText(q.matchSnippet, search, 3)
                    }
                }
            // Legacy per-question tags stay searchable but are no longer shown; tags are global now.
            meta?.perceivedDifficulty?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "我感觉：$it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                listOfNotNull(
                        q.normalizedCategory,
                        q.source,
                        q.difficulty.takeIf { it != "未标注" }?.let { "难度：$it" },
                        "已保存个人核对答案".takeIf {
                            vm.learning.answers[q.id]?.questionHash == q.contentHash
                        },
                    )
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyResult(page: Page, filter: BrowseFilter) {
    val filtered =
        filter.search.isNotBlank() ||
            listOf(filter.source, filter.company, filter.category, filter.kind, filter.difficulty)
                .any { it != "全部" }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (page == Page.MISTAKES) AppIcons.Check else AppIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Text(
            when {
                filtered -> "没有符合条件的题目，试试放宽筛选"
                page == Page.MISTAKES -> "这里还是空的"
                else -> "没有题目"
            },
            style = MaterialTheme.typography.titleSmall,
        )
    }
}
