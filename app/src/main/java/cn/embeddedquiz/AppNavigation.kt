package cn.embeddedquiz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.delay

@Composable
fun QuizApp(vm: QuizViewModel) {
    AppTheme(vm.learning.settings) { QuizLayout(vm) }
}

@Composable
private fun QuizLayout(vm: QuizViewModel) {
    val practiceState = key(vm.practiceSession) { rememberSaveableStateHolder() }
    val pageState = rememberSaveableStateHolder()
    BackHandler(enabled = vm.page != Page.HOME) {
        when (vm.page) {
            Page.NOTES -> if (vm.noteDraft != null) vm.closeStickyNote() else vm.leaveStickyNotes()
            Page.QUESTION,
            Page.REVIEW,
            Page.MISTAKES,
            Page.MORE,
            Page.EXAM,
            // Opened from the home screen's practice modes, so back returns there.
            Page.BROWSE,
            Page.PROGRAMMING -> vm.page = Page.HOME
            else -> vm.page = Page.MORE
        }
    }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now()
            vm.learning.refreshDate(today)
            delay(1000)
        }
    }
    LaunchedEffect(vm.ready, vm.mode, vm.examDeadline, vm.examFinished) {
        if (vm.ready && vm.mode == "模拟考试" && !vm.examFinished && vm.examDeadline > 0) {
            while (!vm.examFinished) {
                if (System.currentTimeMillis() >= vm.examDeadline) vm.finishExam()
                delay(1000)
            }
        }
    }
    val bottom =
        listOf(
            Triple(Page.HOME, "首页", AppIcons.Home),
            Triple(Page.EXAM, "模拟考试", AppIcons.Exam),
            Triple(Page.REVIEW, "复习", AppIcons.Review),
            Triple(Page.MISTAKES, "错题", AppIcons.Mistakes),
            Triple(Page.MORE, "更多", AppIcons.More),
        )
    val current =
        when (vm.page) {
            in bottom.map { it.first } -> vm.page
            // Category, programming and the practice picker are entered from the home screen.
            Page.BROWSE,
            Page.PROGRAMMING,
            Page.QUESTION -> Page.HOME
            else -> Page.MORE
        }
    // Answering is a focused flow with its own action bar, so the tab bar steps aside.
    // Each Scaffold slot reads this itself so it reacts to page changes on its own.
    fun answering() = vm.ready && vm.page == Page.QUESTION && vm.queue.isNotEmpty()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.notice) {
        if (vm.notice.isNotEmpty()) {
            snackbar.showSnackbar(
                vm.notice,
                actionLabel = "关闭",
                duration = SnackbarDuration.Long,
            )
            vm.notice = ""
        }
    }
    Scaffold(
        snackbarHost = {
            // Without a tab bar the message would cover the answer screen's action bar.
            SnackbarHost(
                snackbar,
                Modifier.padding(bottom = if (answering()) 80.dp else 0.dp),
            )
        },
        bottomBar = {
            // Read inside this slot so summary updates only touch the tab bar; hidden while loading.
            val dueCount =
                vm.todaySummary?.takeIf { it.day == today.toEpochDay() }?.dueCount ?: 0
            if (!answering())
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    bottom.forEach { (page, label, icon) ->
                        NavigationBarItem(
                            selected = current == page,
                            onClick = { vm.navigate(page) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (page == Page.REVIEW && dueCount > 0)
                                            Badge { Text(if (dueCount > 99) "99+" else "$dueCount") }
                                    }
                                ) {
                                    Icon(icon, contentDescription = null)
                                }
                            },
                            label = { Text(label) },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            modifier =
                                Modifier.heightIn(min = 64.dp).semantics {
                                    contentDescription = "导航到$label"
                                },
                        )
                    }
                }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { insets ->
        val answering = answering()
        val direction = LocalLayoutDirection.current
        // The answer screen draws its action bar to the screen edge and pads the gesture area itself.
        val padding =
            if (answering)
                PaddingValues(
                    start = insets.calculateStartPadding(direction),
                    top = insets.calculateTopPadding(),
                    end = insets.calculateEndPadding(direction),
                )
            else insets
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!vm.ready) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("嵌入式面试助手", style = MaterialTheme.typography.headlineSmall)
                    Text(vm.loadingMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(
                        progress = { vm.importProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (answering)
                Box(Modifier.weight(1f)) {
                    practiceState.SaveableStateProvider("question-${vm.index}") {
                        QuestionScreen(vm)
                    }
                }
            else
                Box(Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    pageState.SaveableStateProvider(vm.page.name) {
                        when (vm.page) {
                            Page.HOME -> Home(vm, today)
                            Page.QUESTION ->
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Heading("开始刷题")
                                    Action("顺序练习") {
                                        vm.start(vm.questions.filter { it.daily }, "顺序练习")
                                    }
                                    Action("随机练习") {
                                        vm.start(
                                            vm.questions.filter { it.daily }.shuffled(),
                                            "随机练习",
                                        )
                                    }
                                    Action("按分类选题") { vm.page = Page.BROWSE }
                                }
                            Page.BROWSE -> Browse(vm, "分类")
                            Page.REVIEW -> Review(vm, today)
                            Page.PROGRAMMING -> Browse(vm, "编程专区")
                            Page.MISTAKES -> Browse(vm, "错题收藏")
                            Page.EXAM -> ExamScreen(vm)
                            Page.STATISTICS -> History(vm)
                            Page.SETTINGS -> Settings(vm)
                            Page.NOTES -> StickyNotesScreen(vm)
                            Page.MORE -> MoreScreen(vm)
                            Page.WORKBENCH -> AnswerWorkbench(vm)
                            Page.COSTS -> CostsScreen(vm)
                            Page.ERROR_LOG -> ErrorLogScreen(vm)
                        }
                    }
                }
        }
    }
}

@Composable
private fun MoreScreen(vm: QuizViewModel) {
    LazyColumn(
        modifier = Modifier.testTag("more-list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Heading("更多") }
        // Category, programming and exam now live on the home screen and the tab bar.
        listOf(Page.NOTES, Page.WORKBENCH, Page.STATISTICS, Page.COSTS, Page.SETTINGS)
            .forEach { page ->
                item {
                    OutlinedButton(
                        onClick = { vm.navigate(page) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(if (page == Page.NOTES) "全局标签 / 便签" else page.label)
                    }
                }
            }
    }
}
