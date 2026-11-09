package cn.embeddedquiz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * AI conversation UI on the frozen AiConversationFacade (AI_02_CONTRACT_FREEZE_20261106): session
 * list, title, messages, input draft, copy, "jump to the latest reply", send, one-tap requests
 * (including code review), stop, retry and the read-only pre-upgrade history. The question page
 * mounts it through its AI assistant entry (QuestionScreen).
 *
 * Everything the backend owns (selection, the first message page, run status, usage, busy, faults)
 * is read from facade.state. The controller keeps only what the contract leaves to the editor: the
 * text being typed, its localEditVersion, the last acknowledged draftRevision, pages the user loaded
 * on purpose, and the last rejection to show.
 */

/** Draft saving target from the contract: 300ms after the last keystroke, at most 2s while typing. */
internal const val AI_DRAFT_QUIET_MS = 300L
internal const val AI_DRAFT_MAX_WAIT_MS = 2_000L
internal const val AI_TITLE_MAX = 80
internal const val AI_PAGE_SIZE = 20

internal enum class DraftSave { SAVED, SAVING, FAILED }

/**
 * The backend selects one discussion for the whole app. Every page's open, and every release of a
 * selection, goes through this lock, so a late open that has to be released cannot clear the
 * discussion another page selected in between.
 */
private val aiSelectionLock = Mutex()

/** What a send freezes from the question page at the moment the user asks. */
internal data class AiSendContext(val choices: Set<String>, val code: String, val model: String)

/** One-tap requests; their user message is the type label, so the draft is left alone. */
internal val AI_QUICK_TYPES =
    listOf(AiRequestType.EXPLANATION, AiRequestType.SUGGESTED_ANSWER, AiRequestType.VARIANT)

/** The document and range a document discussion will cite, shown above the discussion. */
internal data class AiSourceContext(val title: String, val scope: String)

/** One send, as the controller has it ready; the source turns it into the backend command. */
internal data class AiSendRequest(
    val commandId: String,
    val session: SessionView,
    val type: AiRequestType,
    val input: String,
    val draftToken: DraftToken?,
    val savedDraftRevision: Long?,
)

/**
 * What the discussions are about. Questions use the 1.7.0 facade calls below; 1.7.1 Markdown
 * documents plug in through GPT's document entry (PLAN-1.7.1). Only listing, creating and sending
 * differ by source: opening, messages, drafts, rename, stop and retry are per session and shared.
 */
internal interface AiChatSource {
    val context: AiSourceContext?
        get() = null
    val quickTypes: List<AiRequestType>
    /** Tag on a discussion of an older version of this source (it is read only). */
    val staleLabel: String
    /** Pre-upgrade single-turn history exists only for questions. */
    val legacyQuestion: QuestionOwner?
        get() = null

    suspend fun sessions(facade: AiConversationFacade, cursor: PageCursor?, limit: Int): ResultOf<AiPage<SessionView>>
    suspend fun create(facade: AiConversationFacade, commandId: String): ResultOf<CreateReceipt>
    /** True for a discussion of this source's current version, the only kind a one-tap request may use. */
    fun current(session: SessionView): Boolean
    suspend fun send(facade: AiConversationFacade, request: AiSendRequest): ResultOf<AcceptedReceipt>
    /** The chapters or paragraphs a reply cited, when the backend reports them; null hides the block. */
    fun citation(message: MessageView): String? = null

    /** How much of the source the reply was given (e.g. outline and excerpts of a long document). */
    fun coverageNote(message: MessageView): String? = null
}

/** The 1.7.0 question discussions, unchanged. */
internal class QuestionAiSource(
    val owner: QuestionOwner,
    /** Programming questions also offer the code review request. */
    programming: Boolean = false,
    private val sendContext: () -> AiSendContext = { AiSendContext(emptySet(), "", "") },
) : AiChatSource {
    override val quickTypes =
        if (programming) AI_QUICK_TYPES + AiRequestType.CODE_REVIEW else AI_QUICK_TYPES
    override val staleLabel = "旧版题目"
    override val legacyQuestion = owner

    override suspend fun sessions(facade: AiConversationFacade, cursor: PageCursor?, limit: Int) =
        facade.sessions(owner.questionId, includeArchived = true, cursor, limit)

    override suspend fun create(facade: AiConversationFacade, commandId: String) = facade.createSession(commandId, owner)

    override fun current(session: SessionView) = session.question == owner

    override suspend fun send(facade: AiConversationFacade, request: AiSendRequest): ResultOf<AcceptedReceipt> {
        val ctx = sendContext()
        val s = request.session
        return facade.send(
            SendCommand(
                commandId = request.commandId, sessionId = s.sessionId, question = s.question,
                expectedContextRevision = s.contextRevision, draftToken = request.draftToken,
                savedDraftRevision = request.savedDraftRevision, input = request.input, choices = ctx.choices,
                code = if (request.type == AiRequestType.CODE_REVIEW) ctx.code else "",
                requestType = request.type, model = ctx.model,
            )
        )
    }
}

/** Saves one completed reply as a sticky note; the same command id always answers the same note. */
internal fun interface AiNoteSaver {
    suspend fun save(commandId: String, reply: MessageView): ResultOf<StickyNote>
}

/** Save state of one reply. The command id is kept so a retry or a second tap cannot make a copy. */
internal data class AiNoteSave(
    val commandId: String,
    val saving: Boolean = false,
    val note: StickyNote? = null,
    val error: String? = null,
)

/** Only a finished, complete answer is worth keeping; a partial or failed one is not offered. */
internal fun aiReplySavable(m: MessageView) =
    m.role == MessageRole.ASSISTANT && m.completion == TextState.COMPLETE &&
        m.run?.status == RunStatus.COMPLETED && m.text.isNotBlank()

