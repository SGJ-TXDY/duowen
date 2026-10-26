package cn.embeddedquiz

import android.app.Application
import android.content.SharedPreferences
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** T1 exam ending UI: confirmations, structured result, frozen input, failure stages and recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExamTerminationUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"et-a","stem":"考试第一题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲一"},{"key":"B","text":"乙一"}],"correct":["A"],"reviewRequired":false},
        {"id":"et-b","stem":"考试第二题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲二"},{"key":"B","text":"乙二"}],"correct":["B"],"reviewRequired":false},
        {"id":"et-c","stem":"考试第三题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲三"},{"key":"B","text":"乙三"}],"correct":["A"],"reviewRequired":false}]}
    """

    @Before
    fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        prefs.edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        // Only these three questions: skipping the bundled bank keeps the paper deterministic.
        runBlocking {
            val db = QuizDatabase.open(app)
            try {
                QuizRepository(db).importBank(bank)
            } finally {
                db.close()
            }
        }
        prefs.edit().putInt("bankRevision", 4).commit()
        ui.runOnUiThread {
            vm =
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
                    QuizViewModel::class.java]
        }
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.ready || vm.questions.size != 3) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
        ui.setContent { QuizApp(vm) }
    }

    @After
    fun cleanup() {
        ui.mainClock.autoAdvance = true
        ui.runOnUiThread { store.clear() }
        app.deleteDatabase("embeddedquiz.db")
        prefs.edit().clear().commit()
    }

    private fun await(condition: () -> Boolean) {
        try {
            ui.waitUntil(20000) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Throwable) {
            throw AssertionError(
                "state=${vm.examOperationState}; notice=${vm.notice}; page=${vm.page}; busy=${vm.busy}",
                e,
            )
        }
    }

    private fun settled(check: (ExamOperationState) -> Boolean) = await {
        !vm.busy && check(vm.examOperationState)
    }

    private fun back() {
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private val inList = hasAnyAncestor(hasTestTag("question-list"))

    private fun questionNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        await {
            ui.onAllNodes(
                    hasTestTag("question-list") and
                        hasContentDescription("题目${vm.queue.getOrNull(vm.index)}")
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        if (ui.onAllNodes(matcher and !inList).fetchSemanticsNodes().isEmpty())
            ui.onNodeWithTag("question-list").performScrollToNode(matcher)
        return ui.onNode(matcher)
    }

    private fun listText(text: String, substring: Boolean = false) =
        questionNode(hasText(text, substring = substring) and inList)

    private fun option(text: String) = questionNode(hasText(text) and hasClickAction())

    /** Action bar and top bar controls sit outside the scrolling list. */
    private fun bar(label: String) = questionNode((hasText(label) or hasContentDescription(label)) and hasClickAction())

    private fun startExam(answers: Map<String, String> = emptyMap()) {
        ui.runOnIdle {
            vm.startExam(vm.questions, 3, 30)
            vm.queue = listOf("et-a", "et-b", "et-c")
            answers.forEach { (id, key) -> vm.answerExam(id, setOf(key)) }
            vm.saveExam()
        }
    }

    private fun submitThroughUi() {
        bar("交卷").performClick()
        ui.onNodeWithText("确认交卷").performClick()
    }

    private fun openQuestionAbandon() {
        bar("考试操作").performClick()
        ui.onNodeWithText("放弃本次考试").performClick()
    }

    /** A failure notice opens a snackbar that covers recovery buttons in the small test window. */
    private fun dismissNotice() {
        await { ui.onAllNodesWithText("关闭").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("关闭").performClick()
        await { vm.notice.isEmpty() }
    }

    private fun attempts() = runBlocking { vm.repo.dao.allAttempts() }

    private fun progress() = runBlocking { vm.repo.dao.allProgress() }

    private fun failed() = vm.examOperationState as ExamOperationState.Failed

    private fun assertOptionsFrozen() {
        listOf("甲一", "乙一").forEach { option(it).assertIsNotEnabled() }
    }

    @Test
    fun partialSubmitConfirmationExplainsUnansweredAndResultUsesStructuredSummary() {
        startExam()
        option("甲一").performClick()
        await { vm.examAnswers["et-a"] == setOf("A") }
        bar("交卷").performClick()
        ui.onNodeWithText("确认交卷？").assertExists()
        ui.onNodeWithText("已答 1 题，未答 2 题，共 3 题", substring = true).assertExists()
        ui.onNodeWithText("未答题计 0 分，但不会写入学习记录，也不改变复习安排", substring = true).assertExists()
        ui.onNodeWithText("继续作答").performClick()
        ui.onNodeWithText("确认交卷？").assertDoesNotExist()
        assertEquals(ExamOperationState.Idle, vm.examOperationState)
        assertTrue(attempts().isEmpty())

        submitThroughUi()
        settled { it is ExamOperationState.Submitted }
        assertEquals(ExamResult(3, 1, 2, 1, 33), vm.examSummary)
        listText("答对 1 / 3，得分 33").assertExists()
        listText("已答 1 题 · 未答 2 题（计 0 分，不改变复习安排）", substring = true).assertExists()
        assertEquals(listOf("et-a"), attempts().map { it.questionId })
        assertEquals(listOf("et-a"), progress().map { it.questionId })
        ui.onNodeWithText("交卷").assertDoesNotExist()
    }

    @Test
    fun allUnansweredSubmitScoresZeroWithoutLearningRecords() {
        startExam()
        bar("交卷").performClick()
        ui.onNodeWithText("已答 0 题，未答 3 题，共 3 题", substring = true).assertExists()
        ui.onNodeWithText("确认交卷").performClick()
        settled { it is ExamOperationState.Submitted }
        assertEquals(ExamResult(3, 0, 3, 0, 0), vm.examSummary)
        listText("答对 0 / 3，得分 0").assertExists()
        assertTrue(attempts().isEmpty())
        assertTrue(progress().isEmpty())
    }

    @Test
    fun abandonCancelKeepsPaperAndConfirmReturnsToExamTabWithoutResult() {
        startExam()
        option("甲一").performClick()
        await { vm.examAnswers["et-a"] == setOf("A") }
        val queue = vm.queue
        val answers = vm.examAnswers
        val deadline = vm.examDeadline
        val saved = prefs.getString("exam", null)

        openQuestionAbandon()
        ui.onNodeWithText("放弃本次考试？").assertExists()
        ui.onNodeWithText("不计成绩，不写入学习记录，也不改变复习安排", substring = true).assertExists()
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithText("放弃本次考试？").assertDoesNotExist()
        assertEquals(ExamOperationState.Idle, vm.examOperationState)
        assertEquals(queue, vm.queue)
        assertEquals(answers, vm.examAnswers)
        assertEquals(deadline, vm.examDeadline)
        assertEquals(saved, prefs.getString("exam", null))
        assertEquals(Page.QUESTION, vm.page)
        questionNode(hasText("甲一") and isSelected()).assertExists()

        openQuestionAbandon()
        ui.onNodeWithText("确认放弃").performClick()
        await { vm.examOperationState == ExamOperationState.Abandoned && !vm.busy && vm.page == Page.EXAM }
        ui.onNodeWithText("开始考试").assertExists()
        ui.onAllNodesWithText("得分", substring = true).assertCountEquals(0)
        assertNull(vm.examSummary)
        assertTrue(attempts().isEmpty())
        assertTrue(progress().isEmpty())
        assertFalse(prefs.contains("exam"))
    }

    @Test
    fun endingFreezesInputNavigationAndRepeatedTapsStartOneOperation() {
        startExam()
        option("乙一").performClick()
        await { vm.examAnswers["et-a"] == setOf("B") }
        var operations = 0
        val release = CompletableDeferred<Unit>()
        vm.examState.hooks.beforeCritical = {
            operations++
            release.await()
        }
        repeat(3) { bar("交卷").performClick() }
        // The shell's busy bar is indeterminate; a paused clock lets assertions reach idle.
        ui.mainClock.autoAdvance = false
        ui.onNodeWithText("确认交卷").performClick()
        repeat(3) { ui.mainClock.advanceTimeByFrame() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ExamOperationState.Submitting, vm.examOperationState)
        assertTrue(vm.busy)
        ui.onNodeWithText("确认交卷？").assertDoesNotExist()
        ui.onNodeWithText("正在交卷，请稍候…").assertExists()
        ui.onNode(hasText("正在交卷…") and hasClickAction()).assertIsNotEnabled()
        ui.onNodeWithContentDescription("上一题").assertIsNotEnabled()
        ui.onNodeWithContentDescription("下一题").assertIsNotEnabled()
        ui.onNodeWithContentDescription("考试操作").assertDoesNotExist()
        // The status panel pushes the second option below this small window; the first stays visible.
        ui.onNode(hasText("甲一") and hasClickAction()).assertIsNotEnabled()
        repeat(5) { ui.onNode(hasText("正在交卷…") and hasClickAction()).performClick() }
        ui.onNode(hasText("甲一") and hasClickAction()).performClick()
        ui.onNodeWithContentDescription("下一题").performClick()
        repeat(2) { ui.mainClock.advanceTimeByFrame() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("B"), vm.examAnswers["et-a"])
        assertEquals(0, vm.index)
        assertEquals(1, operations)

        // Leaving during the ending neither cancels nor repeats it.
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        repeat(2) { ui.mainClock.advanceTimeByFrame() }
        assertEquals(Page.HOME, vm.page)
        assertEquals(ExamOperationState.Submitting, vm.examOperationState)
        release.complete(Unit)
        ui.mainClock.autoAdvance = true
        settled { it is ExamOperationState.Submitted }
        assertEquals(1, operations)
        assertEquals(listOf("et-a"), attempts().map { it.questionId })
    }

    @Test
    fun recordFailureFreezesAnswersAndOffersOnlyExplicitRetryOrAbandon() {
        startExam(mapOf("et-a" to "A", "et-b" to "A"))
        vm.db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER fail_exam_insert BEFORE INSERT ON attempts
            WHEN NEW.questionId='et-b' BEGIN SELECT RAISE(ABORT, 'injected'); END"""
        )
        submitThroughUi()
        settled { it is ExamOperationState.Failed }
        assertEquals(ExamOperation.SUBMIT, failed().operation)
        assertEquals(ExamFailureStage.RECORDS, failed().stage)
        assertFalse(failed().recordsCommitted)
        dismissNotice()
        listText("交卷未完成").assertExists()
        listText(failed().message).assertExists()
        assertOptionsFrozen()
        bar("重试交卷").assertIsEnabled()
        questionNode(hasText("放弃本次考试") and hasClickAction()).assertIsEnabled()
        option("乙一").performClick()
        assertEquals(setOf("A"), vm.examAnswers["et-a"])

        // The countdown entry keeps calling finishExam after the deadline; it must never retry.
        var operations = 0
        vm.examState.hooks.beforeCritical = { operations++ }
        val failure = vm.examOperationState
        ui.runOnIdle { vm.examDeadline = System.currentTimeMillis() - 1 }
        ui.mainClock.advanceTimeBy(3_000)
        shadowOf(Looper.getMainLooper()).idle()
        ui.waitForIdle()
        assertEquals(0, operations)
        assertEquals(failure, vm.examOperationState)
        assertTrue(attempts().isEmpty())

        vm.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_exam_insert")
        bar("重试交卷").performClick()
        settled { it is ExamOperationState.Submitted }
        assertEquals(1, operations)
        assertEquals(ExamResult(3, 2, 1, 1, 33), vm.examSummary)
        assertEquals(setOf("et-a", "et-b"), attempts().map { it.questionId }.toSet())
        listText("答对 1 / 3，得分 33").assertExists()
    }

    @Test
    fun committedCleanupFailureOnlyRetriesCleanupAndStaysVisibleFromExamTab() {
        startExam(mapOf("et-a" to "A"))
        val faulty = ClearFailingPreferences(prefs)
        vm.examState.sessionPreferences = faulty
        submitThroughUi()
        settled { it is ExamOperationState.Failed }
        assertEquals(ExamFailureStage.SESSION_CLEAR, failed().stage)
        assertTrue(failed().recordsCommitted)
        dismissNotice()
        listText("成绩已保存，会话清理未完成").assertExists()
        // Not reported as a completed submit, and no route back into abandon or another submit.
        ui.onAllNodesWithText("得分", substring = true).assertCountEquals(0)
        ui.onNodeWithText("放弃本次考试").assertDoesNotExist()
        ui.onNodeWithContentDescription("考试操作").assertDoesNotExist()
        ui.onNodeWithText("交卷").assertDoesNotExist()
        bar("重试清理").assertIsEnabled()
        assertOptionsFrozen()
        assertEquals(1, attempts().size)

        bar("返回首页").performClick()
        await { vm.page == Page.HOME }
        ui.onNodeWithContentDescription("导航到模拟考试").performClick()
        await { vm.page == Page.EXAM }
        ui.onNodeWithText("成绩已保存，会话清理未完成").assertExists()
        ui.onNodeWithText("开始考试").assertDoesNotExist()
        ui.onNodeWithText("放弃本次考试").assertDoesNotExist()
        ui.onNodeWithText("继续考试").assertDoesNotExist()
        ui.onNodeWithText("重试清理").performScrollTo().performClick()
        settled { it is ExamOperationState.Submitted }
        assertEquals(2, faulty.clearAttempts)
        assertEquals(1, attempts().size)
        assertFalse(prefs.contains("exam"))
        ui.onNodeWithText("开始考试").assertExists()
    }

    @Test
    fun abandonCleanupFailureOnlyRetriesAbandonAndNeverOffersSubmit() {
        startExam(mapOf("et-a" to "A"))
        val faulty = ClearFailingPreferences(prefs)
        vm.examState.sessionPreferences = faulty
        ui.runOnIdle { vm.page = Page.EXAM }
        ui.onNodeWithText("继续考试").assertExists()
        ui.onNodeWithText("放弃本次考试").performScrollTo().performClick()
        ui.onNodeWithText("确认放弃").performClick()
        settled { it is ExamOperationState.Failed }
        assertEquals(ExamOperation.ABANDON, failed().operation)
        assertFalse(failed().recordsCommitted)
        dismissNotice()
        ui.onNodeWithText("放弃未完成").assertExists()
        ui.onNodeWithText("放弃本次考试").assertDoesNotExist()
        ui.onNodeWithText("开始考试").assertDoesNotExist()
        ui.onNodeWithText("查看试卷").performScrollTo().performClick()
        await { vm.page == Page.QUESTION }
        listText("放弃未完成").assertExists()
        ui.onNodeWithText("交卷").assertDoesNotExist()
        assertOptionsFrozen()
        bar("重试放弃清理").performClick()
        await { vm.examOperationState == ExamOperationState.Abandoned && !vm.busy && vm.page == Page.EXAM }
        assertEquals(2, faulty.clearAttempts)
        assertTrue(attempts().isEmpty())
        ui.onNodeWithText("开始考试").assertExists()
    }

    @Test
    fun missingQuestionDoesNotTrapUserAndCanStillBeAbandoned() {
        startExam(mapOf("et-a" to "A"))
        vm.db.openHelper.writableDatabase.execSQL("DELETE FROM questions WHERE id='et-b'")
        ui.runOnIdle { vm.index = 1 }
        await {
            ui.onAllNodesWithText("本题无法加载，题目可能已从题库移除。").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("正在加载题目…").assertDoesNotExist()
        ui.onNodeWithText("交卷").performClick()
        ui.onNodeWithText("确认交卷").performClick()
        settled { it is ExamOperationState.Failed }
        assertEquals(ExamFailureStage.VALIDATION, failed().stage)
        dismissNotice()
        ui.onNodeWithText("交卷未完成").assertExists()
        ui.onNodeWithText("重试交卷").assertIsEnabled()
        ui.onNodeWithText("放弃本次考试").performClick()
        ui.onNodeWithText("确认放弃").performClick()
        await { vm.examOperationState == ExamOperationState.Abandoned && !vm.busy && vm.page == Page.EXAM }
        assertTrue(attempts().isEmpty())
        assertTrue(progress().isEmpty())
        assertFalse(prefs.contains("exam"))
    }

    @Test
    fun timeoutWhileAbandonDialogIsOpenSubmitsAndInvalidatesDialog() {
        startExam(mapOf("et-a" to "A"))
        openQuestionAbandon()
        ui.onNodeWithText("确认放弃").assertExists()
        ui.runOnIdle { vm.examDeadline = System.currentTimeMillis() - 1 }
        settled { it is ExamOperationState.Submitted }
        ui.onNodeWithText("放弃本次考试？").assertDoesNotExist()
        ui.onNodeWithText("确认放弃").assertDoesNotExist()
        listText("答对 1 / 3，得分 33").assertExists()
        assertEquals(listOf("et-a"), attempts().map { it.questionId })
    }

    @Test
    fun timeoutWhileSubmitDialogIsOpenClosesItAndSubmitsOnce() {
        startExam(mapOf("et-a" to "A"))
        bar("交卷").performClick()
        ui.onNodeWithText("确认交卷").assertExists()
        ui.runOnIdle { vm.examDeadline = System.currentTimeMillis() - 1 }
        settled { it is ExamOperationState.Submitted }
        ui.onNodeWithText("确认交卷").assertDoesNotExist()
        assertEquals(1, attempts().size)
    }

    @Test
    fun failurePanelReadsInDarkLargeFontAndPracticeHasNoExamControls() {
        ui.runOnIdle { vm.updateSettings { it.copy(theme = "DARK", fontSize = "LARGE") } }
        await { vm.learning.settings.fontSize == "LARGE" && vm.learning.settings.theme == "DARK" && !vm.busy }
        ui.runOnIdle { vm.start(listOf(vm.questions.first { it.id == "et-a" }), "顺序练习") }
        option("甲一").assertIsEnabled()
        ui.onNodeWithContentDescription("考试操作").assertDoesNotExist()
        bar("提交答案").assertExists()
        ui.runOnIdle { vm.page = Page.HOME }

        startExam(mapOf("et-a" to "A"))
        vm.examState.sessionPreferences = ClearFailingPreferences(prefs)
        submitThroughUi()
        settled { it is ExamOperationState.Failed }
        listText("成绩已保存，会话清理未完成")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        bar("重试清理").assertIsEnabled().assertHasClickAction()
        ui.onNodeWithContentDescription("上一题").assertExists()
        ui.onNodeWithContentDescription("下一题").assertExists()
    }
}

/** commit=false after the removal already changed SharedPreferences memory, like the backend test seam. */
private class ClearFailingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
    var failures = 1
    var clearAttempts = 0

    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            private var removesExam = false

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key == "exam") removesExam = true
                editor.remove(key)
                return this
            }

            override fun commit(): Boolean {
                val committed = editor.commit()
                if (removesExam) {
                    clearAttempts++
                    if (failures > 0) {
                        failures--
                        return false
                    }
                }
                return committed
            }
        }
    }
}
