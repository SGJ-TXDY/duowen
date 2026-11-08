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
    private val sourceLabels = mutableMapOf<String, String>()
    private var discussionGeneration = 0
    private var nextId = 0
    private var activeEpoch: String? = null

    val saves = mutableListOf<DraftCommand>()
    val flushes = mutableListOf<Pair<String, Long>>()
    var deselects = 0
        private set
    var renameRejection: Rejection? = null
    /** When set, the next open waits for it inside the backend (PLAN-1.7.3 late-open check). */
    var openGate: CompletableDeferred<Unit>? = null
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

    fun seed(
        question: QuestionOwner, title: String, readOnly: Boolean = false, draft: String = "",
        sourceLabel: String = question.questionId,
    ): SessionView {
        val id = "s${++nextId}"
        val s =
            SessionView(
                id, question, title, 0, 0, DraftView(draft, if (draft.isEmpty()) 0 else 1, false, null),
                readOnly, 1, "{}",
            )
        sessionsById[id] = s
        sourceLabels[id] = sourceLabel
        messagesBySession[id] = mutableListOf()
        touched(id)
        return s
    }

    fun session(id: String) = sessionsById.getValue(id)

    fun add(sessionId: String, role: MessageRole, text: String, run: RunView? = null): MessageView {
        val list = messagesBySession.getValue(sessionId)
        val m = MessageView("m${++nextId}", sessionId, (list.lastOrNull()?.seq ?: 0) + 1, role, text, TextState.COMPLETE, run)
        list += m
        touched(sessionId)
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
        openGate?.let {
            openGate = null
            it.await()
        }
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

    override suspend fun allSessions(cursor: PageCursor?, limit: Int): ResultOf<AiPage<AiDiscussionListItem>> {
        if (limit !in 1..100) return reject(RejectCode.INVALID)
        val prefix = "discussions:$discussionGeneration:"
        val before = if (cursor == null) Int.MAX_VALUE else {
            if (!cursor.opaque.startsWith(prefix)) return reject(RejectCode.STALE_CURSOR)
            cursor.opaque.removePrefix(prefix).toIntOrNull() ?: return reject(RejectCode.STALE_CURSOR)
        }
        val rows = saved().asReversed().asSequence()
            .map { it to it.sessionId.removePrefix("s").toInt() }
            .filter { (_, createdAt) -> createdAt < before }
            .take(limit + 1)
            .toList()
        val items = rows.take(limit).map { (s, createdAt) ->
            AiDiscussionListItem(s.sessionId, s.question, s.title, s.sessionRevision, createdAt.toLong(),
                sourceLabels.getValue(s.sessionId), s.readOnly)
        }
        val next = if (rows.size > limit) PageCursor(prefix + items.last().createdAt) else null
        return ResultOf.Ok(AiPage(items, next))
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
        ResultOf.Ok(CreateReceipt(seed(documentOwner(document), "新讨论 ${sessionsById.size + 1}", sourceLabel = document.title)))

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
        val normalizedTitle = title.trim()
        if (normalizedTitle.codePointCount(0, normalizedTitle.length) !in 1..80) return reject(RejectCode.INVALID)
        val updated = s.copy(title = normalizedTitle, sessionRevision = s.sessionRevision + 1)
        sessionsById[sessionId] = updated
        publish()
        return ResultOf.Ok(RenameReceipt(sessionId, updated.sessionRevision, normalizedTitle))
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
        if (busy?.owner == owner && owner.requestId == "r-busy") {
            _state.value = _state.value.copy(globalBusy = null)
            return ResultOf.Ok(CancelReceipt(owner, RunStatus.CANCELLED, false))
        }
        if (busy?.owner != owner) return ResultOf.Rejected(Rejection(RejectCode.ALREADY_TERMINAL, false, RecoveryAction.NONE))
        settle(owner) { it.copy(run = it.run!!.copy(status = RunStatus.CANCELLED, canRetry = true)) }
        return ResultOf.Ok(CancelReceipt(owner, RunStatus.CANCELLED, false))
    }
    override suspend fun awaitIdle(): ResultOf<IdleReceipt> {
        awaitIdleCalls++
        return ResultOf.Ok(IdleReceipt(_state.value.datasetEpoch))
    }

    // ---- 1.7.2 deletion ----
    val deletes = mutableListOf<Pair<String, QuestionOwner>>()
    var deleteRejection: Rejection? = null
    var awaitIdleCalls = 0
        private set

    /** A request running in [session] with no message behind it; stopping it just frees the slot. */
    fun busyOn(session: SessionView) {
        _state.value = _state.value.copy(globalBusy = BusyRun(RunOwner(session.question, session.sessionId, "r-busy"), TransportPhase.READING))
    }

    override suspend fun deleteSession(sessionId: String, owner: QuestionOwner): ResultOf<Unit> {
        deletes += sessionId to owner
        if (owner.kind == AiOwnerKind.TEMPORARY) return reject(RejectCode.INVALID)
        deleteRejection?.let { return ResultOf.Rejected(it) }
        val s = sessionsById[sessionId] ?: return ResultOf.Ok(Unit) // repeated delete is fine
        if (s.question != owner) return reject(RejectCode.STALE_OWNER)
        if (_state.value.globalBusy?.owner?.sessionId == sessionId) return reject(RejectCode.BUSY)
        sessionsById.remove(sessionId)
        sourceLabels.remove(sessionId)
        messagesBySession.remove(sessionId)
        discussionGeneration++
        if (_state.value.selectedSessionId == sessionId)
            _state.value = _state.value.copy(selectedSessionId = null, selected = null, firstMessages = AiPage(emptyList(), null))
        return ResultOf.Ok(Unit)
    }
    override fun shutdown() = Unit
    override suspend fun awaitClosed(): ClosedReport = ClosedReport(null)


    // ---- 1.7.4 workbench (PLAN-1.7.4, GPT's contract 3053565) ----

    /** Last activity per session: creation, then every message. Recent and groups order by it. */
    private val activity = HashMap<String, Int>()
    private var tick = 0

    /**
     * Where a discussion comes from. Tests give questions and documents a current version (or none)
     * here; by default every source is current under its seed label, and free chats are independent.
     */
    var sourceOf: (SessionView, String) -> AiDiscussionSource = { s, label -> defaultSource(s, label) }

    fun defaultSource(s: SessionView, label: String) =
        AiDiscussionSource(
            AiDiscussionGroupId(s.question.kind, s.question.questionId),
            when (s.question.kind) {
                AiOwnerKind.QUESTION -> AiDiscussionType.QUESTION
                AiOwnerKind.QUICK_NOTE -> AiDiscussionType.QUICK_NOTE
                AiOwnerKind.FREE -> AiDiscussionType.FREE
                AiOwnerKind.TEMPORARY -> AiDiscussionType.TEMPORARY
            },
            label,
            when {
                s.question.kind == AiOwnerKind.FREE -> AiSourceStatus.INDEPENDENT
                s.readOnly -> AiSourceStatus.UPDATED
                else -> AiSourceStatus.CURRENT
            },
            s.question.takeIf { it.kind != AiOwnerKind.FREE },
            references[s.sessionId],
        )

    private val references = HashMap<String, AiDiscussionReference>()
    val freeStarts = mutableListOf<AiFreeDiscussionCommand>()
    val temporaryCloses = mutableListOf<String>()
    var temporaryId: String? = null
        private set

    private fun touched(id: String) {
        activity[id] = ++tick
    }

    private fun saved() = sessionsById.values.filter { it.question.kind != AiOwnerKind.TEMPORARY }

    private fun source(s: SessionView) = sourceOf(s, sourceLabels.getValue(s.sessionId))

    private fun summary(s: SessionView): AiDiscussionSummary {
        val src = source(s)
        val item = AiDiscussionListItem(
            s.sessionId, s.question, s.title, s.sessionRevision, s.sessionId.removePrefix("s").toLong(), src.label,
            src.status == AiSourceStatus.UPDATED || src.status == AiSourceStatus.DELETED,
        )
        return AiDiscussionSummary(item, src, messagesBySession[s.sessionId]?.lastOrNull()?.let { activity.getValue(s.sessionId).toLong() })
    }

    private fun byActivity() = saved().sortedByDescending { activity[it.sessionId] ?: 0 }

    private fun <T> workbenchPage(tag: String, list: List<T>, cursor: PageCursor?, limit: Int): ResultOf<AiPage<T>> {
        if (limit !in 1..100) return reject(RejectCode.INVALID)
        val prefix = "$tag:$discussionGeneration:"
        val from =
            if (cursor == null) 0
            else cursor.opaque.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
                ?: return reject(RejectCode.STALE_CURSOR)
        val items = list.drop(from).take(limit)
        val next = if (from + items.size < list.size) PageCursor(prefix + (from + items.size)) else null
        return ResultOf.Ok(AiPage(items, next))
    }

    override suspend fun discussionGroups(type: AiDiscussionType?, cursor: PageCursor?, limit: Int): ResultOf<AiPage<AiDiscussionGroup>> {
        val groups =
            byActivity().groupBy { source(it).groupId }.map { (_, list) ->
                val head = source(list.first())
                // A group names its source as it is now; only a missing source stays marked.
                val shown = if (head.status == AiSourceStatus.UPDATED) head.copy(status = AiSourceStatus.CURRENT) else head
                AiDiscussionGroup(shown.copy(reference = null), list.size, activity.getValue(list.first().sessionId).toLong())
            }.filter { type == null || it.source.type == type }
        return workbenchPage("groups-$type", groups, cursor, limit)
    }

    override suspend fun groupDiscussions(groupId: AiDiscussionGroupId, cursor: PageCursor?, limit: Int): ResultOf<AiPage<AiDiscussionSummary>> =
        workbenchPage("group-$groupId", byActivity().filter { source(it).groupId == groupId }.map(::summary), cursor, limit)

    override suspend fun recentDiscussions(type: AiDiscussionType?, cursor: PageCursor?, limit: Int): ResultOf<AiPage<AiDiscussionSummary>> =
        workbenchPage("recent-$type", byActivity().map(::summary).filter { type == null || it.source.type == type }, cursor, limit)

    override suspend fun discussionSource(sessionId: String): ResultOf<AiDiscussionSource> {
        val s = sessionsById[sessionId] ?: return reject(RejectCode.NOT_FOUND)
        return ResultOf.Ok(source(s))
    }

    val searches = mutableListOf<String>()

    override suspend fun searchDiscussions(query: String, cursor: PageCursor?, limit: Int): ResultOf<AiPage<AiDiscussionSearchHit>> {
        val q = query.trim()
        searches += q
        if (q.codePointCount(0, q.length) !in 1..120) return reject(RejectCode.INVALID)
        val hits =
            byActivity().mapNotNull { s ->
                val src = source(s)
                val snippet =
                    when {
                        s.title.contains(q, ignoreCase = true) -> s.title
                        src.label.contains(q, ignoreCase = true) -> src.label
                        else -> messagesBySession[s.sessionId].orEmpty().firstOrNull { it.text.contains(q, ignoreCase = true) }?.text
                    }
                snippet?.let { AiDiscussionSearchHit(summary(s), it) }
            }
        return workbenchPage("search-$q", hits, cursor, limit)
    }

    val groupCreates = mutableListOf<AiDiscussionGroupId>()

    override suspend fun createGroupDiscussion(commandId: String, groupId: AiDiscussionGroupId): ResultOf<CreateReceipt> {
        groupCreates += groupId
        if (groupId.ownerKind == AiOwnerKind.FREE || groupId.ownerKind == AiOwnerKind.TEMPORARY) return reject(RejectCode.INVALID)
        val head = byActivity().firstOrNull { source(it).groupId == groupId } ?: return reject(RejectCode.NOT_FOUND)
        val src = source(head)
        val owner = src.currentOwner ?: return reject(RejectCode.NOT_FOUND)
        discussionGeneration++
        return ResultOf.Ok(CreateReceipt(seed(owner, "新讨论 ${sessionsById.size + 1}", sourceLabel = src.label)))
    }

    override suspend fun previewDiscussionReference(groupId: String, sessionId: String): ResultOf<AiDiscussionReference> {
        val s = sessionsById[sessionId] ?: return reject(RejectCode.NOT_FOUND)
        if (s.question.kind != AiOwnerKind.FREE || s.question.questionId != groupId) return reject(RejectCode.STALE_OWNER)
        return ResultOf.Ok(AiDiscussionReference(sessionId, s.title, messagesBySession.getValue(sessionId).size > 8))
    }

    /** The first send creates the free chat, then runs like any send. */
    override suspend fun startFreeDiscussion(command: AiFreeDiscussionCommand): ResultOf<AcceptedReceipt> {
        freeStarts += command
        if (_state.value.globalBusy != null) return reject(RejectCode.BUSY)
        if (command.input.isBlank()) return reject(RejectCode.INVALID)
        val group = command.groupId ?: "00000000-0000-4000-8000-0000000f${(1000 + freeStarts.size).toString().padStart(4, '0')}"
        val topic = command.groupId?.let { g -> saved().firstOrNull { it.question.questionId == g }?.let { sourceLabels.getValue(it.sessionId) } }
        val title = command.input.lineSequence().first { it.isNotBlank() }.trim().take(80)
        val s = seed(QuestionOwner(group, "free-$group", AiOwnerKind.FREE), title, sourceLabel = topic ?: title)
        command.referenceSessionId?.let { ref -> sessionsById[ref]?.let { references[s.sessionId] = AiDiscussionReference(ref, it.title, false) } }
        discussionGeneration++
        return send(SendCommand(command.commandId, s.sessionId, s.question, 0, null, null, command.input, emptySet(), "", AiRequestType.FOLLOW_UP, command.model))
    }

    /** Holds the next temporary open until completed (taken on use, like [openGate]). */
    var temporaryGate: CompletableDeferred<Unit>? = null

    override suspend fun openTemporaryDiscussion(): ResultOf<OpenReceipt> {
        temporaryGate?.let {
            temporaryGate = null
            it.await()
        }
        val id = temporaryId ?: seed(QuestionOwner("tmp", "tmp", AiOwnerKind.TEMPORARY), "临时对话", sourceLabel = "临时对话").sessionId.also { temporaryId = it }
        return open(id)
    }

    override suspend fun closeTemporaryDiscussion(sessionId: String): ResultOf<Unit> {
        temporaryCloses += sessionId
        if (temporaryId != sessionId) return ResultOf.Ok(Unit)
        val busy = _state.value.globalBusy
        if (busy?.owner?.sessionId == sessionId) _state.value = _state.value.copy(globalBusy = null)
        sessionsById.remove(sessionId)
        messagesBySession.remove(sessionId)
        sourceLabels.remove(sessionId)
        temporaryId = null
        if (_state.value.selectedSessionId == sessionId)
            _state.value = _state.value.copy(selectedSessionId = null, selected = null, firstMessages = AiPage(emptyList(), null), activeEditorEpoch = null)
        return ResultOf.Ok(Unit)
    }

    companion object {
        fun usage(input: Long?, output: Long?, estimated: String?, state: UsageState = UsageState.KNOWN) =
            UsageView(input, output, null, state, estimated?.let(::BigDecimal), "USD", estimated?.let { "p1" })
    }
}
