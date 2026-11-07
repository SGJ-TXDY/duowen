package cn.embeddedquiz

import androidx.room.withTransaction
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class AiRuntimeConfig(
    val readKey:()->String,
    val transport:AiChatTransport=AiChatTransport(),
    val now:()->Long=System::currentTimeMillis,
    val zone:()->ZoneId=ZoneId::systemDefault,
    val price:(String)->AiUsageEvidence=AiChatPrices::estimatePolicy,
    val beforeCommit:()->Unit={},
    val beforeDispatch:suspend ()->Unit={},
    val waitDraft:suspend (Long)->Unit={ delay(it) },
    val draftNow:()->Long={ System.nanoTime()/1_000_000 },
)

/** Shared by all Repository and ViewModel entry points for a single database owner. */
internal class AiServices(private val db:QuizDatabase) {
    private var config:AiRuntimeConfig?=null
    private var runtime:AiConversationRuntime?=null
    private var costStore:AiCostsStore?=null
    @Synchronized fun configure(value:AiRuntimeConfig) { check(runtime==null);config=value }
    @Synchronized fun conversations():AiConversationFacade = runtime ?: AiConversationRuntime(QuizRepository(db),
        config ?: AiRuntimeConfig(KeyVault(db.storageContext)::read)).also { runtime=it }
    @Synchronized fun costs():AiCostsStore = costStore ?: AiCostsStore(db).also { costStore=it }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class AiConversationRuntime(private val repo:QuizRepository,private val config:AiRuntimeConfig):AiConversationFacade {
    private val db=repo.db
    private val dao=db.ai()
    private val life=db.aiLifecycle
    private val actor=Mutex()
    private val owner=SupervisorJob()
    private val scope=CoroutineScope(owner+Dispatchers.IO)
    private val stopped=CompletableDeferred<ClosedReport>()
    private val closeLock=Any()
    @Volatile private var phaseValue=ServicePhase.INITIALIZING
    @Volatile private var closeRequested=false
    // Close admission is independent of a concurrent initialize/restore phase publication.
    private var phase:ServicePhase
        get()=when {
            phaseValue==ServicePhase.CLOSED || owner.isCompleted -> ServicePhase.CLOSED
            closeRequested -> ServicePhase.CLOSING
            else -> phaseValue
        }
        set(value) { phaseValue=value }
    @Volatile private var fault:StorageFault?=null
    @Volatile private var active:Slot?=null
    private var datasetEpoch=id()
    private var coordinatorEpoch=id()
    private var store=newStore()
    private val pages=AiPageReader(dao,repo) { datasetEpoch }
    private var selected:String?=null
    private var editor:String?=null
    private var activated=false
    private var initJob:Job?=null
    private var observation:Job?=null
    private val pending=linkedMapOf<String,PendingDraft>()
    private val lastDraftWrite=mutableMapOf<String,Long>()
    private val mutable=MutableStateFlow(AiConversationUiState(phase,null,null,AiPage(emptyList(),null),null,null,null,datasetEpoch,null))
    override val state:StateFlow<AiConversationUiState> = mutable.asStateFlow()

    init {
        owner.invokeOnCompletion {
            phase=ServicePhase.CLOSED;metadata();stopped.complete(ClosedReport(fault))
        }
        life.listen { paused,replaced -> if(paused) pauseForReplacement() else resumeAfterReplacement(replaced) }
        life.onClosing { shutdown();owner }
        initJob=scope.launch { initialize() }
    }
    private fun id()=UUID.randomUUID().toString()
    // Runtime's actor and the lifecycle hooks own these transactions. Draining a revoked run must
    // still be able to settle its existing row before replacement/close; it cannot accept commands.
    private fun newStore()=AiStore(db,dao,repo::question,config.now,config.zone,coordinatorEpoch,
        beforeCommit=config.beforeCommit,ownedByCoordinator=true,useCache=true)
    private class Slot(val owner:RunOwner,val epoch:String) {
        private val monitor=Any()
        private var accepting=true
        private var outcome:Pair<Long,AiChatResult>?=null
        private var seq=0L
        @Volatile var transport=TransportPhase.PREPARING
        @Volatile var exchange:AiChatTransport.Exchange?=null
        lateinit var job:Job
        fun dispatched(n:Long)=synchronized(monitor) { seq=n }
        fun reading()=synchronized(monitor) { if(accepting && transport!=TransportPhase.CLEANING) transport=TransportPhase.READING }
        fun received(value:AiChatResult)=synchronized(monitor) {
            if(accepting && outcome==null) { seq=Math.addExact(seq,1);outcome=seq to value }
        }
        fun cutoff()=synchronized(monitor) { accepting=false;outcome.also { outcome=null } }
        fun closingSeq()=synchronized(monitor) { accepting=false;Math.addExact(seq,1) }
        fun busy()=BusyRun(owner,transport)
    }
    private class PendingDraft(val command:DraftCommand,val started:Long,val reply:CompletableDeferred<ResultOf<DraftReceipt>>) {
        var job:Job?=null
    }
    private suspend fun initialize() {
        try {
            life.awaitAvailable();repo.ensureAiStorage();life.enableNewSender()
            actor.withLock {
                if(phase==ServicePhase.CLOSING || phase==ServicePhase.CLOSED) return
                activated=true;phase=ServicePhase.READY;publish()
                if(observation==null) observation=scope.launch {
                    merge(db.aiInvalidations("ai_sessions","ai_messages","ai_runs","questions").map { Unit },
                        db.quickNotesRepository.aiChanges.map { Unit }).conflate().catch {
                        actor.withLock { storageFault() }
                    }.collect {
                        actor.withLock {
                            if(phase==ServicePhase.READY && life.token()!=null) try { publish() }
                            catch(e:CancellationException) { throw e } catch(_:Exception) { storageFault() }
                        }
                    }
                }
            }
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { actor.withLock { if(phase!=ServicePhase.CLOSING && phase!=ServicePhase.CLOSED) storageFault() } }
    }
    private fun metadata() { mutable.update { it.copy(phase=phase,globalBusy=active?.busy(),storageFault=fault,datasetEpoch=datasetEpoch,activeEditorEpoch=editor) } }
    private fun storageFault() {
        fault=StorageFault("STORAGE_FAILED",true)
        if(phase!=ServicePhase.CLOSING && phase!=ServicePhase.CLOSED && phase!=ServicePhase.REPLACING) phase=ServicePhase.STORAGE_ERROR
        active?.let { it.cutoff();it.transport=TransportPhase.CLEANING;it.exchange?.cancel();it.job.cancel() }
        metadata()
    }
    private suspend fun publish(release:Slot?=null) {
        val sid=selected
        if(sid==null || phase!=ServicePhase.READY) { if(release!=null && owns(release)) active=null;metadata();return }
        val data=db.withTransaction {
            val row=dao.session(sid) ?: return@withTransaction null
            val stored=pages.session(row)
            val visible=pending[sid]?.command?.let { stored.copy(draft=stored.draft.copy(text=it.text,pending=true)) } ?: stored
            val first=pages.messages(sid,null,20,active!=null && release==null)
            val latest=dao.latestMessage(sid)?.takeIf { it.role=="ASSISTANT" }
                ?.let { dao.runForAssistant(it.messageId) }
            val run=latest?.let { pages.run(it,stored.question,!stored.readOnly && (active==null || release!=null) && it.status in setOf("FAILED","CANCELLED") && dao.completedAttempts(it.userMessageId)==0) }
            Triple(visible,first,run)
        }
        if(release!=null && owns(release)) active=null
        if(data==null) { clearSelection();return }
        mutable.value=AiConversationUiState(phase,sid,data.first,data.second,data.third,active?.busy(),fault,datasetEpoch,editor)
    }
    private fun clearSelection() {
        selected=null;editor=null
        mutable.value=AiConversationUiState(phase,null,null,AiPage(emptyList(),null),null,active?.busy(),fault,datasetEpoch,null)
    }
    private fun rejection(code:RejectCode,terminal:RunStatus?=null):Rejection {
        val recovery=when(code) {
            RejectCode.BUSY,RejectCode.REPLACING -> RecoveryAction.WAIT_IDLE
            RejectCode.NOT_READY -> RecoveryAction.REOPEN
            RejectCode.STALE_CONTEXT,RejectCode.STALE_SESSION,RejectCode.STALE_DRAFT,RejectCode.STALE_CURSOR -> RecoveryAction.RELOAD
            RejectCode.INVALID -> RecoveryAction.EDIT
            RejectCode.CAPACITY_EXCEEDED -> RecoveryAction.EXPORT_BACKUP
            RejectCode.STORAGE_FAILED -> RecoveryAction.RESTART
            else -> RecoveryAction.NONE
        }
        return Rejection(code,recovery in setOf(RecoveryAction.WAIT_IDLE,RecoveryAction.REOPEN,RecoveryAction.RELOAD,RecoveryAction.EDIT),
            recovery,active?.busy(),mutable.value.selected,terminal)
    }
    private fun phaseRejection():RejectCode? = when(phase) {
        ServicePhase.INITIALIZING -> RejectCode.NOT_READY
        ServicePhase.REPLACING -> RejectCode.REPLACING
        ServicePhase.STORAGE_ERROR -> RejectCode.STORAGE_FAILED
        ServicePhase.CLOSING -> RejectCode.CLOSING
        ServicePhase.CLOSED -> RejectCode.CLOSED
        ServicePhase.READY -> if(life.isClosing()) RejectCode.CLOSING else if(life.token()==null) RejectCode.REPLACING else null
    }
    private fun refuse(code:RejectCode):Nothing=throw AiStoreFailure(code)
    private fun invalidCode(e:Exception)=if(e.message in setOf("CAPACITY_EXCEEDED","MESSAGE_LIMIT","RESERVATION_EXCEEDED")) RejectCode.CAPACITY_EXCEEDED else RejectCode.INVALID
    private suspend fun <T> action(terminalRequest:String?=null,work:suspend ()->T):ResultOf<T> {
        phaseRejection()?.let { return ResultOf.Rejected(rejection(it)) }
        return try { life.operation {
            actor.withLock {
                phaseRejection()?.let { return@withLock ResultOf.Rejected(rejection(it)) }
                // Once a command is accepted, caller cancellation must not orphan its persisted run.
                withContext(NonCancellable) {
                    try { ResultOf.Ok(work()) }
                    catch(e:AiStoreFailure) {
                        val terminal=if(e.code==RejectCode.ALREADY_TERMINAL) terminalRequest?.let { dao.run(it)?.status?.let(RunStatus::valueOf) } else null
                        ResultOf.Rejected(rejection(e.code,terminal))
                    } catch(e:IllegalArgumentException) { ResultOf.Rejected(rejection(invalidCode(e))) }
                    catch(e:ArithmeticException) { ResultOf.Rejected(rejection(RejectCode.INVALID)) }
                    catch(e:CancellationException) { throw e }
                    catch(_:Exception) { storageFault();ResultOf.Rejected(rejection(RejectCode.STORAGE_FAILED)) }
                }
            }
        } } catch(e:CancellationException) { throw e }
        catch(_:Exception) { ResultOf.Rejected(rejection(if(life.isClosing()) RejectCode.CLOSING else RejectCode.STORAGE_FAILED)) }
    }
    private fun receipt(row:AiCommandRow)=JSONObject(row.receiptJson)
    private suspend fun session(sid:String)=dao.session(sid) ?: refuse(RejectCode.NOT_FOUND)
    private fun question(s:AiSessionRow)=s.owner
    override suspend fun createSession(commandId:String,question:QuestionOwner)=action {
        require(question.kind == AiOwnerKind.QUESTION)
        // Replay uses the immutable snapshot even if the current bank question changed.
        val prior=dao.command(commandId)
        if(prior!=null && prior.kind!="CREATE") refuse(RejectCode.IDEMPOTENCY_CONFLICT)
        val q=if(prior!=null) {
            val row=session(requireNotNull(prior.sessionId));if(question(row)!=question) refuse(RejectCode.IDEMPOTENCY_CONFLICT)
            AiSnapshotCodec.readQuestion(row.questionSnapshot,question)
        } else {
            val current=repo.question(question.questionId) ?: refuse(RejectCode.NOT_FOUND)
            if(current.contentHash!=question.contentHash) refuse(RejectCode.READ_ONLY);current
        }
        creationReceipt(store.create(commandId, q))
    }

    override suspend fun createDocumentSession(commandId: String, document: QuickNoteSummary) = action {
        val owner = QuestionOwner(document.id, document.contentHash, AiOwnerKind.QUICK_NOTE)
        AiJson.uuid(owner.questionId)
        AiJson.hash(owner.contentHash)
        val prior = dao.command(commandId)
        val snapshot = if (prior != null) {
            if (prior.kind != "CREATE") refuse(RejectCode.IDEMPOTENCY_CONFLICT)
            val row = session(requireNotNull(prior.sessionId))
            if (row.owner != owner) refuse(RejectCode.IDEMPOTENCY_CONFLICT)
            row.questionSnapshot
        } else {
            val summary = db.quickNotesRepository.aiSummary(owner.questionId) ?: refuse(RejectCode.NOT_FOUND)
            if (summary.contentHash != owner.contentHash) refuse(RejectCode.STALE_OWNER)
            AiSnapshotCodec.document(summary)
        }
        creationReceipt(store.createDocument(commandId, owner, snapshot))
    }

    private suspend fun creationReceipt(row: AiCommandRow): CreateReceipt {
        val json=receipt(row);val s=session(requireNotNull(row.sessionId))
        return CreateReceipt(pages.session(s.copy(title=json.getString("title"),sessionRevision=0,contextRevision=0,
            draftRevision=0,draftText="",lastEditorEpoch=null,lastLocalEditVersion=null)))
    }
    override suspend fun open(sessionId:String)=action {
        val old=selected;val oldEditor=editor
        if(old!=null && old!=sessionId) flushPending(old,propagate=true)
        flushPending(sessionId,propagate=true);val token=store.openEditor(sessionId)
        if(old!=null && old!=sessionId && oldEditor!=null) store.revokeEditor(old,oldEditor)
        selected=sessionId;editor=token;publish()
        OpenReceipt(requireNotNull(mutable.value.selected),token)
    }
    override suspend fun deselect()=action {
        selected?.let { sid-> flushPending(sid,propagate=true);editor?.let { store.revokeEditor(sid,it) } }
        clearSelection()
    }
    override suspend fun sessions(questionId:String,includeArchived:Boolean,cursor:PageCursor?,limit:Int)=action {
        db.withTransaction { pages.sessions(questionId,includeArchived,cursor,limit) }
    }
    override suspend fun documentSessions(document: QuickNoteSummary, includeArchived: Boolean, cursor: PageCursor?, limit: Int) = action {
        AiJson.uuid(document.id)
        AiJson.hash(document.contentHash)
        val owner = QuestionOwner(document.id, document.contentHash, AiOwnerKind.QUICK_NOTE)
        db.withTransaction { pages.sessions(owner, includeArchived, cursor, limit) }
    }
    override suspend fun messages(sessionId:String,cursor:PageCursor?,limit:Int)=action {
        db.withTransaction { pages.messages(sessionId,cursor,limit,active!=null) }
    }
    override suspend fun legacy(filter:HistoryFilter,cursor:PageCursor?,limit:Int)=action {
        db.withTransaction { pages.legacy(filter,cursor,limit) }
    }
    override suspend fun rename(commandId:String,sessionId:String,expectedSessionRevision:Long,title:String)=action {
        val j=receipt(store.rename(commandId,sessionId,expectedSessionRevision,title));publish()
        RenameReceipt(j.getString("sessionId"),j.getLong("sessionRevision"),j.getString("title"))
    }
    private fun draftReceipt(row:AiCommandRow):DraftReceipt = receipt(row).let {
        DraftReceipt(it.getString("sessionId"),it.getLong("draftRevision"),DraftToken(it.getString("editorEpoch"),it.getLong("localEditVersion")))
    }
    override suspend fun saveDraft(command:DraftCommand):ResultOf<DraftReceipt> {
        val queued=action {
            AiJson.uuid(command.commandId);AiJson.uuid(command.token.editorEpoch)
            require(command.token.localEditVersion>=0 && AiJson.utf8(command.text)<=AiConversationCodec.TEXT_BYTES)
            if(dao.command(command.commandId)!=null) return@action CompletableDeferred<ResultOf<DraftReceipt>>(ResultOf.Ok(draftReceipt(store.saveDraft(command))))
            pending.values.firstOrNull { it.command.commandId==command.commandId }?.let {
                if(it.command!=command) refuse(RejectCode.IDEMPOTENCY_CONFLICT)
                return@action it.reply
            }
            val s=session(command.sessionId)
            if(pages.session(s).readOnly) refuse(RejectCode.READ_ONLY)
            if(s.activeEditorEpoch!=command.token.editorEpoch || s.draftRevision!=command.expectedDraftRevision ||
                (s.lastEditorEpoch==command.token.editorEpoch && command.token.localEditVersion<=(s.lastLocalEditVersion ?: -1))) refuse(RejectCode.STALE_DRAFT)
            val old=pending[s.sessionId]
            if(old!=null && command.token.localEditVersion<=old.command.token.localEditVersion) refuse(RejectCode.STALE_DRAFT)
            if(old==null && pending.size>=32) refuse(RejectCode.BUSY)
            old?.job?.cancel();old?.reply?.complete(ResultOf.Rejected(rejection(RejectCode.STALE_DRAFT)))
            val now=config.draftNow();val item=PendingDraft(command,old?.started ?: now,CompletableDeferred())
            pending[s.sessionId]=item
            val quiet=lastDraftWrite[s.sessionId]?.let { now-it>=300 } ?: true
            val delayMs=if(old==null && (s.draftRevision==0L || quiet)) 0 else minOf(300L,(item.started+2000-now).coerceAtLeast(0))
            item.job=scope.launch {
                config.waitDraft(delayMs)
                actor.withLock { if(pending[s.sessionId]===item && phase==ServicePhase.READY && life.token()!=null) flushPending(s.sessionId,false) }
            }
            publish();item.reply
        }
        return when(queued) { is ResultOf.Ok -> queued.value.await();is ResultOf.Rejected -> queued }
    }
    /** Actor only. Pending text is not marked saved until Store's receipt is committed. */
    private suspend fun flushPending(sid:String,stopTimer:Boolean=true,propagate:Boolean=false) {
        val item=pending.remove(sid) ?: return
        if(stopTimer) item.job?.cancel()
        val result=try { ResultOf.Ok(draftReceipt(store.saveDraft(item.command))) }
        catch(e:AiStoreFailure) { ResultOf.Rejected(rejection(e.code)) }
        catch(e:IllegalArgumentException) { ResultOf.Rejected(rejection(invalidCode(e))) }
        catch(_:Exception) { storageFault();ResultOf.Rejected(rejection(RejectCode.STORAGE_FAILED)) }
        if(result is ResultOf.Ok) lastDraftWrite[sid]=config.draftNow()
        item.reply.complete(result)
        if(phase==ServicePhase.READY) try { publish() } catch(_:Exception) { storageFault() }
        if(propagate && result is ResultOf.Rejected) refuse(result.reason.code)
    }
    override suspend fun flushDraft(sessionId:String,throughDraftRevision:Long)=action {
        require(throughDraftRevision>=0)
        val actual=session(sessionId).draftRevision
        require(throughDraftRevision<=actual || pending[sessionId]!=null && throughDraftRevision==Math.addExact(actual,1))
        flushPending(sessionId,propagate=true)
        FlushReceipt(sessionId,store.flushDraft(sessionId,throughDraftRevision))
    }
    override suspend fun send(command:SendCommand):ResultOf<AcceptedReceipt> {
        val frozen=command.copy(choices=command.choices.toSet())
        return action {
            val replay=dao.command(frozen.commandId)!=null
            if(!replay) {
                if(active!=null) refuse(RejectCode.BUSY)
                require(AiJson.utf8(frozen.input)<=AiSnapshotCodec.INPUT_BYTES && AiJson.utf8(frozen.code)<=AiSnapshotCodec.INPUT_BYTES)
                session(frozen.sessionId);flushPending(frozen.sessionId,propagate=true)
            }
            accepted(store.accept(frozen),replay)
        }
    }
    override suspend fun retry(command:RetryCommand)=action(command.owner.requestId) {
        val replay=dao.command(command.commandId)!=null
        if(!replay) { if(active!=null) refuse(RejectCode.BUSY);session(command.owner.sessionId) }
        accepted(store.retry(command.commandId,command.owner),replay)
    }
    private suspend fun accepted(row:AiCommandRow,replay:Boolean):AcceptedReceipt {
        val r=requireNotNull(dao.run(requireNotNull(row.requestId)));val s=session(r.sessionId);val j=receipt(row)
        val runOwner=RunOwner(question(s),s.sessionId,r.requestId)
        val token=if(j.isNull("editorEpoch")) null else DraftToken(j.getString("editorEpoch"),j.getLong("localEditVersion"))
        if(!replay && r.status=="PREPARING") {
            val slot=Slot(runOwner,coordinatorEpoch);active=slot
            slot.job=scope.launch(start=CoroutineStart.ATOMIC) { execute(slot,r) }
        }
        publish()
        return AcceptedReceipt(row.commandId,runOwner,r.userMessageId,r.assistantMessageId,j.getLong("contextRevision"),j.getLong("draftRevision"),token,j.getBoolean("draftCleared"))
    }
    private fun owns(slot:Slot)=active===slot && slot.epoch==coordinatorEpoch
    private suspend fun execute(slot:Slot,row:AiRunRow) {
        var price=AiUsageEvidence()
        try {
            currentCoroutineContext().ensureActive()
            config.beforeDispatch()
            val key=try { config.readKey().trim() } catch(_:Exception) { "" }
            if(key.isEmpty() || key.length>4096 || key.any { it !in '!'..'~' }) {
                actor.withLock { if(owns(slot) && fault==null) finishWithoutReply(slot,"CREDENTIALS_UNAVAILABLE",RunStatus.FAILED) }
                return
            }
            val exchange=config.transport.prepare(AiSnapshotCodec.payload(row.contextSnapshot),key,{ slot.reading();metadata() },slot::received)
            val started=actor.withLock {
                if(!owns(slot) || phase!=ServicePhase.READY || life.token()==null || fault!=null) false
                else {
                    slot.exchange=exchange;price=config.price(row.model)
                    slot.dispatched(store.dispatched(row.requestId,price));slot.transport=TransportPhase.CONNECTING;publish();true
                }
            }
            if(!started) { exchange.cancel();return }
            try { exchange.receive() }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { slot.received(AiChatResult(error="NETWORK_FAILED")) }
            actor.withLock { if(owns(slot) && fault==null) settleReceived(slot,price) }
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { actor.withLock { if(owns(slot)) storageFault() } }
        finally {
            slot.exchange?.cancel()
            withContext(NonCancellable) {
                slot.exchange?.awaitClosed()
                actor.withLock {
                    if(owns(slot)) {
                        try { if(fault==null && dao.run(row.requestId)?.status in setOf("PREPARING","GENERATING")) finishWithoutReply(slot,"REQUEST_INTERRUPTED",RunStatus.CANCELLED) }
                        catch(_:Exception) { storageFault() }
                        // Do not publish idle until response exit and the final Room read are done.
                        try { publish(release=slot) } catch(_:Exception) { storageFault();active=null;metadata() }
                    }
                }
            }
        }
    }
    private suspend fun settleReceived(slot:Slot,price:AiUsageEvidence?=null) {
        val (seq,value)=slot.cutoff() ?: return
        val row=requireNotNull(dao.run(slot.owner.requestId))
        if(row.status !in setOf("PREPARING","GENERATING")) return
        val usage=value.usage.copy(priceVersion=price?.priceVersion ?: row.priceVersion,missRate=price?.missRate ?: row.missRate,
            hitRate=price?.hitRate ?: row.hitRate,outputRate=price?.outputRate ?: row.outputRate)
        store.checkpoint(row.requestId,seq,value.text,if(value.complete) RunStatus.COMPLETED else RunStatus.FAILED,usage,
            errorCode=value.error,finishReason=value.finish,providerResponseId=value.responseId)
        slot.transport=TransportPhase.CLEANING;metadata();publish()
    }
    private suspend fun finishWithoutReply(slot:Slot,code:String,status:RunStatus) {
        val row=requireNotNull(dao.run(slot.owner.requestId))
        if(row.status !in setOf("PREPARING","GENERATING")) return
        val message=requireNotNull(dao.message(row.assistantMessageId))
        store.checkpoint(row.requestId,slot.closingSeq(),message.text,status,errorCode=code)
        slot.transport=TransportPhase.CLEANING;metadata();publish()
    }
    override suspend fun cancel(commandId:String,owner:RunOwner)=action(owner.requestId) {
        val existing=dao.command(commandId)
        if(existing!=null) {
            val old=store.cancel(commandId,owner)
            return@action CancelReceipt(owner,RunStatus.valueOf(receipt(old).getString("status")),true)
        }
        AiJson.uuid(commandId)
        val s=dao.session(owner.sessionId) ?: refuse(RejectCode.STALE_OWNER)
        val row=dao.run(owner.requestId) ?: refuse(RejectCode.STALE_OWNER)
        if(question(s)!=owner.question || row.sessionId!=s.sessionId) refuse(RejectCode.STALE_OWNER)
        if(row.status !in setOf("PREPARING","GENERATING")) refuse(RejectCode.ALREADY_TERMINAL)
        val slot=active?.takeIf { it.owner==owner && owns(it) } ?: refuse(RejectCode.STALE_OWNER)
        slot.transport=TransportPhase.CLEANING;metadata()
        try {
            settleReceived(slot)
            store.cancel(commandId,owner);publish()
            CancelReceipt(owner,RunStatus.CANCELLED,true)
        } catch(e:AiStoreFailure) { throw e }
        catch(_:Exception) { storageFault();refuse(RejectCode.STORAGE_FAILED) }
        finally { slot.exchange?.cancel();slot.job.cancel() }
    }
    override suspend fun awaitIdle():ResultOf<IdleReceipt> {
        state.first { it.globalBusy==null }
        val blocked=phaseRejection();return if(blocked!=null) ResultOf.Rejected(rejection(blocked)) else ResultOf.Ok(IdleReceipt(state.value.datasetEpoch))
    }
    private suspend fun stopWork(reason:String) {
        val job=actor.withLock {
            pending.keys.toList().forEach { flushPending(it) }
            active?.let { slot ->
                slot.transport=TransportPhase.CLEANING;metadata()
                try { if(fault==null) { settleReceived(slot);finishWithoutReply(slot,reason,RunStatus.CANCELLED) } }
                catch(_:Exception) { storageFault() }
                finally { slot.cutoff();slot.exchange?.cancel();slot.job.cancel() }
                slot.job
            }
        }
        job?.join()
    }
    private suspend fun pauseForReplacement() {
        if(phase==ServicePhase.CLOSING || phase==ServicePhase.CLOSED) { awaitClosed();return }
        phase=ServicePhase.REPLACING;metadata();stopWork("DATASET_REPLACING")
    }
    private suspend fun resumeAfterReplacement(replaced:Boolean) {
        actor.withLock {
            if(phase==ServicePhase.CLOSING || phase==ServicePhase.CLOSED) return
            coordinatorEpoch=id();store=newStore();editor=null;lastDraftWrite.clear()
            if(replaced) { datasetEpoch=id();fault=null;clearSelection() }
            phase=if(fault!=null) ServicePhase.STORAGE_ERROR else if(activated) ServicePhase.READY else ServicePhase.INITIALIZING
            metadata()
            if(phase==ServicePhase.READY) try { publish() } catch(_:Exception) { storageFault() }
            else if(replaced && initJob?.isCompleted==true) initJob=scope.launch { initialize() }
        }
    }
    override fun shutdown() {
        synchronized(closeLock) {
            if(phase==ServicePhase.CLOSING || phase==ServicePhase.CLOSED) return
            closeRequested=true;phase=ServicePhase.CLOSING;metadata()
            scope.launch {
                try {
                    initJob?.cancelAndJoin();observation?.cancelAndJoin()
                    stopWork("OWNER_CLOSED")
                } catch(_:Exception) { fault=StorageFault("STORAGE_FAILED",true) }
                finally { owner.cancel() }
            }
        }
    }
    override suspend fun awaitClosed():ClosedReport=stopped.await()
}
