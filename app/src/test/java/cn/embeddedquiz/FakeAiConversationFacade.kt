package cn.embeddedquiz

import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory AiConversationFacade for UI tests. It follows the frozen contract rules the UI depends
 * on (revisions, editor epochs, stale draft/session rejections, cursor paging, read-only sessions);
 * it is not a backend. Sending only records the command and adds a pending reply; tests finish,
 * fail or stop it explicitly. firstMessages is the newest page and its cursor leads to older
 * messages, as AI_02B_CALLABLE_20261107 states.
 */
class FakeAiConversationFacade(private val pageSize: Int = 20) : AiConversationFacade {
    private val sessionsById = LinkedHashMap<String, SessionView>()
    private val messagesBySession = HashMap<String, MutableList<MessageView>>()
    private var nextId = 0
    private var activeEpoch: String? = null

    val saves = mutableListOf<DraftCommand>()
    val flushes = mutableListOf<Pair<String, Long>>()
    var deselects = 0
        private set
    var renameRejection: Rejection? = null
    var saveRejection: Rejection? = null
    var flushRejection: Rejection? = null
    /** When set, the next saveDraft waits for it before answering. */
    var saveGate: CompletableDeferred<Unit>? = null
    val sends = mutableListOf<SendCommand>()
    val cancels = mutableListOf<RunOwner>()
    val retries = mutableListOf<RetryCommand>()
    var sendRejection: Rejection? = null
    /** When set, the next send waits for it before it is processed. */
    var sendGate: CompletableDeferred<Unit>? = null
    val legacyEntries = mutableListOf<LegacyEntryView>()
    private val assistantFor = HashMap<String, String>() // requestId -> assistant messageId

    private val _state =
        MutableStateFlow(
            AiConversationUiState(
                phase = ServicePhase.READY, selectedSessionId = null, selected = null,
                firstMessages = AiPage(emptyList(), null), selectedRun = null, globalBusy = null,
                storageFault = null, datasetEpoch = "d1", activeEditorEpoch = null,
            )
        )
    override val state: StateFlow<AiConversationUiState> = _state

    fun setPhase(phase: ServicePhase) {
        _state.value = _state.value.copy(phase = phase)
    }

    fun seed(question: QuestionOwner, title: String, readOnly: Boolean = false, draft: String = ""): SessionView {
        val id = "s${++nextId}"
        val s =
            SessionView(
                id, question, title, 0, 0, DraftView(draft, if (draft.isEmpty()) 0 else 1, false, null),
                readOnly, 1, "{}",
            )
        sessionsById[id] = s
        messagesBySession[id] = mutableListOf()
        return s
    }

    fun session(id: String) = sessionsById.getValue(id)

    fun add(sessionId: String, role: MessageRole, text: String, run: RunView? = null): MessageView {
        val list = messagesBySession.getValue(sessionId)
        val m = MessageView("m${++nextId}", sessionId, (list.lastOrNull()?.seq ?: 0) + 1, role, text, TextState.COMPLETE, run)
        list += m
        publish()
        return m
    }

    fun replace(message: MessageView) {
        val list = messagesBySession.getValue(message.sessionId)
        list[list.indexOfFirst { it.messageId == message.messageId }] = message
        publish()
    }

    fun run(sessionId: String, question: QuestionOwner, usage: UsageView, status: RunStatus = RunStatus.COMPLETED, source: RunSource = RunSource.NETWORK) =
        RunView(RunOwner(question, sessionId, "r${++nextId}"), status, source, null, null, usage, null, false)

    private fun newestPage(id: String): AiPage<MessageView> {
        val list = messagesBySession.getValue(id)
        val page = list.takeLast(pageSize)
        val next = if (list.size > page.size) PageCursor("before:${page.first().seq}") else null
        return AiPage(page, next)
    }

    private fun publish() {
        val id = _state.value.selectedSessionId ?: return
        _state.value = _state.value.copy(selected = sessionsById.getValue(id), firstMessages = newestPage(id))
    }

    private fun reject(code: RejectCode) = ResultOf.Rejected(Rejection(code, false, RecoveryAction.NONE))

    override suspend fun createSession(commandId: String, question: QuestionOwner): ResultOf<CreateReceipt> =
        ResultOf.Ok(CreateReceipt(seed(question, "新讨论 ${sessionsById.size + 1}")))

    override suspend fun open(sessionId: String): ResultOf<OpenReceipt> {
        val s = sessionsById[sessionId] ?: return reject(RejectCode.NOT_FOUND)
        val epoch = "e${++nextId}"
        activeEpoch = epoch
        _state.value =
            _state.value.copy(
                selectedSessionId = sessionId, selected = s, firstMessages = newestPage(sessionId),
                activeEditorEpoch = epoch,
            )
        return ResultOf.Ok(OpenReceipt(s, epoch))
    }

