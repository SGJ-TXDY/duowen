package cn.embeddedquiz

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AiLocalStoreTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private val q=Question("local","local snapshot",Kind.SHORT,"fixture")
    private fun host(trace:MutableList<String> = mutableListOf(),name:String?=null):AiFoundationTestDb =
        (if(name==null) Room.inMemoryDatabaseBuilder(context,AiFoundationTestDb::class.java) else Room.databaseBuilder(context,AiFoundationTestDb::class.java,name))
            .allowMainThreadQueries().setQueryCallback({ sql,_ -> synchronized(trace) { trace.add(sql) } },Executor { it.run() }).build()
    private fun store(db:AiFoundationTestDb,hook:()->Unit={})=AiStore(db,db.ai(),{q},now={1000L},beforeCommit=hook)
    private suspend fun check(db:AiFoundationTestDb,s:AiStore) {
        s.audit();assertEquals(AiConversationCodec.encodedBytes(s.dataset()),AiMeter.total(db.ai().measures()))
    }
    private fun rejects(action:()->Unit) { assertTrue(runCatching(action).isFailure) }
    @Test fun draftAndRenameNeverSelectUnrelatedBodiesAndMatchFullOracle()=runBlocking {
        val trace=mutableListOf<String>();val db=host(trace)
        try {
            val s=store(db); val selected=s.create(id(),q).sessionId!!
            repeat(4) { i -> val sid=s.create(id(),q).sessionId!!;val e=s.openEditor(sid)
                s.saveDraft(DraftCommand(id(),sid,0,DraftToken(e,0),("different-$i\n"+('a'+i).toString().repeat(100000)))) }
            val editor=s.openEditor(selected);trace.clear()
            s.saveDraft(DraftCommand(id(),selected,0,DraftToken(editor,1),"target\u0001\n中"))
            s.rename(id(),selected,0,"new title")
            val reads=synchronized(trace) { trace.toList() }.filter { it.trimStart().startsWith("SELECT",true) }
            assertTrue(reads.isNotEmpty())
            assertTrue(reads.none { it.contains("SELECT *",true) && !it.contains(" WHERE ",true) })
            assertTrue(reads.none { it.contains("FROM ai_messages",true) && it.contains("SELECT *",true) })
            assertTrue(reads.none { it.contains("FROM ai_runs",true) && it.contains("SELECT *",true) })
            check(db,s)
        } finally { db.close() }
    }
    @Test fun globalBusyAndCommandReplayWorkAcrossIndependentStores()=runBlocking {
        val db=host();try {
            val s=store(db);val second=store(db);val a=s.create(id(),q).sessionId!!;val b=s.create(id(),q).sessionId!!
            val c=SendCommand(id(),a,QuestionOwner(q.id,q.contentHash),0,null,null,"input",emptySet(),"",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH)
            val receipt=s.accept(c);assertEquals(receipt,second.accept(c))
            assertTrue(runCatching { second.accept(c.copy(commandId=id(),sessionId=b)) }.isFailure)
            check(db,s)
        } finally { db.close() }
    }
    @Test fun checkpointsAcrossSessionsChargeExactArraySeparatorsAndKeepMeterOnRollback()=runBlocking {
        val db=host();try {
            val s=store(db)
            repeat(5) {
                val sid=s.create(id(),q).sessionId!!
                val c=SendCommand(id(),sid,QuestionOwner(q.id,q.contentHash),0,null,null,"input",emptySet(),"",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH)
                val r=s.accept(c);s.checkpoint(r.requestId!!,1,"partial");check(db,s)
                s.checkpoint(r.requestId,2,"complete",RunStatus.COMPLETED);check(db,s)
            }
            val before=s.dataset();val stats=db.ai().measures();val sid=before.sessions.first().sessionId
            assertTrue(runCatching { store(db,hook={error("transaction fault")}).rename(id(),sid,0,"will roll back") }.isFailure)
            assertEquals(before,s.dataset());assertEquals(stats,db.ai().measures());check(db,s)
        } finally { db.close() }
    }
    @Test fun metadataIsVerifiedAtImportAndColdAuditAndNotAcceptedAsWireAuthority()=runBlocking {
        val db=host();try {
            val s=store(db);val sid=id();val row=AiSessionRow(sid,q.id,q.contentHash,questionSnapshot=AiSnapshotCodec.question(q),title="x",createdAt=1000,updatedAt=1000,encodedBytes=1)
            assertTrue(runCatching { s.importIntoEmpty(AiDataset(sessions=listOf(row))) }.isFailure)
            assertEquals(AiDataset(),s.dataset())
            val accepted=s.create(id(),q);check(db,s)
            val wire=JSONObject(AiConversationCodec.encode(s.dataset()))
            wire.getJSONArray("aiSessions").getJSONObject(0).put("encodedBytes",1)
            rejects { AiConversationCodec.decode(wire.toString()) }
            db.openHelper.writableDatabase.execSQL("UPDATE ai_sessions SET encodedBytes=1 WHERE sessionId=?",arrayOf(accepted.sessionId))
            assertTrue(runCatching { s.audit() }.isFailure)
        } finally { db.close() }
    }
    @Test fun processReopenRetainsExactMeterAndRejectsStaleEditor()=runBlocking {
        val name="ai-local-${id()}.db";var db=host(name=name)
        val s=store(db);val sid=s.create(id(),q).sessionId!!;val editor=s.openEditor(sid)
        val c=DraftCommand(id(),sid,0,DraftToken(editor,1),"durable")
        val receipt=s.saveDraft(c);val expected=s.dataset();db.close()
        db=host(name=name)
        try {
            val reopened=store(db);assertEquals(expected,reopened.dataset());assertEquals(receipt,reopened.saveDraft(c))
            assertTrue(runCatching { reopened.saveDraft(c.copy(commandId=id(),expectedDraftRevision=1,token=DraftToken(editor,2))) }.isFailure)
            check(db,reopened)
        } finally { db.close();context.deleteDatabase(name) }
    }    @Test fun interruptedClockRollbackDoesNotMakeValidLedgerUnrecoverable()=runBlocking {
        val db=host();try {
            val future=java.time.Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()
            val before=AiStore(db,db.ai(),{q},now={future})
            val sid=before.create(id(),q).sessionId!!
            before.accept(SendCommand(id(),sid,QuestionOwner(q.id,q.contentHash),0,null,null,"clock",emptySet(),"",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH))
            val cold=store(db);cold.recoverInterrupted();val row=cold.dataset().runs.single()
            assertEquals("FAILED",row.status);assertEquals(future,row.finishedAt);assertEquals(0L,row.reservedBytes);check(db,cold)
        } finally { db.close() }
    }
    @Test fun newerEditorOnSecondStoreRevokesOldOwnerEvenIfOldWriterReloadsRevision()=runBlocking {
        val db=host();try {
            val old=store(db);val next=store(db);val sid=old.create(id(),q).sessionId!!
            val oldEpoch=old.openEditor(sid)
            old.saveDraft(DraftCommand(id(),sid,0,DraftToken(oldEpoch,1),"old saved"))
            val newEpoch=next.openEditor(sid)
            next.saveDraft(DraftCommand(id(),sid,1,DraftToken(newEpoch,1),"new saved"))
            // A stale writer must not regain authority simply by reloading the latest revision.
            val result=runCatching { old.saveDraft(DraftCommand(id(),sid,2,DraftToken(oldEpoch,2),"late overwrite")) }
            assertTrue(result.isFailure);assertEquals(RejectCode.STALE_DRAFT,(result.exceptionOrNull() as AiStoreFailure).code)
            assertEquals("new saved",next.dataset().sessions.single().draftText);check(db,next)
        } finally { db.close() }
    }

}