@Stable
internal class AiChatController(
    /** Resolved on first use: taking the facade hands the send right to the new conversations. */
    facadeProvider: () -> AiConversationFacade,
    val source: AiChatSource,
    private val scope: CoroutineScope,
    /** Null where saving replies to sticky notes is not offered. */
    private val noteSaver: AiNoteSaver? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** The question page's discussions, as in 1.7.0. */
    constructor(
        facadeProvider: () -> AiConversationFacade,
        question: QuestionOwner,
        scope: CoroutineScope,
        programming: Boolean = false,
        sendContext: () -> AiSendContext = { AiSendContext(emptySet(), "", "") },
        noteSaver: AiNoteSaver? = null,
        newId: () -> String = { UUID.randomUUID().toString() },
    ) : this(facadeProvider, QuestionAiSource(question, programming, sendContext), scope, noteSaver, newId)

    val facade: AiConversationFacade by lazy(facadeProvider)

    val canSaveNotes: Boolean
        get() = noteSaver != null

    /** Keyed by reply messageId. */
    var noteSaves by mutableStateOf<Map<String, AiNoteSave>>(emptyMap())
        private set

    var legacy by mutableStateOf<List<LegacyEntryView>>(emptyList())
        private set
    private var legacyNext: PageCursor? = null
    var hasMoreLegacy by mutableStateOf(false)
        private set

    /** True from the tap until the backend answers, so one tap sends once. */
    var sending by mutableStateOf(false)
        private set

    var sessions by mutableStateOf<List<SessionView>>(emptyList())
        private set
    private var sessionsNext: PageCursor? = null
    var hasMoreSessions by mutableStateOf(false)
        private set

    /** The session from the last open/rename receipt; [current] prefers the live one in facade.state. */
    var opened by mutableStateOf<SessionView?>(null)
        private set
    private var editorEpoch: String? = null
    var draft by mutableStateOf("")
        private set
    private var localVersion by mutableLongStateOf(0L)
    private var savedVersion by mutableLongStateOf(0L)
    private var draftRevision = 0L
    var saveError by mutableStateOf<String?>(null)
        private set

    var older by mutableStateOf<List<MessageView>>(emptyList())
        private set
    private var olderNext: PageCursor? = null
    private var olderLoaded by mutableStateOf(false)

    /** Text of the last rejected action; cleared by the next action. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Confirmation of the last finished action (e.g. a deletion); cleared by the next action. */
    var info by mutableStateOf<String?>(null)
        private set

    /** The discussion being deleted, while its stop, cleanup and deletion run. */
    var deleting by mutableStateOf<String?>(null)
        private set

    private val saveLock = Mutex()

    /**
     * Each open takes a new generation; leaving (back, leave) and any newer open move it on. An open
     * still waiting for the service when that happens gives up, and one that already reached the
     * backend releases what it selected, so it keeps no editor right after the page is gone.
     */
    private var openGeneration = 0L
    private var quietJob: Job? = null
    private var maxJob: Job? = null

    val draftSave: DraftSave
        get() =
            when {
                saveError != null -> DraftSave.FAILED
                savedVersion == localVersion -> DraftSave.SAVED
                else -> DraftSave.SAVING
            }

    fun current(state: AiConversationUiState = facade.state.value): SessionView? {
        val mine = opened ?: return null
        return state.selected?.takeIf { it.sessionId == mine.sessionId } ?: mine
    }

    /** The backend's first page belongs to this session only while the facade has it selected. */
    fun messages(state: AiConversationUiState): List<MessageView> {
        val id = opened?.sessionId ?: return emptyList()
        val first = if (state.selectedSessionId == id) state.firstMessages.items else emptyList()
        val byId = LinkedHashMap<String, MessageView>()
        older.forEach { byId[it.messageId] = it }
        first.forEach { byId[it.messageId] = it } // the live page wins
        return byId.values.filter { it.sessionId == id }.sortedBy { it.seq }
    }

    fun hasOlder(state: AiConversationUiState): Boolean =
        if (olderLoaded) olderNext != null
        else opened?.sessionId == state.selectedSessionId && state.firstMessages.next != null

    // ---- session list ----

    suspend fun loadSessions(reset: Boolean) {
        val cursor = if (reset) null else sessionsNext ?: return
        when (val r = source.sessions(facade, cursor, AI_PAGE_SIZE)) {
            is ResultOf.Ok -> {
                sessions = if (reset) r.value.items else (sessions + r.value.items).distinctBy { it.sessionId }
                sessionsNext = r.value.next
                hasMoreSessions = r.value.next != null
            }
            is ResultOf.Rejected ->
                if (r.reason.code == RejectCode.STALE_CURSOR && !reset) loadSessions(reset = true)
                else notice = aiRejectionText(r.reason)
        }
    }

    fun moreSessions() = scope.launch { loadSessions(reset = false) }

    /**
     * Rereads the list from the top until it holds at least [keep] discussions and [include] (or
     * nothing more is left). On failure the current list stays as it is.
     */
    private suspend fun reloadSessions(keep: Int, include: String?) {
        var list = emptyList<SessionView>()
        var cursor: PageCursor? = null
        do {
            when (val r = source.sessions(facade, cursor, AI_PAGE_SIZE)) {
                is ResultOf.Ok -> {
                    list = (list + r.value.items).distinctBy { it.sessionId }
                    cursor = r.value.next
                }
                is ResultOf.Rejected -> {
                    notice = aiRejectionText(r.reason)
                    return
                }
            }
        } while (cursor != null && (list.size < keep || (include != null && list.none { it.sessionId == include })))
        sessions = list
        sessionsNext = cursor
        hasMoreSessions = cursor != null
    }

    /** Old single-turn records of this question (every version), read only; documents have none. */
    suspend fun loadLegacy(reset: Boolean) {
        val question = source.legacyQuestion ?: return
        val cursor = if (reset) null else legacyNext ?: return
        when (val r = facade.legacy(HistoryFilter.AllVersions(question.questionId), cursor, AI_PAGE_SIZE)) {
            is ResultOf.Ok -> {
                legacy = if (reset) r.value.items else (legacy + r.value.items).distinctBy { it.cacheKey }
                legacyNext = r.value.next
                hasMoreLegacy = r.value.next != null
            }
            is ResultOf.Rejected ->
                if (r.reason.code == RejectCode.STALE_CURSOR && !reset) loadLegacy(reset = true)
                else notice = aiRejectionText(r.reason)
        }
    }

    fun moreLegacy() = scope.launch { loadLegacy(reset = false) }

    /**
     * Taking the facade starts it; the first tap on a page (often code review) arrives while it is
     * still initializing, so every action waits for that to finish instead of being refused.
     */
    private suspend fun awaitStarted() {
        facade.state.first { it.phase != ServicePhase.INITIALIZING }
    }

    suspend fun refresh() {
        awaitStarted()
        loadSessions(reset = true)
        loadLegacy(reset = true)
    }

    fun create() =
        scope.launch {
            notice = null
            awaitStarted()
            createAndOpen()
        }

    private suspend fun createAndOpen(): Boolean =
        when (val r = source.create(facade, newId())) {
            is ResultOf.Ok -> {
                loadSessions(reset = true)
                openNow(r.value.session.sessionId)
                current()?.sessionId == r.value.session.sessionId
            }
            is ResultOf.Rejected -> {
                notice = aiRejectionText(r.reason)
                false
            }
        }

    /**
     * Makes sure an editable discussion of this source version is open: keeps the open one, else
     * opens the most recent editable one, else creates one.
     */
    private suspend fun ensureEditable(): Boolean {
        current()?.let { if (!it.readOnly) return true }
        loadSessions(reset = true)
        val target = sessions.firstOrNull { !it.readOnly && source.current(it) }
        if (target == null) return createAndOpen()
        openNow(target.sessionId)
        return current()?.let { !it.readOnly } == true
    }

    // ---- sending ----

    /** Sends the draft as a follow-up; the draft is saved first so the backend can consume it. */
    fun send() =
        scope.launch {
            if (draft.isBlank()) return@launch
            submit(AiRequestType.FOLLOW_UP, useDraft = true)
        }

    /** A one-tap request (explanation, suggested answer, variant, code review) in this question's discussion. */
    fun quick(type: AiRequestType) =
        scope.launch {
            if (sending) return@launch
            notice = null
            awaitStarted()
            if (ensureEditable()) submit(type, useDraft = false)
        }

    private suspend fun submit(type: AiRequestType, useDraft: Boolean) {
        if (sending) return
        sending = true
        try {
            notice = null
            var token: DraftToken? = null
            var savedRevision: Long? = null
            val text = if (useDraft) draft else ""
            if (useDraft) {
                quietJob?.cancel()
                maxJob?.cancel()
                // Text restored on open belongs to an older editor; save it under this one first.
                if (localVersion == savedVersion && savedVersion == 0L) localVersion = 1
                if (!saveNow()) return
                val epoch = editorEpoch ?: return
                token = DraftToken(epoch, savedVersion)
                savedRevision = draftRevision
            }
            val s = current() ?: return
            when (val r = source.send(facade, AiSendRequest(newId(), s, type, text, token, savedRevision))) {
                is ResultOf.Ok -> {
                    if (!useDraft || opened?.sessionId != s.sessionId) return
                    draftRevision = r.value.draftRevision
                    // Clear only what was sent: text typed after the tap stays and is saved later.
                    if (r.value.draftCleared && r.value.acceptedDraftToken == DraftToken(editorEpoch ?: "", localVersion)) {
                        draft = ""
                        savedVersion = localVersion
                    }
                }
                is ResultOf.Rejected -> notice = aiRejectionText(r.reason)
            }
        } finally {
            sending = false
        }
    }

    /** Stops the run that holds the send slot; the owner always comes from the backend state. */
    fun stop(owner: RunOwner) =
        scope.launch {
            when (val r = facade.cancel(newId(), owner)) {
                is ResultOf.Ok -> Unit
                is ResultOf.Rejected -> if (r.reason.code != RejectCode.ALREADY_TERMINAL) notice = aiRejectionText(r.reason)
            }
        }

    fun retry(run: RunView) =
        scope.launch {
            if (sending) return@launch
            sending = true
            try {
                notice = null
                when (val r = facade.retry(RetryCommand(newId(), run.owner))) {
                    is ResultOf.Ok -> Unit
                    is ResultOf.Rejected -> notice = aiRejectionText(r.reason)
                }
            } finally {
                sending = false
            }
        }

    fun open(sessionId: String): Job {
        val generation = ++openGeneration
        return scope.launch {
            awaitStarted()
            openNow(sessionId, generation)
        }
    }

    /**
     * Opens [sessionId] and sends [text] as its first message (the temporary chat, PLAN-1.7.4). The
     * text goes through the draft like any typed follow-up, so a refused send leaves it in the box.
     */
    fun openAndSend(sessionId: String, text: String): Job {
        val generation = ++openGeneration
        return scope.launch {
            awaitStarted()
            openNow(sessionId, generation)
            if (current()?.sessionId != sessionId || text.isBlank()) return@launch
            draft = text
            localVersion++
            submit(AiRequestType.FOLLOW_UP, useDraft = true)
        }
    }

    /**
     * Deletes one discussion from the list (PLAN-1.7.2). A request still running in it is stopped and
     * its cleanup awaited first; on failure the discussion and its draft stay and the reason shows.
     * Saved sticky notes are separate copies and stay readable.
     */
    fun delete(session: SessionView) =
        scope.launch {
            if (deleting != null) return@launch
            deleting = session.sessionId
            notice = null
            info = null
            try {
                awaitStarted()
                when (val r = aiDeleteDiscussion(facade, newId, session.sessionId, session.question)) {
                    is ResultOf.Ok -> {
                        info = "已删除讨论「${session.title}」。"
                        loadSessions(reset = true)
                    }
                    is ResultOf.Rejected -> notice = "讨论未删除：" + aiRejectionText(r.reason)
                }
            } finally {
                deleting = null
            }
        }

    /**
     * Deletes the discussion that is open (its ⋯ menu, PLAN-1.7.4), in the same order as from the
     * list: a running request is stopped and cleaned up first. The draft goes with the discussion, so
     * nothing is saved before; on success the editor is gone and [onDeleted] runs, on failure the
     * discussion stays open with its text and the reason shows.
     */
    fun deleteOpened(onDeleted: () -> Unit = {}) =
        scope.launch {
            val s = current() ?: return@launch
            if (deleting != null) return@launch
            deleting = s.sessionId
            notice = null
            info = null
            try {
                quietJob?.cancel()
                maxJob?.cancel()
                when (val r = aiDeleteDiscussion(facade, newId, s.sessionId, s.question)) {
                    is ResultOf.Ok -> {
                        // The backend dropped the selection with the discussion; a late open of it is void too.
                        openGeneration++
                        opened = null
                        editorEpoch = null
                        draft = ""
                        saveError = null
                        older = emptyList()
                        olderLoaded = false
                        info = "已删除讨论「${s.title}」。"
                        onDeleted()
                        loadSessions(reset = true)
                    }
                    is ResultOf.Rejected -> notice = "讨论未删除：" + aiRejectionText(r.reason)
                }
            } finally {
                deleting = null
            }
        }

    /** Saves a finished reply as a sticky note. Taps while saving or after success do nothing more. */
    fun saveToNote(reply: MessageView) =
        scope.launch {
            val saver = noteSaver ?: return@launch
            if (!aiReplySavable(reply)) return@launch
            val prior = noteSaves[reply.messageId]
            if (prior != null && (prior.saving || prior.note != null)) return@launch
            val commandId = prior?.commandId ?: newId()
            noteSaves = noteSaves + (reply.messageId to AiNoteSave(commandId, saving = true))
            val outcome =
                when (val r = saver.save(commandId, reply)) {
                    is ResultOf.Ok -> AiNoteSave(commandId, note = r.value)
                    is ResultOf.Rejected -> AiNoteSave(commandId, error = aiRejectionText(r.reason))
                }
            noteSaves = noteSaves + (reply.messageId to outcome)
        }

    private suspend fun openNow(sessionId: String, generation: Long = ++openGeneration) {
        if (generation != openGeneration) return
        notice = null
        if (opened != null && !leaveNow()) return
        aiSelectionLock.withLock {
            if (generation != openGeneration) return
            val r = facade.open(sessionId)
            if (generation != openGeneration) {
                // Left (or another discussion was asked for) while this open was on its way.
                if (r is ResultOf.Ok && facade.state.value.selectedSessionId == sessionId) facade.deselect()
                return
            }
            when (r) {
            is ResultOf.Ok -> {
                val s = r.value.session
                editorEpoch = r.value.editorEpoch
                draft = s.draft.text
                localVersion = 0
                savedVersion = 0
                draftRevision = s.draft.revision
                saveError = null
                older = emptyList()
                olderNext = null
                olderLoaded = false
                opened = s
            }
            is ResultOf.Rejected -> notice = aiRejectionText(r.reason)
            }
        }
    }

    // ---- open session ----

    /** Leaves the open discussion; an open still under way is abandoned and released. */
    fun back(): Job {
        openGeneration++
        return scope.launch { leaveNow() }
    }

    /** Leaves the open discussion, then [then] once its draft is saved and it is deselected. */
    fun leave(then: () -> Unit): Job {
        openGeneration++
        return scope.launch { if (leaveNow()) then() }
    }

    /**
     * Leaving saves the current text, waits for it to be durable, then deselects. If either step
     * fails the editor stays open with its text, so nothing typed is lost.
     */
    suspend fun leaveNow(): Boolean {
        val s = opened ?: return true
        quietJob?.cancel()
        maxJob?.cancel()
        if (!saveNow()) return false
        if (savedVersion > 0 && !s.readOnly) {
            when (val r = facade.flushDraft(s.sessionId, draftRevision)) {
                is ResultOf.Ok -> Unit
                is ResultOf.Rejected -> {
                    saveError = aiRejectionText(r.reason)
                    return false
                }
            }
        }
        // Another page may already have opened its own discussion; leave that one alone.
        val released =
            aiSelectionLock.withLock {
                if (facade.state.value.selectedSessionId == s.sessionId) facade.deselect() else ResultOf.Ok(Unit)
            }
        if (released is ResultOf.Rejected) {
            notice = aiRejectionText(released.reason)
            return false
        }
        opened = null
        editorEpoch = null
        older = emptyList()
        olderLoaded = false
        loadSessions(reset = true)
        return true
    }

    fun edit(text: String) {
        val s = current() ?: return
        if (s.readOnly || text == draft) return
        draft = text
        localVersion++
        saveError = null
        quietJob?.cancel()
        // Timers only wait; the save runs in its own job so cancelling a timer never cancels a save.
        quietJob =
            scope.launch {
                delay(AI_DRAFT_QUIET_MS)
                maxJob?.cancel()
                scope.launch { saveNow() }
            }
        if (maxJob?.isActive != true)
            maxJob =
                scope.launch {
                    delay(AI_DRAFT_MAX_WAIT_MS)
                    quietJob?.cancel()
                    scope.launch { saveNow() }
                }
    }

    /** Saves the text as it is now; one save at a time. Returns false if it was rejected. */
    private suspend fun saveNow(): Boolean =
        saveLock.withLock {
            val s = current() ?: return@withLock true
            val epoch = editorEpoch ?: return@withLock true
            if (s.readOnly) return@withLock true
            val version = localVersion
            if (version == savedVersion) return@withLock saveError == null
            val command = DraftCommand(newId(), s.sessionId, draftRevision, DraftToken(epoch, version), draft)
            when (val r = facade.saveDraft(command)) {
                is ResultOf.Ok -> {
                    // A late answer for another editor or session never touches this one.
                    if (opened?.sessionId != s.sessionId || r.value.token.editorEpoch != editorEpoch) return@withLock true
                    draftRevision = r.value.draftRevision
                    savedVersion = maxOf(savedVersion, r.value.token.localEditVersion)
                    true
                }
                is ResultOf.Rejected -> {
                    saveError = aiRejectionText(r.reason)
                    false
                }
            }
        }

    /** Renames the open discussion (the pencil in its title bar). */
    fun rename(title: String) =
        scope.launch {
            notice = null
            val s = current() ?: return@launch
            renameNow(s, title, listed = false)
        }

    /** True while a rename from the list is being saved; the editor takes no second tap. */
    var renaming by mutableStateOf(false)
        private set

    /**
     * Renames a discussion from the list without opening it. [onDone] runs only on success, so a
     * failed rename leaves the editor open with what was typed. A stale revision reloads the list:
     * the editor then shows the current name, and saving again uses the new revision.
     */
    fun rename(sessionId: String, title: String, onDone: () -> Unit) =
        scope.launch {
            if (renaming) return@launch
            renaming = true
            notice = null
            info = null
            try {
                awaitStarted()
                val target = current()?.takeIf { it.sessionId == sessionId } ?: sessions.firstOrNull { it.sessionId == sessionId }
                if (target != null && renameNow(target, title, listed = true)) onDone()
            } finally {
                renaming = false
            }
        }

    private suspend fun renameNow(s: SessionView, title: String, listed: Boolean): Boolean =
        when (val r = aiRename(facade, newId(), s.sessionId, s.sessionRevision, title)) {
            is ResultOf.Ok -> {
                opened = opened?.renamed(r.value)
                sessions = sessions.map { it.renamed(r.value) }
                true
            }
            is ResultOf.Rejected -> {
                notice = aiRenameRejectionText(r.reason, listed)
                // Renamed elsewhere (e.g. from 更多 › AI 讨论) since this list was read: fetch the current
                // title without dropping the pages already read, so the editor keeps its target.
                if (listed && r.reason.code == RejectCode.STALE_SESSION) reloadSessions(keep = sessions.size, include = s.sessionId)
                false
            }
        }

    fun loadOlder() =
        scope.launch {
            val state = facade.state.value
            val s = current(state) ?: return@launch
            val cursor = (if (olderLoaded) olderNext else state.firstMessages.next) ?: return@launch
            when (val r = facade.messages(s.sessionId, cursor, AI_PAGE_SIZE)) {
                is ResultOf.Ok -> {
                    older = (older + r.value.items).distinctBy { it.messageId }
                    olderNext = r.value.next
                    olderLoaded = true
                }
                is ResultOf.Rejected -> {
                    if (r.reason.code == RejectCode.STALE_CURSOR) {
                        older = emptyList()
                        olderNext = null
                        olderLoaded = false
                    }
                    notice = aiRejectionText(r.reason)
                }
            }
        }

    fun dismissNotice() {
        notice = null
        info = null
    }
}