    override suspend fun deselect(): ResultOf<Unit> {
        deselects++
        activeEpoch = null
        _state.value =
            _state.value.copy(
                selectedSessionId = null, selected = null, firstMessages = AiPage(emptyList(), null),
                activeEditorEpoch = null,
            )
        return ResultOf.Ok(Unit)
    }

    override suspend fun sessions(questionId: String, includeArchived: Boolean, cursor: PageCursor?, limit: Int): ResultOf<AiPage<SessionView>> {
        val all = sessionsById.values.filter { it.question.questionId == questionId && (includeArchived || !it.readOnly) }
        val from = cursor?.opaque?.removePrefix("at:")?.toInt() ?: 0
        val items = all.drop(from).take(limit)
        val next = if (from + items.size < all.size) PageCursor("at:${from + items.size}") else null
        return ResultOf.Ok(AiPage(items, next))
    }

    /** 1.7.1 document discussions: keyed by document id and content hash, like the backend. */
    fun documentOwner(document: QuickNoteSummary) = QuestionOwner(document.id, document.contentHash, AiOwnerKind.QUICK_NOTE)

    override suspend fun documentSessions(document: QuickNoteSummary, includeArchived: Boolean, cursor: PageCursor?, limit: Int) =
        sessions(document.id, includeArchived, cursor, limit)

    override suspend fun createDocumentSession(commandId: String, document: QuickNoteSummary): ResultOf<CreateReceipt> =
        ResultOf.Ok(CreateReceipt(seed(documentOwner(document), "新讨论 ${sessionsById.size + 1}")))

    override suspend fun messages(sessionId: String, cursor: PageCursor?, limit: Int): ResultOf<AiPage<MessageView>> {
        val list = messagesBySession[sessionId] ?: return reject(RejectCode.NOT_FOUND)
        val before = cursor?.opaque?.removePrefix("before:")?.toLong() ?: Long.MAX_VALUE
        val older = list.filter { it.seq < before }
        val page = older.takeLast(limit)
        val next = if (older.size > page.size) PageCursor("before:${page.first().seq}") else null
        return ResultOf.Ok(AiPage(page, next))
    }

    override suspend fun legacy(filter: HistoryFilter, cursor: PageCursor?, limit: Int): ResultOf<AiPage<LegacyEntryView>> {
        val id = (filter as HistoryFilter.AllVersions).questionId
        val all = legacyEntries.filter { it.questionId == id }
        val from = cursor?.opaque?.removePrefix("legacy:")?.toInt() ?: 0
        val items = all.drop(from).take(limit)
        val next = if (from + items.size < all.size) PageCursor("legacy:${from + items.size}") else null
        return ResultOf.Ok(AiPage(items, next))
    }

    override suspend fun rename(commandId: String, sessionId: String, expectedSessionRevision: Long, title: String): ResultOf<RenameReceipt> {
        renameRejection?.let { return ResultOf.Rejected(it) }
        val s = sessionsById[sessionId] ?: return reject(RejectCode.NOT_FOUND)
        if (s.sessionRevision != expectedSessionRevision) return reject(RejectCode.STALE_SESSION)
        if (title.codePointCount(0, title.length) !in 1..80) return reject(RejectCode.INVALID)
        val updated = s.copy(title = title, sessionRevision = s.sessionRevision + 1)
        sessionsById[sessionId] = updated
        publish()
        return ResultOf.Ok(RenameReceipt(sessionId, updated.sessionRevision, title))
    }

    override suspend fun saveDraft(command: DraftCommand): ResultOf<DraftReceipt> {
        saves += command
        saveGate?.let {
            saveGate = null
            it.await()
        }
        saveRejection?.let { return ResultOf.Rejected(it) }
        val s = sessionsById[command.sessionId] ?: return reject(RejectCode.NOT_FOUND)
        if (s.readOnly) return reject(RejectCode.READ_ONLY)
        if (command.token.editorEpoch != activeEpoch || command.expectedDraftRevision != s.draft.revision)
            return reject(RejectCode.STALE_DRAFT)
        val revision = s.draft.revision + 1
        sessionsById[s.sessionId] = s.copy(draft = DraftView(command.text, revision, false, command.token))
        publish()
        return ResultOf.Ok(DraftReceipt(s.sessionId, revision, command.token))
    }

    override suspend fun flushDraft(sessionId: String, throughDraftRevision: Long): ResultOf<FlushReceipt> {
        flushes += sessionId to throughDraftRevision
        flushRejection?.let { return ResultOf.Rejected(it) }
        val s = sessionsById[sessionId] ?: return reject(RejectCode.NOT_FOUND)
        if (throughDraftRevision > s.draft.revision) return reject(RejectCode.INVALID)
        return ResultOf.Ok(FlushReceipt(sessionId, s.draft.revision))
    }

