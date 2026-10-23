package cn.embeddedquiz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.delay

@Composable
fun QuizApp(vm: QuizViewModel) {
    val practiceState = key(vm.practiceSession) { rememberSaveableStateHolder() }
    BackHandler(enabled = vm.page != Page.HOME) {
        if (vm.page == Page.NOTES) {
            if (vm.noteDraft != null) vm.closeStickyNote() else vm.leaveStickyNotes()
        } else vm.page = Page.HOME
    }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now()
            delay(15000)
        }
    }
    // Keep the exam deadline active even while the question page is not composed.
    LaunchedEffect(vm.ready, vm.mode, vm.examDeadline, vm.examFinished) {
        if (vm.ready && vm.mode == "模拟考试" && !vm.examFinished && vm.examDeadline > 0) {
            while (!vm.examFinished) {
                if (System.currentTimeMillis() >= vm.examDeadline) vm.finishExam()
                delay(1000)
            }
        }
    }
    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "EmbeddedQuiz",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("嵌入式面试助手 · 离线学习", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Page.entries
                .filter { it != Page.QUESTION }
                .forEach { name ->
                    FilterChip(
                        selected = vm.page == name,
                        onClick = { vm.navigate(name) },
                        label = { Text(name.label) },
                    )
                }
        }
        if (vm.notice.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp)) {
                    Text(vm.notice, Modifier.weight(1f))
                    TextButton(onClick = { vm.notice = "" }) { Text("关闭") }
                }
            }
        }
        if (vm.busy || !vm.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.ready)
            when (vm.page) {
                Page.HOME -> Home(vm, today)
                Page.BROWSE -> Browse(vm, "分类")
                Page.REVIEW -> Review(vm, today)
                Page.PROGRAMMING -> Browse(vm, "编程专区")
                Page.MISTAKES -> Browse(vm, "错题收藏")
            Page.EXAM -> ExamScreen(vm)
                Page.STATISTICS -> History(vm)
                Page.SETTINGS -> Settings(vm)
                Page.NOTES -> StickyNotesScreen(vm)
                Page.QUESTION ->
                    practiceState.SaveableStateProvider("question-${vm.index}") {
                        QuestionScreen(vm)
                    }
            }
    }
}
