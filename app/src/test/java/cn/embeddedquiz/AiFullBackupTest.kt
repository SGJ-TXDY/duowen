package cn.embeddedquiz

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AiFullBackupTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private val bank="""{"schemaVersion":1,"questions":[{"id":"backup-q","kind":"SHORT","stem":"保留原题","source":"fixture","raw":" untouched raw \\n"}]}"""
    private fun host(name:String):AiFoundationTestDb=Room.databaseBuilder(context,AiFoundationTestDb::class.java,name).allowMainThreadQueries().addMigrations(AiConversationMigration, StickyNoteSourceMigration, AiDeletedCostMigration).build()
    private suspend fun fixture(name:String):AiFoundationTestDb {
        val old=AiLegacyV5TestDb.open(context,name)
        try {
            old.dao().addQuestions(BankCodec.parseBank(bank))
            old.dao().putProgress(Progress("backup-q",seen=1,notes="original note",code="int x;"))
            old.dao().putAi(AiEntry("old-empty","backup-q",DeepSeekClient.FLASH,"legacy prompt","old empty hash",10,2,0,0.1,1000))
            old.dao().putAi(AiEntry("old-hash","backup-q",DeepSeekClient.FLASH,"legacy prompt2","old known version",10,3,0,0.2,1001,contentHash="old-version"))
            old.dao().putStickyNote(LegacyStickyNote("note","original title","original body",1,2))
            old.dao().putSettings(LearningSettings(dailyGoal=42))
            old.dao().putPersonalAnswer(PersonalAnswer("backup-q","A","keep personal","a".repeat(64),1))
            old.dao().putPersonalMetadata(QuestionPersonal("backup-q","keep tags","中","",false))
            old.dao().addAttempt(Attempt(questionId="backup-q",timestamp=1,day=0,response="answer",correct=null,feedback="GOOD",mode="fixture",wasNew=true))
            val q=old.dao().question("backup-q")!!;old.dao().updateQuestions(listOf(q.copy(json=" \n"+q.json+"\n ")))
        } finally { old.close() }
        return host(name)
    }
    private fun store(db:AiFoundationTestDb)=AiStore(db,db.ai(),{ key -> db.old().question(key)?.let { HistoricalQuestionV3.read(it.id,it.json) } },now={1000L})
    private suspend fun addConversations(db:AiFoundationTestDb,pending:Boolean=true) {
        val s=store(db);val q=db.old().question("backup-q")!!.let { HistoricalQuestionV3.read(it.id,it.json) }
        s.create(id(),q) // zero-message session
        val draft=s.create(id(),q).sessionId!!;val editor=s.openEditor(draft)
        s.saveDraft(DraftCommand(id(),draft,0,DraftToken(editor,1),"draft only"))
        val failed=s.create(id(),q).sessionId!!
        val c=SendCommand(id(),failed,QuestionOwner(q.id,q.contentHash),0,null,null,"failed question",emptySet(),"",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH)
        val first=s.accept(c);s.checkpoint(first.requestId!!,1,"partial failed reply",RunStatus.FAILED)
        if(pending) {
            val next=s.retry(id(),RunOwner(QuestionOwner(q.id,q.contentHash),failed,first.requestId))
            s.checkpoint(next.requestId!!,1,"saved pending reply",usage=AiUsageEvidence(input=20,state=UsageState.PARTIAL))
        }
    }
    private suspend fun exported(codec:AiFullBackupCodec,source:AiFoundationTestDb):ByteArray {
        val out=ByteArrayOutputStream();val receipt=codec.export(source,{out})
        val bytes=out.toByteArray();assertEquals(bytes.size.toLong(),receipt.bytes)
        assertEquals(DeepSeekClient.hash(bytes.toString(Charsets.UTF_8)),receipt.sha256)
        return bytes
    }
    private suspend fun failed(action:suspend()->Unit) { assertTrue(runCatching { action() }.isFailure) }
    private suspend fun scoped(action:suspend(AiFoundationTestDb,AiFoundationTestDb,AiFullBackupCodec,File)->Unit) {
        val one="bs-${id().take(8)}.db";val two="bt-${id().take(8)}.db"
        println("AI_BACKUP_PATH originalCandidateLength=${context.getDatabasePath("backup-source-${id()}.db").absolutePath.length} shortLength=${context.getDatabasePath(one).absolutePath.length}")
        val dir=File(requireNotNull(System.getProperty("java.io.tmpdir")),"ai-bk-"+id().take(8));check(dir.mkdirs())
        val source=fixture(one);val target=fixture(two)
        target.openHelper.writableDatabase.execSQL("UPDATE progress SET notes='target note'")
        target.openHelper.writableDatabase.execSQL("UPDATE sticky_notes SET title='target title'")
        target.openHelper.writableDatabase.execSQL("UPDATE personal_answers SET notes='target personal'")
        target.openHelper.writableDatabase.execSQL("UPDATE question_personal SET tags='target tags'")
        target.openHelper.writableDatabase.execSQL("INSERT INTO exam_terminations VALUES ('preserve-target-exam','ABANDONED',NULL,NULL,NULL,NULL,NULL,1,1,NULL)")
        try { action(source,target,AiFullBackupCodec(context,dir),dir) }
        finally { source.close();target.close();context.deleteDatabase(one);context.deleteDatabase(two)
            check(dir.canonicalPath.startsWith(File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalPath));dir.deleteRecursively() }
    }
    private fun legacyRows(db:AiFoundationTestDb):Map<String,List<String>> = AiLegacyBackupCodec.tables.mapValues { (_,table) ->
        db.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { c -> buildList {
            while(c.moveToNext()) { val row=AiSqlAccess.record(c);if(table=="attempts") row.remove("id");add(AiLegacyBackupCodec.canonical(row)) }
        }.sorted() }
    }
    @Test fun versionFourRoundTripPreservesRawHistoryDraftsReceiptsAndInterruptsPendingRuns()=runBlocking { scoped { source,target,codec,dir ->
        addConversations(source);val before=store(source).dataset();val legacy=legacyRows(source)
        val bytes=exported(codec,source)
        codec.prepare(ByteArrayInputStream(bytes)).use { stage -> AiBackupTestRestorer(target).replace(stage) }
        assertEquals(legacy,legacyRows(target))
        target.openHelper.readableDatabase.query("SELECT status FROM exam_terminations WHERE sessionId='preserve-target-exam'").use { assertTrue(it.moveToFirst());assertEquals("ABANDONED",it.getString(0)) }
        val restored=store(target).dataset()
        assertEquals(before.sessions,restored.sessions);assertEquals(before.messages,restored.messages);assertEquals(before.commands,restored.commands)
        assertEquals(2,target.old().allAi().size);assertEquals(setOf("","old-version"),target.old().allAi().map { it.contentHash }.toSet())
        assertTrue(restored.runs.all { it.status=="FAILED" });assertEquals(0L,AiConversationCodec.reserved(restored))
        assertTrue(restored.runs.any { it.terminalReason=="PROCESS_INTERRUPTED" && it.inputTokens==20L && it.estimatedUsd==null })
        store(target).audit();assertTrue(dir.listFiles()!!.isEmpty())
        codec.prepare(ByteArrayInputStream(bytes)).use { AiBackupTestRestorer(target).replace(it) }
        assertEquals(before.runs.size,store(target).dataset().runs.size)
    } }
    @Test fun legacyOneTwoThreePreserveNewConversationsAndMissingLegacySectionsExactly()=runBlocking { scoped { source,target,codec,_ ->
        addConversations(target,pending=false);val conversations=store(target).dataset()
        val full=JSONObject(exported(codec,source).toString(Charsets.UTF_8))
        for(version in 1..3) {
            target.openHelper.writableDatabase.execSQL("UPDATE sticky_notes SET title='local title'")
            target.old().putSettings(LearningSettings(dailyGoal=99,notificationPermissionAsked=true))
            val old=JSONObject(full.toString()).asLegacyBackup(version)
            AiBackupStage.aiSections.keys.forEach(old::remove)
            if(version<2) old.remove("stickyNotes")
            if(version<3) listOf("learningSettings","personalAnswers","questionPersonal").forEach(old::remove)
            codec.prepare(ByteArrayInputStream(old.toString().toByteArray())).use { AiBackupTestRestorer(target).replace(it) }
            assertEquals(conversations,store(target).dataset())
            assertEquals(if(version<2) "local title" else "original title",target.old().allStickyNotes().single().title)
            assertEquals(if(version<3) 99 else 42,target.old().getSettings()!!.dailyGoal)
            assertTrue(target.old().getSettings()!!.notificationPermissionAsked)
            assertEquals(if(version<3) "target personal" else "keep personal",target.old().allPersonalAnswers().single().notes)
            assertEquals(if(version<3) "target tags" else "keep tags",target.old().allPersonalMetadata().single().tags)
        }
    } }
    @Test fun malformedVersionMissingSegmentDuplicateIdAndUnknownEnumNeverReplaceTarget()=runBlocking { scoped { source,target,codec,dir ->
        addConversations(source);val full=JSONObject(exported(codec,source).toString(Charsets.UTF_8))
        val before=legacyRows(target);val mutations=listOf<(JSONObject)->Unit>(
            { it.put("backupVersion",99) },{ it.remove("aiMessages") },
            { it.getJSONArray("progress").getJSONObject(0).put("seen",4294967296L) },
            { val a=it.getJSONArray("aiSessions");a.put(a.getJSONObject(0)) },
            { it.getJSONArray("aiRuns").getJSONObject(0).put("status","UNKNOWN_FINISH") },
            { it.getJSONArray("aiRuns").getJSONObject(0).put("reservedBytes",0).put("status","GENERATING").put("finishedAt",JSONObject.NULL) },
            { it.getJSONArray("aiCommands").getJSONObject(0).put("receiptJson","{}") },
            { it.getJSONArray("aiMessages").getJSONObject(0).put("sessionId",id()) }
        )
        for(mutate in mutations) { val bad=JSONObject(full.toString());mutate(bad)
            failed { codec.prepare(ByteArrayInputStream(bad.toString().toByteArray())).use { AiBackupTestRestorer(target).replace(it) } }
            assertEquals(before,legacyRows(target));assertEquals(AiDataset(),store(target).dataset());assertTrue(dir.listFiles()!!.isEmpty())
        }
    } }
    @Test fun targetReplacementFailureRollsBackLegacyAndAiAndCanRetryWithoutDuplicatingCosts()=runBlocking { scoped { source,target,codec,_ ->
        addConversations(source);addConversations(target,pending=false)
        val bytes=exported(codec,source);val old=legacyRows(target);val ai=store(target).dataset()
        codec.prepare(ByteArrayInputStream(bytes)).use { stage ->
            failed { AiBackupTestRestorer(target) { error("after all inserts") }.replace(stage) }
            assertEquals(old,legacyRows(target));assertEquals(ai,store(target).dataset())
            AiBackupTestRestorer(target).replace(stage)
        }
        assertEquals(2,target.old().allAi().size);store(target).audit()
    } }
    @Test fun reversedLongRetryChainRestoresByDependencyAndRejectsCycle()=runBlocking { scoped { source,target,codec,_ ->
        val s=store(source);val q=source.old().question("backup-q")!!.let { HistoricalQuestionV3.read(it.id,it.json) }
        val sid=s.create(id(),q).sessionId!!;var receipt=s.accept(SendCommand(id(),sid,QuestionOwner(q.id,q.contentHash),0,null,null,"chain",emptySet(),"",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH))
        repeat(24) { s.checkpoint(receipt.requestId!!,1,"failed $it",RunStatus.FAILED);receipt=s.retry(id(),RunOwner(QuestionOwner(q.id,q.contentHash),sid,receipt.requestId!!)) }
        s.checkpoint(receipt.requestId!!,1,"final",RunStatus.COMPLETED)
        val json=JSONObject(exported(codec,source).toString(Charsets.UTF_8));val rows=json.getJSONArray("aiRuns")
        json.put("aiRuns",JSONArray((0 until rows.length()).reversed().map { rows.getJSONObject(it) }))
        codec.prepare(ByteArrayInputStream(json.toString().toByteArray())).use { AiBackupTestRestorer(target).replace(it) }
        assertEquals(AiConversationCodec.encode(s.dataset()),AiConversationCodec.encode(store(target).dataset()))
        val bad=JSONObject(json.toString());val r=bad.getJSONArray("aiRuns").getJSONObject(0);r.put("retryOf",r.getString("requestId"))
        val before=store(target).dataset();failed { codec.prepare(ByteArrayInputStream(bad.toString().toByteArray())).close() };assertEquals(before,store(target).dataset())
    } }
    @Test fun failedInputOutputCloseCancellationAndDiskResourcesCleanTemporaryFilesAndPreserveTarget()=runBlocking { scoped { source,target,codec,dir ->
        val bytes=exported(codec,source);val before=legacyRows(target)
        val input=object:InputStream() { override fun read():Int=throw IOException("input fault") }
        failed { codec.prepare(input).close() };assertTrue(dir.listFiles()!!.isEmpty())
        val closeInput=object:ByteArrayInputStream(bytes) { override fun close() { super.close();throw IOException("input close fault") } }
        failed { codec.prepare(closeInput).close() };assertTrue(dir.listFiles()!!.isEmpty())
        val occupied=File(dir,"occupied");occupied.writeText("keep this file")
        var inputClosed=false
        val tracked=object:ByteArrayInputStream(bytes) { override fun close() { inputClosed=true;super.close() } }
        failed { AiFullBackupCodec(context,occupied).prepare(tracked).close() }
        assertTrue(inputClosed);assertEquals("keep this file",occupied.readText());occupied.delete()
        assertTrue(dir.listFiles()!!.isEmpty())
        failed { codec.prepare(ByteArrayInputStream(bytes)) { throw IOException("disk full") }.close() };assertTrue(dir.listFiles()!!.isEmpty())
        failed { codec.prepare(ByteArrayInputStream(bytes)) { throw CancellationException("cancelled input") }.close() };assertTrue(dir.listFiles()!!.isEmpty())
        val writeFault=object:OutputStream() { override fun write(v:Int) { throw IOException("write fault") } }
        failed { codec.export(source,{writeFault}) };assertTrue(dir.listFiles()!!.isEmpty())
        val closeFault=object:ByteArrayOutputStream() { override fun close() { super.close();throw IOException("close fault") } }
        failed { codec.export(source,{closeFault}) };assertTrue(dir.listFiles()!!.isEmpty())
        assertEquals(before,legacyRows(target))
    } }
    @Test fun inconsistentEstimateAndForgedSizeAndOverLimitAiAreRejectedInStaging()=runBlocking { scoped { source,target,codec,_ ->
        val s=store(source);val q=source.old().question("backup-q")!!.let { HistoricalQuestionV3.read(it.id,it.json) }
        val sid=s.create(id(),q).sessionId!!;val r=s.accept(SendCommand(id(),sid,QuestionOwner(q.id,q.contentHash),0,null,null,"price",emptySet(),"",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH))
        s.checkpoint(r.requestId!!,1,"priced",RunStatus.COMPLETED,AiUsageEvidence(100,20,0,UsageState.KNOWN,"fixture","0.3","0.006","1.2"))
        val full=JSONObject(exported(codec,source).toString(Charsets.UTF_8))
        val variants=listOf<(JSONObject)->Unit>(
            { it.getJSONArray("aiRuns").getJSONObject(0).put("estimatedUsd","0") },
            { it.getJSONArray("aiSessions").getJSONObject(0).put("encodedBytes",1) },
            { it.getJSONArray("aiSessions").getJSONObject(0).put("draftText","x".repeat(1024*1024+1)) },
            { it.getJSONArray("aiRuns").getJSONObject(0).put("billingDay",999999) }
        )
        for(mutate in variants) { val bad=JSONObject(full.toString());mutate(bad)
            failed { codec.prepare(ByteArrayInputStream(bad.toString().toByteArray())).use { AiBackupTestRestorer(target).replace(it) } }
            assertEquals(AiDataset(),store(target).dataset())
        }
    } }
    @Test fun invalidSourcePreflightNeverOpensDestinationOrTouchesExistingValidBackup()=runBlocking { scoped { source,_,codec,dir ->
        addConversations(source);val valid=exported(codec,source);val previous=File(dir,"previous-valid.json");previous.writeBytes(valid)
        source.openHelper.writableDatabase.execSQL("UPDATE ai_runs SET reservedBytes=0 WHERE status IN ('PREPARING','GENERATING')")
        var opened=false
        failed { codec.export(source,{opened=true;ByteArrayOutputStream()}) }
        assertFalse(opened);assertArrayEquals(valid,previous.readBytes())
        assertEquals(listOf("previous-valid.json"),dir.listFiles()!!.map { it.name });previous.delete()
    } }
    @Test fun copyFailureAndCancellationAfterPartialWritesDoNotReturnSuccess()=runBlocking { scoped { source,_,codec,dir ->
        val previous=File(dir,"previous-valid.json");previous.writeText("previous backup")
        var written=0
        val partial=object:OutputStream() { override fun write(v:Int) { if(written++>=128) throw IOException("partial copy fault") } }
        failed { codec.export(source,{partial}) };assertTrue(written>128)
        var cancelledBytes=0
        val cancelled=object:OutputStream() { override fun write(v:Int) { if(cancelledBytes++>=128) throw CancellationException("copy cancelled") } }
        failed { codec.export(source,{cancelled}) };assertTrue(cancelledBytes>128)
        assertEquals("previous backup",previous.readText());assertEquals(listOf("previous-valid.json"),dir.listFiles()!!.map { it.name });previous.delete()
    } }
    @Test fun cachedSourceFanoutKeepsOnlyOriginalNetworkUsageAndRestoresRestrictOrder()=runBlocking { scoped { source,target,codec,_ ->
        val store=store(source);val q=source.old().question("backup-q")!!.let { HistoricalQuestionV3.read(it.id,it.json) }
        val sid=store.create(id(),q).sessionId!!
        val c=SendCommand(id(),sid,QuestionOwner(q.id,q.contentHash),0,null,null,"cached request",emptySet(),"",AiRequestType.EXPLANATION,DeepSeekClient.FLASH)
        val receipt=store.accept(c)
        store.checkpoint(receipt.requestId!!,1,"network origin",RunStatus.COMPLETED,AiUsageEvidence(100,20,0,UsageState.KNOWN,"fixture","0.30","0.006","1.20"))
        val base=store.dataset();val origin=base.runs.single();val messages=base.messages.toMutableList();val runs=base.runs.toMutableList();val commands=base.commands.toMutableList()
        repeat(12) { n ->
            val commandId=id();val runId=id();val u=id();val a=id()
            messages.add(AiMessageRow(u,sid,2L+n*2,"USER","cached request","COMPLETE",1000))
            messages.add(AiMessageRow(a,sid,3L+n*2,"ASSISTANT","network origin","COMPLETE",1000))
            runs.add(origin.copy(requestId=runId,commandId=commandId,userMessageId=u,assistantMessageId=a,source="CACHE",sourceRequestId=origin.requestId,
                eventSeq=0,usageCutoffSeq=0,inputTokens=null,outputTokens=null,hitTokens=null,usageStatus="NOT_APPLICABLE",priceVersion=null,
                missRate=null,hitRate=null,outputRate=null,estimatedUsd="0"))
            val raw=JSONObject(base.commands.single { it.commandId==c.commandId }.receiptJson).put("requestId",runId).put("userMessageId",u).put("assistantMessageId",a).put("contextRevision",3L+n*2)
            commands.add(AiCommandRow(commandId,"SEND",DeepSeekClient.hash("fixture-$n"),AiJson.canonical(raw),1000,sid,runId))
        }
        val data=base.copy(sessions=base.sessions.map { it.copy(contextRevision=26) },messages=messages,runs=runs,commands=commands)
        // Replace only the fixture's AI tables using a validated wire file, not a production sender.
        val root=JSONObject(exported(codec,source).toString(Charsets.UTF_8));val encoded=JSONObject(AiConversationCodec.encode(data))
        AiBackupStage.aiSections.keys.forEach { root.put(it,encoded.getJSONArray(it)) }
        codec.prepare(ByteArrayInputStream(root.toString().toByteArray())).use { AiBackupTestRestorer(target).replace(it) }
        codec.prepare(ByteArrayInputStream(root.toString().toByteArray())).use { AiBackupTestRestorer(target).replace(it) }
        val restored=store(target).dataset();assertEquals(13,restored.runs.size)
        assertEquals(1,restored.runs.count { it.source=="NETWORK" });assertEquals(12,restored.runs.count { it.source=="CACHE" })
        val sum=restored.runs.mapNotNull { it.estimatedUsd?.toBigDecimal() }.fold(java.math.BigDecimal.ZERO,java.math.BigDecimal::add)
        assertEquals(0,sum.compareTo(java.math.BigDecimal("0.000054")));store(target).audit()
    } }
    @Test fun totalNewAiSegmentOver32MiBIsRejectedEvenWhenEveryFieldIsLegal()=runBlocking { scoped { source,target,codec,dir ->
        val base=JSONObject(exported(codec,source).toString(Charsets.UTF_8));base.remove("aiSessions")
        val q=source.old().question("backup-q")!!.let { HistoricalQuestionV3.read(it.id,it.json) }
        val file=File(dir,"oversized-ai.json");val draft="x".repeat(1024*1024)
        file.bufferedWriter().use { out ->
            out.write(base.toString().dropLast(1));out.write(",\"aiSessions\":[")
            repeat(32) { n ->
                if(n>0) out.write(",")
                val row=AiSessionRow(id(),q.id,q.contentHash,questionSnapshot=AiSnapshotCodec.question(q),title="capacity",createdAt=1000,updatedAt=1000,draftText=draft)
                out.write(AiJson.canonical(AiRowCodec.encode(row)))
            };out.write("]}")
        }
        val before=legacyRows(target)
        val rejected=runCatching { codec.prepare(file.inputStream()).use { AiBackupTestRestorer(target).replace(it) } }
        assertTrue(rejected.isFailure);assertEquals("CAPACITY_EXCEEDED",rejected.exceptionOrNull()!!.message)
        assertEquals(before,legacyRows(target));assertEquals(AiDataset(),store(target).dataset())
        assertEquals(listOf("oversized-ai.json"),dir.listFiles()!!.map { it.name });file.delete()
    } }
    @Test fun settingsUseSingletonOneAndPreserveOtherLocalRowsLikeLegacyRestore()=runBlocking { scoped { source,target,codec,_ ->
        source.old().putSettings(LearningSettings(id=0,dailyGoal=9));target.old().putSettings(LearningSettings(id=0,dailyGoal=77))
        val bytes=exported(codec,source)
        assertEquals(42,JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("learningSettings").getInt("dailyGoal"))
        codec.prepare(ByteArrayInputStream(bytes)).use { AiBackupTestRestorer(target).replace(it) }
        assertEquals(42,target.old().getSettings()!!.dailyGoal)
        target.openHelper.readableDatabase.query("SELECT dailyGoal FROM learning_settings WHERE id=0").use { assertTrue(it.moveToFirst());assertEquals(77,it.getInt(0)) }
    } }
    @Test fun fullBackupExceeding32MiBFromLegacyRowsStreamsAndRestoresWithoutAiCapConfusion()=runBlocking { scoped { source,target,codec,dir ->
        val sql=source.openHelper.writableDatabase
        source.runInTransaction {
            repeat(600) { n ->
                val raw=JSONObject().put("id","large-$n").put("kind","SHORT").put("source","fixture").put("stem","stem-$n "+"z".repeat(28*1024))
                AiSqlAccess.insert(sql,"questions",AiLegacyBackupCodec.validate("questions",raw) { null })
            }
        }
        source.old().putAi(AiEntry("large-old","backup-q",DeepSeekClient.FLASH,"old","legacy-"+"y".repeat(1024*1024+1),1,1,0,0.01,1000))
        val file=File(dir,"delivered-new.json")
        val factory=Class.forName("java.lang.management.ManagementFactory",true,ClassLoader.getSystemClassLoader())
        val threads=factory.getMethod("getThreadMXBean").invoke(null)
        val type=Class.forName("com.sun.management.ThreadMXBean",true,ClassLoader.getSystemClassLoader())
        type.getMethod("setThreadAllocatedMemoryEnabled",Boolean::class.javaPrimitiveType).invoke(threads,true)
        val allocated=type.getMethod("getTotalThreadAllocatedBytes")
        fun allocation()=(allocated.invoke(threads) as Number).toLong()
        val heapBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
        val allocationBefore=allocation();val start=System.nanoTime()
        val receipt=codec.export(source,{file.outputStream()});val exportedAt=System.nanoTime();assertTrue(receipt.bytes>32L*1024*1024)
        val stage=codec.prepare(file.inputStream());val preflightAt=System.nanoTime()
        stage.use { AiBackupTestRestorer(target).replace(it) };val replacedAt=System.nanoTime()
        assertEquals(601,target.old().questionCount());assertEquals(3,target.old().allAi().size)
        assertTrue(target.old().allAi().single { it.cacheKey=="large-old" }.response.length>1024*1024)
        println("AI_BACKUP_LARGE bytes=${receipt.bytes} exportNs=${exportedAt-start} preflightNs=${preflightAt-exportedAt} replaceNs=${replacedAt-preflightAt} allocatedBytes=${allocation()-allocationBefore} heapUsedBefore=$heapBefore heapUsedAfter=${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()} legacyQuestions=601 streaming=true hostProcessWindow=true nativePeakNotMeasured=true")
        file.delete();assertTrue(dir.listFiles()!!.isEmpty())
    } }
}
