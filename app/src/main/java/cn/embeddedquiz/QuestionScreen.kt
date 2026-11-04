package cn.embeddedquiz

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.*

@Composable
fun QuestionScreen(vm: QuizViewModel) {
    val id = vm.queue.getOrNull(vm.index)
    if (id == null) {
        Text("没有可继续的题目", Modifier.padding(16.dp))
        return
    }
    val hash = vm.questions.firstOrNull { it.id == id }?.contentHash
    var question by remember(id, hash) { mutableStateOf<Question?>(null) }
    var missing by remember(id, hash) { mutableStateOf(false) }
    LaunchedEffect(id, hash) {
        question = vm.loadQuestion(id)
        missing = question == null
    }
    val q = question
    if (q == null) {
        if (missing) MissingQuestion(vm)
        else
            // Static text: an indeterminate spinner never lets Compose tests reach idle.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在加载题目…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        return
    }
    key(q.id, vm.mode) { QuestionContent(vm, q) }
}

/** What the exam screens may offer, read only from the T1 contract state table; no grading here. */
internal class ExamControls(vm: QuizViewModel) {
    val state = vm.examOperationState
    /** An unsubmitted paper is open: idle, ending or failed. Submitted and Abandoned are not. */
    val open = vm.mode == "模拟考试" && !vm.examFinished && vm.queue.isNotEmpty()
    private val failed = state as? ExamOperationState.Failed
    private val submitRetryable =
        failed != null &&
            failed.operation == ExamOperation.SUBMIT &&
            !failed.recordsCommitted &&
            failed.stage != ExamFailureStage.SESSION_CLEAR
    val ending = state == ExamOperationState.Submitting || state == ExamOperationState.Abandoning
    /** Only an idle open exam accepts selections, drafts or a submit; busy alone is not enough. */
    val editable = open && state == ExamOperationState.Idle && !vm.busy
    val abandonOffered = open && (state == ExamOperationState.Idle || submitRetryable)
    val canAbandon = abandonOffered && !vm.busy
    val canRetry = open && failed != null && !vm.busy
}

internal enum class ExamConfirm { SUBMIT, ABANDON }

internal fun examRetryLabel(state: ExamOperationState.Failed) =
    when {
        state.operation == ExamOperation.ABANDON -> "重试放弃清理"
        state.stage == ExamFailureStage.SESSION_CLEAR -> "重试清理"
        else -> "重试交卷"
    }

/**
 * Submit / abandon confirmation. Any state change (a timeout submit, a finished operation, a busy
 * task) closes it, and the confirm button re-checks what is allowed at the moment of the tap.
 */
@Composable
internal fun ExamConfirmDialog(vm: QuizViewModel, request: ExamConfirm?, onClose: () -> Unit) {
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(vm.examOperationState, vm.examFinished, vm.mode, vm.busy) { close() }
    val controls = ExamControls(vm)
    val allowed =
        when (request) {
            ExamConfirm.SUBMIT -> controls.editable
            ExamConfirm.ABANDON -> controls.canAbandon
            null -> false
        }
    if (!allowed) return
    val total = vm.queue.size
    val answered = vm.queue.count { vm.examAnswers[it].orEmpty().isNotEmpty() }
    val submit = request == ExamConfirm.SUBMIT
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (submit) "确认交卷？" else "放弃本次考试？") },
        text = {
            Text(
                when {
                    submit ->
                        "已答 $answered 题，未答 ${total - answered} 题，共 $total 题。\n" +
                            "未答题计 0 分，但不会写入学习记录，也不改变复习安排和错题本。交卷后统一判分，不能再修改。"
                    controls.state is ExamOperationState.Failed ->
                        "本次交卷没有成功，也没有写入学习记录。放弃后考试结束，不计成绩，已选答案将被丢弃。"
                    else ->
                        "已答 $answered / $total 题。放弃后考试立即结束：不计成绩，不写入学习记录，也不改变复习安排；已选答案将被丢弃，无法恢复。"
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    close()
                    val now = ExamControls(vm)
                    if (submit && now.editable) vm.finishExam()
                    else if (!submit && now.canAbandon) vm.abandonExam()
                }
            ) {
                Text(if (submit) "确认交卷" else "确认放弃")
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(if (submit) "继续作答" else "取消") } },
    )
}

/**
 * Ending status and recovery actions. Shown on the answer screen, the missing-question view and
 * the exam tab, so a failed ending stays visible and recoverable after leaving the paper.
 */
