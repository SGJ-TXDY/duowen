package cn.embeddedquiz

import android.app.Application
import android.content.SharedPreferences
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Backend contracts only: no Compose screen or UI event implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExamLifecycleContractTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private lateinit var vm: QuizViewModel
    private var observerJobs: List<Job> = emptyList()
    private val bank = """{"schemaVersion":1,"questions":[
        {"id":"a","stem":"one","source":"测试","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]},
        {"id":"b","stem":"two","source":"测试","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]},
        {"id":"c","stem":"three","source":"测试","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]}]}"""

    @Before
    fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        prefs.edit().clear().commit()
        runBlocking {
            val db = QuizDatabase.open(app)
            try { QuizRepository(db).importBank(bank) } finally { db.close() }
        }
        prefs.edit().putInt("bankRevision", 4).commit()
        model()
    }

    @After
    fun cleanup() {
        clearModel()
        app.deleteDatabase("embeddedquiz.db")
        prefs.edit().clear().commit()
    }

    /** Drain unrelated Room observers before intentionally racing only the exam job with close. */
    private fun clearModel() {
        observerJobs.forEach { it.cancel() }
        await { observerJobs.all { it.isCompleted } }
        store.clear()
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            LockSupport.parkNanos(1_000_000)
        }
        assertTrue("等待考试状态超时：${if (::vm.isInitialized) vm.examOperationState else "启动"}", condition())
    }

    private fun model() {
        vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        await { vm.ready && vm.questions.size == 3 && vm.todaySummary != null && !vm.quickNotes.state.loading }
        observerJobs = vm.viewModelScope.coroutineContext[Job]!!.children.toList()
    }

    private fun start(answers: Boolean = true) {
        vm.startExam(vm.questions.filter { it.id != "c" }, 2, 30)
        vm.queue = listOf("a", "b")
        if (answers) { vm.answerExam("a", setOf("A")); vm.answerExam("b", setOf("B")) }
        vm.saveExam()
    }

    private fun settle() = await { !vm.busy }
    private fun failed() = vm.examOperationState as ExamOperationState.Failed
    private fun attempts() = runBlocking { vm.repo.dao.allAttempts() }
    private fun progress() = runBlocking { vm.repo.dao.allProgress() }
    private fun failSecondInsert() = vm.db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_exam_insert BEFORE INSERT ON attempts
        WHEN NEW.questionId='b' BEGIN SELECT RAISE(ABORT, 'injected'); END""")

    @Test
    fun manualSubmitIsSingleOperationAndKeepsLegacyFieldsAndPaper() {
        start()
        val queue = vm.queue
        val answers = vm.examAnswers
        val deadline = vm.examDeadline
        repeat(10) { vm.finishExam(); vm.abandonExam() }
        settle()
        assertEquals(ExamResult(2, 2, 0, 1, 50), vm.examSummary)
        assertEquals(ExamOperationState.Submitted(vm.examSummary!!), vm.examOperationState)
        assertTrue(vm.examFinished)
        assertEquals("答对 1 / 2，得分 50", vm.examResult)
        assertEquals(queue, vm.queue)
        assertEquals(answers, vm.examAnswers)
        assertEquals(deadline, vm.examDeadline)
        assertEquals(2, attempts().size)
        assertFalse(prefs.contains("exam"))
        vm.finishExam(); vm.retryExamOperation(); vm.answerExam("a", setOf("B")); vm.saveExam()
        assertEquals(2, attempts().size)
        assertEquals(answers, vm.examAnswers)
        assertFalse(prefs.contains("exam"))
    }

    @Test
    fun timeoutWithAllUnansweredScoresZeroWithoutLearningRows() {
        start(false)
        vm.examDeadline = System.currentTimeMillis() - 1
        vm.answerExam("a", setOf("A")) // Existing timeout entry must use the same finish command.
        settle()
        assertEquals(ExamResult(2, 0, 2, 0, 0), vm.examSummary)
        assertTrue(attempts().isEmpty())
        assertTrue(progress().isEmpty())
    }

    @Test
    fun abandonDoesNotWriteLearningAndLateCallbacksCannotRestoreSession() {
        start()
        val beforeAttempts = attempts()
        val beforeProgress = progress()
        vm.abandonExam(); vm.finishExam()
        settle()
        assertEquals(ExamOperationState.Abandoned, vm.examOperationState)
        assertFalse(vm.examFinished)
        assertNull(vm.examSummary)
        assertEquals("", vm.examResult)
        assertTrue(vm.queue.isEmpty())
        assertTrue(vm.examAnswers.isEmpty())
        assertEquals(0L, vm.examDeadline)
        assertEquals("顺序练习", vm.mode)
        vm.answerExam("a", setOf("A")); vm.saveExam(); vm.finishExam(); vm.retryExamOperation()
        assertEquals(beforeAttempts, attempts())
        assertEquals(beforeProgress, progress())
        assertFalse(prefs.contains("exam"))
        clearModel(); model()
        assertTrue(vm.queue.isEmpty())
        assertFalse(vm.examState.active())
    }

    @Test
    fun cancelConfirmationLeavesOriginalExamRecoverableAndDoesNotCreateLearning() {
        start()
        val queue = vm.queue
        val answers = vm.examAnswers
        val deadline = vm.examDeadline
        // Cancel confirmation means no abandonExam call.
        clearModel(); model()
        assertEquals(queue, vm.queue)
        assertEquals(answers, vm.examAnswers)
        assertEquals(deadline, vm.examDeadline)
        assertTrue(attempts().isEmpty())
        assertTrue(progress().isEmpty())
    }

    @Test
    fun missingQuestionFailureCanBeAbandonedAndIsNotRestored() {
        start()
        vm.queue = listOf("a", "missing")
        vm.finishExam(); settle()
        assertEquals(ExamFailureStage.VALIDATION, failed().stage)
        assertFalse(failed().recordsCommitted)
        assertTrue(attempts().isEmpty())
        vm.abandonExam(); settle()
        assertEquals(ExamOperationState.Abandoned, vm.examOperationState)
        clearModel(); model()
        assertFalse(vm.examState.active())
        assertTrue(vm.queue.isEmpty())
    }

    @Test
    fun lostGradingEligibilityFailsWholePaperAndAllowsAbandon() {
        start()
        val original = runBlocking { vm.repo.dao.question("b")!! }
        val invalid = JSONObject(original.json).put("reviewRequired", true).toString()
        vm.db.openHelper.writableDatabase.execSQL("UPDATE questions SET json=? WHERE id='b'", arrayOf(invalid))
        vm.finishExam(); settle()
        assertEquals(ExamFailureStage.VALIDATION, failed().stage)
        assertTrue(attempts().isEmpty())
        assertTrue(progress().isEmpty())
        vm.abandonExam(); settle()
        assertEquals(ExamOperationState.Abandoned, vm.examOperationState)
        clearModel(); model()
        assertFalse(vm.examState.active())
    }

    @Test
    fun rollbackCanBeRetriedWithFrozenInputsInsteadOfLaterPageOrAnswers() {
        start()
        failSecondInsert()
        vm.finishExam(); settle()
        assertEquals(ExamFailureStage.RECORDS, failed().stage)
        assertTrue(attempts().isEmpty())
        assertTrue(progress().isEmpty())
        vm.answerExam("a", setOf("B"))
        assertEquals(setOf("A"), vm.examAnswers["a"])
        vm.queue = listOf("c")
        vm.index = 7
        vm.examAnswers = mapOf("c" to setOf("A")) // Legacy writable state cannot alter frozen submit input.
        vm.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_exam_insert")
        repeat(4) { vm.finishExam() }
        assertTrue(attempts().isEmpty())
        vm.retryExamOperation(); settle()
        assertEquals(ExamResult(2, 2, 0, 1, 50), vm.examSummary)
        assertEquals(setOf("a", "b"), attempts().map { it.questionId }.toSet())
    }

    @Test
    fun rollbackFailurePreservesOldRecordsAndCanBeAbandoned() {
        runBlocking { vm.repo.record(vm.repo.question("a")!!, "B", setOf("B"), "旧练习") }
        val oldAttempts = attempts()
        val oldProgress = progress()
        start(); failSecondInsert()
        vm.finishExam(); settle()
        assertEquals(ExamFailureStage.RECORDS, failed().stage)
        assertEquals(oldAttempts, attempts())
        assertEquals(oldProgress, progress())
        vm.abandonExam(); settle()
        assertEquals(ExamOperationState.Abandoned, vm.examOperationState)
        assertEquals(oldAttempts, attempts())
        assertEquals(oldProgress, progress())
    }

    @Test
    fun committedClearFailureDoesNotAutoRetryOrUnlockWhenPrefsKeyAlreadyGone() {
        start()
        val faulty = DeleteThenFailPreferences(prefs)
        vm.examState.sessionPreferences = faulty
        vm.finishExam(); settle()
        val state = failed()
        assertEquals(ExamFailureStage.SESSION_CLEAR, state.stage)
        assertTrue(state.recordsCommitted)
        assertFalse(vm.examFinished)
        assertEquals(state.result, vm.examSummary)
        assertEquals("", vm.examResult)
        assertFalse(prefs.contains("exam"))
        val paper = vm.queue
        val answers = vm.examAnswers
        vm.notice = "unchanged"
        repeat(10) { vm.finishExam() }
        assertEquals("unchanged", vm.notice)
        assertEquals(1, faulty.clearAttempts)
        assertEquals(2, attempts().size)
        vm.abandonExam()
        assertEquals(state, vm.examOperationState)
        vm.start(vm.questions, "顺序练习")
        vm.startExam(vm.questions, 3, 45)
        vm.answerExam("a", setOf("B")); vm.saveExam()
        assertEquals(paper, vm.queue)
        assertEquals(answers, vm.examAnswers)
        assertEquals("模拟考试", vm.mode)
        assertFalse(prefs.contains("exam"))
        vm.retryExamOperation(); settle()
        assertTrue(vm.examFinished)
        assertEquals(2, faulty.clearAttempts)
        assertEquals(2, attempts().size)
        vm.start(vm.questions, "顺序练习")
        assertEquals("顺序练习", vm.mode)
        assertEquals(ExamOperationState.Idle, vm.examOperationState)
        assertNull(vm.examSummary)
    }

    @Test
    fun abandonClearFailureOnlyAllowsExplicitCleanupRetryAndLocksWithoutPrefsKey() {
        start()
        val paper = vm.queue
        val answers = vm.examAnswers
        val deadline = vm.examDeadline
        val faulty = DeleteThenFailPreferences(prefs)
        vm.examState.sessionPreferences = faulty
        vm.abandonExam(); settle()
        assertEquals(ExamOperation.ABANDON, failed().operation)
        assertFalse(failed().recordsCommitted)
        assertFalse(prefs.contains("exam"))
        repeat(10) { vm.finishExam(); vm.abandonExam() }
        vm.start(vm.questions, "顺序练习"); vm.startExam(vm.questions, 3, 30)
        vm.answerExam("a", emptySet()); vm.saveExam()
        assertEquals(1, faulty.clearAttempts)
        assertEquals(paper, vm.queue)
        assertEquals(answers, vm.examAnswers)
        assertEquals(deadline, vm.examDeadline)
        assertTrue(attempts().isEmpty())
        vm.retryExamOperation(); settle()
        assertEquals(ExamOperationState.Abandoned, vm.examOperationState)
        assertEquals(2, faulty.clearAttempts)
        vm.saveExam(); vm.answerExam("a", setOf("A"))
        assertFalse(prefs.contains("exam"))
        vm.startExam(vm.questions, 3, 30)
        assertEquals(3, vm.queue.size)
        assertEquals(ExamOperationState.Idle, vm.examOperationState)
    }

    @Test
    fun endingRejectsEditsAndCompetingCommandsBeforeCriticalSection() {
        start()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.examState.hooks.beforeCritical = { entered.complete(Unit); release.await() }
        vm.finishExam()
        await { entered.isCompleted }
        assertTrue(vm.busy)
        assertEquals(ExamOperationState.Submitting, vm.examOperationState)
        val paper = vm.queue
        val answers = vm.examAnswers
        val saved = prefs.getString("exam", null)
        vm.answerExam("a", emptySet()); vm.saveExam()
        vm.start(vm.questions, "顺序练习"); vm.startExam(vm.questions, 3, 30)
        vm.abandonExam(); vm.retryExamOperation(); vm.finishExam()
        assertEquals(paper, vm.queue)
        assertEquals(answers, vm.examAnswers)
        assertEquals(saved, prefs.getString("exam", null))
        release.complete(Unit); settle()
        assertEquals(2, attempts().size)
    }

    @Test
    fun cancellationBeforeCriticalSectionReleasesBusyAndOperationOwnership() {
        start()
        val entered = CompletableDeferred<Unit>()
        vm.examState.hooks.beforeCritical = { entered.complete(Unit); awaitCancellation() }
        vm.finishExam()
        await { entered.isCompleted }
        clearModel() // Cancels ViewModel job and closes the DB before any learning write.
        settle()
        assertEquals(ExamOperationState.Idle, vm.examOperationState)
        assertNull(vm.examSummary)
        assertTrue(prefs.contains("exam"))
        model()
        assertTrue(vm.examState.active())
        assertTrue(attempts().isEmpty())
    }

    @Test
    fun cancellationInsideTransactionDeterminesOutcomeAndDoesNotStrandBusy() {
        start()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.examState.hooks.afterRecord = { count -> if (count == 1) { entered.complete(Unit); release.await() } }
        vm.finishExam()
        await { entered.isCompleted }
        vm.viewModelScope.coroutineContext.cancelChildren()
        assertTrue(vm.busy)
        release.complete(Unit); settle()
        assertTrue(vm.examFinished)
        assertEquals(2, attempts().size)
        assertFalse(prefs.contains("exam"))
    }

    @Test
    fun lifecycleCancellationAndClosedDatabaseBeforeTransactionReportActualFailure() {
        start()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.examState.hooks.beforeTransaction = { entered.complete(Unit); release.await() }
        vm.finishExam()
        await { entered.isCompleted }
        clearModel() // Critical section entered, but db is closed before the transaction is started.
        release.complete(Unit); settle()
        assertEquals(ExamFailureStage.RECORDS, failed().stage)
        assertFalse(failed().recordsCommitted)
        assertFalse(vm.examFinished)
        assertTrue(prefs.contains("exam"))
        model()
        assertTrue(attempts().isEmpty())
        assertTrue(vm.examState.active())
    }

    @Test
    fun lifecycleCloseAfterCommitStillReportsKnownCommittedOutcome() {
        start()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.examState.hooks.afterCommit = { entered.complete(Unit); release.await() }
        vm.finishExam()
        await { entered.isCompleted }
        assertEquals(ExamResult(2, 2, 0, 1, 50), vm.examSummary)
        assertEquals(2, attempts().size)
        clearModel()
        release.complete(Unit); settle()
        assertTrue(vm.examFinished)
        assertEquals(ExamOperationState.Submitted(vm.examSummary!!), vm.examOperationState)
        assertFalse(prefs.contains("exam"))
        model()
        assertFalse(vm.examState.active())
        assertEquals(2, attempts().size)
    }

    @Test
    fun cancellingExplicitRetryRestoresOriginalFailedStateAndKnownResult() {
        start()
        vm.examState.sessionPreferences = DeleteThenFailPreferences(prefs)
        vm.finishExam(); settle()
        val originalFailure = failed()
        val entered = CompletableDeferred<Unit>()
        vm.examState.hooks.beforeCritical = { entered.complete(Unit); awaitCancellation() }
        vm.retryExamOperation()
        await { entered.isCompleted }
        clearModel(); settle()
        assertEquals(originalFailure, vm.examOperationState)
        assertEquals(originalFailure.result, vm.examSummary)
        assertTrue(vm.examState.active())
        assertFalse(vm.examFinished)
        val reopened = QuizDatabase.open(app)
        try { assertEquals(2, runBlocking { reopened.dao().allAttempts().size }) }
        finally { reopened.close() }
    }

    @Test
    fun selectedThenClearedIsUnansweredAndCallerCannotMutateStoredAnswer() {
        start(false)
        val selection = mutableSetOf("A")
        vm.answerExam("a", selection)
        selection.clear()
        assertEquals(setOf("A"), vm.examAnswers["a"])
        vm.answerExam("b", setOf("A"))
        vm.answerExam("b", emptySet())
        vm.finishExam(); settle()
        assertEquals(ExamResult(2, 1, 1, 1, 50), vm.examSummary)
        assertEquals("a", attempts().single().questionId)
        assertNull(runBlocking { vm.repo.dao.getProgress("b") })
    }

    @Test
    fun validationFailureCanRetryOriginalPaperAfterMissingQuestionIsRestored() {
        start()
        vm.queue = listOf("a", "missing")
        vm.finishExam(); settle()
        assertEquals(ExamFailureStage.VALIDATION, failed().stage)
        runBlocking {
            val replacement = JSONObject(vm.repo.dao.question("a")!!.json).put("id", "missing")
            vm.repo.importBank(JSONObject().put("schemaVersion", 1)
                .put("questions", org.json.JSONArray().put(replacement)).toString())
        }
        vm.queue = listOf("c")
        vm.examAnswers = mapOf("c" to setOf("A"))
        vm.retryExamOperation(); settle()
        assertEquals(ExamResult(2, 1, 1, 1, 50), vm.examSummary)
        assertEquals("a", attempts().single().questionId)
    }

    @Test
    fun repeatedExplicitCleanupFailuresKeepCommittedResultAndNeverRegrade() {
        start()
        val faulty = DeleteThenFailPreferences(prefs).apply { failures = 2 }
        vm.examState.sessionPreferences = faulty
        vm.finishExam(); settle()
        val result = vm.examSummary
        vm.retryExamOperation(); settle()
        assertEquals(ExamFailureStage.SESSION_CLEAR, failed().stage)
        assertTrue(failed().recordsCommitted)
        assertEquals(result, vm.examSummary)
        assertEquals(2, attempts().size)
        assertEquals(2, faulty.clearAttempts)
        vm.retryExamOperation(); settle()
        assertEquals(ExamOperationState.Submitted(result!!), vm.examOperationState)
        assertEquals(2, attempts().size)
        assertEquals(3, faulty.clearAttempts)
    }

    @Test
    fun unknownRetryOrFinishWithoutAnExamDoesNotCreateAnOperation() {
        repeat(3) { vm.retryExamOperation(); vm.finishExam(); vm.abandonExam() }
        assertFalse(vm.busy)
        assertEquals(ExamOperationState.Idle, vm.examOperationState)
        assertNull(vm.examSummary)
        assertTrue(attempts().isEmpty())
        assertFalse(prefs.contains("exam"))
    }
}

/** Models commit=false after the removal has already changed SharedPreferences memory. */
private class DeleteThenFailPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
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
                    if (failures > 0) { failures--; return false }
                }
                return committed
            }
        }
    }
}
