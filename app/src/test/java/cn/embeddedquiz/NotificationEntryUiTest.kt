package cn.embeddedquiz

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** MainActivity notification entry against exam restoration, through the real Activity and UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationEntryUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"nt-a","stem":"通知第一题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲一"},{"key":"B","text":"乙一"}],"correct":["A"],"reviewRequired":false},
        {"id":"nt-b","stem":"通知第二题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲二"},{"key":"B","text":"乙二"}],"correct":["B"],"reviewRequired":false}]}
    """
    private val deadline = System.currentTimeMillis() + 30 * 60_000L
    private val snapshot =
        """{"ids":["nt-a","nt-b"],"index":1,"deadline":$deadline,"answers":{"nt-a":["A"]}}"""
    private lateinit var recomposers: Set<Any>
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var vm: QuizViewModel

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
        prefs.edit().putInt("bankRevision", 4).putString("exam", snapshot).commit()
        resetDefaultViewModelFactory()
        recomposers = runningRecomposers()
    }

    @After
    fun cleanup() {
        try {
            controller?.destroyAndAwaitRecomposers(recomposers)
        } finally {
            prefs.edit().clear().commit()
            app.deleteDatabase("embeddedquiz.db")
        }
    }

    private fun intent(review: Boolean) =
        Intent(app, MainActivity::class.java).apply {
            if (review) putExtra(ReviewReminders.OPEN_REVIEW, true)
        }

    private fun launch(review: Boolean, saved: Bundle? = null): InitializationOutcome {
        val built = Robolectric.buildActivity(MainActivity::class.java, intent(review))
        controller = built
        built.create(saved).start().resume().visible()
        vm = ViewModelProvider(built.get())[QuizViewModel::class.java]
        // The ViewModel's own completion point, not ready or a single looper pass.
        val outcome = vm.viewModelScope.async { vm.awaitInitialization() }
        await { outcome.isCompleted }
        return runBlocking { outcome.await() }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            LockSupport.parkNanos(1_000_000)
        }
        assertTrue("page=${vm.page}; ready=${vm.ready}; notice=${vm.notice}", condition())
    }

    private fun assertExamKept() {
        assertEquals("模拟考试", vm.mode)
        assertEquals(listOf("nt-a", "nt-b"), vm.queue)
        assertEquals(1, vm.index)
        assertEquals(mapOf("nt-a" to setOf("A")), vm.examAnswers)
        assertEquals(deadline, vm.examDeadline)
        assertFalse(vm.examFinished)
        assertEquals(snapshot, prefs.getString("exam", null))
        assertTrue(runBlocking { vm.repo.dao.allAttempts() }.isEmpty())
    }

    private fun questionShown(id: String) {
        ui.waitUntil(20_000) {
            ui.onAllNodes(hasTestTag("question-list") and hasContentDescription("题目$id"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun reviewShown() = ui.onNodeWithText("智能复习").assertExists()

    @Test
    fun coldNotificationOpensReviewThenUnfinishedExamResumesFromHome() {
        assertEquals(InitializationOutcome.Ready(examRestored = true), launch(review = true))
        assertEquals(Page.REVIEW, vm.page)
        assertFalse(controller!!.get().intent.hasExtra(ReviewReminders.OPEN_REVIEW))
        reviewShown()
        assertExamKept()

        ui.onNodeWithContentDescription("导航到首页").performClick()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("继续未交卷考试"))
        ui.onNodeWithText("继续未交卷考试").performClick()
        assertEquals(Page.QUESTION, vm.page)
        questionShown("nt-b")
        ui.onNode(hasContentDescription("剩余时间", substring = true)).assertExists()
        ui.onNodeWithContentDescription("上一题").performClick()
        questionShown("nt-a")
        ui.onNode(hasText("甲一") and isSelected()).assertExists()
        assertEquals(deadline, vm.examDeadline)
        assertTrue(runBlocking { vm.repo.dao.allAttempts() }.isEmpty())
    }

    @Test
    fun ordinaryStartRestoresUnfinishedExamOnQuestionScreen() {
        assertEquals(InitializationOutcome.Ready(examRestored = true), launch(review = false))
        assertEquals(Page.QUESTION, vm.page)
        questionShown("nt-b")
        ui.onNodeWithText("智能复习").assertDoesNotExist()
        assertExamKept()
    }

    @Test
    fun runningNotificationsSwitchToReviewAndRecreationDoesNotReplayThem() {
        assertEquals(InitializationOutcome.Ready(examRestored = true), launch(review = false))
        questionShown("nt-b")
        val running = controller!!

        running.newIntent(intent(review = true))
        assertEquals(Page.REVIEW, vm.page)
        assertFalse(running.get().intent.hasExtra(ReviewReminders.OPEN_REVIEW))
        reviewShown()
        repeat(2) { running.newIntent(intent(review = true)) }
        assertEquals(Page.REVIEW, vm.page)
        assertExamKept()

        ui.onNodeWithContentDescription("导航到首页").performClick()
        assertEquals(Page.HOME, vm.page)
        // A plain intent and a configuration change must not reopen the review.
        running.newIntent(intent(review = false))
        running.recreate().visible()
        val recreated = ViewModelProvider(running.get())[QuizViewModel::class.java]
        assertSame(vm, recreated)
        ui.waitForIdle()
        assertEquals(Page.HOME, vm.page)
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("继续未交卷考试"))
        ui.onNodeWithText("继续未交卷考试").assertExists()
        assertExamKept()
    }

    @Test
    fun restoredActivityIgnoresTheStaleLaunchNotification() {
        // Process recreation re-delivers the original launch intent together with saved state.
        assertEquals(InitializationOutcome.Ready(examRestored = true), launch(review = true, saved = Bundle()))
        assertEquals(Page.QUESTION, vm.page)
        questionShown("nt-b")
        assertExamKept()
    }

    @Test
    fun notificationWithoutExamOpensReviewAndOrdinaryStartStaysHome() {
        prefs.edit().remove("exam").commit()
        assertEquals(InitializationOutcome.Ready(examRestored = false), launch(review = true))
        assertEquals(Page.REVIEW, vm.page)
        reviewShown()
        assertTrue(vm.queue.isEmpty())
        controller!!.destroyAndAwaitRecomposers(recomposers)
        controller = null

        assertEquals(InitializationOutcome.Ready(examRestored = false), launch(review = false))
        assertEquals(Page.HOME, vm.page)
        assertTrue(vm.queue.isEmpty())
        assertFalse(prefs.contains("exam"))
    }
}