@Composable
internal fun ExamEndPanel(vm: QuizViewModel, showRetry: Boolean, onAbandon: () -> Unit) {
    val controls = ExamControls(vm)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val state = controls.state) {
            ExamOperationState.Submitting -> ExamStatus("正在交卷，请稍候…")
            ExamOperationState.Abandoning -> ExamStatus("正在放弃本次考试…")
            is ExamOperationState.Failed -> {
                ExamStatus(
                    when {
                        state.operation == ExamOperation.ABANDON -> "放弃未完成"
                        state.stage == ExamFailureStage.SESSION_CLEAR -> "成绩已保存，会话清理未完成"
                        else -> "交卷未完成"
                    }
                )
                ErrorNotice(state.message)
                Text(
                    when {
                        state.operation == ExamOperation.ABANDON ->
                            "本次考试没有产生学习记录。请重试放弃清理；完成前不能改为交卷，也不能开始新的练习或考试。"
                        state.stage == ExamFailureStage.SESSION_CLEAR ->
                            "学习记录已保存，重试只清理考试会话，不会重复计分。完成前不能修改答案、放弃或开始新的练习。"
                        else -> "答案已冻结，不能再修改。可以重试交卷，或放弃本次考试（不计成绩，不写入学习记录）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showRetry)
                    Action(examRetryLabel(state), controls.canRetry) { vm.retryExamOperation() }
            }
            else -> {}
        }
        if (controls.abandonOffered)
            SecondaryAction("放弃本次考试", controls.canAbandon, Modifier.fillMaxWidth(), onAbandon)
    }
}

@Composable
private fun ExamStatus(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier =
            Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * Whether the open paper is on disk. Saving never blocks answering; a failure stays visible until a
 * later save succeeds. Retry is offered only while answers are still editable: once an ending has
 * frozen them, the ending panel owns recovery.
 */
@Composable
internal fun ExamSaveStatus(vm: QuizViewModel, allowRetry: Boolean, modifier: Modifier = Modifier) {
    when (val state = vm.examSaveState) {
        is ExamSaveState.Saving -> ExamSaveLabel("保存中…", modifier)
        is ExamSaveState.Saved -> ExamSaveLabel("已保存到本机", modifier)
        is ExamSaveState.Error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = modifier.fillMaxWidth(),
            ) {
                // The message wraps beside the button, so large text stays inside narrow screens.
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier.weight(1f).padding(vertical = 10.dp).semantics {
                                liveRegion = LiveRegionMode.Assertive
                            },
                    )
                    if (allowRetry) TextButton(onClick = vm::retryExamSave) { Text("重试保存") }
                }
            }
        ExamSaveState.Inactive -> {}
    }
}

@Composable
private fun ExamSaveLabel(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Leaving an editable exam waits for its latest answers to reach disk; a failed save keeps the user
 * on the paper with a retry instead of navigating away. Ending states are not gated: their inputs
 * are frozen and the ending panel shows what remains to be done.
 *
 * A navigation from elsewhere (a notification opening the review) may land while the flush is
 * still pending. The screen's scope is only cancelled on the next frame, so a flush finishing in
 * between would still resume here; the request therefore re-checks that it still owns the screen
 * and otherwise drops both the navigation and the failure dialog.
 */
@Stable
internal class ExamLeave(private val vm: QuizViewModel, private val scope: CoroutineScope) {
    var pending by mutableStateOf(false)
        private set
    var blocked by mutableStateOf<(() -> Unit)?>(null)
        private set

    fun leave(target: () -> Unit) {
        if (pending || blocked != null) return
        val controls = ExamControls(vm)
        if (!controls.open || controls.state != ExamOperationState.Idle) return target()
        val page = vm.page
        val session = vm.practiceSession
        pending = true
        scope.launch {
            val saved =
                try {
                    vm.flushExamSave()
                } finally {
                    pending = false
                }
            if (vm.page != page || vm.practiceSession != session) return@launch
            if (saved) target() else blocked = target
        }
    }

    fun retry() {
        val target = blocked ?: return
        blocked = null
        vm.retryExamSave()
        leave(target)
    }

    fun stay() {
        blocked = null
    }
}

@Composable
internal fun rememberExamLeave(vm: QuizViewModel): ExamLeave {
    val scope = rememberCoroutineScope()
    return remember(vm) { ExamLeave(vm, scope) }
}

@Composable
internal fun ExamLeaveDialog(leave: ExamLeave) {
    if (leave.blocked == null) return
    AlertDialog(
        onDismissRequest = leave::stay,
        title = { Text("保存失败，暂未离开") },
        text = {
            Text("最新修改尚未保存到本机，为避免丢失，已留在答题页。可以重试保存，保存成功后会继续离开。")
        },
        confirmButton = { TextButton(onClick = leave::retry) { Text("重试保存") } },
        dismissButton = { TextButton(onClick = leave::stay) { Text("留在本页") } },
    )
}

/**
 * Whether this question's code/notes draft is on disk. Typing is never blocked by a save; a failure
 * stays visible with an explicit retry. Inactive shows nothing: no edit here, nothing to report.
 */
@Composable
internal fun DraftSaveStatus(vm: QuizViewModel, id: String, allowRetry: Boolean) {
    when (val state = vm.codeDraftSaveState(id)) {
        is CodeDraftSaveState.Saving -> DraftSaveLabel("草稿保存中…")
        is CodeDraftSaveState.Saved -> DraftSaveLabel("草稿已保存到本机")
        is CodeDraftSaveState.Error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier.weight(1f).padding(vertical = 10.dp).semantics {
                                liveRegion = LiveRegionMode.Assertive
                            },
                    )
                    if (allowRetry) TextButton(onClick = { vm.retryCodeDrafts(id) }) { Text("重试保存") }
                }
            }
        CodeDraftSaveState.Inactive -> {}
    }
}

