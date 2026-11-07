package cn.embeddedquiz

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.locks.LockSupport
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AiRuntimeTest {
    private val app=ApplicationProvider.getApplicationContext<Context>()
    private val now=Instant.parse("2026-11-30T12:00:00Z").toEpochMilli()
    private val bank="""{"schemaVersion":1,"questions":[{"id":"q","stem":"original","kind":"SHORT","source":"fixture"}]}"""
    private val key="fixture-secret-never-persist"
    private fun id()=UUID.randomUUID().toString()
    private fun <T> ok(result:ResultOf<T>):T = when(result) { is ResultOf.Ok -> result.value;is ResultOf.Rejected -> error("unexpected rejection ${result.reason}") }
    private fun rejected(code:RejectCode,result:ResultOf<*>):Rejection {
        assertTrue("expected $code, got $result",result is ResultOf.Rejected)
        return (result as ResultOf.Rejected).reason.also { assertEquals(code,it.code) }
    }
    private fun response(content:String="answer",finish:String="stop",usage:JSONObject?=JSONObject().put("prompt_tokens",100)
        .put("completion_tokens",20).put("prompt_cache_hit_tokens",10),responseId:String="completion-1"):MockResponse {
        val message=JSONObject().put("role","assistant").put("content",content)
        val choice=JSONObject().put("index",0).put("finish_reason",finish).put("message",message)
        return MockResponse().setBody(JSONObject().put("id",responseId).put("choices",JSONArray().put(choice)).put("usage",usage ?: JSONObject.NULL).toString())
    }
    private class Gate {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val count=AtomicInteger()
        fun hook() { if(count.getAndIncrement()==0) { entered.countDown();check(release.await(15,TimeUnit.SECONDS)) } }
        suspend fun waitEntered()=withContext(Dispatchers.IO) { assertTrue(entered.await(8,TimeUnit.SECONDS)) }
    }
    private inner class Fixture(val db:QuizDatabase,val repo:QuizRepository,val server:MockWebServer,val service:AiConversationFacade,val q:Question) {
        val question=QuestionOwner(q.id,q.contentHash)
        suspend fun opened():SessionView {
            val created=ok(service.createSession(id(),question)).session
            return ok(service.open(created.sessionId)).session
        }
        fun send(s:SessionView,input:String="frozen",type:AiRequestType=AiRequestType.EXPLANATION)=SendCommand(id(),s.sessionId,question,s.contextRevision,null,null,input,emptySet(),"",type,DeepSeekClient.FLASH)
        suspend fun request()=withContext(Dispatchers.IO) { requireNotNull(server.takeRequest(8,TimeUnit.SECONDS)) }
        suspend fun idle()=withTimeout(12000) { ok(service.awaitIdle()) }
        suspend fun whenState(test:(AiConversationUiState)->Boolean)=withTimeout(12000) { service.state.first(test) }
    }
    private suspend fun fixture(configure:(AiRuntimeConfig)->AiRuntimeConfig={it},cleanup:()->Unit={},test:suspend Fixture.()->Unit) {
        val name="b-"+id().take(8)+".db";val db=QuizDatabase.open(app,name);val repo=QuizRepository(db)
        val server=MockWebServer();server.start()
        try {
            repo.importBank(bank)
            db.aiServices.configure(configure(AiRuntimeConfig({key},AiChatTransport(server.url("/chat/completions").toString()),{now},{ZoneId.of("UTC")})))
            val service=repo.aiConversations
            val ready=withTimeout(10000) { service.state.first { it.phase!=ServicePhase.INITIALIZING } }
            assertEquals(ServicePhase.READY,ready.phase)
            Fixture(db,repo,server,service,repo.question("q")!!).test()
        } finally {
            cleanup();withContext(NonCancellable) { withTimeout(15000) { db.aiLifecycle.closing().joinAll() } }
            db.close();app.deleteDatabase(name);server.shutdown()
        }
    }
    @Test fun frozenBodyReplayCacheAndFees()=runBlocking {
        val dispatch=CompletableDeferred<Unit>()
        fixture(configure={it.copy(beforeDispatch={dispatch.await()})},cleanup={dispatch.complete(Unit)}) {
            val s=opened();val cmd=send(s)
            val accepted=ok(service.send(cmd));assertEquals(0,server.requestCount)
            val row=db.ai().run(accepted.owner.requestId)!!
            assertEquals("PREPARING",row.status);assertFalse(row.networkDispatched)
            repo.dao.updateQuestions(BankCodec.parseBank(bank.replace("original","new version")))
            server.enqueue(response());dispatch.complete(Unit)
            val sent=request();assertEquals(AiSnapshotCodec.payload(row.contextSnapshot),sent.body.readUtf8());assertEquals("Bearer $key",sent.getHeader("Authorization"))
            idle();whenState { it.selected?.readOnly==true }
            assertEquals(accepted,ok(service.send(cmd)));assertEquals(1,server.requestCount)
            rejected(RejectCode.READ_ONLY,service.send(cmd.copy(commandId=id(),expectedContextRevision=2)))
            repo.dao.updateQuestions(BankCodec.parseBank(bank));whenState { it.selected?.readOnly==false }
            val cached=ok(service.send(cmd.copy(commandId=id(),expectedContextRevision=2)))
            val replay=db.ai().run(cached.owner.requestId)!!
            assertEquals("CACHE",replay.source);assertEquals("COMPLETED",replay.status);assertEquals(accepted.owner.requestId,replay.sourceRequestId)
            assertNull(replay.inputTokens);assertEquals("0",replay.estimatedUsd);assertFalse(replay.networkDispatched);assertEquals(1,server.requestCount)
            assertEquals(4,ok(service.messages(s.sessionId,null,20)).items.size)
            assertSame(service,QuizRepository(db).aiConversations)
            repo.dao.putAi(AiEntry("legacy","q",DeepSeekClient.FLASH,"legacy prompt","legacy reply",0,0,0,0.1,now))
            val costs=repo.aiCosts.observe();assertSame(costs,QuizRepository(db).aiCosts.observe())
            val month=withTimeout(8000) { costs.first { it.months.any { m -> m.cacheHits==1L && m.legacyRecords==1L } } }.months.single()
            assertEquals(BigDecimal("0.10005106"),month.knownEstimated);assertEquals(1L,month.networkCalls)
            assertEquals(0L,month.unknownEstimateCalls);assertEquals(0L,month.unknownUsageCalls)
            val bytes=repo.backup();assertFalse(bytes.contains(key));assertFalse(bytes.contains("Authorization"))
        }
    }
    @Test fun stopWaitsForExitAndRetryUsesOneUser()=runBlocking {
        val gate=Gate();val time=AtomicLong(now)
        fixture(configure={it.copy(now=time::get,transport=AiChatTransport(it.transport.url,gate::hook))},cleanup={gate.release.countDown()}) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));val s=opened();val first=ok(service.send(send(s)))
            val body=request().body.readUtf8();val cancelId=id();val stopped=ok(service.cancel(cancelId,first.owner))
            assertEquals(RunStatus.CANCELLED,stopped.terminal);assertTrue(stopped.cleanupPending)
            gate.waitEntered();assertEquals(TransportPhase.CLEANING,service.state.value.globalBusy!!.phase)
            val busy=rejected(RejectCode.BUSY,service.retry(RetryCommand(id(),first.owner)));assertEquals(first.owner,busy.busy!!.owner)
            assertEquals(stopped,ok(service.cancel(cancelId,first.owner)));assertEquals(1,server.requestCount)
            gate.release.countDown();idle();assertEquals(stopped,ok(service.cancel(cancelId,first.owner)))
            assertEquals(RunStatus.CANCELLED,rejected(RejectCode.ALREADY_TERMINAL,service.cancel(id(),first.owner)).terminal)
            time.set(Instant.parse("2026-12-01T12:00:00Z").toEpochMilli());server.enqueue(response(responseId="completion-2"))
            val retry=ok(service.retry(RetryCommand(id(),first.owner)));assertEquals(body,request().body.readUtf8());idle()
            assertEquals(first.userMessageId,retry.userMessageId);assertNotEquals(first.assistantMessageId,retry.assistantMessageId)
            assertEquals(first.owner.requestId,db.ai().run(retry.owner.requestId)!!.retryOf)
            val months=withTimeout(8000) { repo.aiCosts.observe().first { it.months.size==2 } }.months
            assertEquals(listOf(12,11),months.map { it.month });assertEquals(1L,months[1].unknownEstimateCalls);assertEquals(1L,months[1].networkCalls)
        }
    }
    @Test fun completionStillOwnsCleaningSlot()=runBlocking {
        val gate=Gate()
        fixture(configure={it.copy(transport=AiChatTransport(it.transport.url,gate::hook))},cleanup={gate.release.countDown()}) {
            server.enqueue(response());val s=opened();val cmd=send(s);val first=ok(service.send(cmd));request();gate.waitEntered()
            whenState { it.selectedRun?.status==RunStatus.COMPLETED && it.globalBusy?.phase==TransportPhase.CLEANING }
            assertEquals(RunStatus.COMPLETED,rejected(RejectCode.ALREADY_TERMINAL,service.cancel(id(),first.owner)).terminal)
            rejected(RejectCode.BUSY,service.send(cmd.copy(commandId=id(),expectedContextRevision=2)))
            assertEquals(first,ok(service.send(cmd)));assertEquals(1,server.requestCount)
            gate.release.countDown();idle()
            server.enqueue(response(responseId="completion-next"));val other=opened();ok(service.send(send(other)));request();idle()
            assertEquals(2,server.requestCount);assertEquals(2,db.ai().allRuns().count { it.source=="NETWORK" })
        }
    }
    @Test fun partialAndInvalidUsageAreNotFreeOrCompleteCache()=runBlocking { fixture {
        server.enqueue(response("partial result","length",JSONObject().put("prompt_tokens",10).put("completion_tokens",-1)))
        val s=opened();val first=ok(service.send(send(s)));request();idle()
        val old=db.ai().run(first.owner.requestId)!!;assertEquals("FAILED",old.status);assertEquals("INVALID",old.usageStatus);assertNull(old.estimatedUsd)
        assertEquals("OUTPUT_TRUNCATED",old.errorCode);assertEquals("partial result",db.ai().message(first.assistantMessageId)!!.text)
        assertTrue(db.ai().allCaches().isEmpty())
        server.enqueue(response(usage=JSONObject().put("prompt_tokens",100),responseId="completion-retry"))
        val next=ok(service.retry(RetryCommand(id(),first.owner)));request();idle()
        assertEquals("PARTIAL",db.ai().run(next.owner.requestId)!!.usageStatus);assertEquals(1,db.ai().allCaches().size)
        val m=withTimeout(8000) { repo.aiCosts.observe().first { it.months.any { x -> x.networkCalls==2L } } }.months.single()
        assertEquals(2L,m.unknownEstimateCalls);assertEquals(2L,m.unknownUsageCalls);assertEquals(BigDecimal.ZERO,m.knownEstimated)
    } }
    @Test fun badCredentialsNeverDispatch()=runBlocking { fixture(configure={it.copy(readKey={""})}) {
        val s=opened();val accepted=ok(service.send(send(s)));idle()
        val row=db.ai().run(accepted.owner.requestId)!!;assertFalse(row.networkDispatched);assertEquals("CREDENTIALS_UNAVAILABLE",row.errorCode)
        assertEquals(0,server.requestCount);assertTrue(withTimeout(8000) { repo.aiCosts.observe().first { !it.loading } }.months.isEmpty())
    } }
    @Test fun replacementWaitsAndInvalidatesCursorAndWriter()=runBlocking {
        val gate=Gate()
        fixture(configure={it.copy(transport=AiChatTransport(it.transport.url,gate::hook))},cleanup={gate.release.countDown()}) {
            opened();val s=opened();val cursor=ok(service.sessions("q",true,null,1)).next!!;val epoch=service.state.value.datasetEpoch
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));val cmd=send(s);val accepted=ok(service.send(cmd));request()
            val backup=repo.backup();val beforeCommit=CompletableDeferred<Unit>();val other=QuizRepository(db)
            other.beforeRestoreCommit={ beforeCommit.complete(Unit) }
            coroutineScope {
                val restore=async(Dispatchers.IO) { other.restoreFrom(ByteArrayInputStream(backup.toByteArray())) }
                try {
                    gate.waitEntered();whenState { it.phase==ServicePhase.REPLACING }
                    assertFalse(restore.isCompleted);assertFalse(beforeCommit.isCompleted)
                    rejected(RejectCode.REPLACING,service.send(cmd.copy(commandId=id())))
                } finally { gate.release.countDown() }
                withTimeout(10000) { restore.await() }
            }
            whenState { it.phase==ServicePhase.READY };assertNotEquals(epoch,service.state.value.datasetEpoch)
            rejected(RejectCode.STALE_CURSOR,service.sessions("q",true,cursor,1));assertNull(service.state.value.selected)
            assertEquals("PROCESS_INTERRUPTED",db.ai().run(accepted.owner.requestId)!!.terminalReason)
            assertEquals(accepted,ok(service.send(cmd)));assertEquals(1,server.requestCount)
            assertEquals("FAILED",db.ai().run(accepted.owner.requestId)!!.status)
        }
    }
    @Test fun failedCancelStillStopsAndBlocksNewWork()=runBlocking {
        val fail=AtomicBoolean(false)
        fixture(configure={it.copy(beforeCommit={if(fail.get()) error("injected disk failure")})}) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));val s=opened();val first=ok(service.send(send(s)));request()
            fail.set(true);rejected(RejectCode.STORAGE_FAILED,service.cancel(id(),first.owner))
            rejected(RejectCode.STORAGE_FAILED,withTimeout(12000) { service.awaitIdle() })
            rejected(RejectCode.STORAGE_FAILED,service.send(send(s)));assertEquals(1,server.requestCount)
            assertEquals("GENERATING",db.ai().run(first.owner.requestId)!!.status)
            service.shutdown();assertNotNull(withTimeout(10000) { service.awaitClosed() }.storageFault)
        }
    }
    @Test fun closingWaitsForTransportAndPersistsTerminal()=runBlocking {
        val gate=Gate()
        fixture(configure={it.copy(transport=AiChatTransport(it.transport.url,gate::hook))},cleanup={gate.release.countDown()}) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));val s=opened();val first=ok(service.send(send(s)));request()
            service.shutdown();gate.waitEntered()
            assertEquals(ServicePhase.CLOSING,service.state.value.phase)
            rejected(RejectCode.CLOSING,service.send(send(s)))
            val jobs=db.aiLifecycle.closing()
            assertTrue(jobs.any { !it.isCompleted });assertEquals(ServicePhase.CLOSING,service.state.value.phase)
            rejected(RejectCode.CLOSING,service.send(send(s)));assertTrue(db.isOpen)
            gate.release.countDown();withTimeout(10000) { jobs.joinAll() }
            assertNull(service.awaitClosed().storageFault);assertEquals(ServicePhase.CLOSED,service.state.value.phase)
            assertEquals("CANCELLED",db.ai().run(first.owner.requestId)!!.status)
        }
    }
    @Test fun draftCoalescesAndFlushesDuringClose()=runBlocking {
        val timer=CompletableDeferred<Unit>()
        fixture(configure={it.copy(waitDraft={ if(it>0) timer.await() },draftNow={0})},cleanup={timer.complete(Unit)}) {
            val created=ok(service.createSession(id(),question));val opened=ok(service.open(created.session.sessionId));val sid=opened.session.sessionId
            val first=DraftCommand(id(),sid,0,DraftToken(opened.editorEpoch,1),"first");assertEquals(1L,ok(service.saveDraft(first)).draftRevision)
            coroutineScope {
                val older=async { service.saveDraft(DraftCommand(id(),sid,1,DraftToken(opened.editorEpoch,2),"superseded")) }
                whenState { it.selected?.draft?.text=="superseded" && it.selected.draft.pending }
                val latest=async { service.saveDraft(DraftCommand(id(),sid,1,DraftToken(opened.editorEpoch,3),"latest")) }
                whenState { it.selected?.draft?.text=="latest" && it.selected.draft.pending }
                rejected(RejectCode.STALE_DRAFT,older.await());assertEquals(2L,ok(service.flushDraft(sid,2)).throughDraftRevision)
                assertEquals(2L,ok(latest.await()).draftRevision)
                assertEquals(2,db.ai().allCommands().count { it.kind=="DRAFT" })
                val final=async { service.saveDraft(DraftCommand(id(),sid,2,DraftToken(opened.editorEpoch,4),"save on close")) }
                whenState { it.selected?.draft?.pending==true };service.shutdown()
                assertEquals(3L,ok(final.await()).draftRevision);assertNull(withTimeout(10000) { service.awaitClosed() }.storageFault)
                assertEquals("save on close",db.ai().session(sid)!!.draftText)
            }
        }
    }
    @Test fun pagingArchiveAndImmutableReceipts()=runBlocking { fixture {
        val cmd=id();val original=ok(service.createSession(cmd,question)).session;val s=ok(service.open(original.sessionId))
        val renameId=id();val renamed=ok(service.rename(renameId,s.session.sessionId,0,"renamed"))
        val draft=DraftCommand(id(),s.session.sessionId,0,DraftToken(s.editorEpoch,1),"kept draft");ok(service.saveDraft(draft))
        assertEquals(original,ok(service.createSession(cmd,question)).session);assertEquals(renamed,ok(service.rename(renameId,original.sessionId,0,"renamed")))
        rejected(RejectCode.IDEMPOTENCY_CONFLICT,service.rename(renameId,original.sessionId,0,"different"))
        val next=ok(service.createSession(id(),question)).session
        val page1=ok(service.sessions("q",true,null,1));val page2=ok(service.sessions("q",true,page1.next,1))
        assertEquals(setOf(original.sessionId,next.sessionId),(page1.items+page2.items).map { it.sessionId }.toSet());assertNull(page2.next)
        rejected(RejectCode.STALE_CURSOR,service.messages(original.sessionId,page1.next,1))
        repo.dao.putAi(AiEntry("a","q",DeepSeekClient.FLASH,"old","unknown",0,0,0,0.0,now))
        repo.dao.putAi(AiEntry("b","q",DeepSeekClient.FLASH,"old","exact",1,2,0,0.0,now,contentHash=q.contentHash))
        assertNull(ok(service.legacy(HistoryFilter.UnknownHash("q"),null,10)).items.single().contentHash)
        assertEquals("b",ok(service.legacy(HistoryFilter.Exact(question),null,10)).items.single().cacheKey)
        repo.dao.updateQuestions(BankCodec.parseBank(bank.replace("original","revised")))
        assertTrue(ok(service.open(original.sessionId)).session.readOnly)
        assertTrue(ok(service.sessions("q",false,null,10)).items.isEmpty());assertEquals(2,ok(service.sessions("q",true,null,10)).items.size)
        rejected(RejectCode.READ_ONLY,service.saveDraft(draft.copy(commandId=id(),expectedDraftRevision=1)))
    } }
    @Test fun redirectDoesNotSendAgain()=runBlocking { fixture {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location",server.url("/redirect")))
        val s=opened();val accepted=ok(service.send(send(s)));request();idle()
        assertEquals("HTTP_307",db.ai().run(accepted.owner.requestId)!!.errorCode);assertEquals(1,server.requestCount)
        assertTrue(db.ai().allCaches().isEmpty());assertTrue(repo.dao.allAi().isEmpty())
    } }
    @Test fun providerTextLimitAndCredentialRedaction() {
        val raw=response("x".repeat(1024*1024-2)+"🚀",responseId=key).getBody()!!.readUtf8()
        val capped=AiChatTransport.decode(raw,key)
        assertEquals("OUTPUT_LIMIT",capped.error);assertFalse(capped.complete);assertNull(capped.responseId)
        assertEquals((1024*1024-2).toLong(),AiJson.utf8(capped.text))
        val clean=AiChatTransport.decode(response("answer $key").getBody()!!.readUtf8(),key)
        assertTrue(clean.complete);assertFalse(clean.text.contains(key))
        assertTrue(runCatching { AiChatTransport.decode("""{"id":"a","id":"b"}""") }.isFailure)
    }

    @Test fun newestWindowPagesBackwardsWithoutGaps()=runBlocking { fixture {
        server.enqueue(response());val s=opened();val cmd=send(s)
        ok(service.send(cmd));request();idle()
        repeat(11) { val current=service.state.value.selected!!;ok(service.send(cmd.copy(commandId=id(),expectedContextRevision=current.contextRevision))) }
        val first=service.state.value.firstMessages
        assertEquals((4L..23L).toList(),first.items.map { it.seq });assertNotNull(first.next)
        val older=ok(service.messages(s.sessionId,first.next,20))
        assertEquals((0L..3L).toList(),older.items.map { it.seq });assertNull(older.next)
        assertEquals(1,server.requestCount)
    } }
    @Test fun costCurrenciesRemainSeparate()=runBlocking { fixture {
        repo.dao.putAi(AiEntry("old-usd","q",DeepSeekClient.FLASH,"old","old",0,0,0,0.1,now))
        server.enqueue(response());val s=opened();val sent=ok(service.send(send(s)));request();idle()
        // A valid imported ledger may contain another currency; grouping must never convert it.
        val run=db.ai().run(sent.owner.requestId)!!;db.ai().updateRun(AiMeter.stored(run.copy(currency="EUR")))
        val result=withTimeout(8000) { repo.aiCosts.observe().first { it.months.size==2 } }
        assertEquals(mapOf("USD" to BigDecimal("0.1"),"EUR" to BigDecimal("0.00005106")),result.months.associate { it.currency to it.knownEstimated })
    } }
    @Test fun cutoverDrainsLegacyWriterBeforeNewSend() {
        val name="sw-"+id().take(8)+".db";val db=QuizDatabase.open(app,name);val repo=QuizRepository(db)
        val server=MockWebServer();server.start();val legacy=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        fun await(condition:()->Boolean) {
            val until=System.nanoTime()+12_000_000_000L
            while(!condition() && System.nanoTime()<until) { shadowOf(Looper.getMainLooper()).idle();LockSupport.parkNanos(1_000_000) }
            assertTrue(condition())
        }
        try {
            val q=runBlocking { repo.importBank(bank);repo.question("q")!! }
            val holder=AiStateHolder(legacy,repo,KeyVault(app),app.getSharedPreferences("old-ai-test",0),
                clientFactory={ http-> DeepSeekClient(http,server.url("/").toString()) },readKey={key},notify={})
            holder.beforeLegacyWrite={ withContext(NonCancellable) { entered.complete(Unit);release.await() } }
            server.enqueue(response("legacy reply"));holder.ask(q,"old",emptySet(),AiRequestType.FOLLOW_UP);await { entered.isCompleted }
            db.aiServices.configure(AiRuntimeConfig({key},AiChatTransport(server.url("/").toString()),{now},{ZoneId.of("UTC")}))
            val service=repo.aiConversations
            await { db.aiLifecycle.legacyRetired() && !holder.busy }
            assertEquals(ServicePhase.INITIALIZING,service.state.value.phase)
            holder.ask(q,"blocked",emptySet(),AiRequestType.FOLLOW_UP);assertTrue(holder.error.contains("旧AI入口已停止发送"))
            assertEquals(1,server.requestCount);release.complete(Unit);await { service.state.value.phase==ServicePhase.READY }
            server.enqueue(response("new reply"))
            runBlocking {
                val owner=QuestionOwner(q.id,q.contentHash);val s=ok(service.createSession(id(),owner)).session
                ok(service.send(SendCommand(id(),s.sessionId,owner,0,null,null,"new",emptySet(),"",AiRequestType.FOLLOW_UP,DeepSeekClient.FLASH)))
                withTimeout(12000) { ok(service.awaitIdle()) }
                assertTrue(repo.dao.allAi().isEmpty());assertEquals("COMPLETED",db.ai().allRuns().single().status)
            }
            assertEquals(2,server.requestCount)
        } finally {
            release.complete(Unit);legacy.cancel();val tasks=db.aiLifecycle.closing()
            await { tasks.all { it.isCompleted } && legacy.coroutineContext.job.isCompleted }
            db.close();app.deleteDatabase(name);server.shutdown()
        }
    }

    @Test fun serverRetryAfterCannotReplayOneShot()=runBlocking { fixture {
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After","0"))
        server.enqueue(response("unexpected automatic replay"))
        val s=opened();val accepted=ok(service.send(send(s)));request();idle()
        assertEquals(1,server.requestCount)
        assertEquals("HTTP_503",db.ai().run(accepted.owner.requestId)!!.errorCode)
        assertTrue(db.ai().allCaches().isEmpty())
    } }

}
