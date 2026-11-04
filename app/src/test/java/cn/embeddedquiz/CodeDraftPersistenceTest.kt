package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal class ManualDraftClock : CodeDraftClock {
    private val time = MutableStateFlow(0L)
    override fun now() = time.value
    override suspend fun until(deadline: Long) { time.first { it >= deadline } }
    fun at(value: Long) { check(value >= now()); time.value = value }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CodeDraftPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "t4-drafts-${System.nanoTime()}.db"
    private lateinit var db: QuizDatabase
    private lateinit var repo: QuizRepository
    private lateinit var drafts: CodeDraftStateHolder
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val clock = ManualDraftClock()
    private val bank = """{"schemaVersion":1,"questions":[{"id":"a","stem":"first","source":"T4","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]},{"id":"b","stem":"second","source":"T4","kind":"SHORT"}]}"""

    @Before fun setup() = runBlocking {
        db = QuizDatabase.open(context, name); repo = QuizRepository(db)
        repo.importBank(bank)
        repo.update("a") { it.copy(code = "base", notes = "base note", favorite = true, flagged = true) }
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE draft_audit(id TEXT NOT NULL, code TEXT NOT NULL, notes TEXT NOT NULL)")
        sql.execSQL("CREATE TRIGGER audit_draft_update AFTER UPDATE ON progress WHEN OLD.code IS NOT NEW.code OR OLD.notes IS NOT NEW.notes BEGIN INSERT INTO draft_audit VALUES(NEW.questionId,NEW.code,NEW.notes); END")
        sql.execSQL("CREATE TRIGGER audit_draft_insert AFTER INSERT ON progress BEGIN INSERT INTO draft_audit VALUES(NEW.questionId,NEW.code,NEW.notes); END")
        drafts = CodeDraftStateHolder(owner, repo)
        drafts.persistence.clock = clock
        drafts.seed("a", "base", "base note")
    }
    @After fun cleanup() {
        if (::drafts.isInitialized) runBlocking { drafts.shutdown().join() }
        owner.cancel()
        if (::db.isInitialized) db.close()
        context.deleteDatabase(name)
    }
    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < until) LockSupport.parkNanos(1_000_000)
        assertTrue("state=${drafts.state("a")}; pending=${drafts.writer.pending}", condition())
    }
    private fun audit(): List<String> = db.openHelper.readableDatabase.query("SELECT code FROM draft_audit ORDER BY rowid").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }
    private fun row(id: String = "a") = runBlocking { repo.dao.getProgress(id)!! }
    private fun reopen() {
        runBlocking { drafts.shutdown().join() }; db.close()
        db = QuizDatabase.open(context, name); repo = QuizRepository(db)
    }

    @Test fun hundredEditsCoalesceAndIdenticalTextDoesNotWriteAgain() {
        assertEquals(CodeDraftSaveState.Inactive, drafts.state("a"))
        assertTrue(runBlocking { drafts.flush() })
        assertTrue(audit().isEmpty())
        repeat(100) { i ->
            clock.at(i * 10L)
            drafts.update("a", "  int v = $i;\n\treturn v;  ", "笔记 $i\n")
        }
        clock.at(1290)
        waitFor { drafts.state("a") == CodeDraftSaveState.Saved(100) }
        assertTrue(runBlocking { drafts.flush() })
        val writes = audit().size
        assertTrue("actual committed draft transactions=$writes", writes in 1..2)
        repeat(100) { drafts.update("a", "  int v = 99;\n\treturn v;  ", "笔记 99\n") }
        clock.at(10000)
        assertTrue(runBlocking { drafts.flush() })
        assertEquals(CodeDraftSaveState.Saved(100), drafts.state("a"))
        assertEquals(writes, audit().size)
        reopen()
        assertEquals("  int v = 99;\n\treturn v;  ", row().code)
        assertEquals("笔记 99\n", row().notes)
        assertTrue(row().favorite); assertTrue(row().flagged)
        println("T4 actual committed writes for 100 edits/10ms: $writes; reopened bytes match")
    }

    @Test fun continuousTypingKeepsTheTwoSecondLimitAndFinalTrailingSave() {
        val times = ConcurrentLinkedQueue<Long>()
        drafts.persistence.afterWrite = { times.add(clock.now()) }
        repeat(60) { i ->
            clock.at(i * 100L)
            // At the cap the timer runs before this timestamp's next edit, by an explicit barrier.
            if (i > 0 && i % 20 == 0) waitFor { drafts.state("a") == CodeDraftSaveState.Saved(i.toLong()) }
            drafts.update("a", "v$i", "n$i")
        }
        clock.at(6000)
        waitFor { drafts.state("a") == CodeDraftSaveState.Saved(60) }
        assertEquals(listOf(2000L, 4000L, 6000L), times.toList())
        assertEquals(listOf("v19", "v39", "v59"), audit())
        assertEquals("v59", row().code)
        println("T4 virtual committed save times ms: ${times.toList()}; codes=${audit()}")
    }

    @Test fun slowIoHasOneWriterAndOneReplaceablePendingVersionPerQuestion() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val active = AtomicInteger(); val maximum = AtomicInteger()
        drafts.persistence.beforeWrite = { value ->
            val n = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, n) }
            if (value.code == "a0") { entered.complete(Unit); gate.await() }
        }
        drafts.persistence.afterWrite = { active.decrementAndGet() }
        drafts.update("a", "a0", "n0")
        val waiter = owner.async(Dispatchers.IO) { drafts.flush("a") }
        entered.await()
        try {
            repeat(1000) { drafts.update("a", "a${it + 1}", "n${it + 1}") }
            repeat(100) { drafts.update("b", "b$it", "bn$it") }
            assertEquals(2, drafts.persistence.pendingQuestions)
            assertEquals(1, active.get())
            assertTrue(audit().isEmpty())
        } finally { gate.complete(Unit) }
        assertTrue(waiter.await()); assertTrue(drafts.flush())
        assertEquals(1, maximum.get())
        assertEquals("a1000", row().code); assertEquals("b99", row("b").code)
        assertEquals(3, audit().size)
    }

    @Test fun failedOldVersionThenNewSuccessCannotBeReplayedByRetry() = runBlocking {
        var fail = true
        drafts.persistence.beforeWrite = { if (fail) error("injected") }
        drafts.update("a", "old failed", "n1")
        assertFalse(drafts.flush("a"))
        assertTrue(drafts.state("a") is CodeDraftSaveState.Error)
        drafts.update("a", "old failed", "n1")
        clock.at(10000)
        assertFalse(drafts.flush("a")); assertTrue(audit().isEmpty())
        fail = false
        drafts.update("a", "latest succeeds", "n2")
        assertTrue(drafts.flush())
        drafts.retry("a"); drafts.retry()
        assertTrue(drafts.flush())
        assertEquals(listOf("latest succeeds"), audit())
        assertEquals(CodeDraftSaveState.Saved(2), drafts.state("a"))
    }

    @Test fun retryAllOnlyRetriesTheLatestFailedQuestions() = runBlocking {
        var fail = true
        drafts.persistence.beforeWrite = { if (fail) error("injected") }
        drafts.update("a", "a1", "an"); drafts.update("b", "b1", "bn")
        assertFalse(drafts.flush())
        waitFor { drafts.state("a") is CodeDraftSaveState.Error && drafts.state("b") is CodeDraftSaveState.Error }
        fail = false
        drafts.retry("a"); assertTrue(drafts.flush("a"))
        assertTrue(drafts.state("b") is CodeDraftSaveState.Error)
        drafts.retry(); assertTrue(drafts.flush())
        assertEquals(setOf("a1", "b1"), audit().toSet())
        assertEquals(2, audit().size)
    }

    @Test fun cancellingFlushDoesNotCancelTheAcceptedWrite() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        drafts.persistence.beforeWrite = { entered.complete(Unit); gate.await() }
        drafts.update("a", "must survive", "notes")
        val waiting = owner.async(Dispatchers.IO) { drafts.flush() }
        entered.await(); waiting.cancelAndJoin()
        gate.complete(Unit)
        assertTrue(drafts.flush()); assertEquals("must survive", row().code)
    }

    @Test fun supersededGenerationDoesNotReportAnOldFlushAsSaved() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        drafts.persistence.beforeWrite = { entered.complete(Unit); gate.await() }
        val paused = object : CoroutineDispatcher() {
            val queue = ConcurrentLinkedQueue<Runnable>()
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
            fun drain() { while (true) (queue.poll() ?: break).run() }
        }
        val waiterScope = CoroutineScope(SupervisorJob() + paused)
        drafts.update("a", "old generation", "old")
        val waiting = waiterScope.async(start = CoroutineStart.UNDISPATCHED) { drafts.flush() }
        entered.await(); gate.complete(Unit)
        waitFor { drafts.state("a") is CodeDraftSaveState.Saved }
        drafts.exclusive { drafts.advanceGeneration(retainText = false) }
        paused.drain()
        assertFalse(waiting.await())
        assertEquals(CodeDraftSaveState.Inactive, drafts.state("a"))
        drafts.persistence.beforeWrite = {}
        drafts.update("a", "new generation", "new"); assertTrue(drafts.flush())
        drafts.retry(); clock.at(10000)
        assertEquals("new generation", row().code)
        waiterScope.cancel()
    }

    @Test fun draftTransactionPreservesConcurrentLearningAndPersonalFields() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        drafts.persistence.beforeWrite = { entered.complete(Unit); gate.await() }
        drafts.update("a", "new code", "new notes")
        val waiting = owner.async(Dispatchers.IO) { drafts.flush() }
        entered.await()
        repo.update("a") { it.copy(favorite = false, flagged = false) }
        repo.record(repo.question("a")!!, "A", setOf("A"), "concurrent practice")
        val learning = row()
        gate.complete(Unit); assertTrue(waiting.await())
        assertEquals(learning.copy(code = "new code", notes = "new notes"), row())
        assertEquals(1, repo.dao.allAttempts().size)
    }

    @Test fun shutdownFlushesTheWindowAndRejectsLaterUpdates() = runBlocking {
        drafts.update("a", "last accepted", "last notes")
        val closing = drafts.shutdown()
        assertTrue(drafts.editingBlocked)
        assertFalse(drafts.update("a", "rejected", "rejected"))
        closing.join(); reopen()
        assertEquals("last accepted", row().code); assertEquals("last notes", row().notes)
    }

    @Test fun shutdownCancelsAStalledPreWriteWithoutPretendingItWasSaved() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        drafts.persistence.closeBudgetMillis = 100
        drafts.persistence.beforeWrite = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
        drafts.update("a", "not committed", "not committed")
        val waiting = owner.async(Dispatchers.IO) { drafts.flush() }
        entered.await(); withTimeout(3000) { drafts.shutdown().join() }
        assertFalse(waiting.await())
        assertTrue(drafts.state("a") is CodeDraftSaveState.Error)
        assertEquals("base", row().code); assertTrue(audit().isEmpty())
    }

    @Test fun cancellingAnExclusiveBoundaryReleasesEditingAndPreservesAcceptedText() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        drafts.persistence.beforeBoundary = { entered.complete(Unit); gate.await() }
        drafts.update("a", "accepted before cancel", "n1")
        val task = owner.async(Dispatchers.IO) { drafts.exclusive { error("must not enter") } }
        entered.await(); assertTrue(drafts.editingBlocked)
        task.cancelAndJoin()
        assertFalse(drafts.editingBlocked)
        assertEquals("accepted before cancel", drafts.drafts["a"]!!.code)
        drafts.update("a", "accepted after cancel", "n2")
        assertTrue(drafts.flush())
        assertEquals("accepted after cancel", row().code)
    }
}