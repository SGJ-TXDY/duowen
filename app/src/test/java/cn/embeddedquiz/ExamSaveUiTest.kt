package cn.embeddedquiz

import android.app.Application
import android.content.SharedPreferences
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
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

/** T2 exam save UI: status, explicit retry, leaving only after a flush, and restart recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExamSaveUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private var shown by mutableStateOf<QuizViewModel?>(null)
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"et-a","stem":"考试第一题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲一"},{"key":"B","text":"乙一"}],"correct":["A"],"reviewRequired":false},
        {"id":"et-b","stem":"考试第二题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲二"},{"key":"B","text":"乙二"}],"correct":["B"],"reviewRequired":false},
        {"id":"et-c","stem":"考试第三题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲三"},{"key":"B","text":"乙三"}],"correct":["A"],"reviewRequired":false}]}
    """

    // Write seam: the writer calls it on IO before every commit.
    @Volatile private var failWrites = false
    @Volatile private var gate: CompletableDeferred<Unit>? = null
    private val writes = AtomicInteger()

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
        runBlocking {
            val db = QuizDatabase.open(app)
            try {
                QuizRepository(db).importBank(bank)
            } finally {
                db.close()
            }
        }
        prefs.edit().putInt("bankRevision", 4).commit()
        openModel()
        // A restart brings a new composition, never a new ViewModel inside a living one: keyed so
        // nothing composed for the old instance (handlers included) survives the swap.
        ui.setContent { shown?.let { key(it) { QuizApp(it) } } }
    }

    @After
    fun cleanup() {
        gate?.complete(Unit)
        failWrites = false
        closeModel()
        app.deleteDatabase("embeddedquiz.db")
        prefs.edit().clear().commit()
    }

    private fun openModel() {
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
        vm.examState.persistence.beforeWrite = {
            writes.incrementAndGet()
            gate?.await()
            if (failWrites) throw IOException("injected write failure")
        }
        shown = vm
    }

    /** Like a process restart: the old owner closes its database before a new one reads disk. */
    private fun closeModel() {
        val old = vm
        ui.runOnUiThread { store.clear() }
        await { old.examResourcesClosed?.isCompleted == true }
    }

    private fun await(condition: () -> Boolean) {
        try {
            ui.waitUntil(20000) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Throwable) {
            throw AssertionError(
                "save=${vm.examSaveState}; state=${vm.examOperationState}; page=${vm.page}; busy=${vm.busy}; writes=${writes.get()}",
                e,
            )
        }
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

    private fun option(text: String) = questionNode(hasText(text) and hasClickAction())

    private fun bar(label: String) =
        questionNode((hasText(label) or hasContentDescription(label)) and hasClickAction() and !hasAnyAncestor(isDialog()))

    private fun inDialog(text: String) = ui.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    /** The save status sits on the answer screen, which appears once the question has loaded. */
    private fun startExam() {
        ui.runOnIdle {
            vm.startExam(vm.questions, 3, 30)
            vm.queue = listOf("et-a", "et-b", "et-c")
            vm.saveExam()
        }
        questionNode(hasTestTag("question-list"))
    }

    private fun savedOnDisk() = DurableExamSnapshot.decode(requireNotNull(prefs.getString("exam", null)))

    private fun dismissNotice() {
        await { ui.onAllNodesWithText("关闭").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("关闭").performClick()
        await { vm.notice.isEmpty() }
    }

    /** A restored failure may or may not raise a notice; never wait for one that is not coming. */
    private fun dismissNoticeIfShown() {
        ui.waitForIdle()
        if (ui.onAllNodesWithText("关闭").fetchSemanticsNodes().isNotEmpty()) dismissNotice()
    }

    private fun attempts() = runBlocking { vm.repo.dao.allAttempts() }

    @Test
    fun savingDoesNotBlockAnsweringAndSavedAppearsOnlyForTheLatestVersion() {
        gate = CompletableDeferred()
        startExam()
        await { vm.examSaveState is ExamSaveState.Saving }
        ui.onNodeWithText("保存中…").assertExists()
        assertFalse(vm.busy)

        option("甲一").assertIsEnabled().performClick()
        await { vm.examAnswers["et-a"] == setOf("A") }
        bar("下一题").assertIsEnabled().performClick()
        await { vm.index == 1 }
        option("乙二").performClick()
        await { vm.examAnswers["et-b"] == setOf("B") }
        ui.onNodeWithText("保存中…").assertExists()
        ui.onNodeWithText("已保存到本机").assertDoesNotExist()
        val latest = (vm.examSaveState as ExamSaveState.Saving).revision

        gate!!.complete(Unit)
        await { vm.examSaveState == ExamSaveState.Saved(latest) }
        ui.onNodeWithText("已保存到本机").assertExists()
        ui.onNodeWithText("保存中…").assertDoesNotExist()
        val disk = savedOnDisk()
        assertEquals(latest, disk.revision)
        assertEquals(1, disk.index)
        assertEquals(mapOf("et-a" to setOf("A"), "et-b" to setOf("B")), disk.answers)
    }

    @Test
    fun saveErrorStaysVisibleWhileAnsweringAndOnlyAnExplicitRetrySavesIt() {
        failWrites = true
        startExam()
        await { vm.examSaveState is ExamSaveState.Error }
        ui.onNodeWithText("保存失败，最新修改尚未保存").assertExists()
        ui.onNodeWithText("重试保存").assertIsEnabled()

        option("甲一").assertIsEnabled().performClick()
        await { vm.examAnswers["et-a"] == setOf("A") && vm.examSaveState is ExamSaveState.Error && writes.get() >= 2 }
        ui.onNodeWithText("保存失败，最新修改尚未保存").assertExists()
        val tries = writes.get()
        // No change, no new attempt: the failure is not retried in a loop behind the user's back.
        ui.mainClock.advanceTimeBy(5_000)
        shadowOf(Looper.getMainLooper()).idle()
        ui.waitForIdle()
        assertEquals(tries, writes.get())
        assertTrue(vm.examSaveState is ExamSaveState.Error)

        failWrites = false
        ui.onNodeWithText("重试保存").performClick()
        await { vm.examSaveState is ExamSaveState.Saved }
        assertEquals(tries + 1, writes.get())
        ui.onNodeWithText("已保存到本机").assertExists()
        ui.onNodeWithText("保存失败，最新修改尚未保存").assertDoesNotExist()
        assertEquals(setOf("A"), savedOnDisk().answers["et-a"])
    }

    @Test
    fun leavingWaitsForTheFlushAndFreezesInputUntilItLands() {
        startExam()
        await { vm.examSaveState is ExamSaveState.Saved }
        gate = CompletableDeferred()
        option("甲一").performClick()
        await { vm.examSaveState is ExamSaveState.Saving }

        bar("返回首页").performClick()
        ui.waitForIdle()
        assertEquals(Page.QUESTION, vm.page)
        option("乙一").assertIsNotEnabled()
        bar("下一题").assertIsNotEnabled()
        repeat(3) { bar("返回首页").performClick() }
        gate!!.complete(Unit)
        await { vm.page == Page.HOME }
        assertEquals(setOf("A"), savedOnDisk().answers["et-a"])

        // The system back gesture takes the same path.
        gate = CompletableDeferred()
        ui.runOnIdle { vm.page = Page.QUESTION }
        option("乙一").performClick()
        await { vm.examSaveState is ExamSaveState.Saving }
        back()
        assertEquals(Page.QUESTION, vm.page)
        gate!!.complete(Unit)
        await { vm.page == Page.HOME }
        assertEquals(setOf("B"), savedOnDisk().answers["et-a"])
    }

    /**
     * A notification opens the review while a leave is waiting for its flush, and the flush then
     * finishes before the next frame disposes the paper. The order is fixed with a paused clock.
     */
    private fun notifyWhileLeaving(saveFails: Boolean) {
        startExam()
        await { vm.examSaveState is ExamSaveState.Saved }
        gate = CompletableDeferred()
        option("甲一").performClick()
        await { vm.examSaveState is ExamSaveState.Saving }
        failWrites = saveFails
        bar("返回首页").performClick()
        ui.waitForIdle()
        assertEquals(Page.QUESTION, vm.page)

        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread {
                vm.requestNotificationReview()
                assertEquals(Page.REVIEW, vm.page)
                gate!!.complete(Unit)
            }
            // Let the flush resume on Main without a frame, which is when a stale callback would run.
            await {
                if (saveFails) vm.examSaveState is ExamSaveState.Error
                else vm.examSaveState is ExamSaveState.Saved
            }
            repeat(50) {
                shadowOf(Looper.getMainLooper()).idle()
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
            }
            assertEquals("A late leave must not overwrite the notification's page", Page.REVIEW, vm.page)
        } finally {
            gate!!.complete(Unit)
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertEquals(Page.REVIEW, vm.page)
        ui.onNodeWithText("智能复习").assertExists()
        ui.onNodeWithText("保存失败，暂未离开").assertDoesNotExist()
    }

    @Test
    fun notificationDuringLeaveWinsWhenTheFlushSucceedsBeforeTheNextFrame() {
        notifyWhileLeaving(saveFails = false)
        assertEquals(setOf("A"), savedOnDisk().answers["et-a"])
    }

    @Test
    fun notificationDuringLeaveWinsAndNoStaleDialogWhenTheFlushFails() {
        notifyWhileLeaving(saveFails = true)
        // The failure itself is not lost: the exam tab still shows it with a retry.
        failWrites = false
        ui.onNodeWithContentDescription("导航到模拟考试").performClick()
        await { vm.page == Page.EXAM }
        ui.onNodeWithText("保存失败，最新修改尚未保存").assertExists()
        ui.onNodeWithText("重试保存").performScrollTo().performClick()
        await { vm.examSaveState is ExamSaveState.Saved }
        assertEquals(setOf("A"), savedOnDisk().answers["et-a"])
    }

    @Test
    fun failedFlushKeepsTheUserOnThePaperUntilAnExplicitRetrySucceeds() {
        startExam()
        await { vm.examSaveState is ExamSaveState.Saved }
        failWrites = true
        option("甲一").performClick()
        await { vm.examSaveState is ExamSaveState.Error }

        bar("返回首页").performClick()
        await { ui.onAllNodesWithText("保存失败，暂未离开").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Page.QUESTION, vm.page)
        inDialog("留在本页").performClick()
        ui.onNodeWithText("保存失败，暂未离开").assertDoesNotExist()
        assertEquals(Page.QUESTION, vm.page)
        assertEquals(setOf("A"), vm.examAnswers["et-a"])

        // Another way out of the paper is held the same way and resumes after the retry.
        bar("打开全局标签").performClick()
        await { ui.onAllNodesWithText("保存失败，暂未离开").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Page.QUESTION, vm.page)
        failWrites = false
        inDialog("重试保存").performClick()
        await { vm.page == Page.NOTES }
        assertTrue(vm.examSaveState is ExamSaveState.Saved)
        assertEquals(setOf("A"), savedOnDisk().answers["et-a"])
    }

    @Test
    fun saveBarrierFailureFreezesTheSubmitAndRetriesWithTheFrozenPaper() {
        startExam()
        option("甲一").performClick()
        await { vm.examSaveState is ExamSaveState.Saved }
        failWrites = true
        bar("下一题").performClick()
        await { vm.index == 1 }
        option("甲二").performClick()
        await { vm.examSaveState is ExamSaveState.Error }

        bar("交卷").performClick()
        ui.onNodeWithText("确认交卷").performClick()
        await { vm.examOperationState is ExamOperationState.Failed && !vm.busy }
        val failed = vm.examOperationState as ExamOperationState.Failed
        assertEquals(ExamFailureStage.RECORDS, failed.stage)
        assertFalse(failed.recordsCommitted)
        assertEquals("考试保存失败，未提交成绩", failed.message)
        dismissNotice()
        questionNode(hasText("考试保存失败，未提交成绩") and inList).assertExists()
        ui.onNodeWithText("保存失败，最新修改尚未保存").assertExists()
        // Answers are frozen now, so only the ending panel's retry is offered.
        ui.onNode(hasText("重试保存") and hasClickAction()).assertDoesNotExist()
        option("乙二").assertIsNotEnabled()
        assertTrue(attempts().isEmpty())

        // A frozen, failed ending is not held on the page; its recovery stays on the exam tab.
        bar("返回首页").performClick()
        await { vm.page == Page.HOME }
        failWrites = false
        ui.onNodeWithContentDescription("导航到模拟考试").performClick()
        await { vm.page == Page.EXAM }
        ui.onNodeWithText("重试交卷").performScrollTo().performClick()
        await { vm.examOperationState is ExamOperationState.Submitted && !vm.busy }
        assertEquals(ExamResult(3, 2, 1, 1, 33), vm.examSummary)
        assertEquals(mapOf("et-a" to 1, "et-b" to 1), attempts().groupingBy { it.questionId }.eachCount())
    }

    @Test
    fun committedCleanupFailureSurvivesRestartWithItsResultAndRecordsOnce() {
        startExam()
        option("甲一").performClick()
        await { vm.examSaveState is ExamSaveState.Saved }
        vm.examState.sessionPreferences = RefusingExamClearPreferences(prefs)
        bar("交卷").performClick()
        ui.onNodeWithText("确认交卷").performClick()
        await { vm.examOperationState is ExamOperationState.Failed && !vm.busy }
        assertTrue((vm.examOperationState as ExamOperationState.Failed).recordsCommitted)
        val result = vm.examSummary
        assertEquals(ExamResult(3, 1, 2, 1, 33), result)
        assertEquals(1, attempts().size)
        assertNotNull("清理失败后快照仍在磁盘上", prefs.getString("exam", null))

        closeModel()
        openModel()
        await {
            (vm.examOperationState as? ExamOperationState.Failed)?.stage == ExamFailureStage.SESSION_CLEAR
        }
        assertEquals(result, vm.examSummary)
        assertEquals(Page.QUESTION, vm.page)
        assertEquals(ExamSaveState.Inactive, vm.examSaveState)
        dismissNoticeIfShown()
        questionNode(hasText("成绩已保存，会话清理未完成") and inList).assertExists()
        ui.onAllNodesWithText("得分", substring = true).assertCountEquals(0)
        ui.onNodeWithText("交卷").assertDoesNotExist()
        ui.onNodeWithText("已保存到本机").assertDoesNotExist()
        assertEquals(1, attempts().size)

        bar("重试清理").performClick()
        await { vm.examOperationState is ExamOperationState.Submitted && !vm.busy }
        assertEquals(ExamOperationState.Submitted(result!!), vm.examOperationState)
        questionNode(hasText("答对 1 / 3，得分 33") and inList).assertExists()
        assertEquals(1, attempts().size)
        assertNull(prefs.getString("exam", null))
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun saveFailureIsReadableAndReachableOnASmallScreenWithLargeText() {
        ui.runOnIdle { vm.updateSettings { it.copy(fontSize = "LARGE") } }
        await { vm.learning.settings.fontSize == "LARGE" && !vm.busy }
        failWrites = true
        startExam()
        await { vm.examSaveState is ExamSaveState.Error }
        ui.onNodeWithText("保存失败，最新修改尚未保存")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        ui.onNodeWithText("重试保存").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        option("甲一").assertIsEnabled()
        bar("交卷").assertIsDisplayed()
    }
}

/** Refuses to remove the exam snapshot without touching disk, so it survives into a restart. */
private class RefusingExamClearPreferences(private val delegate: SharedPreferences) :
    SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            private var removesExam = false

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key == "exam") removesExam = true else editor.remove(key)
                return this
            }

            override fun commit(): Boolean = if (removesExam) false else editor.commit()
        }
    }
}
