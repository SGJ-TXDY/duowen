package cn.embeddedquiz

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.time.LocalDate
import kotlinx.coroutines.delay

private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
        else -> null
    }

fun appColors(dark: Boolean) =
    if (dark)
        darkColorScheme(
            primary = Color(0xFF80D5C5),
            secondary = Color(0xFFA9CADD),
            error = Color(0xFFFFB4AB),
        )
    else
        lightColorScheme(
            primary = Color(0xFF126B62),
            secondary = Color(0xFF426278),
            background = Color(0xFFF5F8F7),
        )

@Composable
fun AppTheme(settings: LearningSettings, content: @Composable () -> Unit) {
    val dark =
        when (settings.theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> isSystemInDarkTheme()
        }
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        view.context.activity()?.let { activity ->
            activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
            activity.window.navigationBarColor =
                if (Build.VERSION.SDK_INT >= 29) android.graphics.Color.TRANSPARENT
                else if (dark) 0xFF1C1B1F.toInt() else 0xFFF5F8F7.toInt()
            if (Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scale = FontChoice.entries.firstOrNull { it.name == settings.fontSize }?.scale ?: 1f
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * scale)
    ) {
        MaterialTheme(colorScheme = appColors(dark), content = content)
    }
}

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
            Page.MORE -> vm.page = Page.HOME
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
            Page.HOME to "首页",
            Page.QUESTION to "刷题",
            Page.REVIEW to "复习",
            Page.MISTAKES to "错题",
            Page.MORE to "更多",
        )
    val current = if (vm.page in bottom.map { it.first }) vm.page else Page.MORE
    Scaffold(
        bottomBar = {
            NavigationBar {
                bottom.forEachIndexed { index, (page, label) ->
                    NavigationBarItem(
                        selected = current == page,
                        onClick = { vm.navigate(page) },
                        icon = { Text(listOf("⌂", "✓", "↻", "!", "⋯")[index]) },
                        label = { Text(label) },
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
        Column(
            Modifier.fillMaxSize()
                .padding(insets)
                .consumeWindowInsets(insets)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "嵌入式面试助手",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (vm.notice.isNotEmpty())
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp)) {
                        Text(vm.notice, Modifier.weight(1f))
                        TextButton(
                            onClick = { vm.notice = "" },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("关闭")
                        }
                    }
                }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!vm.ready) {
                Text(vm.loadingMessage)
                LinearProgressIndicator(
                    progress = { vm.importProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else
                Box(Modifier.weight(1f)) {
                    if (vm.page == Page.QUESTION && vm.queue.isNotEmpty()) {
                        practiceState.SaveableStateProvider("question-${vm.index}") {
                            QuestionScreen(vm)
                        }
                    } else
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
        listOf(
                Page.BROWSE,
                Page.PROGRAMMING,
                Page.EXAM,
                Page.NOTES,
                Page.WORKBENCH,
                Page.STATISTICS,
                Page.COSTS,
                Page.SETTINGS,
            )
            .forEach { page ->
                item {
                    OutlinedButton(
                        onClick = { vm.navigate(page) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(page.label)
                    }
                }
            }
    }
}
