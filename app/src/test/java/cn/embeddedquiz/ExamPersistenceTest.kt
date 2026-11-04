package cn.embeddedquiz

import android.app.Application
import android.content.SharedPreferences
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Every restart below reopens a file DB and constructs an independent file preferences cache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExamPersistenceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val name = "t2-${System.nanoTime().toString(36)}.db"
    private lateinit var db: QuizDatabase
    private lateinit var repo: QuizRepository
    private lateinit var preferences: ExamFilePreferences
    private lateinit var holder: ExamStateHolder
    private val holders = mutableListOf<ExamStateHolder>()
    private val file get() = File(app.filesDir, "$name.exam")
    private val bank = """{"schemaVersion":1,"questions":[
        {"id":"a","stem":"one","source":"T2","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]},
        {"id":"b","stem":"two","source":"T2","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]}]}"""
    private val legacy = """{"ids":["b","a"],"index":1,"deadline":9999999999999,"answers":{"a":["A"]}}"""

    @Before fun setup() = runBlocking {
        db = QuizDatabase.open(app, name); repo = QuizRepository(db); repo.importBank(bank)
        reopenHolder()
    }
    private fun reopenHolder() {
        preferences = ExamFilePreferences(app.getSharedPreferences(name, 0), file)
        holder = ExamStateHolder(preferences, repo); holders += holder
    }
    private fun reopen(failSave: Boolean = false) = runBlocking {
        holder.onOwnerCleared().join(); db.close()
        db = QuizDatabase.open(app, name); repo = QuizRepository(db); reopenHolder(); preferences.failSave = failSave
        holder.restore()
    }
    @After fun cleanup() = runBlocking {
        holders.forEach { it.onOwnerCleared().join() }
        db.close(); app.deleteDatabase(name); file.delete(); Unit
    }
    private fun start() {
        assertTrue(holder.startSession(true)); holder.deadline = 9999999999999
        holder.answers = mapOf("a" to setOf("A"))
        holder.save(listOf("b", "a"), 1, "模拟考试")
    }
    private fun stored() = DurableExamSnapshot.decode(file.readText())
    private fun execute(submit: Boolean) = runBlocking {
        assertTrue(holder.accept(if (submit) ExamOperation.SUBMIT else ExamOperation.ABANDON, listOf("b", "a"), 1))
        holder.executeOperation()
    }
    private fun failure() = holder.operationState as ExamOperationState.Failed
    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle(); LockSupport.parkNanos(1_000_000)
        }
        assertTrue("timeout: ${holder.saveState} / ${holder.operationState}", condition())
    }

    @Test fun blocked500msDiskStillAllowsMainEventsAndEditsThenConflatesLatest() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var writes = 0
        holder.persistence.beforeWrite = { if (++writes == 1) { entered.complete(Unit); release.await() } }
        start(); entered.await()
        val initial = storedOrNull()
        var events = 0
        android.os.Handler(Looper.getMainLooper()).post { events++ }
        val end = System.nanoTime() + 500_000_000L
        var edits = 0
        while (System.nanoTime() < end) {
            holder.answers = mapOf("a" to setOf(if (edits % 2 == 0) "A" else "B"))
            holder.save(listOf("b", "a"), edits % 2, "模拟考试")
            edits++
            shadowOf(Looper.getMainLooper()).idle()
            LockSupport.parkNanos(2_000_000)
        }
        assertTrue(edits > 20); assertEquals(1, events); assertEquals(1, writes)
        assertEquals(initial, storedOrNull()); assertTrue(holder.saveState is ExamSaveState.Saving)
        holder.answers = mapOf("a" to setOf("B")); holder.save(listOf("b", "a"), 1, "模拟考试")
        release.complete(Unit); assertTrue(holder.flushSave())
        assertTrue("only one pending snapshot", writes <= 2)
        val last = stored(); assertEquals(mapOf("a" to setOf("B")), last.answers)
        assertEquals(1, last.index); assertEquals(listOf("b", "a"), last.ids)
        assertEquals(9999999999999, last.deadline)
        reopen(); assertTrue(holder.flushSave()); assertEquals(last, stored())
        assertEquals(last.answers, holder.answers)
    }
    private fun storedOrNull() = if (file.exists()) file.readText() else null

    @Test fun falseCommitChangesCacheButNeverReportsSavedAndNewEditCanRecover() = runBlocking {
        preferences.failSave = true; start()
        assertFalse(holder.flushSave()); assertTrue(holder.saveState is ExamSaveState.Error)
        assertTrue(preferences.contains("exam")); assertFalse(file.exists())
        assertNull(ExamFilePreferences(app.getSharedPreferences(name, 0), file).getString("exam", null))
        holder.answers = mapOf("a" to setOf("B")); holder.save(listOf("b", "a"), 0, "模拟考试")
        assertFalse(holder.flushSave())
        preferences.failSave = false; holder.retrySave(); assertTrue(holder.flushSave())
        reopen(); assertTrue(holder.flushSave()); assertEquals(setOf("B"), holder.answers["a"])
    }

    @Test fun legacyIdentityMustReachDiskBeforeAccountingAndAbandonSurvivesFailedIdentityAndClear() = runBlocking {
        file.writeText(legacy); reopen(failSave = true)
        // Freeze/save uses a new attempt, which is also made to fail.
        execute(true)
        assertEquals("考试保存失败，未提交成绩", failure().message)
        assertFalse(failure().recordsCommitted); assertTrue(repo.dao.allAttempts().isEmpty())
        preferences.failClear = true; execute(false)
        assertEquals(ExamFailureStage.SESSION_CLEAR, failure().stage)
        assertFalse(holder.acceptsAnswers); assertTrue(repo.dao.allProgress().isEmpty())
        // Simulate the original anonymous disk image surviving both failed commits.
        file.writeText(legacy)
        reopen()
        assertEquals(ExamOperation.ABANDON, failure().operation)
        assertFalse(holder.acceptsAnswers)
        assertTrue(holder.acceptRetry()); holder.executeOperation()
        assertEquals(ExamOperationState.Abandoned, holder.operationState)
        assertFalse(file.exists()); assertTrue(repo.dao.allAttempts().isEmpty())
    }

    @Test fun legacyIdentityIsStableOnReopenAndSamePaperNewExamsHaveDifferentIds() = runBlocking {
        file.writeText(legacy); reopen(); assertTrue(holder.flushSave())
        val first = stored().sessionId; UUID.fromString(first)
        reopen(); assertTrue(holder.flushSave()); assertEquals(first, stored().sessionId)
        execute(true); assertEquals(1, repo.dao.allAttempts().size)
        start(); assertTrue(holder.flushSave()); val second = stored().sessionId
        assertNotEquals(first, second); execute(true)
        assertEquals(2, repo.dao.allAttempts().size); assertEquals(2, repo.dao.getProgress("a")!!.seen)
        assertNull(repo.dao.getProgress("b"))
    }

    @Test fun faultsBeforeAndInsideTransactionRollbackThenReopenedHolderSubmitsOnce() = runBlocking {
        for (point in listOf("before", "inside")) {
            start(); assertTrue(holder.flushSave())
            if (point == "before") holder.hooks.beforeTransaction = { error("before txn") }
            else holder.hooks.afterRecord = { error("inside txn") }
            val before = repo.dao.allAttempts(); val progress = repo.dao.allProgress()
            execute(true)
            assertEquals(ExamFailureStage.RECORDS, failure().stage)
            assertEquals(before, repo.dao.allAttempts()); assertEquals(progress, repo.dao.allProgress())
            reopen(); assertTrue(holder.flushSave()); execute(true)
            assertEquals(before.size + 1, repo.dao.allAttempts().size)
            assertNull(repo.dao.getProgress("b"))
        }
    }

    @Test fun afterCommitAndClearFaultsRestoreLedgerResultWithoutRegradingOrDoubleAccounting() = runBlocking {
        for (point in listOf("after", "clear")) {
            start(); assertTrue(holder.flushSave())
            if (point == "after") holder.hooks.afterCommit = { error("after commit") }
            else preferences.failClear = true
            execute(true)
            assertTrue(failure().recordsCommitted)
            val result = holder.summary; val attempts = repo.dao.allAttempts(); val progress = repo.dao.allProgress()
            val daily = repo.dao.dailyCounts(LocalDate.now().toEpochDay()).first()
            val old = stored()
            reopen()
            assertEquals(result, holder.summary); assertEquals(ExamFailureStage.SESSION_CLEAR, failure().stage)
            assertFalse(holder.acceptsAnswers); assertTrue(holder.saveState is ExamSaveState.Inactive)
            // A changed/missing question cannot affect a terminal replay.
            assertEquals(result, repo.endExam(old.copy(ids = listOf("missing")), true).result())
            repeat(3) {
                if (holder.operationState is ExamOperationState.Failed) {
                    assertTrue(holder.acceptRetry()); holder.executeOperation()
                }
            }
            assertEquals(ExamOperationState.Submitted(result!!), holder.operationState)
            assertEquals(attempts, repo.dao.allAttempts()); assertEquals(progress, repo.dao.allProgress())
            assertEquals(daily, repo.dao.dailyCounts(LocalDate.now().toEpochDay()).first())
            // A stale snapshot after successful clear is still terminal on a fresh cache/DB.
            file.writeText(old.encode()); reopen()
            assertFalse(holder.acceptsAnswers); assertEquals(result, holder.summary)
            assertTrue(holder.acceptRetry()); holder.executeOperation()
        }
    }

    @Test fun abandonDrainsInFlightSaveAndLateCallbacksCannotResurrectNewSession() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        holder.persistence.beforeWrite = { entered.complete(Unit); release.await() }
        start(); entered.await()
        assertTrue(holder.accept(ExamOperation.ABANDON, listOf("b", "a")))
        val ending = async { holder.executeOperation() }
        yield(); assertFalse(ending.isCompleted)
        release.complete(Unit); ending.await()
        assertFalse(file.exists()); holder.save(listOf("a"), 0, "模拟考试"); holder.retrySave()
        assertFalse(file.exists()); assertTrue(repo.dao.allAttempts().isEmpty())
        holder.persistence.beforeWrite = {}; start(); assertTrue(holder.flushSave())
        assertEquals(ExamSaveState.Saved(stored().revision), holder.saveState)
        execute(false); assertFalse(file.exists())
    }

    @Test fun flushCapturedOldRevisionWaitsForNewerAttemptAfterOldWriteFails() = runBlocking {
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>(); val releaseSecond = CompletableDeferred<Unit>()
        var writes = 0
        holder.persistence.beforeWrite = {
            if (++writes == 1) { preferences.failSave = true; first.complete(Unit); releaseFirst.await() }
            else { preferences.failSave = false; second.complete(Unit); releaseSecond.await() }
        }
        start(); first.await()
        val waiting = async { holder.flushSave() }; yield()
        holder.answers = mapOf("a" to setOf("B")); holder.save(listOf("b", "a"), 0, "模拟考试")
        releaseFirst.complete(Unit); second.await(); yield()
        assertFalse(waiting.isCompleted); assertTrue(holder.saveState is ExamSaveState.Saving)
        releaseSecond.complete(Unit); assertTrue(waiting.await())
        assertEquals(setOf("B"), stored().answers["a"])
    }

    @Test fun terminalInsertFailureRollsBackLearningAndRetryIsIdempotent() = runBlocking {
        start(); assertTrue(holder.flushSave()); val frozen = stored()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_marker BEFORE INSERT ON exam_terminations BEGIN SELECT RAISE(ABORT, 'marker fault'); END")
        execute(true)
        assertEquals(ExamFailureStage.RECORDS, failure().stage)
        assertTrue(repo.dao.allAttempts().isEmpty()); assertTrue(repo.dao.allProgress().isEmpty())
        assertNull(repo.dao.examTermination(frozen.sessionId))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_marker")
        assertTrue(holder.acceptRetry()); holder.executeOperation()
        val before = repo.dao.allAttempts(); val progress = repo.dao.allProgress()
        repeat(3) {
            assertEquals(holder.summary, repo.endExam(frozen.copy(ids = emptyList()), true).result())
        }
        assertEquals(before, repo.dao.allAttempts()); assertEquals(progress, repo.dao.allProgress())
        assertEquals("SUBMITTED", repo.endExam(frozen, false).status)
    }
    @Test fun cancellingFlushWaiterDoesNotCancelAcceptedWrite() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        holder.persistence.beforeWrite = { entered.complete(Unit); release.await() }
        start(); entered.await()
        val waiter = launch { holder.flushSave() }; yield(); waiter.cancelAndJoin()
        release.complete(Unit); assertTrue(holder.flushSave()); assertTrue(file.exists())
    }

    @Test fun backupRemainsV3GuardsRestoreAndResetAndKeepsTerminalLedger() = runBlocking {
        val backup = repo.backup(); start(); assertTrue(holder.flushSave()); val snap = stored()
        assertEquals(3, JSONObject(backup).getInt("backupVersion"))
        for (action in listOf<suspend () -> Unit>({ repo.reset() }, { repo.restore(backup) })) {
            try { action(); fail("active exam must reject replacement") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("请先结束")) }
        }
        preferences.failClear = true; execute(false)
        try { repo.reset(); fail("cleanup pending must reject") } catch (_: IllegalStateException) {}
        preferences.failClear = false; assertTrue(holder.acceptRetry()); holder.executeOperation()
        val terminal = repo.dao.examTermination(snap.sessionId)
        repo.reset(); repo.restore(backup)
        assertEquals(terminal, repo.dao.examTermination(snap.sessionId))
        val exported = repo.backup()
        assertFalse(exported.contains("exam_terminations")); assertFalse(exported.contains(snap.sessionId))
        assertEquals("ABANDONED", repo.endExam(snap, true).status)
        assertTrue(repo.dao.allAttempts().isEmpty())
    }

    private fun withVm(block: (QuizViewModel, (Boolean) -> QuizViewModel) -> Unit) {
        app.deleteDatabase("embeddedquiz.db")
        val settings = app.getSharedPreferences("settings", 0)
        settings.edit().clear().putInt("bankRevision", 4)
            .putString("categoryMapHash", CategoryMap.fingerprint).commit()
        runBlocking {
            val seed = QuizDatabase.open(app); QuizRepository(seed).importBank(bank); seed.close()
        }
        val vmFile = File(app.filesDir, "$name.vm-exam")
        val store = ViewModelStore()
        var current: QuizViewModel? = null
        fun create(notify: Boolean): QuizViewModel {
            val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
            current = vm
            vm.examState.sessionPreferences = ExamFilePreferences(settings, vmFile)
            if (notify) vm.requestNotificationReview()
            waitFor { vm.ready && vm.questions.size == 2 && !vm.quickNotes.state.loading }
            return vm
        }
        fun close() {
            current?.let { vm ->
                val jobs = vm.viewModelScope.coroutineContext[Job]!!.children.toList()
                jobs.forEach { it.cancel() }; waitFor { jobs.all { it.isCompleted } }
                store.clear(); waitFor { vm.examResourcesClosed?.isCompleted == true }
            }
        }
        try {
            val first = create(false)
            block(first) { notify -> close(); create(notify) }
        } finally { close(); app.deleteDatabase("embeddedquiz.db"); vmFile.delete(); settings.edit().clear().commit() }
    }

    @Test fun viewModelAnswerEventsContinueDuring500msWriteAndBusyStaysFalse() = withVm { vm, _ ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        vm.examState.persistence.beforeWrite = { entered.complete(Unit); release.await() }
        try {
            vm.startExam(vm.questions, 2, 30); waitFor { entered.isCompleted }
            var events = 0
            val end = System.nanoTime() + 500_000_000L
            while (System.nanoTime() < end) {
                android.os.Handler(Looper.getMainLooper()).post { events++ }
                vm.answerExam("a", setOf(if (events % 2 == 0) "A" else "B"))
                vm.index = events % 2; vm.saveExam()
                assertFalse(vm.busy); assertEquals(ExamOperationState.Idle, vm.examOperationState)
                shadowOf(Looper.getMainLooper()).idle(); LockSupport.parkNanos(2_000_000)
            }
            assertTrue(events > 20); assertTrue(vm.examSaveState is ExamSaveState.Saving)
        } finally { release.complete(Unit) }
        val flush = vm.viewModelScope.async { vm.flushExamSave() }
        waitFor { flush.isCompleted }; assertTrue(runBlocking { flush.await() })
        assertTrue(vm.examSaveState is ExamSaveState.Saved)
    }

    @Test fun newViewModelAndIndependentPrefsRecoverCommittedResultBeforeApplyingNotification() = withVm { first, restart ->
        var vm = first
        for (fault in listOf("afterCommit", "clear")) {
            vm.startExam(vm.questions, 2, 30); vm.answerExam("a", setOf("A"))
            if (fault == "afterCommit") vm.examState.hooks.afterCommit = { error("crash window") }
            else (vm.examState.sessionPreferences as ExamFilePreferences).failClear = true
            vm.finishExam(); waitFor { !vm.busy }
            val failed = vm.examOperationState as ExamOperationState.Failed
            assertTrue(failed.recordsCommitted)
            val before = runBlocking { Triple(vm.repo.dao.allAttempts(), vm.repo.dao.allProgress(), vm.repo.dao.dailyCounts(0).first()) }
            vm = restart(true)
            assertEquals(Page.REVIEW, vm.page)
            assertEquals(InitializationOutcome.Ready(true), runBlocking { vm.awaitInitialization() })
            assertEquals(failed.result, vm.examSummary)
            assertEquals(ExamFailureStage.SESSION_CLEAR, (vm.examOperationState as ExamOperationState.Failed).stage)
            vm.answerExam("b", setOf("A")); vm.finishExam(); vm.abandonExam(); vm.retryExamSave()
            assertFalse(vm.examState.acceptsAnswers)
            vm.retryExamOperation(); waitFor { !vm.busy }
            assertEquals(ExamOperationState.Submitted(failed.result!!), vm.examOperationState)
            val after = runBlocking { Triple(vm.repo.dao.allAttempts(), vm.repo.dao.allProgress(), vm.repo.dao.dailyCounts(0).first()) }
            assertEquals(before, after); assertNull(runBlocking { vm.repo.dao.getProgress("b") })
        }
    }
    @Test fun ownerClearWaitsForTransactionThenReopensWithoutDuplicate() {
        // Use the actual ViewModel facade and its onCleared resource coordination.
        runBlocking { holder.onOwnerCleared().join(); db.close() }
        app.deleteDatabase("embeddedquiz.db")
        val settings = app.getSharedPreferences("settings", 0); settings.edit().clear().commit()
        runBlocking {
            val seed = QuizDatabase.open(app)
            QuizRepository(seed).importBank(bank); seed.close()
        }
        settings.edit().putInt("bankRevision", 4).putString("categoryMapHash", CategoryMap.fingerprint).commit()
        val store = ViewModelStore()
        var vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            waitFor { vm.ready && vm.questions.size == 2 && !vm.quickNotes.state.loading }
            vm.startExam(vm.questions, 2, 30); vm.answerExam("a", setOf("A"))
            vm.examState.hooks.afterRecord = { entered.complete(Unit); release.await() }
            vm.finishExam(); waitFor { entered.isCompleted }
            val observers = vm.viewModelScope.coroutineContext[Job]!!.children.toList()
            // Keep the owned exam job until its transaction completes; drain unrelated observers.
            observers.filter { it != vm.examOperationJob }.forEach { it.cancel() }
            store.clear()
            assertTrue(vm.db.isOpen); release.complete(Unit)
            waitFor { !vm.busy }
            waitFor { vm.examResourcesClosed?.isCompleted == true }
            waitFor { observers.all { it.isCompleted } }
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
            waitFor { vm.ready && !vm.quickNotes.state.loading }
            assertFalse(vm.examState.active())
            runBlocking {
                assertEquals(1, vm.repo.dao.allAttempts().size)
                assertEquals(1, vm.repo.dao.getProgress("a")!!.seen)
                assertNull(vm.repo.dao.getProgress("b"))
            }
        } finally {
            release.complete(Unit)
            val observers = vm.viewModelScope.coroutineContext[Job]!!.children.toList()
            observers.forEach { it.cancel() }; waitFor { observers.all { it.isCompleted } }
            store.clear(); waitFor { vm.examResourcesClosed?.isCompleted != false }
            app.deleteDatabase("embeddedquiz.db"); settings.edit().clear().commit()
            db = QuizDatabase.open(app, name)
        }
    }
}