/**
 * One rename, shared by a discussion's own title bar, its list and 更多 › AI 讨论. The title is
 * trimmed and checked here; a title out of range is rejected as INVALID without calling the backend.
 */
internal suspend fun aiRename(
    facade: AiConversationFacade,
    commandId: String,
    sessionId: String,
    expectedRevision: Long,
    title: String,
): ResultOf<RenameReceipt> {
    val t = title.trim()
    if (t.codePointCount(0, t.length) !in 1..AI_TITLE_MAX)
        return ResultOf.Rejected(Rejection(RejectCode.INVALID, false, RecoveryAction.EDIT))
    return facade.rename(commandId, sessionId, expectedRevision, t)
}

/**
 * Deletes one discussion (PLAN-1.7.2), shared by the source lists, the open discussion and the
 * workbench. A request still running in it is stopped and its cleanup awaited first. Saved sticky
 * notes are separate copies and stay; costs already incurred stay in the cost statistics.
 */
internal suspend fun aiDeleteDiscussion(
    facade: AiConversationFacade,
    newId: () -> String,
    sessionId: String,
    owner: QuestionOwner,
): ResultOf<Unit> {
    val busy = facade.state.value.globalBusy
    if (busy != null && busy.owner.sessionId == sessionId) {
        facade.cancel(newId(), busy.owner)
        facade.awaitIdle()
    }
    val r = facade.deleteSession(sessionId, owner)
    if (r is ResultOf.Rejected && r.reason.code == RejectCode.BUSY) {
        facade.awaitIdle()
        return facade.deleteSession(sessionId, owner)
    }
    return r
}

