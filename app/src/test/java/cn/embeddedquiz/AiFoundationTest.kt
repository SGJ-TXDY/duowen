package cn.embeddedquiz

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AiFoundationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private val q=Question("q","指针与内存",Kind.MULTIPLE,"test",options=listOf(Option("B","乙"),Option("A","甲")),correct=setOf("A"),raw="  { \"keep\": 1 }\n")
    private fun host(name: String?=null): AiFoundationTestDb = (if(name==null) Room.inMemoryDatabaseBuilder(context,AiFoundationTestDb::class.java)
        else Room.databaseBuilder(context,AiFoundationTestDb::class.java,name)).allowMainThreadQueries().addMigrations(AiConversationMigration, StickyNoteSourceMigration).build()
    private suspend fun insert(db:AiFoundationTestDb, question:Question=q) {
        db.old().addQuestions(listOf(QuestionRow(question.id,JSONObject(AiSnapshotCodec.question(question)).getJSONObject("question").toString(),contentHash=question.contentHash)))
    }
    private fun store(db:AiFoundationTestDb, cap:Long=AiConversationCodec.CAPACITY, epoch:String=UUID.randomUUID().toString(), clock:()->Long={1000L}, zone:()->java.time.ZoneId=java.time.ZoneId::systemDefault, hook:()->Unit={}):AiStore = AiStore(db,db.ai(),{ key -> db.old().question(key)?.let { HistoricalQuestionV3.read(it.id,it.json) } },now=clock,businessZone=zone,epoch=epoch,capacity=cap,beforeCommit=hook)
    private suspend fun session(store:AiStore)=store.create(id(),q).sessionId!!
    private suspend fun send(store:AiStore,sid:String, input:String="解释", choices:Set<String> = setOf("A","B"),token:DraftToken?=null,rev:Long?=null,commandId:String=id()):AiCommandRow {
        val s=store.dataset().sessions.single { it.sessionId==sid }
        return store.accept(SendCommand(commandId,sid,QuestionOwner(q.id,q.contentHash),s.contextRevision,token,rev,input,choices,"",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH))
    }
    private suspend fun failed(action:suspend()->Unit):Throwable {
        val result=runCatching { action() }; assertTrue("expected rejection",result.isFailure); return result.exceptionOrNull()!!
    }
    private suspend fun withDb(action:suspend(AiFoundationTestDb,AiStore)->Unit) {
        val db=host(); try { insert(db); action(db,store(db)) } finally { db.close() }
    }
    @Test fun questionSnapshotPreservesRawAndOrderAndRejectsCorruption() {
        val snapshot=AiSnapshotCodec.question(q)
        assertEquals(q,AiSnapshotCodec.readQuestion(snapshot,QuestionOwner(q.id,q.contentHash)))
        for(key in listOf("raw","stem")) {
            val bad=JSONObject(snapshot); bad.getJSONObject("question").put(key,"changed")
            assertTrue(runCatching { AiSnapshotCodec.readQuestion(bad.toString(),QuestionOwner(q.id,q.contentHash)) }.isFailure)
        }
        val bad=JSONObject(snapshot); val options=bad.getJSONObject("question").getJSONArray("options")
        val first=options.get(0); options.put(0,options.get(1)); options.put(1,first)
        assertTrue(runCatching { AiSnapshotCodec.readQuestion(bad.toString(),QuestionOwner(q.id,q.contentHash)) }.isFailure)
    }
    @Test fun zeroRunDraftRoundTripHasNoQuestionForeignKey()=runBlocking { withDb { db,s ->
        val sid=session(s); val editor=s.openEditor(sid)
        s.saveDraft(DraftCommand(id(),sid,0,DraftToken(editor,1),"未发送草稿"))
        db.old().clearQuestions()
        val exported=AiConversationCodec.encode(s.dataset()); val data=AiConversationCodec.decode(exported)
        assertTrue(data.runs.isEmpty()); assertEquals("未发送草稿",data.sessions.single().draftText)
        val second=host(); try { store(second).importIntoEmpty(data); assertEquals(data,store(second).dataset()) } finally { second.close() }
        val e=failed { send(s,sid) }; assertEquals(RejectCode.READ_ONLY,(e as AiStoreFailure).code)
    } }
    @Test fun idempotentCreateAndDraftSurviveStoreRecreationAndConflictingInputIsRejected()=runBlocking { withDb { db,s ->
        val command=id(); val first=s.create(command,q)
        assertEquals(first,store(db).create(command,q)); assertEquals(1,s.dataset().sessions.size)
        val e=failed { s.create(command,q.copy(stem="不同输入")) }
        assertEquals(RejectCode.IDEMPOTENCY_CONFLICT,(e as AiStoreFailure).code)
        val sid=first.sessionId!!; val editor=s.openEditor(sid)
        val c=DraftCommand(id(),sid,0,DraftToken(editor,1),"保存")
        assertEquals(s.saveDraft(c),store(db).saveDraft(c))
        assertEquals(2,s.dataset().commands.size)
    } }
    @Test fun staleDraftTokenAndOldEditorNeverOverwriteNewDraft()=runBlocking { withDb { _,s ->
        val sid=session(s); val editor=s.openEditor(sid)
        s.saveDraft(DraftCommand(id(),sid,0,DraftToken(editor,2),"new"))
        failed { s.saveDraft(DraftCommand(id(),sid,1,DraftToken(editor,1),"old")) }
        val other=s.openEditor(sid)
        failed { s.saveDraft(DraftCommand(id(),sid,1,DraftToken(editor,3),"late")) }
        s.saveDraft(DraftCommand(id(),sid,1,DraftToken(other,0),"new editor"))
        assertEquals("new editor",s.dataset().sessions.single().draftText)
    } }
    @Test fun acceptReplayIsStableAndDoesNotClearNewerDraft()=runBlocking { withDb { db,s ->
        val sid=session(s); val editor=s.openEditor(sid)
        s.saveDraft(DraftCommand(id(),sid,0,DraftToken(editor,1),"first"))
        s.saveDraft(DraftCommand(id(),sid,1,DraftToken(editor,2),"second"))
        val command=id(); val first=send(s,sid,token=DraftToken(editor,1),rev=1,commandId=command)
        val c=SendCommand(command,sid,QuestionOwner(q.id,q.contentHash),0,DraftToken(editor,1),1,"解释",setOf("B","A"),"",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH)
        assertEquals(first,store(db).accept(c)); assertEquals(2,s.dataset().messages.size)
        assertEquals("second",s.dataset().sessions.single().draftText)
        failed { s.accept(c.copy(input="changed")) }
    } }
    @Test fun acceptedDraftClearAndReceiptAreAtomicAndRollbackLeavesNoHalfRound()=runBlocking { withDb { db,s ->
        val sid=session(s); val editor=s.openEditor(sid)
        s.saveDraft(DraftCommand(id(),sid,0,DraftToken(editor,1),"draft"))
        val before=AiConversationCodec.encode(s.dataset())
        failed { send(store(db,hook={error("disk failure")}),sid,token=DraftToken(editor,1),rev=1) }
        assertEquals(before,AiConversationCodec.encode(s.dataset()))
        val receipt=send(s,sid,token=DraftToken(editor,1),rev=1)
        assertTrue(JSONObject(receipt.receiptJson).getBoolean("draftCleared")); assertEquals("",s.dataset().sessions.single().draftText)
        assertEquals(2L,s.dataset().sessions.single().draftRevision)
    } }
    @Test fun frozenPayloadSurvivesImmediateQuestionReplacementAndStableChoiceOrder()=runBlocking { withDb { db,s ->
        val sid=session(s); val a=send(s,sid); val frozen=s.dataset().runs.single().contextSnapshot
        val current=db.old().question(q.id)!!
        val revised=q.copy(stem="new stem")
        db.old().updateQuestions(listOf(current.copy(json=JSONObject(AiSnapshotCodec.question(revised)).getJSONObject("question").toString(),contentHash=revised.contentHash)))
        assertEquals(frozen,s.dataset().runs.single().contextSnapshot)
        val body=AiSnapshotCodec.payload(frozen); assertTrue(body.contains("指针与内存")); assertFalse(body.contains("new stem"))
        assertEquals(AiSnapshotCodec.request(AiSnapshotCodec.question(q),DeepSeekClient.FLASH,AiRequestType.FOLLOW_UP,"解释",linkedSetOf("B","A"),"",emptyList()),frozen)
        s.checkpoint(a.requestId!!,1,"part",RunStatus.FAILED)
        failed { s.retry(id(),RunOwner(QuestionOwner(q.id,q.contentHash),sid,a.requestId)) }.also { assertEquals(RejectCode.READ_ONLY,(it as AiStoreFailure).code) }
    } }
    @Test fun retriesKeepFrozenInputAndProduceOnlyOneSuccessfulContextRound()=runBlocking { withDb { _,s ->
        val sid=session(s); val first=send(s,sid); val original=s.dataset().runs.single()
        s.checkpoint(first.requestId!!,1,"partial",RunStatus.FAILED)
        val command=id(); val owner=RunOwner(QuestionOwner(q.id,q.contentHash),sid,first.requestId)
        val retry=s.retry(command,owner); assertEquals(retry,s.retry(command,owner))
        assertEquals(original.contextSnapshot,s.dataset().runs.single { it.requestId==retry.requestId }.contextSnapshot)
        s.checkpoint(retry.requestId!!,1,"final",RunStatus.COMPLETED)
        assertEquals(1,s.dataset().messages.count { it.role=="USER" }); assertEquals(2,s.dataset().messages.count { it.role=="ASSISTANT" })
        assertEquals(1,s.acceptedHistory(sid).size); assertEquals("final",s.acceptedHistory(sid).single().getString("assistant"))
        failed { s.retry(id(),owner) }
    } }
    @Test fun newUserClosesOldRetryAndContextUsesLastFourOrderedCompleteRoundsOnly()=runBlocking { withDb { _,s ->
        val sid=session(s); val first=send(s,sid)
        s.checkpoint(first.requestId!!,1,"partial",RunStatus.FAILED)
        repeat(6) { i -> val r=send(s,sid,"user$i"); s.checkpoint(r.requestId!!,1,"reply$i",RunStatus.COMPLETED) }
        assertEquals(listOf("user2","user3","user4","user5"),s.acceptedHistory(sid).map { it.getString("user") })
        failed { s.retry(id(),RunOwner(QuestionOwner(q.id,q.contentHash),sid,first.requestId)) }.also { assertEquals(RejectCode.STALE_ATTEMPT,(it as AiStoreFailure).code) }
    } }
    @Test fun globalSingleInFlightAndCancelAreIdempotentWithoutNetwork()=runBlocking { withDb { _,s ->
        val one=session(s); val two=session(s); val r=send(s,one)
        failed { send(s,two) }.also { assertEquals(RejectCode.BUSY,(it as AiStoreFailure).code) }
        val command=id(); val owner=RunOwner(QuestionOwner(q.id,q.contentHash),one,r.requestId!!)
        assertEquals(s.cancel(command,owner),s.cancel(command,owner))
        assertTrue(s.dataset().cache.isEmpty()); assertEquals(0L,s.dataset().runs.single().reservedBytes)
        send(s,two)
    } }
    @Test fun foreignKeysAndDuplicateSequenceAreEnforcedByDatabase()=runBlocking { withDb { db,s ->
        val sid=session(s)
        failed { db.ai().insertMessage(AiMessageRow(id(),id(),0,"USER","x","COMPLETE",1000)) }
        db.ai().insertMessage(AiMessageRow(id(),sid,0,"USER","x","COMPLETE",1000))
        failed { db.ai().insertMessage(AiMessageRow(id(),sid,0,"USER","y","COMPLETE",1000)) }
    } }
    @Test fun crossSessionReferencesAndRetrySourceCyclesAndForgedReceiptsAreRejected()=runBlocking { withDb { _,s ->
        val sid=session(s); val other=session(s); val c=send(s,sid); val d=s.dataset(); val r=d.runs.single()
        for(bad in listOf(
            d.copy(messages=d.messages.map { if(it.messageId==r.userMessageId) it.copy(sessionId=other) else it }),
            d.copy(runs=listOf(r.copy(retryOf=r.requestId))),
            d.copy(runs=listOf(r.copy(source="CACHE",sourceRequestId=r.requestId))),
            d.copy(commands=d.commands.map { if(it.commandId==c.commandId) it.copy(receiptJson=AiJson.canonical(JSONObject(it.receiptJson).put("userMessageId",id()))) else it })
        )) failed { AiConversationCodec.validate(bad) }
    } }
    @Test fun partialAndCancelledRepliesCannotBecomeCacheAndUnknownUsageStaysNull()=runBlocking { withDb { _,s ->
        val sid=session(s); val c=send(s,sid); s.checkpoint(c.requestId!!,1,"part")
        val partial=s.dataset(); val r=partial.runs.single()
        assertNull(r.inputTokens); assertNull(r.estimatedUsd); assertEquals("UNKNOWN",r.usageStatus)
        failed { AiConversationCodec.validate(partial.copy(cache=listOf(AiCacheRow(r.cacheKey,r.requestId,1000)))) }
        s.cancel(id(),RunOwner(QuestionOwner(q.id,q.contentHash),sid,r.requestId))
        failed { AiConversationCodec.validate(s.dataset().copy(cache=listOf(AiCacheRow(r.cacheKey,r.requestId,1000)))) }
    } }
    @Test fun usageOverflowUnknownEnumsAndNonFiniteMoneyAreRejectedBeforeRestore()=runBlocking { withDb { _,s ->
        val sid=session(s); send(s,sid); val d=s.dataset(); val r=d.runs.single()
        for(bad in listOf(r.copy(status="FUTURE_COMPLETE"),r.copy(inputTokens=-1),r.copy(inputTokens=1,hitTokens=2),
            r.copy(estimatedUsd="NaN"),r.copy(estimatedUsd="Infinity"),r.copy(usageStatus="KNOWN"))) {
            failed { AiConversationCodec.validate(d.copy(runs=listOf(bad))) }
        }
        val encoded=AiConversationCodec.encode(d).replace("\"eventSeq\":0","\"eventSeq\":9223372036854775808")
        failed { AiConversationCodec.decode(encoded) }
        val snapshot=JSONObject(r.contextSnapshot).put("Authorization","secret")
        failed { AiSnapshotCodec.validateRequest(AiJson.canonical(snapshot),QuestionOwner(q.id,q.contentHash),r.model,r.requestType) }
    } }
    @Test fun capacityReservationConsumptionIsIdempotentAndTransactionFailureRollsBack()=runBlocking { withDb { db,s ->
        val sid=session(s); val c=send(s,sid); val before=s.dataset(); val outstanding=AiConversationCodec.reserved(before)
        s.checkpoint(c.requestId!!,1,"控制\n\u0001".repeat(100))
        val checkpoint=s.dataset(); assertTrue(AiConversationCodec.reserved(checkpoint)<outstanding)
        assertTrue(AiConversationCodec.encodedBytes(checkpoint)+AiConversationCodec.reserved(checkpoint)<=AiConversationCodec.encodedBytes(before)+outstanding)
        s.checkpoint(c.requestId,1,"ignored repeated event")
        assertEquals(checkpoint,s.dataset())
        failed { store(db,epoch=checkpoint.runs.single().coordinatorEpoch,hook={error("rollback")}).checkpoint(c.requestId,2,"larger".repeat(1000),expectedEpoch=checkpoint.runs.single().coordinatorEpoch) }
        assertEquals(checkpoint,s.dataset())
    } }
    @Test fun textAndMessageLimitsIncludeEscapingAndExactCapacityBoundary()=runBlocking { withDb { _,s ->
        val sid=session(s); val data=s.dataset()
        val exact=AiConversationCodec.encodedBytes(data)
        AiConversationCodec.capacity(data,exact); failed { AiConversationCodec.capacity(data,exact-1) }
        val nonAscii=AiJson.canonical(JSONObject().put("v","中\n\u0001"))
        assertEquals(nonAscii.toByteArray(Charsets.UTF_8).size.toLong(),AiJson.utf8(nonAscii))
        val base=AiMessageRow(id(),sid,0,"USER","x","COMPLETE",1000)
        AiConversationCodec.capacity(data.copy(messages=List(100000) { base }))
        failed { AiConversationCodec.capacity(data.copy(messages=List(100001) { base })) }
        val srow=data.sessions.single()
        AiConversationCodec.validate(data.copy(sessions=listOf(srow.copy(draftText="a".repeat(1024*1024)))))
        failed { AiConversationCodec.validate(data.copy(sessions=listOf(srow.copy(draftText="a".repeat(1024*1024+1))))) }
    } }
    @Test fun exactAcceptedCapacityCanCheckpointMaximumEscapedTextAndFinish()=runBlocking { withDb { db,s ->
        val sid=session(s); val c=send(s,sid); val before=s.dataset()
        val exact=AiConversationCodec.encodedBytes(before)+AiConversationCodec.reserved(before)
        val bounded=store(db,cap=exact,epoch=before.runs.single().coordinatorEpoch)
        bounded.checkpoint(c.requestId!!,1,"\u0001".repeat(1024*1024))
        val partial=bounded.dataset()
        assertTrue(AiConversationCodec.encodedBytes(partial)+AiConversationCodec.reserved(partial)<=exact)
        bounded.checkpoint(c.requestId,2,"\u0001".repeat(1024*1024),RunStatus.COMPLETED)
        val done=bounded.dataset(); AiConversationCodec.validate(done,exact)
        assertEquals(0L,AiConversationCodec.reserved(done)); assertEquals(1,done.cache.size)
    } }
    @Test fun refusalAtCapacityDoesNotLeaveReceiptRunOrClearSavedDraft()=runBlocking { withDb { db,s ->
        val sid=session(s); val editor=s.openEditor(sid)
        s.saveDraft(DraftCommand(id(),sid,0,DraftToken(editor,1),"draft"))
        val before=s.dataset(); val exact=AiConversationCodec.encodedBytes(before)
        failed { send(store(db,cap=exact),sid,token=DraftToken(editor,1),rev=1) }
        assertEquals(before,s.dataset())
    } }
    @Test fun explicitZeroUsageHasPriceSnapshotAndMalformedReceiptCannotHideCredentials()=runBlocking { withDb { _,s ->
        val sid=session(s); val c=send(s,sid)
        s.checkpoint(c.requestId!!,1,"done",RunStatus.COMPLETED,AiUsageEvidence(0,0,0,UsageState.KNOWN,"v1","0.30","0.006","1.20","0"))
        val d=s.dataset(); assertEquals("0",d.runs.single().estimatedUsd)
        val receipt=d.commands.last()
        failed { AiConversationCodec.validate(d.copy(commands=d.commands.filterNot { it.commandId==receipt.commandId }+
            receipt.copy(receiptJson=AiJson.canonical(JSONObject(receipt.receiptJson).put("Authorization","secret"))))) }
    } }
    @Test fun usageIsNotErasedByLaterTextOnlyCheckpointAndSnapshotHistoryMustBelongToSession()=runBlocking { withDb { _,s ->
        val sid=session(s); val first=send(s,sid)
        val known=AiUsageEvidence(100,20,0,UsageState.KNOWN,"v1","0.30","0.006","1.20","0.000054")
        s.checkpoint(first.requestId!!,1,"partial",usage=known)
        s.checkpoint(first.requestId,2,"done",RunStatus.COMPLETED)
        assertEquals(100L,s.dataset().runs.single().inputTokens)
        val second=send(s,sid); val d=s.dataset(); val r=d.runs.single { it.requestId==second.requestId }
        val bad=JSONObject(r.contextSnapshot); bad.getJSONObject("data").getJSONArray("history").getJSONObject(0).put("assistantMessageId",r.assistantMessageId)
        // Invalid immutable payload and invalid history references are both rejected before persistence.
        failed { AiConversationCodec.validate(d.copy(runs=d.runs.map { if(it.requestId==r.requestId) r.copy(contextSnapshot=AiJson.canonical(bad),contextDigest=DeepSeekClient.hash(AiJson.canonical(bad))) else it })) }
    } }
    @Test fun draftCoalescingUsesTail300msAndMaximum2secondsAndExplicitFlush()=runBlocking { withDb { _,s ->
        val sid=session(s); val e=s.openEditor(sid); var time=0L; val writer=AiDraftCoalescer { time }
        repeat(20) { i -> time=i*100L; writer.offer(DraftToken(e,i.toLong()),"text$i"); assertNull(writer.due()) }
        time=2000; val due=writer.due()!!; assertEquals("text19",due.second)
        s.saveDraft(DraftCommand(id(),sid,0,due.first,due.second)); assertEquals(2,s.dataset().commands.size)
        writer.offer(DraftToken(e,21),"flush"); val flush=writer.due(force=true)!!
        s.saveDraft(DraftCommand(id(),sid,1,flush.first,flush.second)); assertEquals(2L,s.flushDraft(sid,2))
        writer.offer(DraftToken(e,22),"tail"); time+=299; assertNull(writer.due()); time++; assertEquals("tail",writer.due()!!.second)
    } }
    @Test fun runningSnapshotRoundTripCanRecoverInterruptedWithoutExceedingReservation()=runBlocking { withDb { _,s ->
        val sid=session(s); val c=send(s,sid); s.checkpoint(c.requestId!!,1,"partial")
        val exported=AiConversationCodec.decode(AiConversationCodec.encode(s.dataset()))
        val second=host(); try {
            val target=store(second); target.importIntoEmpty(exported); target.recoverInterrupted()
            val recovered=target.dataset(); assertEquals("PROCESS_INTERRUPTED",recovered.runs.single().terminalReason)
            assertEquals(0L,AiConversationCodec.reserved(recovered)); assertEquals("partial",recovered.messages.single { it.role=="ASSISTANT" }.text)
            AiConversationCodec.validate(recovered)
        } finally { second.close() }
    } }
    @Test fun restoreRetryChainInReversedInputOrderRespectsImmediateForeignKeys()=runBlocking { withDb { _,s ->
        val sid=session(s); val first=send(s,sid)
        s.checkpoint(first.requestId!!,1,"failed part",RunStatus.FAILED)
        val retry=s.retry(id(),RunOwner(QuestionOwner(q.id,q.contentHash),sid,first.requestId))
        s.checkpoint(retry.requestId!!,1,"complete",RunStatus.COMPLETED)
        val data=s.dataset(); val seq=data.messages.associate { it.messageId to it.seq }
        val reversed=data.copy(runs=data.runs.sortedByDescending { seq.getValue(it.assistantMessageId) })
        val second=host(); try {
            val target=store(second); target.importIntoEmpty(reversed)
            assertEquals(AiConversationCodec.encode(data),AiConversationCodec.encode(target.dataset()))
        } finally { second.close() }
    } }
    @Test fun realV5MigrationAddsOnlyFiveTablesAndKeepsAllLegacyRowsAndJson()=runBlocking {
        val name="ai-v5-${id()}.db"; val old=AiLegacyV5TestDb.open(context,name)
        val raw=" {\"id\":\"legacy\",\"kind\":\"SHORT\",\"stem\":\"unchanged\",\"source\":\"test\"} "
        try {
            old.dao().addQuestions(listOf(QuestionRow("legacy",raw)))
            old.dao().putAi(AiEntry("old","legacy",DeepSeekClient.FLASH,"task","old reply",1,2,0,0.1,1))
            old.dao().putProgress(Progress("legacy",seen=3,notes="keep"))
            old.openHelper.writableDatabase.execSQL("INSERT INTO exam_terminations VALUES ('exam','ABANDONED',NULL,NULL,NULL,NULL,NULL,1,1,NULL)")
            old.openHelper.writableDatabase.execSQL("INSERT INTO sticky_notes VALUES ('note','title','body',1,2)")
            old.openHelper.writableDatabase.execSQL("INSERT INTO learning_settings VALUES (1,20,'SYSTEM','MEDIUM',0,1200,0,-1)")
            old.openHelper.writableDatabase.execSQL("INSERT INTO personal_answers VALUES ('legacy','A','note','hash',1)")
            old.openHelper.writableDatabase.execSQL("INSERT INTO question_personal VALUES ('legacy','tag','','',0)")
            old.openHelper.writableDatabase.execSQL("INSERT INTO attempts VALUES (1,'legacy',1,1,'A',1,'GOOD','test',1,'','ORIGINAL','','')")
        } finally { old.close() }
        val sqlite=context.openOrCreateDatabase(name,0,null)
        fun digestRows(db:android.database.sqlite.SQLiteDatabase)=listOf("questions","ai","progress","attempts","exam_terminations","sticky_notes","learning_settings","personal_answers","question_personal").associateWith { table ->
            val columns = if (table == "sticky_notes") "id,title,content,created,updated" else "*"
            db.rawQuery("SELECT $columns FROM $table",null).use { c -> buildString { while(c.moveToNext()) for(i in 0 until c.columnCount) append(c.getString(i)).append('|') } }
        }
        val before=digestRows(sqlite); sqlite.close()
        val migrated=host(name)
        try {
            migrated.openHelper.writableDatabase // Room checks every table, column, index and FK against generated entities.
            assertEquals(raw,migrated.old().question("legacy")!!.json)
            val c=migrated.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'ai_%'")
            c.use { assertEquals(5,it.count) }
            assertEquals(7,migrated.openHelper.readableDatabase.version)
        } finally { migrated.close() }
        val after=context.openOrCreateDatabase(name,0,null); assertEquals(before,digestRows(after)); after.close()
        context.deleteDatabase(name)
        val productionName="ai-production-${id()}.db"; val production=QuizDatabase.open(context,productionName)
        try { assertEquals(7,production.openHelper.readableDatabase.version) } finally { production.close(); context.deleteDatabase(productionName) }
    }
    @Test fun migrationFailureRollsBackNewTablesAndLeavesV5Readable() {
        val name="ai-fail-${id()}.db"; val old=AiLegacyV5TestDb.open(context,name)
        old.openHelper.writableDatabase.execSQL("INSERT INTO sticky_notes VALUES ('note','title','body',1,2)"); old.close()
        val failure=object:Migration(5,6) { override fun migrate(db:SupportSQLiteDatabase) { AiConversationMigration.migrate(db); error("injected migration failure") } }
        val bad=Room.databaseBuilder(context,AiFoundationTestDb::class.java,name).addMigrations(failure).allowMainThreadQueries().build()
        try { assertTrue(runCatching { bad.openHelper.writableDatabase }.isFailure) } finally { bad.close() }
        val check=context.openOrCreateDatabase(name,0,null)
        try {
            assertEquals(5,check.version)
            check.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='ai_sessions'",null).use { it.moveToFirst(); assertEquals(0,it.getInt(0)) }
            check.rawQuery("SELECT content FROM sticky_notes WHERE id='note'",null).use { it.moveToFirst(); assertEquals("body",it.getString(0)) }
        } finally { check.close(); context.deleteDatabase(name) }
    }
    @Test fun f1RejectsForgedZeroReserveBeforeImport()=runBlocking { withDb { _,s ->
        val sid=session(s); send(s,sid); val d=s.dataset()
        val forged=d.copy(runs=d.runs.map { it.copy(reservedBytes=0) })
        failed { AiConversationCodec.decode(AiConversationCodec.encode(forged)) }
        val second=host(); try {
            failed { store(second).importIntoEmpty(forged) }
            assertEquals(AiDataset(),store(second).dataset())
        } finally { second.close() }
    } }
    @Test fun f1RejectsFalseKnownEstimate()=runBlocking { withDb { _,s ->
        val sid=session(s); val c=send(s,sid)
        s.checkpoint(c.requestId!!,1,"done",RunStatus.COMPLETED,
            AiUsageEvidence(100,20,0,UsageState.KNOWN,"fixture-v1","0.30","0.006","1.20","0.000054"))
        val d=s.dataset(); val forged=d.copy(runs=d.runs.map { it.copy(estimatedUsd="0") })
        failed { AiConversationCodec.decode(AiConversationCodec.encode(forged)) }
    } }

    @Test fun f1RetryHasNewDayAndKeepsOriginalFrozenRequestAndBilling()=runBlocking { withDb { db,_ ->
        var t=java.time.Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()
        val custom=store(db,clock={t},zone={java.time.ZoneId.of("UTC")})
        val sid=session(custom); val first=send(custom,sid); val original=custom.dataset().runs.single()
        custom.checkpoint(first.requestId!!,1,"partial",RunStatus.FAILED)
        t+=86_400_000
        val retried=custom.retry(id(),RunOwner(QuestionOwner(q.id,q.contentHash),sid,first.requestId))
        val all=custom.dataset().runs; val new=all.single { it.requestId==retried.requestId }
        assertEquals(original.billingDay,all.single { it.requestId==first.requestId }.billingDay)
        assertEquals(original.billingDay+1,new.billingDay); assertEquals(t,new.createdAt)
        assertEquals(original.contextSnapshot,new.contextSnapshot); assertEquals(original.model,new.model)
        assertEquals(original.effectiveParameters,new.effectiveParameters)
    } }
    @Test fun f1RetryCapturesChangedBusinessTimezoneWithoutChangingSystemDefaults()=runBlocking { withDb { db,_ ->
        val t=java.time.Instant.parse("2026-11-06T00:30:00Z").toEpochMilli()
        var zone=java.time.ZoneId.of("UTC"); val custom=store(db,clock={t},zone={zone})
        val sid=session(custom); val first=send(custom,sid); val original=custom.dataset().runs.single()
        custom.checkpoint(first.requestId!!,1,"part",RunStatus.FAILED)
        zone=java.time.ZoneId.of("America/Los_Angeles")
        val retried=custom.retry(id(),RunOwner(QuestionOwner(q.id,q.contentHash),sid,first.requestId))
        val new=custom.dataset().runs.single { it.requestId==retried.requestId }
        assertEquals(original.billingDay-1,new.billingDay); assertEquals(zone.id,new.billingZone)
        assertEquals("UTC",custom.dataset().runs.single { it.requestId==first.requestId }.billingZone)
    } }
    @Test fun f1AcceptReadsClockAndZoneExactlyOnceAcrossMidnight()=runBlocking { withDb { db,base ->
        val sid=session(base); var times=0; var zones=0
        val beforeMidnight=java.time.Instant.parse("2026-11-06T23:59:59.999Z").toEpochMilli()
        val custom=store(db,clock={times++; beforeMidnight+if(times==1) 0 else 1},zone={zones++; java.time.ZoneId.of(if(zones==1) "UTC" else "Asia/Tokyo")})
        val command=send(custom,sid); val d=custom.dataset(); val r=d.runs.single()
        assertEquals(1,times); assertEquals(1,zones); assertEquals(beforeMidnight,r.createdAt)
        assertEquals(java.time.LocalDate.of(2026,11,6).toEpochDay(),r.billingDay); assertEquals("UTC",r.billingZone)
        assertEquals(r.createdAt,command.createdAt)
        assertTrue(d.messages.all { it.createdAt==r.createdAt })
    } }
    @Test fun f1ForgedBillingDayIsRejectedBeforeAnyTargetWrite()=runBlocking { withDb { _,s ->
        val sid=session(s); send(s,sid); val d=s.dataset()
        val forged=d.copy(runs=d.runs.map { it.copy(billingDay=it.billingDay+1) })
        failed { AiConversationCodec.decode(AiConversationCodec.encode(forged)) }
        val second=host(); try {
            failed { store(second).importIntoEmpty(forged) }; assertEquals(AiDataset(),store(second).dataset())
        } finally { second.close() }
    } }
    @Test fun f1InsufficientClosingFloorIsRejectedButExactFloorRecoversWithoutDroppingText()=runBlocking { withDb { _,s ->
        val sid=session(s); val first=send(s,sid); s.checkpoint(first.requestId!!,1,"saved partial")
        val d=s.dataset(); val r=d.runs.single(); val floor=AiConversationCodec.requiredClosingReserve(r)
        for(n in listOf(0L,floor-1)) {
            val invalid=d.copy(runs=listOf(r.copy(reservedBytes=n)))
            failed { AiConversationCodec.decode(AiConversationCodec.encode(invalid)) }
        }
        val minimal=d.copy(runs=listOf(r.copy(reservedBytes=floor)))
        AiConversationCodec.validate(minimal)
        val ceiling=AiConversationCodec.encodedBytes(minimal)+floor
        val second=host(); try {
            val target=store(second,cap=ceiling); target.importIntoEmpty(AiConversationCodec.decode(AiConversationCodec.encode(minimal)))
            target.recoverInterrupted(); val result=target.dataset()
            assertEquals("PROCESS_INTERRUPTED",result.runs.single().terminalReason)
            assertEquals("saved partial",result.messages.single { it.role=="ASSISTANT" }.text)
            assertEquals(0L,AiConversationCodec.reserved(result)); assertTrue(AiConversationCodec.encodedBytes(result)<=ceiling)
        } finally { second.close() }
    } }
    @Test fun f1CheckpointCannotSpendClosingFloorAndCancelReceiptRollbackCanRetry()=runBlocking { withDb { _,s ->
        val sid=session(s); val first=send(s,sid); s.checkpoint(first.requestId!!,1,"part")
        val d=s.dataset(); val r=d.runs.single(); val floor=AiConversationCodec.requiredClosingReserve(r)
        val minimal=d.copy(runs=listOf(r.copy(reservedBytes=floor)))
        val exact=AiConversationCodec.encodedBytes(minimal)+floor
        val second=host(); try {
            val target=store(second,cap=exact,epoch=r.coordinatorEpoch); target.importIntoEmpty(minimal)
            failed { target.checkpoint(r.requestId,2,"z".repeat(32768)) }.also { assertEquals("CLOSING_RESERVE_EXHAUSTED",it.message) }
            assertEquals(minimal,target.dataset())
            val owner=RunOwner(QuestionOwner(q.id,q.contentHash),sid,r.requestId); val cmd=id()
            failed { store(second,cap=exact,epoch=r.coordinatorEpoch,hook={error("fault after writes")}).cancel(cmd,owner) }
            assertEquals(minimal,target.dataset())
            val receipt=target.cancel(cmd,owner); assertEquals(receipt,target.cancel(cmd,owner))
            val end=target.dataset(); assertEquals("CANCELLED",end.runs.single().status)
            assertEquals(0L,AiConversationCodec.reserved(end)); assertEquals(minimal.commands.size+1,end.commands.size)
            assertTrue(AiConversationCodec.encodedBytes(end)<=exact)
        } finally { second.close() }
    } }
    @Test fun f1MaximumEscapedBodyAndPartialUsageKeepClosingFloorAtFullCapacity()=runBlocking { withDb { db,s ->
        val sid=session(s); val first=send(s,sid); val d=s.dataset(); val r=d.runs.single()
        val exact=AiConversationCodec.encodedBytes(d)+AiConversationCodec.reserved(d)
        val target=store(db,cap=exact,epoch=r.coordinatorEpoch)
        target.checkpoint(first.requestId!!,1,"\u0001".repeat(1024*1024),usage=AiUsageEvidence(input=100,state=UsageState.PARTIAL))
        val checkpoint=target.dataset(); val current=checkpoint.runs.single()
        assertTrue(current.reservedBytes>=AiConversationCodec.requiredClosingReserve(current))
        assertTrue(AiConversationCodec.encodedBytes(checkpoint)+AiConversationCodec.reserved(checkpoint)<=exact)
        assertNull(current.estimatedUsd); assertEquals(100L,current.inputTokens)
        target.recoverInterrupted(); val end=target.dataset()
        assertEquals("\u0001".repeat(1024*1024),end.messages.single { it.role=="ASSISTANT" }.text)
        assertEquals(100L,end.runs.single().inputTokens); assertTrue(AiConversationCodec.encodedBytes(end)<=exact)
    } }
    @Test fun f1BackendComputesEstimateAndRestoreComparesDecimalValuesNotText()=runBlocking { withDb { _,s ->
        val sid=session(s); val first=send(s,sid)
        // Deliberately untrusted caller estimate is ignored by the backend.
        s.checkpoint(first.requestId!!,1,"done",RunStatus.COMPLETED,
            AiUsageEvidence(100,20,10,UsageState.KNOWN,"fixture-v1","0.30","0.006","1.20","0"))
        val d=s.dataset(); assertEquals("0.00005106",d.runs.single().estimatedUsd)
        assertEquals(d,AiConversationCodec.decode(AiConversationCodec.encode(d)))
        val trailing=d.copy(runs=d.runs.map { it.copy(estimatedUsd="0.0000510600") })
        AiConversationCodec.validate(AiConversationCodec.decode(AiConversationCodec.encode(trailing)))
        val noPrice=d.copy(runs=d.runs.map { it.copy(priceVersion=null,estimatedUsd=null) })
        AiConversationCodec.validate(noPrice)
        failed { AiConversationCodec.validate(noPrice.copy(runs=noPrice.runs.map { it.copy(estimatedUsd="0") })) }
    } }
    @Test fun f1EstimateRoundsOnlyFinalAmountHalfUpAtTwelveDecimalPlaces()=runBlocking { withDb { _,s ->
        val sid=session(s); val first=send(s,sid)
        s.checkpoint(first.requestId!!,1,"tiny",RunStatus.COMPLETED,
            AiUsageEvidence(1,0,0,UsageState.KNOWN,"fixture-v1","0.0000005","0","0","99"))
        assertEquals("0.000000000001",s.dataset().runs.single().estimatedUsd)
    } }

}