/** Deliberately models Android's cache changing before a false disk acknowledgement. */
internal class ExamFilePreferences(private val delegate: SharedPreferences, private val file: File) : SharedPreferences by delegate {
    private var cached: String? = if (file.exists()) file.readText() else null
    @Volatile var failSave = false
    @Volatile var failClear = false
    override fun getString(key: String?, defValue: String?): String? =
        if (key == "exam") synchronized(this) { cached ?: defValue } else delegate.getString(key, defValue)
    override fun contains(key: String?): Boolean = if (key == "exam") synchronized(this) { cached != null } else delegate.contains(key)
    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            private var changed = false
            private var value: String? = null
            override fun putString(key: String?, next: String?): SharedPreferences.Editor {
                if (key == "exam") { changed = true; value = next } else editor.putString(key, next)
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key == "exam") { changed = true; value = null } else editor.remove(key)
                return this
            }
            override fun commit(): Boolean = synchronized(this@ExamFilePreferences) {
                if (changed) {
                    cached = value
                    if (if (value == null) failClear else failSave) return@synchronized false
                    if (value == null) { if (file.exists()) check(file.delete()) }
                    else {
                        java.io.FileOutputStream(file).use { output ->
                            output.write(value!!.toByteArray()); output.fd.sync()
                        }
                    }
                }
                editor.commit()
            }
            override fun apply() { error("test requires durable acknowledgement") }
        }
    }
}