/** Confirms one deletion; [source] names where the discussion came from (the workbench shows it). */
@Composable
internal fun AiDeleteDialog(title: String, source: String?, running: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除讨论？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将删除「$title」的全部消息和草稿，无法恢复。")
                source?.let {
                    Text("来源：$it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai-delete-source"))
                }
                Text("已经保存到便签的内容仍可查看；已产生的 AI 费用仍计入费用统计。")
                if (running) Text("这条讨论正在生成回复，会先停止，收尾完成后再删除。", modifier = Modifier.testTag("ai-delete-running"))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("ai-delete-confirm")) {
                Text(if (running) "停止并删除" else "删除")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** [listed]: renamed from a list, which reloads on a stale revision so the next try can succeed. */
internal fun aiRenameRejectionText(r: Rejection, listed: Boolean): String =
    when {
        r.code == RejectCode.INVALID -> "标题需为 1～$AI_TITLE_MAX 个字。"
        listed && r.code == RejectCode.STALE_SESSION -> "讨论名称已在别处修改，上方已显示当前名称；确认后再保存。"
        else -> aiRejectionText(r)
    }

internal fun SessionView.renamed(r: RenameReceipt): SessionView =
    if (sessionId == r.sessionId) copy(title = r.title, sessionRevision = r.sessionRevision) else this

internal fun aiRejectionText(r: Rejection): String =
    when (r.code) {
        RejectCode.NOT_READY -> "AI 存储尚未就绪，请稍后再试。"
        RejectCode.BUSY -> "AI 正在处理另一条请求，完成后再试。"
        RejectCode.READ_ONLY -> "题目已更新，这段讨论只能查看和复制。"
        RejectCode.NOT_FOUND -> "找不到这段讨论，列表已过期，请返回刷新。"
        RejectCode.INVALID -> "内容不符合要求，未保存。"
        RejectCode.STALE_CONTEXT, RejectCode.STALE_SESSION -> "讨论已在别处更新，请返回列表后重新打开。"
        RejectCode.STALE_DRAFT -> "草稿已在别处更新，当前文字未保存；可先复制，再返回列表重新打开。"
        RejectCode.STALE_OWNER, RejectCode.STALE_ATTEMPT -> "这次请求已过期。"
        RejectCode.PROVIDER_CHANGED -> "原 AI 接口已更改或删除，请选择接口后重新发送。"
        RejectCode.STALE_CURSOR -> "数据已更新，已重新载入。"
        RejectCode.IDEMPOTENCY_CONFLICT -> "操作冲突，未执行。"
        RejectCode.ALREADY_TERMINAL -> "请求已经结束。"
        RejectCode.CAPACITY_EXCEEDED ->
            "AI 存储空间已满，不能再保存新内容；已保存的讨论不受影响。导出备份不会腾出空间。"
        RejectCode.STORAGE_FAILED -> "AI 存储写入失败，内容未保存。"
        RejectCode.REPLACING -> "正在恢复备份，完成后再试。"
        RejectCode.CLOSING, RejectCode.CLOSED -> "AI 存储正在关闭。"
    }

internal fun aiPhaseText(phase: ServicePhase): String? =
    when (phase) {
        ServicePhase.READY -> null
        ServicePhase.INITIALIZING -> "AI 存储准备中…"
        ServicePhase.REPLACING -> "正在恢复备份，讨论暂时只读。"
        ServicePhase.STORAGE_ERROR -> "AI 存储出错，讨论暂不可用。"
        ServicePhase.CLOSING, ServicePhase.CLOSED -> "AI 存储已关闭。"
    }

/** Token and cost line; an estimate is never shown as a bill, and unknown stays unknown. */
internal fun aiUsageText(run: RunView): String? {
    val u = run.usage
    if (run.source == RunSource.CACHE) return "缓存命中 · 本次不计费"
    return when (u.state) {
        UsageState.NOT_APPLICABLE -> null
        UsageState.UNKNOWN, UsageState.INVALID -> "用量未知 · 费用未知"
        UsageState.KNOWN, UsageState.PARTIAL -> {
            val tokens = "输入 ${u.input ?: "?"} / 输出 ${u.output ?: "?"} tokens"
            val cost = u.estimated?.let { "估算 ${aiMoney(it, u.currency)}" } ?: "费用未知"
            val partial = if (u.state == UsageState.PARTIAL) " · 用量不完整" else ""
            "$tokens · $cost$partial"
        }
    }
}

internal fun aiMoney(amount: BigDecimal, currency: String): String {
    val plain = amount.setScale(6, RoundingMode.HALF_UP).toPlainString()
    return if (currency == "USD") "$$plain" else "$plain $currency"
}

internal fun aiRunStatusText(run: RunView): String? =
    when (run.status) {
        RunStatus.PREPARING -> "准备中…"
        RunStatus.GENERATING -> "正在生成…"
        RunStatus.COMPLETED -> null
        RunStatus.CANCELLED -> "已取消"
        RunStatus.FAILED -> if (run.errorCode == "PROVIDER_CHANGED") "原 AI 接口已更改，请重新发送"
            else "失败" + (run.errorCode?.let { "（$it）" } ?: "")
    }

@Composable
internal fun rememberAiChatController(
    facade: AiConversationFacade,
    question: QuestionOwner,
    programming: Boolean = false,
    sendContext: () -> AiSendContext = { AiSendContext(emptySet(), "", "") },
    noteSaver: AiNoteSaver? = null,
): AiChatController {
    val scope = rememberCoroutineScope()
    return remember(facade, question) { AiChatController({ facade }, question, scope, programming, sendContext, noteSaver) }
}

@Composable
internal fun rememberAiChatController(facade: AiConversationFacade, source: AiChatSource, noteSaver: AiNoteSaver? = null): AiChatController {
    val scope = rememberCoroutineScope()
    return remember(facade, source) { AiChatController({ facade }, source, scope, noteSaver) }
}

/** The whole conversation page: the session list, or the open session with its messages and draft. */
@Composable
internal fun AiChatScreen(
    controller: AiChatController,
    modifier: Modifier = Modifier,
    /** Leaves the conversation page from its list; back inside a session returns to the list. */
    onClose: (() -> Unit)? = null,
    /** Opens a note saved from a reply; null hides the link (the saved message still shows). */
    onOpenNote: ((StickyNote) -> Unit)? = null,
    /**
     * Set when the page shows one discussion by itself (更多 › AI 讨论): its source's list is not
     * shown, and leaving the discussion (after its draft is saved) calls this instead.
     */
    onLeaveSession: (() -> Unit)? = null,
    /** Shown under the title of the open discussion in place of the source's own context card. */
    sourceHeader: (@Composable (SessionView) -> Unit)? = null,
    /** Names the source in the delete confirmation. */
    deleteSource: String? = null,
    /** Runs after the open discussion was deleted from its ⋯ menu; the source list shows otherwise. */
    onDeleted: (() -> Unit)? = null,
    /** False for a temporary chat: nothing to rename or delete, it is discarded on leaving. */
    manageable: Boolean = true,
) {
    val state by controller.facade.state.collectAsState()
    val session = controller.current(state)
    LaunchedEffect(controller) { if (onLeaveSession == null) controller.refresh() }
    val leaveSession: () -> Unit = {
        if (onLeaveSession != null) controller.leave(onLeaveSession) else controller.back()
    }
    BackHandler(enabled = session != null || onClose != null || onLeaveSession != null) {
        when {
            session != null -> leaveSession()
            onLeaveSession != null -> onLeaveSession()
            else -> onClose?.invoke()
        }
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        aiPhaseText(state.phase)?.let { InlineNote(it, Modifier.testTag("ai-phase")) }
        state.storageFault?.let {
            ErrorNotice(if (it.recoverByRestart) "AI 存储故障（${it.code}），请重启应用。" else "AI 存储故障（${it.code}）。")
        }
        controller.notice?.let { text ->
            Box(Modifier.testTag("ai-notice").clickable { controller.dismissNotice() }) { ErrorNotice(text) }
        }
        controller.info?.let { InlineNote(it, Modifier.testTag("ai-info")) }
        when {
            session != null ->
                AiSessionPane(
                    controller, state, session, Modifier.weight(1f), onOpenNote, leaveSession, sourceHeader, deleteSource, onDeleted,
                    // Shown alone under a page bar (更多 › AI 讨论), whose back already leaves the discussion.
                    showBack = onLeaveSession == null,
                    manageable = manageable,
                )
            onLeaveSession != null ->
                // Still opening, or opening was refused (the notice above says why).
                if (controller.notice == null) InlineNote("正在打开讨论…", Modifier.testTag("ai-opening"))
                else TextButton(onClick = onLeaveSession, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回列表") }
            else -> AiSessionList(controller, state, Modifier.weight(1f), onClose)
        }
    }
}

@Composable
private fun InlineNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun AiSessionList(
    controller: AiChatController,
    state: AiConversationUiState,
    modifier: Modifier,
    onClose: (() -> Unit)?,
) {
    var confirmDelete by remember { mutableStateOf<SessionView?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    confirmDelete?.let { target ->
        val running = state.globalBusy?.owner?.sessionId == target.sessionId
        AiDeleteDialog(target.title, null, running, onDismiss = { confirmDelete = null }) {
            controller.delete(target)
            confirmDelete = null
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null)
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(AppIcons.Back, contentDescription = "返回题目")
                }
            Text(
                "AI 讨论",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Button(
                onClick = { controller.create() },
                enabled = state.phase == ServicePhase.READY,
                modifier = Modifier.heightIn(min = 48.dp).testTag("ai-new-session"),
            ) {
                Text("新建讨论")
            }
        }
        controller.source.context?.let { AiSourceContextCard(it) }
        renameTarget?.let { id -> controller.sessions.firstOrNull { it.sessionId == id } }?.let { target ->
            AiListRenameEditor(target.sessionId, target.title, controller.renaming, onDismiss = { renameTarget = null }) {
                controller.rename(target.sessionId, it) { renameTarget = null }
            }
        }
        if (controller.sessions.isEmpty())
            InlineNote("还没有讨论。点“新建讨论”开始。", Modifier.testTag("ai-sessions-empty"))
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("ai-sessions"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(controller.sessions, key = { it.sessionId }) { s ->
                AiSessionRow(
                    s,
                    controller.source,
                    deleting = controller.deleting == s.sessionId,
                    menuEnabled = controller.deleting == null,
                    onRename = { renameTarget = s.sessionId },
                    onDelete = { confirmDelete = s },
                ) { controller.open(s.sessionId) }
            }
            if (controller.hasMoreSessions)
                item(key = "more") {
                    TextButton(onClick = { controller.moreSessions() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("加载更多讨论")
                    }
                }
            if (controller.legacy.isNotEmpty())
                item(key = "legacy-head") {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("早期记录（只读）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                        InlineNote("升级前的单轮问答，只能查看和复制，不会带入新讨论的上下文。")
                    }
                }
            items(controller.legacy, key = { "legacy-" + it.cacheKey }) { e ->
                controller.source.legacyQuestion?.let { AiLegacyRow(e, it) }
            }
            if (controller.hasMoreLegacy)
                item(key = "legacy-more") {
                    TextButton(onClick = { controller.moreLegacy() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("加载更多早期记录")
                    }
                }
        }
    }
}

@Composable
private fun AiSessionRow(
    s: SessionView,
    source: AiChatSource,
    deleting: Boolean,
    menuEnabled: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        enabled = !deleting,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ai-session-row"),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val tags =
                buildList {
                    if (s.readOnly) add("只读")
                    if (!source.current(s)) add(source.staleLabel)
                    if (s.draft.text.isNotBlank()) add("草稿：" + s.draft.text.lineSequence().first())
                }
            if (tags.isNotEmpty())
                Text(
                    tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            if (deleting) InlineNote("正在删除…", Modifier.testTag("ai-session-deleting"))
        }
        // Read-only discussions of an older version can be deleted too.
        Box {
            IconButton(
                onClick = { menu = true },
                enabled = menuEnabled,
                modifier = Modifier.size(48.dp).testTag("ai-session-menu"),
            ) {
                Icon(AppIcons.MoreVert, contentDescription = "更多操作：" + s.title)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = {
                        menu = false
                        onRename()
                    },
                    modifier = Modifier.testTag("ai-session-rename"),
                )
                DropdownMenuItem(
                    text = { Text("删除讨论") },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("ai-session-delete"),
                )
            }
        }
      }
    }
}

/** One pre-upgrade record: collapsed to its question, expanded to the full reply. */
@Composable
private fun AiLegacyRow(e: LegacyEntryView, question: QuestionOwner) {
    var open by rememberSaveable(e.cacheKey) { mutableStateOf(false) }
    Surface(
        onClick = { open = !open },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ai-legacy-row"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(e.prompt.ifBlank { "（无提问文字）" }, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            val version =
                when (e.contentHash) {
                    null -> "题目版本未知"
                    question.contentHash -> null
                    else -> "旧版题目"
                }
            InlineNote(
                listOfNotNull(
                        java.time.Instant.ofEpochMilli(e.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                        e.model,
                        version,
                        "旧估算 " + aiMoney(e.originalEstimatedUsd, "USD"),
                    )
                    .joinToString(" · ")
            )
            if (open) {
                MarkdownContent(e.response)
                AiCopyButton(e.response, "复制早期回复")
            }
        }
    }
}

/** Which document the discussion is about and which part of it the answers will cite. */
@Composable
private fun AiSourceContextCard(context: AiSourceContext) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("ai-source-context"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(context.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            InlineNote(context.scope)
        }
    }
}