    override suspend fun send(command: SendCommand): ResultOf<AcceptedReceipt> {
        sends += command
        sendGate?.let {
            sendGate = null
            it.await()
        }
        sendRejection?.let { return ResultOf.Rejected(it) }
        if (_state.value.globalBusy != null) return reject(RejectCode.BUSY)
        val s = sessionsById[command.sessionId] ?: return reject(RejectCode.NOT_FOUND)
        if (s.readOnly) return reject(RejectCode.READ_ONLY)
        if (s.contextRevision != command.expectedContextRevision) return reject(RejectCode.STALE_CONTEXT)
        if (command.requestType == AiRequestType.FOLLOW_UP && command.input.isBlank()) return reject(RejectCode.INVALID)
        // The draft is consumed only when the declared token is still the saved one.
        val clear = command.draftToken != null && command.draftToken == s.draft.savedToken &&
            command.savedDraftRevision == s.draft.revision
        val draft = if (clear) DraftView("", s.draft.revision + 1, false, s.draft.savedToken) else s.draft
        sessionsById[s.sessionId] = s.copy(contextRevision = s.contextRevision + 1, draft = draft)
        val user = add(s.sessionId, MessageRole.USER, command.input.ifBlank { command.requestType.label })
        val owner = RunOwner(s.question, s.sessionId, "r${++nextId}")
        val reply = pending(owner, null)
        return ResultOf.Ok(
            AcceptedReceipt(command.commandId, owner, user.messageId, reply.messageId, s.contextRevision + 1, draft.revision, command.draftToken, clear)
        )
    }

    private fun pending(owner: RunOwner, retryOf: String?): MessageView {
        val run = RunView(owner, RunStatus.GENERATING, RunSource.NETWORK, retryOf, null, usage(null, null, null, UsageState.UNKNOWN), null, false)
        val list = messagesBySession.getValue(owner.sessionId)
        val m = MessageView("m${++nextId}", owner.sessionId, list.last().seq + 1, MessageRole.ASSISTANT, "", TextState.EMPTY, run)
        list += m
        assistantFor[owner.requestId] = m.messageId
        _state.value = _state.value.copy(globalBusy = BusyRun(owner, TransportPhase.READING))
        publish()
        return m
    }

    private fun settle(owner: RunOwner, update: (MessageView) -> MessageView) {
        val id = assistantFor.getValue(owner.requestId)
        val m = messagesBySession.getValue(owner.sessionId).first { it.messageId == id }
        _state.value = _state.value.copy(globalBusy = null)
        replace(update(m))
    }

    /** Ends the running request with a complete answer. */
    fun finish(text: String) {
        val owner = _state.value.globalBusy!!.owner
        settle(owner) {
            it.copy(text = text, completion = TextState.COMPLETE, run = it.run!!.copy(status = RunStatus.COMPLETED, usage = usage(10, 20, "0.0001")))
        }
    }

    fun fail(code: String) {
        val owner = _state.value.globalBusy!!.owner
        settle(owner) { it.copy(run = it.run!!.copy(status = RunStatus.FAILED, errorCode = code, canRetry = true)) }
    }

    fun busyElsewhere(question: QuestionOwner) {
        _state.value = _state.value.copy(globalBusy = BusyRun(RunOwner(question, "elsewhere", "r-elsewhere"), TransportPhase.READING))
    }

    override suspend fun retry(command: RetryCommand): ResultOf<AcceptedReceipt> {
        retries += command
        if (_state.value.globalBusy != null) return reject(RejectCode.BUSY)
        val old = command.owner
        val oldId = assistantFor.getValue(old.requestId)
        messagesBySession.getValue(old.sessionId).first { it.messageId == oldId }.let { replace(it.copy(run = it.run!!.copy(canRetry = false))) }
        val owner = old.copy(requestId = "r${++nextId}")
        val reply = pending(owner, old.requestId)
        val s = sessionsById.getValue(old.sessionId)
        return ResultOf.Ok(AcceptedReceipt(command.commandId, owner, "", reply.messageId, s.contextRevision, s.draft.revision, null, false))
    }

    override suspend fun cancel(commandId: String, owner: RunOwner): ResultOf<CancelReceipt> {
        cancels += owner
        val busy = _state.value.globalBusy
        if (busy?.owner != owner) return ResultOf.Rejected(Rejection(RejectCode.ALREADY_TERMINAL, false, RecoveryAction.NONE))
        settle(owner) { it.copy(run = it.run!!.copy(status = RunStatus.CANCELLED, canRetry = true)) }
        return ResultOf.Ok(CancelReceipt(owner, RunStatus.CANCELLED, false))
    }
    override suspend fun awaitIdle(): ResultOf<IdleReceipt> = ResultOf.Ok(IdleReceipt(_state.value.datasetEpoch))
    override fun shutdown() = Unit
    override suspend fun awaitClosed(): ClosedReport = ClosedReport(null)

    companion object {
        fun usage(input: Long?, output: Long?, estimated: String?, state: UsageState = UsageState.KNOWN) =
            UsageView(input, output, null, state, estimated?.let(::BigDecimal), "USD", estimated?.let { "p1" })
    }
}
