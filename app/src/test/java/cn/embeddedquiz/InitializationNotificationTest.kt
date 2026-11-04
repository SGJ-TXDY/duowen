package cn.embeddedquiz

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Business protocol only: no Activity, Compose test rule or notification navigation effect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InitializationNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val store = ViewModelStore()
    private var model: QuizViewModel? = null
    private val deadline = System.currentTimeMillis() + 600_000
    private val saved = """{"ids":["b","a","c"],"index":1,"deadline":$deadline,"answers":{"a":["A"],"b":["B"]}}"""
    private lateinit var oldProgress: List<Progress>
    private lateinit var oldAttempts: List<Attempt>
    private lateinit var oldDaily: List<DailyCount>

    @Before
    fun setup() = runBlocking {
        app.deleteDatabase("embeddedquiz.db")
        prefs.edit().clear().commit()
        val db = QuizDatabase.open(app)
        try {
            val repo = QuizRepository(db)
            repo.importBank("""{"schemaVersion":1,"questions":[
                {"id":"a","stem":"first","source":"G1","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]},
                {"id":"b","stem":"second","source":"G1","kind":"MULTIPLE","reviewRequired":false,"options":[{"key":"A","text":"one"},{"key":"B","text":"two"}],"correct":["A","B"]},
                {"id":"c","stem":"unanswered","source":"G1","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]}]}""")
            repo.record(repo.question("a")!!, "A", setOf("A"), "原练习")
            repo.dao.putProgress(Progress("a", seen = 8, correctCount = 5, repetitions = 3,
                interval = 27, ease = 2.2, due = 23456, lapses = 3, wrong = true,
                favorite = true, flagged = true, code = "keep code", notes = "保留笔记"))
            oldProgress = repo.dao.allProgress()
            oldAttempts = repo.dao.allAttempts()
            oldDaily = repo.dao.dailyCounts(0).first()
        } finally { db.close() }
        prefs.writeDurably {
            putInt("bankRevision", 4)
            putString("categoryMapHash", CategoryMap.fingerprint)
            putString("exam", saved)
        }
    }

    @After
    fun cleanup() {
        model?.let { vm ->
            val jobs = vm.viewModelScope.coroutineContext[Job]!!.children.toList()
            jobs.forEach { it.cancel() }
            waitFor { jobs.all { it.isCompleted } }
        }
        store.clear()
        app.deleteDatabase("embeddedquiz.db")
        prefs.edit().clear().commit()
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            LockSupport.parkNanos(1_000_000)
        }
        assertTrue("等待初始化信号超时：${model?.notice}", condition())
    }

    private fun create(): QuizViewModel {
        val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        model = vm
        assertFalse("构造后 Main 尚未处理 IO 返回，信号应仍未完成", vm.ready)
        return vm
    }

    private fun outcome(vm: QuizViewModel): InitializationOutcome {
        val waiting = vm.viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { vm.awaitInitialization() }
        waitFor { waiting.isCompleted }
        return runBlocking { waiting.await() }
    }

    private fun assertExamPreserved(vm: QuizViewModel) {
        assertEquals(listOf("b", "a", "c"), vm.queue)
        assertEquals(1, vm.index)
        assertEquals(mapOf("a" to setOf("A"), "b" to setOf("B")), vm.examAnswers)
        assertEquals(deadline, vm.examDeadline)
        assertEquals("模拟考试", vm.mode)
        assertTrue(vm.examState.active())
        assertFalse(vm.examFinished)
        assertNull(vm.examSummary)
        val flushed = vm.viewModelScope.async { vm.flushExamSave() }
        waitFor { flushed.isCompleted }
        assertTrue(runBlocking { flushed.await() })
        val persisted = DurableExamSnapshot.decode(prefs.getString("exam", null)!!)
        assertEquals(vm.queue, persisted.ids)
        assertEquals(vm.index, persisted.index)
        assertEquals(vm.examAnswers, persisted.answers)
        assertEquals(vm.examDeadline, persisted.deadline)
        java.util.UUID.fromString(persisted.sessionId)
        assertLearningPreserved(vm)
    }

    private fun assertLearningPreserved(vm: QuizViewModel) = runBlocking {
        assertEquals(oldProgress, vm.repo.dao.allProgress())
        assertEquals(oldAttempts, vm.repo.dao.allAttempts())
        assertEquals(oldDaily, vm.repo.dao.dailyCounts(0).first())
        assertNull(vm.repo.dao.getProgress("c"))
    }

    @Test
    fun clearingOwnerKeepsDatabaseOpenUntilCancelledReadersFinish() {
        val vm = create()
        outcome(vm)
        val flush = vm.viewModelScope.async { vm.flushExamSave() }
        waitFor { flush.isCompleted }
        assertTrue(runBlocking { flush.await() })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val readFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        vm.viewModelScope.launch(Dispatchers.IO) {
            // A SQLite call already in progress can finish after its caller is cancelled.
            withContext(NonCancellable) {
                entered.complete(Unit)
                try {
                    release.await()
                    vm.repo.dao.allPersonalMetadata()
                } catch (failure: Throwable) {
                    readFailure.set(failure)
                } finally { finished.complete(Unit) }
            }
        }
        waitFor { entered.isCompleted }
        try {
            store.clear()
            val closed = checkNotNull(vm.examResourcesClosed)
            val closedEarly = runBlocking {
                withTimeoutOrNull(250) { closed.join(); true } ?: false
            }
            assertFalse("Database closed while a cancelled reader still owns it", closedEarly)
            assertTrue(vm.db.isOpen)
        } finally {
            release.complete(Unit)
            waitFor { finished.isCompleted }
            waitFor { vm.examResourcesClosed?.isCompleted == true }
        }
        assertNull("Reader must finish before its connection pool closes", readFailure.get())
        assertFalse(vm.db.isOpen)
    }
    @Test
    fun pendingRestoreRejectsResetBeforeInitializationCanPublishExam() {
        val vm = create()
        val failure = runBlocking { runCatching { vm.repo.reset() }.exceptionOrNull() }
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("请先结束"))
        vm.requestNotificationReview()
        assertEquals(InitializationOutcome.Ready(true), outcome(vm))
        assertEquals(Page.REVIEW, vm.page)
        assertExamPreserved(vm)
    }
    @Test
    fun notificationBeforeInitializationPublishesReviewWithRestoredExam() {
        val vm = create()
        val waiting = vm.viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { vm.awaitInitialization() }
        assertFalse(waiting.isCompleted)
        vm.requestNotificationReview()
        assertFalse(vm.ready)
        waitFor { waiting.isCompleted }
        assertEquals(InitializationOutcome.Ready(true), runBlocking { waiting.await() })
        assertTrue(vm.ready)
        assertEquals(Page.REVIEW, vm.page)
        assertExamPreserved(vm)
    }

    @Test
    fun ordinaryStartupRestoresExamAsQuestionWithoutNotification() {
        val vm = create()
        assertEquals(InitializationOutcome.Ready(true), outcome(vm))
        assertEquals(Page.QUESTION, vm.page)
        assertExamPreserved(vm)
    }

    @Test
    fun notificationBeforeInitializationWithoutExamOpensReviewWithoutLearningWrites() {
        prefs.writeDurably { remove("exam") }
        val vm = create()
        vm.requestNotificationReview()
        assertEquals(InitializationOutcome.Ready(false), outcome(vm))
        assertEquals(Page.REVIEW, vm.page)
        assertTrue(vm.queue.isEmpty())
        assertTrue(vm.examAnswers.isEmpty())
        assertEquals(0L, vm.examDeadline)
        assertFalse(vm.examState.active())
        assertFalse(prefs.contains("exam"))
        assertLearningPreserved(vm)
    }

    @Test
    fun ordinaryStartupWithoutExamKeepsHomeAndReturnsNoRestoration() {
        prefs.writeDurably { remove("exam") }
        val vm = create()
        assertEquals(InitializationOutcome.Ready(false), outcome(vm))
        assertEquals(Page.HOME, vm.page)
        assertTrue(vm.ready)
        assertLearningPreserved(vm)
    }

    @Test
    fun ordinaryStartupWithoutExamKeepsExplicitNonReviewDestination() {
        prefs.writeDurably { remove("exam") }
        val vm = create()
        vm.navigate(Page.MORE)
        assertEquals(InitializationOutcome.Ready(false), outcome(vm))
        assertEquals(Page.MORE, vm.page)
        assertLearningPreserved(vm)
    }

    @Test
    fun existingMainActivityReviewAssignmentHasSamePriorityAsNewCommand() {
        val vm = create()
        vm.page = Page.REVIEW // Exact legacy MainActivity notification entry, no Compose effect needed.
        assertEquals(InitializationOutcome.Ready(true), outcome(vm))
        assertEquals(Page.REVIEW, vm.page)
        assertExamPreserved(vm)
    }

    @Test
    fun notificationAfterInitializationImmediatelyOpensReviewAndKeepsOriginalExam() {
        val vm = create()
        assertEquals(InitializationOutcome.Ready(true), outcome(vm))
        assertEquals(Page.QUESTION, vm.page)
        vm.requestNotificationReview()
        assertEquals(Page.REVIEW, vm.page)
        assertExamPreserved(vm)
    }

    @Test
    fun repeatedNotificationsUseOneRestorationAndDoNotRewriteTheExam() {
        val vm = create()
        repeat(10) { vm.requestNotificationReview() }
        assertEquals(InitializationOutcome.Ready(true), outcome(vm))
        repeat(10) { vm.requestNotificationReview() }
        assertEquals(InitializationOutcome.Ready(true), runBlocking { vm.awaitInitialization() })
        assertEquals(Page.REVIEW, vm.page)
        assertFalse(vm.busy)
        assertExamPreserved(vm)
    }

    @Test
    fun notificationIsNotDroppedWhileAnUnrelatedWriteTaskIsBusy() {
        val vm = create()
        outcome(vm)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.launchTask { entered.complete(Unit); release.await() }
        try {
            waitFor { entered.isCompleted }
            assertTrue(vm.busy)
            vm.requestNotificationReview()
            assertEquals(Page.REVIEW, vm.page)
            assertTrue(vm.busy)
            assertExamPreserved(vm)
        } finally { release.complete(Unit); waitFor { !vm.busy } }
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelOtherWaitersOrInitialization() {
        val vm = create()
        val cancelled = vm.viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { vm.awaitInitialization() }
        val second = vm.viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { vm.awaitInitialization() }
        cancelled.cancel()
        vm.requestNotificationReview()
        waitFor { second.isCompleted }
        assertTrue(cancelled.isCancelled)
        assertEquals(InitializationOutcome.Ready(true), runBlocking { second.await() })
        assertEquals(InitializationOutcome.Ready(true), runBlocking { vm.awaitInitialization() })
        assertEquals(Page.REVIEW, vm.page)
        assertExamPreserved(vm)
    }

    @Test
    fun initializationFailureCompletesWithFailureAndKeepsExamAndLearningData() {
        val raw = app.openOrCreateDatabase("embeddedquiz.db", 0, null)
        try { raw.execSQL("DROP TABLE questions") } finally { raw.close() }
        val vm = create()
        vm.requestNotificationReview()
        val result = outcome(vm)
        assertEquals(InitializationOutcome.Failed("题库加载失败，请查看本地错误记录"), result)
        assertFalse(vm.ready)
        assertEquals(Page.HOME, vm.page)
        assertEquals(saved, prefs.getString("exam", null))
        assertLearningPreserved(vm)
        vm.requestNotificationReview()
        assertEquals(result, runBlocking { vm.awaitInitialization() })
        assertFalse(vm.ready)
        assertEquals(saved, prefs.getString("exam", null))
    }

    @Test
    fun clearingOwnerCancelsPendingCompletionInsteadOfLeavingWaitersSuspended() {
        val vm = create()
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val waiting = caller.async(start = CoroutineStart.UNDISPATCHED) { vm.awaitInitialization() }
            vm.requestNotificationReview()
            store.clear()
            waitFor { waiting.isCompleted }
            assertTrue(waiting.isCancelled)
            assertFalse(vm.ready)
            assertEquals(saved, prefs.getString("exam", null))
        } finally { caller.cancel() }
    }
}