@Composable
private fun AiSessionPane(
    controller: AiChatController,
    state: AiConversationUiState,
    session: SessionView,
    modifier: Modifier,
    onOpenNote: ((StickyNote) -> Unit)?,
    onBack: () -> Unit,
    sourceHeader: (@Composable (SessionView) -> Unit)?,
    deleteSource: String?,
    onDeleted: (() -> Unit)?,
    showBack: Boolean = true,
    manageable: Boolean = true,
) {
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val deleting = controller.deleting == session.sessionId
    if (confirmDelete)
        AiDeleteDialog(
            session.title,
            deleteSource,
            state.globalBusy?.owner?.sessionId == session.sessionId,
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            controller.deleteOpened { onDeleted?.invoke() }
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack)
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(AppIcons.Back, contentDescription = "返回讨论列表")
                }
            else Spacer(Modifier.width(4.dp))
            Text(
                session.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() }.testTag("ai-title"),
            )
            if (manageable) Box {
                IconButton(
                    onClick = { menu = true },
                    enabled = !deleting,
                    modifier = Modifier.size(48.dp).testTag("ai-detail-menu"),
                ) {
                    Icon(AppIcons.MoreVert, contentDescription = "讨论操作")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(AppIcons.Pencil, contentDescription = null) },
                        onClick = {
                            menu = false
                            renaming = true
                        },
                        modifier = Modifier.testTag("ai-detail-rename"),
                    )
                    DropdownMenuItem(
                        text = { Text("删除讨论", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(AppIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            confirmDelete = true
                        },
                        modifier = Modifier.testTag("ai-detail-delete"),
                    )
                }
            }
        }
        if (deleting) InlineNote("正在删除…", Modifier.testTag("ai-detail-deleting"))
        sourceHeader?.invoke(session)
        if (renaming)
            AiRenameEditor(session.title, onDismiss = { renaming = false }) {
                controller.rename(it)
                renaming = false
            }
        if (session.readOnly)
            InlineNote("题目已更新，这段讨论只能查看和复制。", Modifier.testTag("ai-read-only"))
        val busy = state.globalBusy
        if (busy != null) AiBusyRow(busy, session, controller)
        val canSend = aiCanSend(state, session, controller)
        if (sourceHeader == null) controller.source.context?.let { AiSourceContextCard(it) }
        AiMessageList(controller, state, session, Modifier.weight(1f), canSend, onOpenNote)
        AiQuickRow(controller, canSend)
        AiDraftBox(controller, session, canSend)
    }
}

