package cn.embeddedquiz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.delay

@Composable
fun QuizApp(vm: QuizViewModel) {
    AppTheme(vm.learning.settings, vm.accent.id) { QuizLayout(vm) }
}

@Composable
private fun QuizLayout(vm: QuizViewModel) {
    val practiceState = key(vm.practiceSession) { rememberSaveableStateHolder() }
    val pageState = rememberSaveableStateHolder()
    val startup = vm.startupState
    // Until Ready only the startup screen exists: no app pages, tab bar or business effects.
    val started = startup is StartupState.Ready
    val trail = rememberSaveable(saver = PageTrail.saver) { PageTrail(vm.page) }
    trail.observe(vm.page)
    // The sub-page top bar dispatches here too, so both backs share one target per page.
    BackHandler(enabled = started && vm.page != Page.HOME) {
        when (vm.page) {
            Page.NOTES -> if (vm.noteDraft != null) vm.closeStickyNote() else vm.leaveStickyNotes()
            else -> vm.page = PagePolicy.parent(vm.page, trail.costsFrom)
        }
    }
    var today by remember { mutableStateOf(LocalDate.now()) }
    // Deliberately not gated on Ready: it only sets a date nobody observes before learning starts.
    // Gating it made later Activity tests in the same process never reach idle (see T3 evidence).
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
    // Abandoning empties the queue, which removes the answer screen before it could react.
    LaunchedEffect(vm.examOperationState) {
        if (vm.examOperationState == ExamOperationState.Abandoned && vm.page == Page.QUESTION)
            vm.page = Page.EXAM
    }
    // 速记 has no Page of its own (page = null): it overlays the content and keeps the quiz session.
    val bottom =
        listOf(
            Tab(Page.HOME, "首页", AppIcons.Home),
            Tab(Page.EXAM, "模拟考试", AppIcons.Exam),
            Tab(null, "速记", AppIcons.QuickNotes),
            Tab(Page.MISTAKES, "错题", AppIcons.Mistakes),
            Tab(Page.MORE, "更多", AppIcons.More),
        )
    val reader = remember { QuickNoteReaderHandle() }
    // A notification or another flow may switch pages while 速记 is open; follow it there.
    LaunchedEffect(vm.page) { if (vm.quickNotes.state.visible) vm.quickNotes.leave() }
    val current =
        when (vm.page) {
            in bottom.map { it.page } -> vm.page
            // Category, programming and the practice picker are entered from the home screen.
            Page.BROWSE,
            Page.PROGRAMMING,
            Page.QUESTION -> Page.HOME
            else -> Page.MORE
        }
    fun select(tab: Tab) {
        val target = tab.page
        when {
            target == null -> vm.openQuickNotes()
            // Store the last reading position before the module hides.
            vm.quickNotes.state.visible -> reader.finish { vm.quickNotes.leave { vm.navigate(target) } }
            else -> vm.navigate(target)
        }
    }
    // Answering is a focused flow with its own action bar, so the tab bar steps aside.
    // Each Scaffold slot reads this itself so it reacts to page changes on its own.
    fun answering() = vm.ready && vm.page == Page.QUESTION && vm.queue.isNotEmpty()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.notice, started) {
        if (vm.notice.isNotEmpty()) {
            // A startup failure is explained by the error page; its generic notice points at pages
            // that cannot be opened yet, so it is consumed without a snackbar.
            if (started)
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
            val notes = vm.quickNotes.state.visible
            val tabs =
                PagePolicy.showsTabBar(
                    vm.page,
                    quickNotesVisible = notes,
                    readerOpen = vm.quickNotes.state.reader != null,
                    answering = answering(),
                )
            // Nothing is emitted when hidden, so the Scaffold pads only the system bars instead.
            if (started && tabs)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    bottom.forEach { tab ->
                        val label = tab.label
                        NavigationBarItem(
                            selected = if (notes) tab.page == null else tab.page != null && current == tab.page,
                            onClick = { select(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
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
            if (startup is StartupState.Loading) StartupLoading(vm, startup)
            // Shown from startupState alone: a failed start never sets ready.
            else if (startup is StartupState.Error)
                Box(Modifier.weight(1f)) { StartupErrorScreen(vm, startup) }
            else if (answering)
                Box(Modifier.weight(1f)) {
                    practiceState.SaveableStateProvider("question-${vm.index}") {
                        QuestionScreen(vm)
                    }
                }
            // Only the content swaps; the date, notification and exam timer effects above stay mounted.
            else if (vm.quickNotes.state.visible)
                Box(Modifier.weight(1f)) {
                    val quickNoteAi = remember(vm) { quickNoteAiEntry(vm) }
                    pageState.SaveableStateProvider("quick-notes") { QuickNotesScreen(vm, reader, quickNoteAi) }
                }
            else {
                val sub = PagePolicy.isSubPage(vm.page)
                if (sub) SubPageTopBar(PagePolicy.title(vm.page))
                Box(
                    Modifier.weight(1f)
                        .then(
                            when {
                                // 更多 is a grouped list whose blocks run edge to edge.
                                vm.page == Page.MORE -> Modifier
                                sub -> Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
                                else -> Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            }
                        )
                ) {
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
                            Page.NOTES -> StickyNotesScreen(vm, remember(vm) { stickyNoteSources(vm) })
                            Page.MORE -> MoreScreen(vm, today)
                            Page.WORKBENCH -> AnswerWorkbench(vm)
                            Page.COSTS -> CostsScreen(vm)
                            Page.ERROR_LOG -> ErrorLogScreen(vm)
                        }
                    }
                }
            }
        }
    }
}

private data class Tab(val page: Page?, val label: String, val icon: ImageVector)
