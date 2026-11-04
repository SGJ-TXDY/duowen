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
class ExamLeaveNotificationProbeTest {
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
    fun notificationWinsWhileLeaveFlushFinishesBeforeTheNextFrame() {
        startExam()
        await { vm.examSaveState is ExamSaveState.Saved }
        gate = CompletableDeferred()
        option("甲一").performClick()
        await { vm.examSaveState is ExamSaveState.Saving }
        bar("返回首页").performClick()
        ui.waitForIdle()
        assertEquals(Page.QUESTION, vm.page)

        // Main receives a notification before the next Compose frame disposes the paper scope.
        // A successful disk completion may arrive within this ordinary inter-frame interval.
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread {
                vm.requestNotificationReview()
                assertEquals(Page.REVIEW, vm.page)
                gate!!.complete(Unit)
            }
            val deadline = System.nanoTime() + 1_000_000_000L
            while (System.nanoTime() < deadline && vm.page != Page.HOME) {
                shadowOf(Looper.getMainLooper()).idle()
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
            }
            assertEquals("A late flush callback must not overwrite the notification's destination", Page.REVIEW, vm.page)
        } finally {
            gate!!.complete(Unit)
            ui.mainClock.autoAdvance = true
        }
    }
}