@Composable
private fun DraftBlockedNotice(vm: QuizViewModel) {
    if (vm.codeDraftEditingBlocked)
        Text(
            "正在备份、恢复或重置学习数据，代码和笔记暂时不能修改。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
}

@Composable
private fun DraftSaveLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Leaving a practice question (previous/next, back, notes) first commits its draft; a failed save
 * keeps the user on the question with a retry. Nothing pending passes straight through. As with
 * [ExamLeave], a navigation from elsewhere while waiting (a notification) wins: the request
 * re-checks page, session and question before acting and otherwise drops both outcomes.
 */
@Stable
internal class DraftLeave(
    private val vm: QuizViewModel,
    private val id: String,
    private val scope: CoroutineScope,
) {
    var pending by mutableStateOf(false)
        private set
    var blocked by mutableStateOf<(() -> Unit)?>(null)
        private set

    fun leave(target: () -> Unit) {
        if (pending || blocked != null) return
        val state = vm.codeDraftSaveState(id)
        if (state is CodeDraftSaveState.Inactive || state is CodeDraftSaveState.Saved) return target()
        val page = vm.page
        val session = vm.practiceSession
        val index = vm.index
        pending = true
        scope.launch {
            val saved =
                try {
                    vm.flushCodeDrafts(id)
                } finally {
                    pending = false
                }
            if (vm.page != page || vm.practiceSession != session || vm.index != index) return@launch
            if (saved) target() else blocked = target
        }
    }

    fun retry() {
        val target = blocked ?: return
        blocked = null
        vm.retryCodeDrafts(id)
        leave(target)
    }

    fun stay() {
        blocked = null
    }
}

@Composable
internal fun rememberDraftLeave(vm: QuizViewModel, id: String): DraftLeave {
    val scope = rememberCoroutineScope()
    return remember(vm, id) { DraftLeave(vm, id, scope) }
}

@Composable
internal fun DraftLeaveDialog(leave: DraftLeave) {
    if (leave.blocked == null) return
    AlertDialog(
        onDismissRequest = leave::stay,
        title = { Text("草稿保存失败，暂未离开") },
        text = {
            Text("代码或笔记的最新修改尚未保存到本机，为避免丢失，已留在本题。可以重试保存，保存成功后会继续离开。")
        },
        confirmButton = { TextButton(onClick = leave::retry) { Text("重试保存") } },
        dismissButton = { TextButton(onClick = leave::stay) { Text("留在本页") } },
    )
}

/** A question removed from the bank never loads; the exam must still be endable from here. */
@Composable
private fun MissingQuestion(vm: QuizViewModel) {
    val controls = ExamControls(vm)
    var confirm by remember { mutableStateOf<ExamConfirm?>(null) }
    ExamConfirmDialog(vm, confirm) { confirm = null }
    val leave = rememberExamLeave(vm)
    ExamLeaveDialog(leave)
    BackHandler(enabled = controls.open) { leave.leave { vm.page = Page.HOME } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { leave.leave { vm.page = Page.HOME } }, modifier = Modifier.size(48.dp)) {
                Icon(AppIcons.Back, contentDescription = "返回首页")
            }
            Text(
                "${vm.mode} · 第 ${vm.index + 1} / ${vm.queue.size} 题",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        ErrorNotice("本题无法加载，题目可能已从题库移除。")
        if (controls.open) ExamSaveStatus(vm, allowRetry = controls.editable)
        if (controls.open) {
            if (controls.state == ExamOperationState.Idle)
                Text(
                    "缺题的试卷无法判分，交卷会提示失败；可以放弃本次考试（不计成绩，不写入学习记录）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            ExamEndPanel(vm, showRetry = true) { confirm = ExamConfirm.ABANDON }
            if (controls.state == ExamOperationState.Idle)
                Action("交卷", controls.editable) { confirm = ExamConfirm.SUBMIT }
        }
    }
}

private enum class OptionState {
    IDLE,
    SELECTED,
    CORRECT,
    CORRECT_CHOSEN,
    WRONG,
    DIMMED,
}

/** Bank options usually repeat their key ("A. 4"); the badge already shows it. */
private fun optionBody(o: Option) =
    o.text.replaceFirst(Regex("^\\s*${Regex.escape(o.key)}\\s*[.．、:：)）]\\s*"), "").ifBlank { o.text }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionContent(vm: QuizViewModel, q: Question) {
    val draft = vm.questionDraft(q)
    var selected by draft.selected
    var response by draft.response
    var revealed by draft.revealed
    val recorded = vm.practiceRecords.containsKey(q.id)
    val savedAssessment = vm.practiceRecords[q.id]?.takeUnless { it == "AUTO" }.orEmpty()
    var code by draft.code
    var notes by draft.notes
    var prompt by draft.prompt
    var remaining by remember { mutableLongStateOf(0) }
    val canGrade = vm.learning.canGrade(q)
    val personal = vm.learning.personal(q)
    LaunchedEffect(q.id, q.contentHash) { vm.aiState.observe(q) }
    val exam = vm.mode == "模拟考试" && !vm.examFinished
    LaunchedEffect(exam, vm.examDeadline) {
        if (exam)
            while (!vm.examFinished) {
                remaining = ((vm.examDeadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                delay(1000)
            }
    }
    val progress = vm.p(q.id)
    val showAnswer = revealed || recorded || vm.examFinished
    // Only confirmed answers are highlighted; pending ones stay as plain reference text.
    val answerKeys = personal?.selected ?: if (q.autoGradable) q.correct else emptySet()
    val aiForQuestion = vm.aiEntries.filter { it.questionId == q.id }
    var notesOpen by rememberSaveable { mutableStateOf(false) }
    var aiOpen by rememberSaveable { mutableStateOf(false) }
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    val controls = ExamControls(vm)
    var confirm by remember { mutableStateOf<ExamConfirm?>(null) }
    val leave = rememberExamLeave(vm)
    // Practice questions carry code/notes drafts; the exam keeps its own leave protocol.
    val draftLeave = rememberDraftLeave(vm, q.id)
    // While waiting to leave, new input would land after the version being confirmed on disk.
    val editable = controls.editable && !leave.pending
    // Backup, restore and reset hold drafts still; so does a pending leave of this question.
    val draftEditable = !vm.codeDraftEditingBlocked && !draftLeave.pending
    // While an ending runs, moving between questions would change what the user believes was handed in.
    val navLocked = (exam && (controls.ending || vm.busy || leave.pending)) || draftLeave.pending
    /** Leaves the question: through the exam protocol in an exam, after the draft flush otherwise. */
    fun leaveQuestion(target: () -> Unit) = if (exam) leave.leave(target) else draftLeave.leave(target)
    val previous: () -> Unit = {
        if (exam) {
            if (!navLocked) {
                vm.index--
                vm.saveExam()
            }
        } else draftLeave.leave { vm.index-- }
    }
    val next: () -> Unit = {
        if (exam) {
            if (!navLocked) {
                vm.index++
                vm.saveExam()
            }
        } else draftLeave.leave { vm.index++ }
    }
    ExamConfirmDialog(vm, confirm) { confirm = null }
    ExamLeaveDialog(leave)
    DraftLeaveDialog(draftLeave)
    // Takes precedence over the shell's back handler while a question is shown.
    BackHandler { leaveQuestion { vm.page = Page.HOME } }

    Column(Modifier.fillMaxSize()) {
        QuestionTopBar(
            title = vm.mode,
            position = "第 ${vm.index + 1} / ${vm.queue.size} 题",
            remaining = if (exam) remaining else null,
            favorite = progress.favorite,
            flagged = progress.flagged,
            showMarks = !exam,
            onBack = { leaveQuestion { vm.page = Page.HOME } },
            onNotes = { leaveQuestion(vm::openStickyNotes) },
            onFavorite = { vm.toggleFavorite(q.id) },
            onFlag = { vm.toggleFlag(q.id) },
            examActions =
                if (exam && controls.abandonOffered) {
                    {
                        // Keyed so a menu left open does not survive a change of what it may offer.
                        key(vm.examOperationState) {
                            ExamMenu(controls.canAbandon) { confirm = ExamConfirm.ABANDON }
                        }
                    }
                } else null,
        )
        LinearProgressIndicator(
            progress = { (vm.index + 1f) / vm.queue.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        if (exam)
            ExamSaveStatus(
                vm,
                allowRetry = editable,
                Modifier.padding(horizontal = 16.dp).padding(top = 6.dp),
            )
        LazyColumn(
            Modifier.weight(1f).testTag("question-list").semantics { contentDescription = "题目${q.id}" },
            state = draft.scroll,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        ) {
            if (exam && controls.state != ExamOperationState.Idle)
                item {
                    SectionCard {
                        // Retry sits in the action bar so it stays reachable without scrolling.
                        ExamEndPanel(vm, showRetry = false) { confirm = ExamConfirm.ABANDON }
                    }
                }
            val summary = vm.examSummary
            if (vm.examFinished && vm.mode == "模拟考试" && summary != null)
                item {
                    ResultBanner(
                        icon = AppIcons.Check,
                        title = "答对 ${summary.correct} / ${summary.total}，得分 ${summary.score}",
                        subtitle =
                            "已答 ${summary.answered} 题 · 未答 ${summary.unanswered} 题（计 0 分，不改变复习安排）\n" +
                                "已交卷，下面可逐题查看对错",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        badge = MaterialTheme.colorScheme.primary,
                        onBadge = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Tag(
                        q.kind.label,
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.primary,
                        bold = true,
                    )
                    Tag(CategoryMap.normalize(q.category))
                    Tag(q.source)
                    if (q.company.isNotBlank() && q.company != "未注明" && q.company != q.source)
                        Tag(q.company)
                    if (q.difficulty != "未标注") Tag("难度：${q.difficulty}")
                    if (q.reviewRequired || progress.flagged)
                        Tag(
                            "待核对",
                            MaterialTheme.status.warningContainer,
                            MaterialTheme.status.onWarningContainer,
                        )
                }
            }
            if (q.reviewRequired && !exam)
                item {
                    VerificationNotice(
                        if (personal != null) "原题仍待核对，本次按个人核对答案判分。" else "原答案缺失或有条件限制，需自行判断。"
                    )
                }
            item { SectionCard { Detail(q.stem) } }
            items(q.images) { pic -> QuestionImage(pic) }
            if (q.kind == Kind.MULTIPLE && q.options.isNotEmpty())
                item {
                    Text(
                        "多选题：选出全部正确选项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            items(q.options) { o ->
                val chosen = o.key in selected
                val state =
                    when {
                        !showAnswer -> if (chosen) OptionState.SELECTED else OptionState.IDLE
                        answerKeys.isEmpty() -> if (chosen) OptionState.SELECTED else OptionState.DIMMED
                        o.key in answerKeys ->
                            if (chosen) OptionState.CORRECT_CHOSEN else OptionState.CORRECT
                        chosen -> OptionState.WRONG
                        else -> OptionState.DIMMED
                    }
                OptionCard(
                    key = o.key,
                    text = optionBody(o),
                    state = state,
                    multiple = q.kind == Kind.MULTIPLE,
                    enabled =
                        if (exam) remaining > 0 && editable
                        else !revealed && !recorded && !vm.examFinished,
                ) {
                    // Re-read at the tap: an ending may have started since this frame was drawn.
                    if (exam && (!ExamControls(vm).editable || leave.pending)) return@OptionCard
                    selected =
                        if (q.kind == Kind.MULTIPLE) {
                            if (o.key in selected) selected - o.key else selected + o.key
                        } else listOf(o.key)
                    if (exam) vm.answerExam(q.id, selected.toSet())
                }
            }
            if (!q.objective && q.daily)
                item {
                    OutlinedTextField(
                        value = response,
                        onValueChange = { response = it },
                        label = { Text("先写下自己的回答") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        minLines = 3,
                        readOnly = revealed || vm.examFinished || (exam && !editable),
                    )
                }
            if (!exam && showAnswer) {
                val grade = Grader.grade(q, selected.toSet(), personal)
                val nextReview =
                    if (recorded) "下次复习 ${LocalDate.ofEpochDay(progress.due)}" else ""
                item {
                    val status = MaterialTheme.status
                    val colors = MaterialTheme.colorScheme
                    when {
                        savedAssessment.isNotBlank() -> {
                            val assessment = Assessment.valueOf(savedAssessment)
                            val good = assessment == Assessment.CORRECT
                            val unknown = assessment == Assessment.UNKNOWN
                            ResultBanner(
                                icon =
                                    if (good) AppIcons.Check
                                    else if (unknown) AppIcons.Help else AppIcons.Close,
                                title = "自评：${assessment.label}",
                                subtitle = "已记录 · $nextReview",
                                container =
                                    if (good) status.successContainer
                                    else if (unknown) status.warningContainer
                                    else colors.errorContainer,
                                content =
                                    if (good) status.onSuccessContainer
                                    else if (unknown) status.onWarningContainer
                                    else colors.onErrorContainer,
                                badge =
                                    if (good) status.success
                                    else if (unknown) status.onWarningContainer else colors.error,
                                onBadge =
                                    if (good) status.onSuccess
                                    else if (unknown) status.warningContainer else colors.onError,
                            )
                        }
                        grade == true ->
                            ResultBanner(
                                icon = AppIcons.Check,
                                title = "回答正确",
                                subtitle =
                                    if (recorded) "已记录 · $nextReview" else "自动判分",
                                container = status.successContainer,
                                content = status.onSuccessContainer,
                                badge = status.success,
                                onBadge = status.onSuccess,
                            )
                        grade == false ->
                            ResultBanner(
                                icon = AppIcons.Close,
                                title = "回答错误",
                                subtitle =
                                    if (recorded) "已加入错题本 · $nextReview" else "自动判分",
                                container = colors.errorContainer,
                                content = colors.onErrorContainer,
                                badge = colors.error,
                                onBadge = colors.onError,
                            )
                        else ->
                            ResultBanner(
                                icon = AppIcons.Info,
                                title = "对照参考答案自行判断",
                                subtitle = "本题没有可自动核对的标准选项",
                                container = colors.primaryContainer,
                                content = colors.onPrimaryContainer,
                                badge = colors.primary,
                                onBadge = colors.onPrimary,
                            )
                    }
                }
                item {
                    SectionCard(title = "参考答案", trailing = vm.learning.answerSource(q)) {
                        if (personal != null)
                            Text(
                                "个人核对选项：${personal.selected.sorted().joinToString()}" +
                                    if (personal.notes.isNotBlank()) "\n备注：${personal.notes}" else "",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        Detail(
                            q.answer.ifBlank {
                                if (q.correct.isNotEmpty()) "标准选项：${q.correct.joinToString()}"
                                else "原文未提供答案"
                            }
                        )
                        if (q.explanation.isNotBlank()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text("解析", style = MaterialTheme.typography.titleSmall)
                            Detail(q.explanation)
                        }
                    }
                }
                if (q.daily && !canGrade && !recorded && !vm.examFinished)
                    item {
                        SectionCard(title = "这次答得怎么样？") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Assessment.entries.forEach { a ->
                                    AssessmentButton(a, enabled = !vm.busy, Modifier.weight(1f)) {
                                        vm.assess(q, response, selected.toSet(), a) {}
                                    }
                                }
                            }
                            Text(
                                "只记录掌握情况，不修改参考答案。正确会延长复习间隔；错误和不会加入错题本，次日再复习。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
            }
            if (!exam) {
                if (!q.daily) {
                    item {
                        SectionCard(title = "编程题工作区") {
                            Text("输入输出示例", style = MaterialTheme.typography.titleSmall)
                            Detail(q.examples.ifBlank { "原文未单列，请查看完整题干" })
                            Text("约束条件", style = MaterialTheme.typography.titleSmall)
                            Detail(q.constraints.ifBlank { "原文未单列" })
                            if (revealed) {
                                Text("解题思路", style = MaterialTheme.typography.titleSmall)
                                Detail(q.approach.ifBlank { "原文未提供" })
                                Text("参考代码", style = MaterialTheme.typography.titleSmall)
                                if (q.referenceCode.isBlank()) Text("原文未提供")
                                else CodeBlock(q.referenceCode, "原题参考代码")
                            }
                        }
                    }
                    item {
                        SectionCard {
                            // Above the editor: it stays in view while typing, even on small screens.
                            // Covers code and the auto-saved notes of this programming question.
                            DraftSaveStatus(vm, q.id, allowRetry = draftEditable)
                            DraftBlockedNotice(vm)
                            CodeEditor(code, draft.codeEditor, draftEditable) {
                                code = it
                                vm.updateCodeDraft(q.id, code, notes)
                            }
                            CodingTimer(draft.timer)
                            TonalAction("AI 代码审查", !vm.aiBusy && code.isNotBlank()) {
                                aiOpen = true
                                vm.aiState.ask(q, "", selected.toSet(), AiRequestType.CODE_REVIEW, code)
                            }
                            Text(
                                "当前版本不执行或在线判定代码，请在自己的开发环境验证。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { SectionCard { VerifiedSolutionEditor(vm, q) } }
                }
                item {
                    val difficulty = vm.learning.metadata[q.id]?.perceivedDifficulty.orEmpty()
                    // Notes and difficulty are separate saves; global tags live behind the top bar icon.
                    ExpandableSection(
                        icon = AppIcons.Pencil,
                        title = "笔记与难度感受",
                        summary =
                            listOfNotNull(
                                    notes.lineSequence().firstOrNull { it.isNotBlank() },
                                    difficulty.takeIf { it.isNotBlank() }?.let { "感觉$it" },
                                )
                                .joinToString(" · ")
                                .ifBlank { "未填写" },
                        expanded = notesOpen,
                        onToggle = { notesOpen = !notesOpen },
                    ) {
                        PerceivedDifficultyChips(vm, q)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        OutlinedTextField(
                            value = notes,
                            onValueChange = {
                                notes = it
                                if (!q.daily) vm.updateCodeDraft(q.id, code, notes)
                            },
                            label = { Text(if (q.daily) "个人笔记（手动保存）" else "个人笔记（自动保存）") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            minLines = 3,
                            readOnly = !draftEditable,
                        )
                        DraftBlockedNotice(vm)
                        Action(if (q.daily) "保存笔记" else "保存代码与笔记", draftEditable) {
                            vm.saveNotes(q.id, code, notes)
                        }
                        if (q.daily) {
                            // Manual notes: local edits are not saved until the button is pressed.
                            val accepted = vm.codeDrafts.drafts[q.id]?.notes ?: progress.notes
                            if (notes != accepted)
                                DraftSaveLabel("笔记有未保存的修改，点“保存笔记”后才会写入")
                            else DraftSaveStatus(vm, q.id, allowRetry = draftEditable)
                        }
                    }
                }
                item {
                    ExpandableSection(
                        icon = AppIcons.Chat,
                        title = "AI 学习助手",
                        summary =
                            if (vm.aiBusy) "正在分析…"
                            else if (aiForQuestion.isNotEmpty()) "${aiForQuestion.size} 条记录"
                            else "DeepSeek · 需自行核实",
                        expanded = aiOpen || vm.aiBusy || vm.aiState.error.isNotBlank(),
                        onToggle = { aiOpen = !aiOpen },
                    ) {
                        Text(
                            "点击后将本题、参考答案、所选选项和最近讨论发送到 DeepSeek。AI 内容独立存储，不写入题库、不参与判分。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TonalAction(if (vm.aiBusy) "正在分析…" else "生成详细解析", !vm.aiBusy) {
                                vm.ask(q, "生成详细解析", selected.toSet(), AiRequestType.EXPLANATION)
                            }
                            SecondaryAction("AI 建议答案（待核对）", !vm.aiBusy) {
                                vm.ask(q, "", selected.toSet(), AiRequestType.SUGGESTED_ANSWER)
                            }
                            SecondaryAction("出一道同知识点变式题", !vm.aiBusy) {
                                vm.ask(q, "", selected.toSet(), AiRequestType.VARIANT)
                            }
                        }
                        if (vm.aiBusy) SecondaryAction("取消 AI 请求") { vm.aiState.cancel() }
                        if (vm.aiState.error.isNotBlank()) {
                            ErrorNotice(vm.aiState.error)
                            SecondaryAction("重试 AI 请求", !vm.aiBusy) { vm.aiState.retry() }
                        }
                        aiForQuestion.forEach { a -> AiEntryCard(a) }
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            label = { Text("追问、错误选项分析、举例或质疑") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        vm.ask(q, prompt, selected.toSet())
                                        prompt = ""
                                    },
                                    enabled = !vm.aiBusy && prompt.isNotBlank(),
                                ) {
                                    Icon(AppIcons.Send, contentDescription = "发送追问")
                                }
                            },
                        )
                    }
                }
                item {
                    ExpandableSection(
                        icon = AppIcons.Info,
                        title = "题目信息",
                        summary = q.id,
                        expanded = infoOpen,
                        onToggle = { infoOpen = !infoOpen },
                    ) {
                        InfoLine("来源", q.source)
                        InfoLine("原文位置", q.locator.ifBlank { "未记录" })
                        InfoLine("原始分类", q.category)
                        InfoLine("答案来源", vm.learning.answerSource(q))
                        if (q.duplicateOf.isNotBlank())
                            InfoLine("同题关联", "${q.duplicateOf}（来源和学习记录独立）")
                    }
                }
            }
        }
        QuestionActionBar {
            val canPrevious = vm.index > 0 && !navLocked
            val atLast = vm.index >= vm.queue.lastIndex
            NavIconButton(AppIcons.ChevronLeft, "上一题", canPrevious, previous)
            when {
                exam ->
                    when (val state = controls.state) {
                        is ExamOperationState.Failed ->
                            PrimaryBarButton(examRetryLabel(state), controls.canRetry, Modifier.weight(1f)) {
                                vm.retryExamOperation()
                            }
                        ExamOperationState.Submitting ->
                            PrimaryBarButton("正在交卷…", false, Modifier.weight(1f)) {}
                        ExamOperationState.Abandoning ->
                            PrimaryBarButton("正在放弃…", false, Modifier.weight(1f)) {}
                        else ->
                            PrimaryBarButton("交卷", editable, Modifier.weight(1f)) {
                                confirm = ExamConfirm.SUBMIT
                            }
                    }
                !showAnswer ->
                    PrimaryBarButton(
                        if (canGrade) "提交答案" else "查看参考答案",
                        !vm.busy && (!canGrade || selected.isNotEmpty()),
                        Modifier.weight(1f),
                    ) {
                        if (canGrade) vm.submit(q, response, selected.toSet()) { revealed = true }
                        else revealed = true
                    }
                atLast ->
                    PrimaryBarButton("完成，回到首页", !navLocked, Modifier.weight(1f)) {
                        leaveQuestion { vm.page = Page.HOME }
                    }
                else -> PrimaryBarButton("下一题", !navLocked, Modifier.weight(1f), AppIcons.ChevronRight, next)
            }
            if (exam || !showAnswer)
                NavIconButton(AppIcons.ChevronRight, "下一题", !atLast && !navLocked, next)
        }
    }
}

@Composable
private fun QuestionTopBar(
    title: String,
    position: String,
    remaining: Long?,
    favorite: Boolean,
    flagged: Boolean,
    showMarks: Boolean,
    onBack: () -> Unit,
    onNotes: () -> Unit,
    onFavorite: () -> Unit,
    onFlag: () -> Unit,
    examActions: (@Composable () -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(AppIcons.Back, contentDescription = "返回首页")
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                position,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (remaining != null) {
            val urgent = remaining < 60
            Surface(
                color =
                    if (urgent) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                contentColor =
                    if (urgent) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
                modifier = Modifier.semantics { contentDescription = "剩余时间 ${remaining / 60} 分 ${remaining % 60} 秒" },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(AppIcons.Clock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        IconButton(onClick = onNotes, modifier = Modifier.size(48.dp)) {
            Icon(AppIcons.Note, contentDescription = "打开全局标签")
        }
        if (showMarks) {
            IconButton(onClick = onFavorite, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (favorite) AppIcons.StarFilled else AppIcons.Star,
                    contentDescription = if (favorite) "取消收藏" else "收藏",
                    tint = if (favorite) MaterialTheme.colorScheme.tertiary else LocalContentColor.current,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(AppIcons.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (flagged) "取消待核对标记" else "标记待核对") },
                        leadingIcon = { Icon(AppIcons.Flag, contentDescription = null) },
                        onClick = {
                            menu = false
                            onFlag()
                        },
                    )
                }
            }
        }
        examActions?.invoke()
    }
}

@Composable
private fun ExamMenu(abandonEnabled: Boolean, onAbandon: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(AppIcons.MoreVert, contentDescription = "考试操作")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("放弃本次考试") },
                enabled = abandonEnabled,
                onClick = {
                    open = false
                    onAbandon()
                },
            )
        }
    }
}

@Composable
private fun OptionCard(
    key: String,
    text: String,
    state: OptionState,
    multiple: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val status = MaterialTheme.status
    val container =
        when (state) {
            OptionState.SELECTED -> colors.primaryContainer
            OptionState.CORRECT,
            OptionState.CORRECT_CHOSEN -> status.successContainer
            OptionState.WRONG -> colors.errorContainer
            else -> colors.surfaceContainerLowest
        }
    val accent =
        when (state) {
            OptionState.SELECTED -> colors.primary
            OptionState.CORRECT,
            OptionState.CORRECT_CHOSEN -> status.success
            OptionState.WRONG -> colors.error
            else -> null
        }
    val label =
        when (state) {
            OptionState.CORRECT -> "正确答案"
            OptionState.CORRECT_CHOSEN -> "你的选择 · 正确"
            OptionState.WRONG -> "你的选择"
            else -> null
        }
    val badgeShape = if (multiple) RoundedCornerShape(8.dp) else CircleShape
    Surface(
        selected = state == OptionState.SELECTED || state == OptionState.CORRECT_CHOSEN || state == OptionState.WRONG,
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = container,
        border =
            if (accent != null) BorderStroke(2.dp, accent)
            else BorderStroke(1.5.dp, colors.outlineVariant),
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                role = if (multiple) Role.Checkbox else Role.RadioButton
            },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(32.dp)
                    .clip(badgeShape)
                    .background(accent ?: colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val onAccent =
                    when (state) {
                        OptionState.CORRECT,
                        OptionState.CORRECT_CHOSEN -> status.onSuccess
                        OptionState.WRONG -> colors.onError
                        OptionState.SELECTED -> colors.onPrimary
                        else -> colors.onSurfaceVariant
                    }
                when (state) {
                    OptionState.CORRECT,
                    OptionState.CORRECT_CHOSEN ->
                        Icon(AppIcons.Check, null, tint = onAccent, modifier = Modifier.size(18.dp))
                    OptionState.WRONG ->
                        Icon(AppIcons.Close, null, tint = onAccent, modifier = Modifier.size(18.dp))
                    else -> Text(key, color = onAccent, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (accent != null) FontWeight.Medium else null,
                color = if (state == OptionState.DIMMED) colors.onSurfaceVariant else colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (label != null)
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (state == OptionState.WRONG) colors.error else status.success,
                )
        }
    }
}

@Composable
private fun ResultBanner(
    icon: ImageVector,
    title: String,
    subtitle: String,
    container: Color,
    content: Color,
    badge: Color,
    onBadge: Color,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(badge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = onBadge, modifier = Modifier.size(20.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AssessmentButton(
    assessment: Assessment,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tone =
        when (assessment) {
            Assessment.CORRECT -> MaterialTheme.status.success
            Assessment.WRONG -> MaterialTheme.colorScheme.error
            Assessment.UNKNOWN -> MaterialTheme.status.onWarningContainer
        }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, tone),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tone),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(assessment.label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuestionActionBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun NavIconButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(52.dp).semantics { contentDescription = label },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PrimaryBarButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier,
    trailing: ImageVector? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.height(52.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (trailing != null)
            Icon(trailing, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AiEntryCard(a: AiEntry) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${a.model} · ${a.prompt}", style = MaterialTheme.typography.titleSmall)
            if (a.contentHash.isBlank())
                Text(
                    "历史解析：题目版本未知，不用于追问上下文",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            MarkdownContent(a.response)
            Text(
                "输入 ${a.inputTokens} / 输出 ${a.outputTokens} tokens · 估算 $${"%.6f".format(a.estimatedUsd)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuestionImage(pic: Picture) {
    var bitmap by remember(pic.data) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pic.data) {
        bitmap =
            withContext(Dispatchers.IO) {
                runCatching {
                        val bytes = Base64.decode(pic.data, Base64.DEFAULT)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                        val options = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            ?.asImageBitmap()
                    }
                    .getOrNull()
            }
    }
    bitmap?.let {
        Image(
            it,
            contentDescription = pic.name,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        )
    }
}