/** One request at a time for the whole app; cleaning up a stopped one still holds the slot. */
private fun aiCanSend(state: AiConversationUiState, session: SessionView, controller: AiChatController) =
    state.phase == ServicePhase.READY && state.globalBusy == null && !session.readOnly && !controller.sending

@Composable
private fun AiBusyRow(busy: BusyRun, session: SessionView, controller: AiChatController) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("ai-busy"),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        val mine = busy.owner.sessionId == session.sessionId
        Text(
            when {
                busy.phase == TransportPhase.CLEANING -> "正在停止，收尾完成后才能再发送…"
                mine -> "正在生成回复…"
                else -> "AI 正在处理其他讨论，完成后才能在这里发送。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Stopping goes to the run that holds the slot, wherever it was started.
        if (busy.phase != TransportPhase.CLEANING)
            TextButton(
                onClick = { controller.stop(busy.owner) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("ai-stop"),
            ) {
                Text("停止")
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiQuickRow(controller: AiChatController, canSend: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        controller.source.quickTypes.forEach { type -> AiQuickChip(type.label, canSend) { controller.quick(type) } }
    }
}

@Composable
internal fun AiQuickChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    SuggestionChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        shape = MaterialTheme.shapes.small,
        colors =
            SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                labelColor = MaterialTheme.colorScheme.primary,
            ),
        border = SuggestionChipDefaults.suggestionChipBorder(enabled = enabled, borderColor = MaterialTheme.selection.line),
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

@Composable
private fun AiMessageList(
    controller: AiChatController,
    state: AiConversationUiState,
    session: SessionView,
    modifier: Modifier,
    canSend: Boolean,
    onOpenNote: ((StickyNote) -> Unit)?,
) {
    val messages = controller.messages(state)
    val hasOlder = controller.hasOlder(state)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val header = if (hasOlder) 1 else 0
    val latestReply = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.messageId
    // Opening lands on the newest message. After that, new messages are followed only while the
    // user is at the bottom; once they scroll up, nothing pulls the list back down.
    var follow by remember(session.sessionId) { mutableStateOf(true) }
    var landed by remember(session.sessionId) { mutableStateOf(false) }
    LaunchedEffect(listState, session.sessionId) {
        // Only the end of a scroll decides; the first emission is the idle start state.
        snapshotFlow { listState.isScrollInProgress }.drop(1).collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    val lastId = messages.lastOrNull()?.messageId
    LaunchedEffect(session.sessionId, lastId) {
        if (lastId != null && (!landed || follow)) {
            listState.scrollToItem(header + messages.lastIndex)
            landed = true
            follow = true
        }
    }
    val latestHidden by
        remember(latestReply, listState) {
            derivedStateOf { latestReply != null && listState.layoutInfo.visibleItemsInfo.none { it.key == latestReply } }
        }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("ai-messages"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (hasOlder)
                item(key = "older") {
                    TextButton(
                        onClick = { controller.loadOlder() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("ai-load-older"),
                    ) {
                        Text("加载更早的消息")
                    }
                }
            if (messages.isEmpty())
                item(key = "empty") { InlineNote("还没有消息。") }
            items(messages, key = { it.messageId }) { m -> AiMessage(m, canSend, controller, onOpenNote) }
        }
        if (latestHidden && latestReply != null)
            FilledTonalButton(
                onClick = {
                    val index = header + messages.indexOfFirst { it.messageId == latestReply }
                    scope.launch { listState.animateScrollToItem(index) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).heightIn(min = 48.dp).testTag("ai-jump-latest"),
            ) {
                Icon(AppIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("最新回复")
            }
    }
}

@Composable
private fun AiMessage(m: MessageView, canRetry: Boolean, controller: AiChatController, onOpenNote: ((StickyNote) -> Unit)?) {
    if (m.role == MessageRole.USER) {
        Column(Modifier.fillMaxWidth().testTag("ai-message-user"), horizontalAlignment = Alignment.End) {
            Surface(
                color = MaterialTheme.selection.container,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                border = BorderStroke(1.dp, MaterialTheme.selection.line),
                modifier = Modifier.fillMaxWidth(0.85f).wrapContentWidth(Alignment.End),
            ) {
                Text(m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            AiCopyButton(m.text, "复制提问")
        }
        return
    }
    Column(Modifier.fillMaxWidth().testTag("ai-message-assistant"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(AppIcons.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text("AI 回复", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            m.run?.let(::aiRunStatusText)?.let {
                Text("· $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (m.text.isNotEmpty()) MarkdownContent(m.text)
        if (m.completion == TextState.PARTIAL)
            InlineNote("回复不完整，只保存了已收到的部分。", Modifier.testTag("ai-partial"))
        controller.source.citation(m)?.let { AiCitationBlock(it, controller.source.coverageNote(m)) }
        m.run?.let(::aiUsageText)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = aiCostTextColor(MaterialTheme.colorScheme), modifier = Modifier.testTag("ai-usage"))
        }
        val save = controller.noteSaves[m.messageId]
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiCopyButton(m.text, "复制回复")
            val run = m.run
            if (run != null && run.canRetry)
                TextButton(
                    onClick = { controller.retry(run) },
                    enabled = canRetry,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ai-retry"),
                ) {
                    Text("重试")
                }
            if (controller.canSaveNotes && aiReplySavable(m) && save?.note == null)
                TextButton(
                    onClick = { controller.saveToNote(m) },
                    enabled = save?.saving != true,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ai-save-note"),
                ) {
                    Text(if (save?.saving == true) "正在保存…" else "保存到便签")
                }
        }
        save?.error?.let { Text("未保存到便签：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai-save-note-error")) }
        save?.note?.let { note ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("ai-saved-note")) {
                Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(
                    "已保存到便签「${note.title.ifBlank { "未命名便签" }}」",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                if (onOpenNote != null)
                    TextButton(onClick = { onOpenNote(note) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开便签") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * What of the user's own material the reply was given, kept apart from the answer: anything not
 * listed here came from the model's general knowledge. Hidden when nothing was cited.
 */
@Composable
private fun AiCitationBlock(citation: String, coverage: String?) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("ai-citation-block"),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("速记依据", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(citation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai-citation"))
            coverage?.let { InlineNote(it, Modifier.testTag("ai-coverage")) }
        }
    }
}

/** Copies the message text as stored (Markdown kept) and says so for two seconds. */
@Composable
private fun AiCopyButton(text: String, label: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    TextButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        enabled = text.isNotEmpty(),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
    ) {
        Text(if (copied) "已复制" else "复制")
    }
}

@Composable
private fun AiDraftBox(controller: AiChatController, session: SessionView, canSend: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = controller.draft,
            onValueChange = controller::edit,
            readOnly = session.readOnly,
            placeholder = { Text(if (session.readOnly) "只读讨论" else "追问、错误选项分析、举例或质疑") },
            modifier = Modifier.fillMaxWidth().testTag("ai-draft"),
            shape = RoundedCornerShape(24.dp),
            maxLines = 5,
            colors =
                OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            trailingIcon = {
                if (!session.readOnly)
                    IconButton(
                        onClick = { controller.send() },
                        enabled = canSend && controller.draft.isNotBlank(),
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.outline,
                            ),
                    ) {
                        Icon(AppIcons.Send, contentDescription = "发送追问", modifier = Modifier.size(20.dp))
                    }
            },
        )
        if (!session.readOnly) {
            val status =
                when (controller.draftSave) {
                    DraftSave.SAVED -> if (controller.draft.isEmpty()) null else "草稿已保存"
                    DraftSave.SAVING -> "正在保存草稿…"
                    DraftSave.FAILED -> "草稿未保存：" + controller.saveError
                }
            if (status != null)
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (controller.draftSave == DraftSave.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ai-draft-status"),
                )
        }
    }
}

/**
 * Renames a discussion picked from a list, inline above the list like the title bar's editor (a
 * text field in a dialog window would take the keyboard away from the page). Back closes it.
 */
@Composable
internal fun AiListRenameEditor(
    sessionId: String,
    /** The current name; refreshed after a stale rename while the typed text stays. */
    title: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var text by remember(sessionId) { mutableStateOf(title) }
    val length = text.trim().let { it.codePointCount(0, it.length) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("ai-list-rename"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "重命名「$title」",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("讨论标题") },
                supportingText = { Text("$length / $AI_TITLE_MAX") },
                isError = length > AI_TITLE_MAX,
                modifier = Modifier.fillMaxWidth().testTag("ai-list-rename-field"),
            )
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                TextButton(
                    onClick = { onSave(text) },
                    // An unchanged title is not offered: it would only bump the revision.
                    enabled = !saving && length in 1..AI_TITLE_MAX && text.trim() != title,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ai-rename-confirm"),
                ) {
                    Text(if (saving) "正在保存…" else "保存标题")
                }
            }
        }
    }
}

/** Inline title editor (no dialog window, so the keyboard stays with the page). */
@Composable
private fun AiRenameEditor(title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(title) }
    val length = text.trim().let { it.codePointCount(0, it.length) }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text("讨论标题") },
            supportingText = { Text("$length / $AI_TITLE_MAX") },
            isError = length > AI_TITLE_MAX,
            modifier = Modifier.fillMaxWidth().testTag("ai-rename-field"),
        )
        Row(Modifier.align(Alignment.End)) {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            TextButton(
                onClick = { onSave(text) },
                enabled = length in 1..AI_TITLE_MAX,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("保存标题")
            }
        }
    }
}
