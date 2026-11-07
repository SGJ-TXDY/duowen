package cn.embeddedquiz

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.io.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AiProductionIntegrationTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val bank="""{"schemaVersion":1,"questions":[{"id":"c","stem":"original","kind":"SHORT","source":"test"}]}"""
    private val reply="""{"choices":[{"message":{"content":"late response"}}],"usage":{"prompt_tokens":10,"completion_tokens":2}}"""
    private fun id()=UUID.randomUUID().toString()
    private fun waitFor(done:()->Boolean) {
        val until=System.nanoTime()+20_000_000_000L
        while(!done() && System.nanoTime()<until) { shadowOf(Looper.getMainLooper()).idle();LockSupport.parkNanos(1_000_000) }
        assertTrue("asynchronous boundary did not finish",done())
    }
    private fun <T> call(action:suspend ()->T):T {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try { val d=scope.async { action() };waitFor { d.isCompleted };return runBlocking { d.await() } }
        finally { scope.cancel() }
    }
    private fun scoped(action:(QuizDatabase,QuizRepository)->Unit) {
        val name="c-"+id().take(8)+".db";val db=QuizDatabase.open(app,name)
        try { val repo=QuizRepository(db);call { repo.importBank(bank);repo.initializeAiStorage() };action(db,repo) }
        finally { db.close();app.deleteDatabase(name) }
    }
    private fun store(db:QuizDatabase)=AiStore(db,db.ai(),{ QuizRepository(db).question(it) },now={1000})
    private fun input(text:String)=ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    private fun tables(db:QuizDatabase):Map<String,List<String>> {
        val names=AiLegacyBackupCodec.tables.values+AiMeter.tables+listOf("exam_terminations")
        return names.associateWith { name -> db.openHelper.readableDatabase.query("SELECT * FROM `$name`").use { c ->
            buildList { while(c.moveToNext()) add(AiLegacyBackupCodec.canonical(AiSqlAccess.record(c))) }.sorted()
        } }
    }
    @Test fun productionRoundTripAndFaultRollbackUseActualAdapter()=scoped { db,repo -> call {
        val s=store(db);val sid=s.create(id(),repo.question("c")!!).sessionId!!;val e=s.openEditor(sid)
        s.saveDraft(DraftCommand(id(),sid,0,DraftToken(e,1),"saved draft"))
        repo.dao.putProgress(Progress("c",notes="original notes"))
        repo.dao.putAi(AiEntry("legacy","c",DeepSeekClient.FLASH,"p","keep old ai",10,2,0,0.1,100))
        repo.dao.addExamTermination(ExamTermination("local-exam","ABANDONED",null,null,null,null,null,1,1,null))
        val backup=repo.backup();assertEquals(4,JSONObject(backup).getInt("backupVersion"))
        repo.dao.putProgress(Progress("c",notes="target before fault"));val before=tables(db)
        repo.beforeRestoreCommit={ throw IOException("target transaction failure") }
        assertTrue(runCatching { repo.restoreFrom(input(backup)) }.isFailure);assertEquals(before,tables(db));assertNotNull(db.aiLifecycle.token())
        repo.beforeRestoreCommit={};repo.restore(backup)
        assertEquals("original notes",repo.dao.getProgress("c")!!.notes);assertEquals("keep old ai",repo.dao.allAi().single().response)
        assertNull(db.ai().session(sid)!!.activeEditorEpoch);assertNotNull(repo.dao.examTermination("local-exam"))
        repo.restore(backup);assertEquals(1,db.ai().allSessions().size);s.audit()
    } }
    @Test fun productionLegacyMatrixRetainsAbsentSectionsAndConversations()=scoped { db,repo -> call {
        val s=store(db);val sid=s.create(id(),repo.question("c")!!).sessionId!!
        s.openEditor(sid)
        val backup=repo.backup()
        for(version in 1..3) {
            repo.dao.putStickyNote(StickyNote("n","local note","body",1,1))
            repo.dao.putSettings(LearningSettings(dailyGoal=99,notificationPermissionAsked=true))
            val old=JSONObject(backup).asLegacyBackup(version)
            if(version<2) old.remove("stickyNotes")
            if(version<3) listOf("learningSettings","personalAnswers","questionPersonal").forEach(old::remove)
            repo.restoreFrom(input(old.toString()))
            assertEquals(1,db.ai().allSessions().size);assertNull(db.ai().session(sid)!!.activeEditorEpoch)
            if(version<2) assertEquals("local note",repo.dao.allStickyNotes().single().title)
            if(version<3) assertEquals(99,repo.dao.getSettings()!!.dailyGoal)
            assertTrue(repo.dao.getSettings()!!.notificationPermissionAsked)
        }
    } }
    @Test fun invalidPreflightAndResourcesNeverPauseOrReplaceTarget()=scoped { db,repo -> call {
        val original=tables(db);val backup=repo.backup()
        for(bad in listOf("{",JSONObject(backup).put("backupVersion",99).toString(),JSONObject(backup).apply { remove("aiRuns") }.toString())) {
            assertTrue(runCatching { repo.restore(bad) }.isFailure);assertEquals(original,tables(db));assertNotNull(db.aiLifecycle.token())
        }
        repo.storageResource={ throw IOException("disk unavailable") }
        var closed=false
        assertTrue(runCatching { repo.restoreFrom(object:ByteArrayInputStream(backup.toByteArray()) { override fun close() { closed=true;super.close() } }) }.isFailure)
        assertTrue(closed);assertEquals(original,tables(db));assertNotNull(db.aiLifecycle.token())
    } }
    @Test fun activeExamIsRecheckedImmediatelyBeforeReplacementAcrossWrappers()=scoped { db,repo -> call {
        val backup=repo.backup();val other=QuizRepository(db);val original=tables(db)
        assertTrue(db.examAccess.claim())
        try { assertTrue(runCatching { other.restore(backup) }.isFailure);assertEquals(original,tables(db));assertNotNull(db.aiLifecycle.token()) }
        finally { db.examAccess.release() }
        other.restore(backup)
    } }
    @Test fun failedProductionExportCloseCannotReportSuccess()=scoped { _,repo -> call {
        var opened=false;var closed=false
        assertTrue(runCatching { repo.backupTo { opened=true;object:ByteArrayOutputStream() { override fun close() { closed=true;throw IOException("provider close") } } } }.isFailure)
        assertTrue(opened);assertTrue(closed)
        val out=ByteArrayOutputStream();repo.backupTo { out };assertEquals(4,JSONObject(out.toString("UTF-8")).getInt("backupVersion"))
    } }
    @Test fun coldValidationRejectsForgedMetadataBeforeAnyProductionStoreWrite()=scoped { db,repo -> call {
        val sid=store(db).create(id(),repo.question("c")!!).sessionId!!
        db.openHelper.writableDatabase.execSQL("UPDATE ai_sessions SET encodedBytes=1 WHERE sessionId=?",arrayOf(sid))
        val before=tables(db)
        assertTrue(runCatching { repo.initializeAiStorage() }.isFailure);assertEquals(before,tables(db))
    } }
    @Test fun productionColdRecoveryRetainsPartialTextAndReleasesReservation()=scoped { db,repo -> call {
        val s=store(db);val q=repo.question("c")!!;val sid=s.create(id(),q).sessionId!!
        val sent=s.accept(SendCommand(id(),sid,QuestionOwner(q.id,q.contentHash),0,null,null,"hello",emptySet(),"",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH))
        s.checkpoint(sent.requestId!!,1,"partial persisted")
        repo.initializeAiStorage()
        val run=db.ai().allRuns().single();assertEquals("FAILED",run.status);assertEquals("PROCESS_INTERRUPTED",run.terminalReason);assertEquals(0L,run.reservedBytes)
        assertEquals("partial persisted",db.ai().allMessages().single { it.role=="ASSISTANT" }.text);s.audit()
    } }
    private fun race(afterWrite:Boolean,failRestore:Boolean) = scoped { db,repo ->
        val backup=call { repo.backup() };val server=MockWebServer();server.start();server.enqueue(MockResponse().setBody(reply))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val io=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val holder=AiStateHolder(scope,repo,KeyVault(app),app.getSharedPreferences("c-ai",0),
            clientFactory={ http -> DeepSeekClient(client=http,endpoint=server.url("/").toString()) },readKey={ "test-key" },notify={})
        val gate:suspend ()->Unit={ withContext(NonCancellable) { entered.complete(Unit);release.await() } }
        if(afterWrite) holder.afterLegacyWrite=gate else holder.beforeLegacyWrite=gate
        try {
            val q=call { repo.question("c")!! };holder.ask(q,"first",emptySet(),AiRequestType.FOLLOW_UP)
            waitFor { entered.isCompleted };val shown=holder.request!!.requestId
            if(failRestore) repo.beforeRestoreCommit={ throw IOException("rollback") }
            val restore=io.async { runCatching { QuizRepository(db).also { it.beforeRestoreCommit=repo.beforeRestoreCommit }.restore(backup) } }
            waitFor { db.aiLifecycle.token()==null }
            assertFalse(restore.isCompleted)
            holder.ask(q,"blocked",emptySet(),AiRequestType.FOLLOW_UP);assertFalse(holder.retry(shown))
            assertEquals(1,server.requestCount)
            release.complete(Unit);waitFor { restore.isCompleted }
            val result=runBlocking { restore.await() };assertEquals(!failRestore,result.isSuccess)
            assertNotNull(db.aiLifecycle.token())
            val rows=call { repo.dao.allAi() }
            assertEquals(if(afterWrite && failRestore) 1 else 0,rows.size)
            if(!failRestore) { assertNull(holder.request);assertTrue(holder.entries.isEmpty()) }
            holder.beforeLegacyWrite={};holder.afterLegacyWrite={}
            server.enqueue(MockResponse().setBody(reply))
            holder.ask(q,"new request",emptySet(),AiRequestType.FOLLOW_UP)
            waitFor { !holder.busy };assertEquals(2,server.requestCount)
        } finally { release.complete(Unit);scope.cancel();io.cancel();call { scope.coroutineContext.job.join();io.coroutineContext.job.join() };server.shutdown() }
    }
    @Test fun oldRequestBeforeWriteIsAwaitedAndCannotArriveAfterReplacement()=race(false,false)
    @Test fun oldRequestAfterWriteIsAwaitedAndRestoredWorldWins()=race(true,false)
    @Test fun failedReplacementResumesAdmissionAndKeepsCommittedOldHistory()=race(true,true)
    @Test fun networkInFlightIsCancelledBeforeDirectRepositoryReplacement()=scoped { db,repo ->
        val backup=call { repo.backup() };val server=MockWebServer();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse { entered.countDown();check(release.await(10,TimeUnit.SECONDS));return MockResponse().setBody(reply) } };server.start()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val holder=AiStateHolder(scope,repo,KeyVault(app),app.getSharedPreferences("c-ai",0),clientFactory={ http -> DeepSeekClient(client=http,endpoint=server.url("/").toString()) },readKey={"test"},notify={})
        val io=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            holder.ask(call { repo.question("c")!! },"network",emptySet(),AiRequestType.FOLLOW_UP);waitFor { entered.count==0L }
            val result=io.async { QuizRepository(db).restore(backup) }
            waitFor { db.aiLifecycle.token()==null || result.isCompleted }
            release.countDown();waitFor { result.isCompleted };runBlocking { result.await() }
            assertTrue(call { repo.dao.allAi() }.isEmpty());assertNull(holder.request);assertFalse(holder.busy)
        } finally { release.countDown();scope.cancel();io.cancel();call { scope.coroutineContext.job.join();io.coroutineContext.job.join() };server.shutdown() }
    }
    @Test fun ownerClosingWaitsActualBackupStreamAndClosesLate()=scoped { db,repo ->
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var outputClosed=false
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val task=scope.async { repo.backupTo { object:OutputStream() {
                override fun write(value:Int) { entered.countDown();check(release.await(10,TimeUnit.SECONDS)) }
                override fun write(bytes:ByteArray,offset:Int,length:Int) { write(0) }
                override fun close() { outputClosed=true }
            } } }
            waitFor { entered.count==0L }
            val pending=db.aiLifecycle.closing();assertTrue(pending.isNotEmpty());assertFalse(task.isCompleted);assertTrue(db.isOpen)
            release.countDown();waitFor { task.isCompleted && pending.all { it.isCompleted } }
            assertTrue(task.isCancelled);assertTrue(outputClosed)
            var closed=false
            assertTrue(runCatching { call { repo.restoreFrom(object:ByteArrayInputStream(byteArrayOf()) { override fun close() { closed=true } }) } }.isFailure);assertTrue(closed)
        } finally { release.countDown();scope.cancel();call { scope.coroutineContext.job.join() } }
    }
    @Test fun resourceGuardKeepsGrowthTimeAndRowChecksWithoutStattingEveryChunk() {
        var time=0L;var calls=0
        val resource=AiStorageResources.guard(app,clock={time},inspect={calls++})
        resource(0);repeat(127) { resource(8192L*(it+1)) };assertEquals(1,calls)
        resource(1024L*1024);assertEquals(2,calls)
        resource(0);assertEquals(3,calls)
        repeat(127) { resource(0) };assertEquals(3,calls);resource(0);assertEquals(4,calls)
        time=500_000_000;resource(0);assertEquals(5,calls)
    }
    @Test fun growingTargetResourceFailureRollsBackAfterWritesHaveStarted()=scoped { db,repo -> call {
        val backup=repo.backup();repo.dao.putProgress(Progress("c",notes="different target"));val before=tables(db)
        var zeroChecks=0
        repo.storageResource={ bytes -> if(bytes==0L && ++zeroChecks==3) throw IOException("WAL growth failure") }
        assertTrue(runCatching { repo.restore(backup) }.isFailure);assertTrue(zeroChecks>=3)
        assertEquals(before,tables(db));assertNotNull(db.aiLifecycle.token())
    } }
    @Test fun cancelledPreflightDoesNotRevokeWriterOrLeaveAdmissionPaused()=scoped { db,repo -> call {
        val backup=repo.backup();val token=db.aiLifecycle.token();val before=tables(db)
        repo.storageResource={ bytes -> if(bytes>0) throw CancellationException("input cancelled") }
        assertTrue(runCatching { repo.restore(backup) }.exceptionOrNull() is CancellationException)
        assertEquals(token,db.aiLifecycle.token());assertEquals(before,tables(db))
    } }
    @Test fun viewModelClosingWaitsForOwnedExportBeforeClosingRoom() {
        app.deleteDatabase("embeddedquiz.db");app.getSharedPreferences("settings",0).edit().clear().commit()
        val owners=ViewModelStore();val vm=ViewModelProvider(owners,ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val entered=CountDownLatch(1);val release=CountDownLatch(1);var closed=false
        try {
            waitFor { vm.ready }
            val backup=scope.async { vm.repo.backupTo { object:OutputStream() {
                override fun write(b:Int) { entered.countDown();check(release.await(10,TimeUnit.SECONDS)) }
                override fun write(b:ByteArray,o:Int,n:Int) { write(0) }
                override fun close() { closed=true }
            } } }
            waitFor { entered.count==0L };owners.clear()
            assertFalse(vm.examResourcesClosed?.isCompleted==true);assertTrue(vm.db.isOpen)
            release.countDown();waitFor { vm.examResourcesClosed?.isCompleted==true }
            assertTrue(backup.isCancelled);assertTrue(closed);assertFalse(vm.db.isOpen)
        } finally { release.countDown();owners.clear();waitFor { vm.examResourcesClosed?.isCompleted==true };scope.cancel();app.deleteDatabase("embeddedquiz.db") }
    }
    @Test fun forgedColdMetadataFailsBeforeReadyAndPreservesSource() {
        app.deleteDatabase("embeddedquiz.db");app.getSharedPreferences("settings",0).edit().clear().putInt("bankRevision",4).commit()
        val seed=QuizDatabase.open(app);val sid=call {
            val repo=QuizRepository(seed);repo.importBank(bank)
            store(seed).create(id(),repo.question("c")!!).sessionId!!
        }
        seed.openHelper.writableDatabase.execSQL("UPDATE ai_sessions SET encodedBytes=1 WHERE sessionId=?",arrayOf(sid));seed.close()
        val owners=ViewModelStore();val vm=ViewModelProvider(owners,ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        try {
            waitFor { vm.ready || vm.startupState is StartupState.Error };assertFalse(vm.ready)
            assertEquals(StartupStage.VALIDATION,(vm.startupState as StartupState.Error).stage);assertNull(vm.db.aiLifecycle.token())
            assertEquals(1L,call { vm.db.ai().session(sid)!!.encodedBytes });assertEquals(1,call { vm.repo.dao.questionCount() })
        } finally { owners.clear();waitFor { vm.examResourcesClosed?.isCompleted==true };app.deleteDatabase("embeddedquiz.db") }
    }
    @Test fun missingAiTablesAndColumnsFailBeforeReadyWithoutDeletingSource() {
        for(table in AiMeter.tables) for(column in listOf(false,true)) {
            app.deleteDatabase("embeddedquiz.db");app.getSharedPreferences("settings",0).edit().clear().putInt("bankRevision",4).commit()
            val seed=QuizDatabase.open(app);call { QuizRepository(seed).importBank(bank) };seed.close()
            val raw=app.openOrCreateDatabase("embeddedquiz.db",0,null)
            raw.execSQL("ALTER TABLE `$table` RENAME TO preserved_ai")
            if(column) raw.execSQL("CREATE TABLE `$table`(broken TEXT)")
            raw.close()
            val owners=ViewModelStore();val vm=ViewModelProvider(owners,ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
            try {
                waitFor { vm.ready || vm.startupState is StartupState.Error }
                assertFalse(vm.ready);assertEquals(StartupStage.DATABASE,(vm.startupState as StartupState.Error).stage)
                assertEquals(1,call { vm.repo.dao.questionCount() });assertNull(vm.db.aiLifecycle.token())
                val diagnostic=JSONObject(call { vm.readStartupDiagnostics() });assertEquals(6,diagnostic.getInt("roomVersion"));assertEquals(4,diagnostic.getInt("backupVersion"));assertEquals(1,diagnostic.getInt("aiConversationVersion"))
            } finally { owners.clear();waitFor { vm.examResourcesClosed?.isCompleted==true };app.deleteDatabase("embeddedquiz.db") }
        }
    }
}
