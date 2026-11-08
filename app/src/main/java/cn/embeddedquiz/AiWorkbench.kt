package cn.embeddedquiz

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * 更多 › AI 讨论 as a workbench (PLAN-1.7.4) on GPT's workbench calls (contract 3053565): a type
 * filter, recent discussions as cards, sources as expandable groups with their discussions, a new
 * discussion per source, rename and delete in place, search, a saved free conversation (created by
 * its first send) and a temporary chat that keeps no record. A discussion opens on this page with
 * the 1.7.3 single-discussion view, under a card naming its source.
 */

internal const val AI_RECENT_LIMIT = 10
internal const val AI_SEARCH_MAX = 120

/** The filter row; 全部 is null. Temporary chats are never listed. */
internal val AI_WORKBENCH_FILTERS =
    listOf(AiDiscussionType.QUESTION, AiDiscussionType.PROGRAMMING, AiDiscussionType.QUICK_NOTE, AiDiscussionType.FREE)

internal fun aiTypeLabel(type: AiDiscussionType) =
    when (type) {
        AiDiscussionType.QUESTION -> "题目"
        AiDiscussionType.PROGRAMMING -> "编程"
        AiDiscussionType.QUICK_NOTE -> "速记"
        AiDiscussionType.FREE -> "对话"
        AiDiscussionType.TEMPORARY -> "临时"
    }

internal val AiDiscussionSummary.sessionId: String
    get() = discussion.sessionId

internal val AiDiscussionSummary.title: String
    get() = discussion.title

/** The first line of a source label, for one-line places (group titles, card captions). */
internal fun aiSourceLine(label: String): String =
    label.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/** The discussions loaded under one open group. */
internal data class AiGroupPage(
    val items: List<AiDiscussionSummary> = emptyList(),
    val next: PageCursor? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** One search result as shown: which part matched, and a window of text around the match. */
internal data class AiSearchView(val summary: AiDiscussionSummary, val field: String, val snippet: String, val match: IntRange?)

internal fun aiSearchView(hit: AiDiscussionSearchHit, query: String): AiSearchView {
    val s = hit.summary
    val field =
        when (hit.snippet) {
            s.discussion.title -> "标题"
            s.source.label -> "来源"
            else -> "内容"
        }
    val flat = hit.snippet.replace(Regex("\\s+"), " ").trim()
    val q = query.trim()
    val at = if (q.isEmpty()) -1 else flat.indexOf(q, ignoreCase = true)
    if (at < 0) return AiSearchView(s, field, flat.take(80) + if (flat.length > 80) "…" else "", null)
    val from = (at - 24).coerceAtLeast(0)
    val to = (at + q.length + 24).coerceAtMost(flat.length)
    val lead = if (from > 0) "…" else ""
    val start = lead.length + at - from
    return AiSearchView(s, field, lead + flat.substring(from, to) + if (to < flat.length) "…" else "", start until start + q.length)
}

internal data class AiWorkbenchSearch(
    val query: String = "",
    val hits: List<AiSearchView> = emptyList(),
    val next: PageCursor? = null,
    val searching: Boolean = false,
    /** True once [query] has been answered, so "nothing found" is not claimed while typing. */
    val searched: Boolean = false,
    val error: String? = null,
)

/** A saved free conversation about to start; nothing is stored until its first send. */
internal data class AiNewFree(
    /** The free group it joins, or null for a new topic. */
    val groupId: String? = null,
    val topic: String? = null,
    /** The discussion of that group it quotes, chosen explicitly. */
    val reference: AiDiscussionReference? = null,
)

/** The 对话 group's ＋: which discussion of the group to quote, if any. */
internal data class AiReferenceChoice(
    val group: AiDiscussionGroup,
    val options: List<AiDiscussionSummary> = emptyList(),
    /** The group's next page; older discussions can be quoted too. */
    val next: PageCursor? = null,
    val loading: Boolean = true,
    val previewing: String? = null,
    val error: String? = null,
)

/** The temporary chat on screen, with what was typed to start it. */
internal data class AiTemporaryChat(val sessionId: String, val owner: QuestionOwner, val firstText: String)

@Stable
internal class AiWorkbenchController(
    facadeProvider: () -> AiConversationFacade,
    /** The ViewModel's scope in the app: a discussion left by navigating away still saves and lets go. */
    val scope: CoroutineScope,
    /** The model the user picked; free and temporary chats send with it. */
    val model: () -> String,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** How long typing pauses before a search runs. */
    private val searchDelayMs: Long = 250,
) {
    val facade: AiConversationFacade by lazy(facadeProvider)

    /** Null is 全部. */
    var filter by mutableStateOf<AiDiscussionType?>(null)
        private set
    var recent by mutableStateOf<List<AiDiscussionSummary>>(emptyList())
        private set
    var groups by mutableStateOf<List<AiDiscussionGroup>>(emptyList())
        private set
    private var groupsNext: PageCursor? = null
    var hasMoreGroups by mutableStateOf(false)
        private set
    /** False until the first read answered, so an empty workbench is not claimed too early. */
    var loaded by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    /** Open groups; kept across filters, searches and trips into a discussion. */
    var expanded by mutableStateOf<Set<AiDiscussionGroupId>>(emptySet())
        private set
    var children by mutableStateOf<Map<AiDiscussionGroupId, AiGroupPage>>(emptyMap())
        private set

    /** Non-null while searching; leaving search restores the filter and open groups untouched. */
    var search by mutableStateOf<AiWorkbenchSearch?>(null)
        private set

    /** The discussion shown on this page; null shows the workbench. */
    var opened by mutableStateOf<AiDiscussionSummary?>(null)
        private set

    var newFree by mutableStateOf<AiNewFree?>(null)
        private set
    /** What is typed into the new free conversation; kept if starting it fails. */
    var freeText by mutableStateOf("")
    var starting by mutableStateOf(false)
        private set
    var choosing by mutableStateOf<AiReferenceChoice?>(null)
        private set
    var temporary by mutableStateOf<AiTemporaryChat?>(null)
        private set
    /** What was typed into "临时问 AI"; handed to the temporary chat when it opens. */
    var temporaryText by mutableStateOf("")
    var openingTemporary by mutableStateOf(false)
        private set

    var renaming by mutableStateOf(false)
        private set
    var deleting by mutableStateOf<String?>(null)
        private set
    /** The group whose new discussion is being created. */
    var creating by mutableStateOf<AiDiscussionGroupId?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set

    /** Scroll positions, kept while a discussion or a source is open. */
    val listState = LazyListState()
    val searchListState = LazyListState()

    private var loadToken = 0
    private var searchJob: Job? = null

    /**
     * Each "临时问 AI" takes a new generation; leaving the page moves it on. An open answered after
     * that is closed instead of shown. Opens and closes of the temporary chat run one at a time, in
     * order: the backend keeps one temporary chat and hands it to every open, so a late close must
     * finish before the next open, or it would close the chat that open gets.
     */
    private var temporaryGeneration = 0L
    private val temporaryLock = Mutex()

    private suspend fun awaitStarted() {
        facade.state.first { it.phase != ServicePhase.INITIALIZING }
    }

    /** Reads pages until at least [atLeast] items are there and [enough] holds, or none remain. */
    private suspend fun <T> readUntil(
        atLeast: Int,
        enough: (List<T>) -> Boolean = { true },
        read: suspend (PageCursor?) -> ResultOf<AiPage<T>>,
    ): ResultOf<AiPage<T>> {
        val list = mutableListOf<T>()
        var cursor: PageCursor? = null
        do {
            when (val r = read(cursor)) {
                is ResultOf.Ok -> {
                    list += r.value.items
                    cursor = r.value.next
                }
                is ResultOf.Rejected -> return r
            }
        } while (cursor != null && (list.size < atLeast || !enough(list)))
        return ResultOf.Ok(AiPage(list, cursor))
    }

    /**
     * Rereads what is on screen: recent cards, as many groups as were shown, and every open group
     * with as many discussions as it showed, so coming back keeps the place with current names.
     * [include] (a discussion id) is read even if it sat further down its group, e.g. for a rename
     * that has to be confirmed again.
     */
    suspend fun refresh(include: String? = null, keepCounts: Boolean = true) {
        awaitStarted()
        val token = ++loadToken
        val f = filter
        loading = true
        try {
            val recentRead = facade.recentDiscussions(f, null, AI_RECENT_LIMIT)
            val groupsRead =
                readUntil(if (keepCounts) maxOf(groups.size, AI_PAGE_SIZE) else AI_PAGE_SIZE) { facade.discussionGroups(f, it, AI_PAGE_SIZE) }
            if (token != loadToken) return
            if (recentRead is ResultOf.Rejected) return failLoad(recentRead.reason)
            if (groupsRead is ResultOf.Rejected) return failLoad(groupsRead.reason)
            val read = (groupsRead as ResultOf.Ok).value
            val open = expanded.filter { id -> read.items.any { it.source.groupId == id } }
            val pages = mutableMapOf<AiDiscussionGroupId, AiGroupPage>()
            for (id in open) {
                val before = children[id]?.items.orEmpty()
                val wanted = include?.takeIf { sid -> before.any { it.sessionId == sid } }
                val r =
                    readUntil(
                        maxOf(before.size, 1),
                        { list -> wanted == null || list.any { it.sessionId == wanted } },
                    ) { facade.groupDiscussions(id, it, AI_PAGE_SIZE) }
                if (token != loadToken) return
                pages[id] =
                    when (r) {
                        is ResultOf.Ok -> AiGroupPage(r.value.items, r.value.next)
                        is ResultOf.Rejected -> AiGroupPage(before, null, error = aiRejectionText(r.reason))
                    }
            }
            recent = (recentRead as ResultOf.Ok).value.items
            groups = read.items
            groupsNext = read.next
            hasMoreGroups = read.next != null
            // Groups shown under another filter keep their pages for when that filter returns.
            children = children + pages
            loadError = null
            loaded = true
        } finally {
            if (token == loadToken) loading = false
        }
    }

    private fun failLoad(reason: Rejection) {
        loadError = aiRejectionText(reason)
    }

    fun reload() = scope.launch { refresh() }

    fun selectFilter(type: AiDiscussionType?) {
        if (type == filter) return
        filter = type
        notice = null
        info = null
        scope.launch {
            listState.scrollToItem(0)
            refresh(keepCounts = false)
        }
    }

    fun moreGroups() =
        scope.launch {
            val cursor = groupsNext ?: return@launch
            if (loading) return@launch
            loading = true
            val token = ++loadToken
            try {
                when (val r = facade.discussionGroups(filter, cursor, AI_PAGE_SIZE)) {
                    is ResultOf.Ok -> {
                        if (token != loadToken) return@launch
                        groups = (groups + r.value.items).distinctBy { it.source.groupId }
                        groupsNext = r.value.next
                        hasMoreGroups = r.value.next != null
                    }
                    is ResultOf.Rejected -> {
                        notice = aiRejectionText(r.reason)
                        if (r.reason.code == RejectCode.STALE_CURSOR) {
                            loading = false
                            refresh()
                        }
                    }
                }
            } finally {
                if (token == loadToken) loading = false
            }
        }

    fun toggle(group: AiDiscussionGroup) {
        val id = group.source.groupId
        if (id in expanded) {
            expanded = expanded - id
            return
        }
        expanded = expanded + id
        if (children[id] == null) moreIn(group)
    }

    /** The next page of [group]'s discussions (the first one when nothing is loaded yet). */
    fun moreIn(group: AiDiscussionGroup) =
        scope.launch {
            val id = group.source.groupId
            val page = children[id] ?: AiGroupPage()
            if (page.loading) return@launch
            if (page.items.isNotEmpty() && page.next == null) return@launch
            children = children + (id to page.copy(loading = true, error = null))
            val r = facade.groupDiscussions(id, page.next, AI_PAGE_SIZE)
            val now = children[id] ?: AiGroupPage()
            children =
                children +
                    (id to
                        when (r) {
                            is ResultOf.Ok -> AiGroupPage((now.items + r.value.items).distinctBy { it.sessionId }, r.value.next)
                            is ResultOf.Rejected ->
                                if (r.reason.code == RejectCode.STALE_CURSOR) {
                                    scope.launch { refresh() }
                                    now.copy(loading = false)
                                } else now.copy(loading = false, error = aiRejectionText(r.reason))
                        })
        }

    fun open(item: AiDiscussionSummary) {
        notice = null
        info = null
        opened = item
    }

    /** Back from a discussion; the workbench rereads what it showed when it appears again. */
    fun closeOpened() {
        opened = null
    }

    private fun find(sessionId: String): AiDiscussionSummary? =
        children.values.firstNotNullOfOrNull { page -> page.items.firstOrNull { it.sessionId == sessionId } }
            ?: recent.firstOrNull { it.sessionId == sessionId }
            ?: search?.hits?.firstOrNull { it.summary.sessionId == sessionId }?.summary
            ?: opened?.takeIf { it.sessionId == sessionId }

    private fun update(sessionId: String, change: (AiDiscussionSummary) -> AiDiscussionSummary) {
        fun AiDiscussionSummary.apply() = if (this.sessionId == sessionId) change(this) else this
        recent = recent.map { it.apply() }
        children = children.mapValues { (_, page) -> page.copy(items = page.items.map { it.apply() }) }
        search = search?.let { s -> s.copy(hits = s.hits.map { it.copy(summary = it.summary.apply()) }) }
        opened = opened?.apply()
    }

    /** Takes a deleted discussion off every list here: recent, its group and the search results. */
    fun forget(item: AiDiscussionSummary, announce: Boolean = false) {
        val id = item.sessionId
        if (announce) info = "已删除讨论「${item.title}」。"
        recent = recent.filterNot { it.sessionId == id }
        children = children.mapValues { (_, page) -> page.copy(items = page.items.filterNot { it.sessionId == id }) }
        search = search?.let { s -> s.copy(hits = s.hits.filterNot { it.summary.sessionId == id }) }
        groups =
            groups.mapNotNull { g ->
                when {
                    g.source.groupId != item.source.groupId -> g
                    g.sessionCount <= 1 -> null
                    else -> g.copy(sessionCount = g.sessionCount - 1)
                }
            }
    }

    /** Renamed from a group's list; [onDone] runs only on success, so a failure keeps the editor and its text. */
    fun rename(sessionId: String, title: String, onDone: () -> Unit) =
        scope.launch {
            if (renaming) return@launch
            val item = find(sessionId) ?: return@launch
            renaming = true
            notice = null
            info = null
            try {
                awaitStarted()
                when (val r = aiRename(facade, newId(), sessionId, item.discussion.sessionRevision, title)) {
                    is ResultOf.Ok -> {
                        update(sessionId) { it.copy(discussion = it.discussion.copy(title = r.value.title, sessionRevision = r.value.sessionRevision)) }
                        info = "已重命名为「${r.value.title}」。"
                        onDone()
                    }
                    is ResultOf.Rejected -> {
                        notice = aiRenameRejectionText(r.reason, listed = true)
                        // Renamed or changed elsewhere: read the current name, keeping the target in its group.
                        if (r.reason.code == RejectCode.STALE_SESSION || r.reason.code == RejectCode.NOT_FOUND) refresh(include = sessionId)
                    }
                }
            } finally {
                renaming = false
            }
        }

    /** Deleted from a group: a running request is stopped and cleaned up first, as everywhere else. */
    fun delete(item: AiDiscussionSummary) =
        scope.launch {
            if (deleting != null) return@launch
            deleting = item.sessionId
            notice = null
            info = null
            try {
                awaitStarted()
                when (val r = aiDeleteDiscussion(facade, newId, item.sessionId, item.discussion.owner)) {
                    is ResultOf.Ok -> forget(item, announce = true)
                    is ResultOf.Rejected -> notice = "讨论未删除：" + aiRejectionText(r.reason)
                }
            } finally {
                deleting = null
            }
        }

    /** A new discussion about the group's current source, opened right away from an empty conversation. */
    fun create(group: AiDiscussionGroup) =
        scope.launch {
            if (creating != null) return@launch
            val id = group.source.groupId
            creating = id
            notice = null
            info = null
            try {
                awaitStarted()
                when (val r = facade.createGroupDiscussion(newId(), id)) {
                    is ResultOf.Ok -> {
                        val s = r.value.session
                        expanded = expanded + id
                        // Read again with the new one when the workbench shows next.
                        children = children - id
                        val source = group.source.copy(status = AiSourceStatus.CURRENT, currentOwner = s.question)
                        opened =
                            AiDiscussionSummary(
                                AiDiscussionListItem(s.sessionId, s.question, s.title, s.sessionRevision, System.currentTimeMillis(), source.label, false),
                                source,
                                null,
                            )
                    }
                    is ResultOf.Rejected -> notice = "未能新建讨论：" + aiRejectionText(r.reason)
                }
            } finally {
                creating = null
            }
        }

    // ---- free conversations ----

    /** The bottom ＋: a saved free conversation on a new topic, with no outside context. */
    fun newConversation() {
        notice = null
        info = null
        newFree = AiNewFree()
    }

    /** The 对话 group's ＋: first choose which discussion of the group to quote, if any. */
    fun chooseReference(group: AiDiscussionGroup) =
        scope.launch {
            if (choosing != null) return@launch
            notice = null
            info = null
            choosing = AiReferenceChoice(group)
            awaitStarted()
            val r = facade.groupDiscussions(group.source.groupId, null, AI_PAGE_SIZE)
            val now = choosing?.takeIf { it.group == group } ?: return@launch
            choosing =
                when (r) {
                    is ResultOf.Ok -> now.copy(options = r.value.items, next = r.value.next, loading = false)
                    is ResultOf.Rejected -> now.copy(loading = false, error = aiRejectionText(r.reason))
                }
        }

    /**
     * The next page of the group in the picker. A cursor made stale by a change elsewhere rereads
     * from the top up to what was shown, so the picked option stays in the list.
     */
    fun moreReferences() =
        scope.launch {
            val c = choosing ?: return@launch
            val cursor = c.next ?: return@launch
            if (c.loading) return@launch
            choosing = c.copy(loading = true, error = null)
            val id = c.group.source.groupId
            var r = facade.groupDiscussions(id, cursor, AI_PAGE_SIZE)
            var fromTop = false
            if (r is ResultOf.Rejected && r.reason.code == RejectCode.STALE_CURSOR) {
                fromTop = true
                r = readUntil(c.options.size + 1) { facade.groupDiscussions(id, it, AI_PAGE_SIZE) }
            }
            val now = choosing?.takeIf { it.group == c.group } ?: return@launch
            choosing =
                when (r) {
                    is ResultOf.Ok ->
                        now.copy(
                            options = (if (fromTop) r.value.items else now.options + r.value.items).distinctBy { it.sessionId },
                            next = r.value.next,
                            loading = false,
                        )
                    is ResultOf.Rejected -> now.copy(loading = false, error = aiRejectionText(r.reason))
                }
        }

    fun dismissChoice() {
        choosing = null
    }

    /** [sessionId] null starts an empty conversation in the group; otherwise its turns are previewed first. */
    fun pickReference(sessionId: String?) =
        scope.launch {
            val c = choosing ?: return@launch
            if (c.previewing != null) return@launch
            val groupId = c.group.source.groupId.sourceId
            val topic = c.group.source.label
            if (sessionId == null) {
                choosing = null
                newFree = AiNewFree(groupId, topic)
                return@launch
            }
            choosing = c.copy(previewing = sessionId, error = null)
            val r = facade.previewDiscussionReference(groupId, sessionId)
            val now = choosing ?: return@launch
            when (r) {
                is ResultOf.Ok -> {
                    choosing = null
                    newFree = AiNewFree(groupId, topic, r.value)
                }
                is ResultOf.Rejected -> choosing = now.copy(previewing = null, error = aiRejectionText(r.reason))
            }
        }

    fun cancelNewFree() {
        newFree = null
    }

    /** The first send stores the conversation; it then opens like any saved discussion. */
    fun startFree() =
        scope.launch {
            val plan = newFree ?: return@launch
            val input = freeText.trim()
            if (input.isEmpty() || starting) return@launch
            starting = true
            notice = null
            try {
                awaitStarted()
                val command = AiFreeDiscussionCommand(newId(), input, model(), plan.groupId, plan.reference?.sessionId)
                when (val r = facade.startFreeDiscussion(command)) {
                    is ResultOf.Ok -> {
                        val owner = r.value.owner
                        val source =
                            when (val s = facade.discussionSource(owner.sessionId)) {
                                is ResultOf.Ok -> s.value
                                is ResultOf.Rejected ->
                                    AiDiscussionSource(
                                        AiDiscussionGroupId(AiOwnerKind.FREE, owner.question.questionId),
                                        AiDiscussionType.FREE, plan.topic ?: aiSourceLine(input), AiSourceStatus.INDEPENDENT, null, plan.reference,
                                    )
                            }
                        val title = aiSourceLine(input).let { if (it.codePointCount(0, it.length) > AI_TITLE_MAX) it.take(AI_TITLE_MAX) else it }
                        freeText = ""
                        newFree = null
                        opened =
                            AiDiscussionSummary(
                                AiDiscussionListItem(owner.sessionId, owner.question, title, 0, System.currentTimeMillis(), source.label, false),
                                source,
                                System.currentTimeMillis(),
                            )
                    }
                    is ResultOf.Rejected -> notice = "对话未开始：" + aiRejectionText(r.reason)
                }
            } finally {
                starting = false
            }
        }

    // ---- temporary chat ----

    /** "临时问 AI": opens a chat that keeps no record and sends what was typed. */
    fun askTemporary() {
        val text = temporaryText.trim()
        if (text.isEmpty() || temporary != null || openingTemporary) return
        val generation = ++temporaryGeneration
        openingTemporary = true
        notice = null
        info = null
        scope.launch {
            try {
                awaitStarted()
                temporaryLock.withLock {
                    if (generation != temporaryGeneration) return@withLock
                    val r = facade.openTemporaryDiscussion()
                    if (generation != temporaryGeneration) {
                        // The page was left while this was opening: nothing is shown or sent.
                        if (r is ResultOf.Ok) facade.closeTemporaryDiscussion(r.value.session.sessionId)
                        return@withLock
                    }
                    when (r) {
                        is ResultOf.Ok -> {
                            temporaryText = ""
                            temporary = AiTemporaryChat(r.value.session.sessionId, r.value.session.question, text)
                        }
                        is ResultOf.Rejected -> notice = "临时对话未打开：" + aiRejectionText(r.reason)
                    }
                }
            } finally {
                if (generation == temporaryGeneration) openingTemporary = false
            }
        }
    }

    /** Leaving the temporary chat stops it and discards its text, draft and cache. */
    fun closeTemporary() {
        val t = temporary ?: return
        temporary = null
        scope.launch { temporaryLock.withLock { facade.closeTemporaryDiscussion(t.sessionId) } }
    }

    /**
     * The workbench page is gone (back to 更多, another tab, a source document). A temporary chat
     * still opening is abandoned and closed when it arrives, one on screen is closed, and what was
     * typed for it is cleared, so coming back never sends it.
     */
    fun leavePage() {
        temporaryGeneration++
        openingTemporary = false
        temporaryText = ""
        closeTemporary()
    }

    // ---- search ----

    fun startSearch() {
        if (search == null) search = AiWorkbenchSearch()
    }

    fun endSearch() {
        searchJob?.cancel()
        search = null
    }

    /** Searches as the user types; a newer query cancels the older one. */
    fun query(text: String) {
        val s = search ?: return
        searchJob?.cancel()
        val q = text.trim()
        if (q.isEmpty()) {
            search = AiWorkbenchSearch(query = text)
            return
        }
        if (q.codePointCount(0, q.length) > AI_SEARCH_MAX) {
            search = s.copy(query = text, hits = emptyList(), next = null, searching = false, searched = false, error = "搜索词最多 $AI_SEARCH_MAX 个字。")
            return
        }
        search = s.copy(query = text, searching = true, error = null)
        searchJob =
            scope.launch {
                delay(searchDelayMs)
                val r = facade.searchDiscussions(q, null, AI_PAGE_SIZE)
                val now = search ?: return@launch
                if (now.query != text) return@launch
                search =
                    when (r) {
                        is ResultOf.Ok -> now.copy(hits = r.value.items.map { aiSearchView(it, q) }, next = r.value.next, searching = false, searched = true)
                        is ResultOf.Rejected -> now.copy(searching = false, searched = true, error = aiRejectionText(r.reason))
                    }
            }
    }

    fun moreHits() =
        scope.launch {
            val s = search ?: return@launch
            val cursor = s.next ?: return@launch
            if (s.searching) return@launch
            search = s.copy(searching = true)
            val q = s.query.trim()
            val r = facade.searchDiscussions(q, cursor, AI_PAGE_SIZE)
            val now = search ?: return@launch
            if (now.query != s.query) return@launch
            search =
                when (r) {
                    is ResultOf.Ok ->
                        now.copy(
                            hits = (now.hits + r.value.items.map { aiSearchView(it, q) }).distinctBy { it.summary.sessionId },
                            next = r.value.next,
                            searching = false,
                        )
                    is ResultOf.Rejected -> now.copy(searching = false, error = aiRejectionText(r.reason))
                }
        }

    fun dismissNotice() {
        notice = null
        info = null
    }
}

// ---- looks ----

internal fun aiTypeIcon(type: AiDiscussionType): ImageVector =
    when (type) {
        AiDiscussionType.QUESTION -> AppIcons.FileText
        AiDiscussionType.PROGRAMMING -> AppIcons.Code
        AiDiscussionType.QUICK_NOTE -> AppIcons.QuickNotes
        AiDiscussionType.FREE -> AppIcons.MessagesSquare
        AiDiscussionType.TEMPORARY -> AppIcons.Sparkles
    }

@Composable
@ReadOnlyComposable
internal fun aiTypeColor(type: AiDiscussionType): Color {
    val more = MaterialTheme.more
    return when (type) {
        AiDiscussionType.QUESTION -> more.stats
        AiDiscussionType.PROGRAMMING -> more.verify
        AiDiscussionType.QUICK_NOTE -> more.tag
        AiDiscussionType.FREE,
        AiDiscussionType.TEMPORARY -> more.ai
    }
}

@Composable
@ReadOnlyComposable
private fun darkSurface() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** A rounded square in the type's tint, holding its icon. */
@Composable
private fun AiTypeTile(type: AiDiscussionType, size: Dp) {
    val color = aiTypeColor(type)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color.copy(alpha = if (darkSurface()) 0.24f else 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(aiTypeIcon(type), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}

internal fun aiSourceText(source: AiDiscussionSource) = aiTypeLabel(source.type) + " · " + aiSourceLine(source.label)

private fun aiStatusText(status: AiSourceStatus) =
    when (status) {
        AiSourceStatus.CURRENT -> "当前版本"
        AiSourceStatus.UPDATED -> "来源已更新"
        AiSourceStatus.DELETED -> "来源已删除"
        AiSourceStatus.INDEPENDENT -> ""
    }

internal fun aiReferenceText(ref: AiDiscussionReference) = "参考「${ref.title}」" + if (ref.partial) "（节选）" else ""

internal fun aiSourceHint(source: AiDiscussionSource): String =
    when {
        source.type == AiDiscussionType.TEMPORARY -> "不保留记录：退出后清除正文和草稿，费用仍计入统计。"
        source.type == AiDiscussionType.FREE ->
            source.reference?.let { aiReferenceText(it) + "；这份引用只用于本对话。" } ?: "自由对话 · 未关联题目或速记"
        source.status == AiSourceStatus.DELETED -> "来源已删除，按讨论时的快照显示；仍可改名和删除。"
        source.status == AiSourceStatus.UPDATED -> "讨论之后来源已更新，旧消息只读；仍可改名和删除。"
        source.type == AiDiscussionType.QUICK_NOTE -> "回答会标明实际引用的段落。"
        else -> "AI 依据这道题回答；查看原题不会开始练习。"
    }

@Composable
private fun AiStatusChip(status: AiSourceStatus) {
    val (container, content) =
        when (status) {
            AiSourceStatus.UPDATED -> MaterialTheme.status.warningContainer to MaterialTheme.status.onWarningContainer
            AiSourceStatus.DELETED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        }
    Surface(shape = RoundedCornerShape(6.dp), color = container, contentColor = content, modifier = Modifier.testTag("ai-source-status")) {
        Text(aiStatusText(status), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Under a discussion's title: what it is about, whether that is still current, and the way to it. */
@Composable
internal fun AiSourceCard(source: AiDiscussionSource, opening: Boolean, onOpen: (() -> Unit)?) {
    val color = aiTypeColor(source.type)
    val unlinked = source.type == AiDiscussionType.FREE || source.type == AiDiscussionType.TEMPORARY
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("ai-source-card"),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AiTypeTile(source.type, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        when (source.type) {
                            AiDiscussionType.FREE -> "自由对话"
                            AiDiscussionType.TEMPORARY -> "临时对话"
                            else -> aiTypeLabel(source.type)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                    )
                    if (!unlinked) AiStatusChip(source.status)
                }
                Text(
                    aiSourceLine(source.label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("ai-source-title"),
                )
                Text(
                    aiSourceHint(source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ai-source-hint"),
                )
            }
            if (onOpen != null)
                TextButton(onClick = onOpen, enabled = !opening, modifier = Modifier.heightIn(min = 48.dp).testTag("ai-source-open")) {
                    Text(if (opening) "正在打开…" else if (source.type == AiDiscussionType.QUICK_NOTE) "原文" else "原题")
                    Spacer(Modifier.width(2.dp))
                    Icon(AppIcons.ArrowUpRight, contentDescription = null, modifier = Modifier.size(16.dp))
                }
        }
    }
}

// ---- the page ----

@Composable
fun AiDiscussionsScreen(vm: QuizViewModel) {
    val wb = vm.aiWorkbench
    AiWorkbenchContent(wb) { item, onLeave ->
        AiWorkbenchDiscussion(
            vm,
            wb,
            item,
            onLeave,
            loadQuestion = { id -> vm.loadQuestion(id) },
            openDocument = { id, back -> vm.quickNotes.openFrom(id, back) },
        )
    }
}

/** One discussion with its source card; the original question opens over it, read only. */
@Composable
private fun AiWorkbenchDiscussion(
    vm: QuizViewModel,
    wb: AiWorkbenchController,
    item: AiDiscussionSummary,
    onLeave: () -> Unit,
    loadQuestion: suspend (String) -> Question?,
    openDocument: (String, () -> Unit) -> Unit,
) {
    val controller =
        remember(item.sessionId) {
            val source =
                aiDiscussionSource(
                    item.discussion,
                    question = { id -> vm.questions.firstOrNull { it.id == id } },
                    document = { id -> vm.quickNotes.state.documents.firstOrNull { it.id == id } },
                    model = { vm.model },
                    savedCode = { id -> vm.p(id).code },
                )
            val noteSaver =
                if (item.discussion.owner.kind == AiOwnerKind.QUESTION)
                    AiNoteSaver { commandId, reply -> vm.saveAiReplyAsStickyNote(commandId, reply) }
                else null
            // The ViewModel's scope, so leaving the page still saves and releases the draft.
            AiChatController({ vm.aiConversations }, source, vm.viewModelScope, noteSaver)
        }
    AiWorkbenchDetail(wb, controller, item, onLeave, loadQuestion, openDocument) { note ->
        controller.leave {
            vm.openStickyNotes()
            vm.editStickyNote(note)
        }
    }
}

/**
 * The open discussion. The question's original opens read only over the discussion, which stays
 * open underneath with its draft; a 速记 document opens in the reader once the draft is saved, and
 * closing the reader comes back here.
 */
@Composable
internal fun AiWorkbenchDetail(
    wb: AiWorkbenchController,
    controller: AiChatController,
    item: AiDiscussionSummary,
    onLeave: () -> Unit,
    loadQuestion: suspend (String) -> Question?,
    openDocument: (String, () -> Unit) -> Unit,
    onOpenNote: ((StickyNote) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<NoteSourceOpen.Ready?>(null) }
    var opening by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    val source = item.source
    val sourceId = source.groupId.sourceId
    val openSource: (() -> Unit)? =
        when {
            source.status == AiSourceStatus.DELETED -> null
            source.type == AiDiscussionType.QUICK_NOTE -> { { controller.leave { openDocument(sourceId) {} } } }
            source.type == AiDiscussionType.QUESTION || source.type == AiDiscussionType.PROGRAMMING -> {
                {
                    if (!opening) {
                        opening = true
                        missing = false
                        scope.launch {
                            try {
                                val q = loadQuestion(sourceId)
                                if (q == null) missing = true
                                else viewing = NoteSourceOpen.Ready(q, changed = q.contentHash != item.discussion.owner.contentHash)
                            } finally {
                                opening = false
                            }
                        }
                    }
                }
            }
            else -> null
        }
    Box(Modifier.fillMaxSize()) {
        AiDiscussionChat(
            controller,
            item.discussion,
            onLeave,
            onOpenNote,
            sourceHeader = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AiSourceCard(source, opening, openSource)
                    if (missing)
                        Text(
                            "原题已不在题库中；这段讨论按快照保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("ai-source-missing"),
                        )
                }
            },
            deleteSource = aiSourceText(source),
            onDeleted = {
                wb.forget(item, announce = true)
                onLeave()
            },
        )
        viewing?.let { open ->
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SourceQuestionView(open, "返回讨论", "讨论开始后题目已更新，下面是题库中的当前版本。") { viewing = null }
            }
        }
    }
}

@Composable
internal fun AiWorkbenchContent(
    wb: AiWorkbenchController,
    discussion: @Composable (AiDiscussionSummary, onLeave: () -> Unit) -> Unit,
) {
    // The controller outlives the page (it lives in the ViewModel); the page's temporary chat does not.
    DisposableEffect(wb) { onDispose { wb.leavePage() } }
    wb.temporary?.let { chat ->
        AiTemporaryPane(wb, chat)
        return
    }
    val opened = wb.opened
    if (opened != null) {
        discussion(opened) { wb.closeOpened() }
        return
    }
    if (wb.newFree != null) {
        AiNewFreePane(wb)
        return
    }
    if (wb.search != null) {
        AiWorkbenchSearchPane(wb)
        return
    }
    LaunchedEffect(wb) { wb.refresh() }
    val state by wb.facade.state.collectAsState()
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<AiDiscussionSummary?>(null) }
    confirmDelete?.let { target ->
        AiDeleteDialog(target.title, aiSourceText(target.source), state.globalBusy?.owner?.sessionId == target.sessionId, onDismiss = { confirmDelete = null }) {
            confirmDelete = null
            wb.delete(target)
        }
    }
    wb.choosing?.let { AiReferenceDialog(wb, it) }
    Column(Modifier.fillMaxSize()) {
        AiFilterRow(wb.filter) { wb.selectFilter(it) }
        // Outside the list, so the outcome of an action far down the list is still in sight.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            aiPhaseText(state.phase)?.let { AiWorkbenchNote(it, Modifier.testTag("ai-phase")) }
            wb.notice?.let { text -> Box(Modifier.testTag("ai-all-notice").clickable { wb.dismissNotice() }) { ErrorNotice(text) } }
            wb.info?.let { text -> AiWorkbenchInfo(text) { wb.dismissNotice() } }
            wb.loadError?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).testTag("ai-all-error")) { ErrorNotice("讨论读取失败：$error") }
                    TextButton(onClick = { wb.reload() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("ai-workbench"),
            state = wb.listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            if (wb.recent.isNotEmpty())
                item(key = "recent") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiSectionTitle("最近")
                        LazyRow(Modifier.testTag("ai-recent"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(wb.recent, key = { it.sessionId }) { AiRecentCard(it) { wb.open(it) } }
                        }
                    }
                }
            item(key = "groups-title") { AiSectionTitle(wb.filter?.let { aiTypeLabel(it) + "来源" } ?: "来源") }
            when {
                !wb.loaded && wb.loadError == null -> item(key = "loading") { AiWorkbenchNote("正在读取讨论…", Modifier.testTag("ai-all-loading")) }
                wb.loaded && wb.groups.isEmpty() -> item(key = "empty") { AiWorkbenchEmpty(wb.filter) }
            }
            items(wb.groups, key = { it.source.groupId.let { id -> id.ownerKind.name + ":" + id.sourceId } }) { group ->
                AiGroupCard(
                    wb,
                    group,
                    renameTarget,
                    onRename = { renameTarget = it.sessionId },
                    onRenameDone = { renameTarget = null },
                    onDelete = { confirmDelete = it },
                )
            }
            if (wb.hasMoreGroups)
                item(key = "more-groups") {
                    TextButton(onClick = { wb.moreGroups() }, enabled = !wb.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (wb.loading) "正在读取…" else "加载更多来源")
                    }
                }
        }
        AiWorkbenchBottomBar(wb)
    }
}

@Composable
private fun AiSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp).semantics { heading() },
    )
}

@Composable
private fun AiWorkbenchNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun AiWorkbenchInfo(text: String, onDismiss: () -> Unit) {
    Surface(
        onClick = onDismiss,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("ai-all-info"),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AiWorkbenchEmpty(filter: AiDiscussionType?) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp).testTag("ai-all-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AiTypeTile(filter ?: AiDiscussionType.FREE, 56.dp)
        Text(
            when (filter) {
                null -> "还没有 AI 讨论"
                AiDiscussionType.FREE -> "还没有自由对话"
                else -> "还没有${aiTypeLabel(filter)}讨论"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            when (filter) {
                AiDiscussionType.FREE -> "点右下角新建一段对话，首次发送后保存在这里。"
                AiDiscussionType.QUICK_NOTE -> "在速记阅读页打开 AI 学习助手即可开始。"
                null -> "在题目页或速记阅读页打开 AI 学习助手，或点右下角新建一段对话。"
                else -> "在题目页打开 AI 学习助手即可开始。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AiFilterRow(filter: AiDiscussionType?, onSelect: (AiDiscussionType?) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().testTag("ai-filters"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        item { AiFilterPill("全部", AppIcons.More, filter == null, "ALL") { onSelect(null) } }
        items(AI_WORKBENCH_FILTERS) { type -> AiFilterPill(aiTypeLabel(type), aiTypeIcon(type), filter == type, type.name) { onSelect(type) } }
    }
}

@Composable
private fun AiFilterPill(label: String, icon: ImageVector, selected: Boolean, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { this.selected = selected }.testTag("ai-filter-$tag"),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AiRecentCard(item: AiDiscussionSummary, onOpen: () -> Unit) {
    val type = item.source.type
    val color = aiTypeColor(type)
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(176.dp).heightIn(min = 128.dp).testTag("ai-recent-card"),
    ) {
        Column(
            Modifier
                .background(Brush.verticalGradient(listOf(color.copy(alpha = if (darkSurface()) 0.16f else 0.10f), Color.Transparent)))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiTypeTile(type, 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(aiTypeLabel(type), style = MaterialTheme.typography.labelMedium, color = color)
            }
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 44.dp))
            Text(
                aiSourceLine(item.source.label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiGroupCard(
    wb: AiWorkbenchController,
    group: AiDiscussionGroup,
    renameTarget: String?,
    onRename: (AiDiscussionSummary) -> Unit,
    onRenameDone: () -> Unit,
    onDelete: (AiDiscussionSummary) -> Unit,
) {
    val source = group.source
    val id = source.groupId
    val title = aiSourceLine(source.label)
    val open = id in wb.expanded
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    val canCreate = source.status != AiSourceStatus.DELETED
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("ai-group"),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (open) "收起" else "展开") { wb.toggle(group) }
                    .semantics { stateDescription = if (open) "已展开" else "已收起" }
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
                    .testTag("ai-group-head"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AiTypeTile(source.type, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("ai-group-title"))
                    Text(
                        listOfNotNull(
                                aiTypeLabel(source.type),
                                "${group.sessionCount} 条讨论",
                                if (source.status == AiSourceStatus.DELETED) "来源已删除" else null,
                            )
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (source.status == AiSourceStatus.DELETED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { if (source.type == AiDiscussionType.FREE) wb.chooseReference(group) else wb.create(group) },
                    enabled = canCreate && wb.creating == null,
                    modifier = Modifier.size(48.dp).testTag("ai-group-new"),
                ) {
                    Icon(
                        AppIcons.Plus,
                        contentDescription = if (canCreate) "在「$title」下新建讨论" else "来源已删除，不能新建讨论",
                        tint = if (canCreate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Icon(
                    AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp).size(20.dp).rotate(turn),
                )
            }
            AnimatedVisibility(open) {
                val page = wb.children[id] ?: AiGroupPage(loading = true)
                Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 52.dp, end = 8.dp, bottom = 4.dp))
                    page.items.forEach { item ->
                        AiChildRow(
                            item,
                            deleting = wb.deleting == item.sessionId,
                            menuEnabled = wb.deleting == null,
                            onOpen = { wb.open(item) },
                            onRename = { onRename(item) },
                            onDelete = { onDelete(item) },
                        )
                        if (renameTarget == item.sessionId) {
                            // Opened from a row near the bottom edge: scroll the whole editor into sight.
                            val sight = remember { BringIntoViewRequester() }
                            LaunchedEffect(Unit) { sight.bringIntoView() }
                            Box(Modifier.bringIntoViewRequester(sight).padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
                                AiListRenameEditor(item.sessionId, item.title, wb.renaming, onDismiss = onRenameDone) {
                                    wb.rename(item.sessionId, it, onRenameDone)
                                }
                            }
                        }
                    }
                    page.error?.let { Box(Modifier.padding(8.dp)) { ErrorNotice("讨论读取失败：$it") } }
                    when {
                        page.loading -> AiWorkbenchNote("正在读取…", Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp))
                        page.next != null ->
                            TextButton(onClick = { wb.moreIn(group) }, modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp)) {
                                Text("加载更多讨论")
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiChildRow(
    item: AiDiscussionSummary,
    deleting: Boolean,
    menuEnabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !deleting, onClick = onOpen)
            .heightIn(min = 52.dp)
            .padding(start = 17.dp)
            .testTag("ai-child"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(aiTypeColor(item.source.type).copy(alpha = 0.7f)))
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val note =
                when {
                    item.source.status == AiSourceStatus.UPDATED -> "旧版来源 · 只读"
                    item.source.reference != null -> aiReferenceText(item.source.reference)
                    else -> null
                }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (deleting) AiWorkbenchNote("正在删除…", Modifier.testTag("ai-child-deleting"))
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = menuEnabled, modifier = Modifier.size(48.dp).testTag("ai-child-menu")) {
                Icon(AppIcons.MoreVert, contentDescription = "更多操作：" + item.title, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(AppIcons.Pencil, contentDescription = null) },
                    onClick = {
                        menu = false
                        onRename()
                    },
                    modifier = Modifier.testTag("ai-child-rename"),
                )
                DropdownMenuItem(
                    text = { Text("删除讨论", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(AppIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("ai-child-delete"),
                )
            }
        }
    }
}

/** Search on the left, "临时问 AI" in the middle, a new free conversation on the right. */
@Composable
private fun AiWorkbenchBottomBar(wb: AiWorkbenchController) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).testTag("ai-bottom-bar"),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            onClick = { wb.startSearch() },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(48.dp).testTag("ai-search-open"),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Search, contentDescription = "搜索讨论", modifier = Modifier.size(22.dp)) }
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Sparkles, contentDescription = null, tint = aiTypeColor(AiDiscussionType.TEMPORARY), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (wb.temporaryText.isEmpty())
                        Text("临时问 AI，不保留记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        value = wb.temporaryText,
                        onValueChange = { wb.temporaryText = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { wb.askTemporary() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "临时问 AI" }.testTag("ai-temp-field"),
                    )
                }
                if (wb.temporaryText.isNotBlank())
                    IconButton(onClick = { wb.askTemporary() }, enabled = !wb.openingTemporary, modifier = Modifier.size(44.dp).testTag("ai-temp-send")) {
                        Icon(AppIcons.Send, contentDescription = "发送临时提问", tint = MaterialTheme.colorScheme.primary)
                    }
                else Spacer(Modifier.width(10.dp))
            }
        }
        FilledIconButton(onClick = { wb.newConversation() }, modifier = Modifier.size(48.dp).testTag("ai-new-free")) {
            Icon(AppIcons.SquarePen, contentDescription = "新建自由对话", modifier = Modifier.size(22.dp))
        }
    }
}

/** The 对话 group's ＋: quote one discussion of the group, or start empty. */
@Composable
private fun AiReferenceDialog(wb: AiWorkbenchController, choice: AiReferenceChoice) {
    var picked by remember(choice.group) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { wb.dismissChoice() },
        title = { Text("在「${aiSourceLine(choice.group.source.label)}」中新开对话") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("ai-reference-choice"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "可以引用同组一条对话的最近内容（最多四轮），作为新对话的参考；不引用则从空白开始。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AiChoiceRow("不引用，新开空白对话", picked == null, "ai-reference-none") { picked = null }
                choice.options.forEach { o -> AiChoiceRow("引用「${o.title}」", picked == o.sessionId, "ai-reference-option") { picked = o.sessionId } }
                when {
                    choice.loading -> AiWorkbenchNote("正在读取…")
                    choice.next != null ->
                        TextButton(
                            onClick = { wb.moreReferences() },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("ai-reference-more"),
                        ) {
                            Text("加载更早的对话")
                        }
                }
                if (picked != null) AiWorkbenchNote("开始后会把引用内容随新对话保存；超出长度时只保留节选。")
                choice.error?.let { ErrorNotice(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { wb.pickReference(picked) }, enabled = choice.previewing == null, modifier = Modifier.testTag("ai-reference-confirm")) {
                Text(if (choice.previewing != null) "正在准备…" else "开始")
            }
        },
        dismissButton = { TextButton(onClick = { wb.dismissChoice() }) { Text("取消") } },
    )
}

@Composable
private fun AiChoiceRow(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A saved free conversation before its first send: nothing is stored until then. */
@Composable
private fun AiNewFreePane(wb: AiWorkbenchController) {
    val plan = wb.newFree ?: return
    BackHandler { wb.cancelNewFree() }
    val source =
        AiDiscussionSource(
            AiDiscussionGroupId(AiOwnerKind.FREE, plan.groupId.orEmpty()),
            AiDiscussionType.FREE,
            plan.topic ?: "新话题",
            AiSourceStatus.INDEPENDENT,
            null,
            plan.reference,
        )
    Column(Modifier.fillMaxSize().testTag("ai-new-free-pane"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (plan.topic == null) "新建自由对话" else "在「${aiSourceLine(plan.topic)}」中新开对话",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
        )
        AiSourceCard(source, opening = false, onOpen = null)
        wb.notice?.let { text -> Box(Modifier.testTag("ai-all-notice").clickable { wb.dismissNotice() }) { ErrorNotice(text) } }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiTypeTile(AiDiscussionType.FREE, 48.dp)
                AiWorkbenchNote(
                    if (plan.reference != null) "发送第一条消息后保存这段对话和所选引用。"
                    else "发送第一条消息后保存到「对话」，不会带上题目、速记或其他对话。",
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 10.dp)) {
            OutlinedTextField(
                value = wb.freeText,
                onValueChange = { wb.freeText = it },
                placeholder = { Text("想聊点什么？") },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).testTag("ai-free-field"),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { wb.startFree() },
                enabled = wb.freeText.isNotBlank() && !wb.starting,
                modifier = Modifier.size(48.dp).testTag("ai-free-send"),
            ) {
                Icon(AppIcons.Send, contentDescription = "发送并保存对话")
            }
        }
    }
}

/** The temporary chat: the first message goes out on open; leaving stops it and discards everything. */
@Composable
private fun AiTemporaryPane(wb: AiWorkbenchController, chat: AiTemporaryChat) {
    val controller =
        remember(chat.sessionId) { AiChatController({ wb.facade }, UnlinkedAiSource(chat.owner, wb.model), wb.scope) }
    LaunchedEffect(controller) { controller.openAndSend(chat.sessionId, chat.firstText) }
    DisposableEffect(controller) {
        onDispose {
            controller.back()
            wb.closeTemporary()
        }
    }
    val source = AiDiscussionSource(AiDiscussionGroupId(AiOwnerKind.TEMPORARY, chat.sessionId), AiDiscussionType.TEMPORARY, "临时对话", AiSourceStatus.INDEPENDENT, null)
    AiChatScreen(
        controller,
        Modifier.testTag("ai-temp-chat"),
        onLeaveSession = { wb.closeTemporary() },
        sourceHeader = { AiSourceCard(source, opening = false, onOpen = null) },
        manageable = false,
    )
}

@Composable
private fun AiWorkbenchSearchPane(wb: AiWorkbenchController) {
    val s = wb.search ?: return
    BackHandler { wb.endSearch() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { wb.endSearch() }, modifier = Modifier.size(48.dp)) {
                Icon(AppIcons.Back, contentDescription = "退出搜索")
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (s.query.isEmpty())
                            Text("搜索讨论", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BasicTextField(
                            value = s.query,
                            onValueChange = { wb.query(it) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "搜索讨论" }.testTag("ai-search-field"),
                        )
                    }
                    if (s.query.isNotEmpty())
                        IconButton(onClick = { wb.query("") }, modifier = Modifier.size(44.dp)) {
                            Icon(AppIcons.Close, contentDescription = "清除搜索")
                        }
                }
            }
        }
        AiWorkbenchNote(
            "搜索所有已保存讨论的标题、来源、你的提问和 AI 回复；临时对话不在其中。",
            Modifier.padding(horizontal = 4.dp).testTag("ai-search-scope"),
        )
        s.error?.let { ErrorNotice("搜索失败：$it") }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("ai-search-results"),
            state = wb.searchListState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(s.hits, key = { it.summary.sessionId }) { hit -> AiSearchRow(hit) { wb.open(hit.summary) } }
            when {
                s.searching -> item(key = "searching") { AiWorkbenchNote("正在搜索…", Modifier.padding(4.dp)) }
                s.next != null ->
                    item(key = "more") {
                        TextButton(onClick = { wb.moreHits() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("更多结果") }
                    }
                s.searched && s.hits.isEmpty() && s.error == null ->
                    item(key = "none") { AiWorkbenchNote("没有找到包含「${s.query.trim()}」的讨论。", Modifier.padding(4.dp).testTag("ai-search-none")) }
            }
        }
    }
}

@Composable
private fun AiSearchRow(hit: AiSearchView, onOpen: () -> Unit) {
    val item = hit.summary
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("ai-search-hit"),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            AiTypeTile(item.source.type, 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    aiSourceText(item.source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val mark = MaterialTheme.colorScheme.primary
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(hit.field + "：") }
                        val m = hit.match
                        if (m == null) append(hit.snippet)
                        else {
                            val start = m.first.coerceIn(0, hit.snippet.length)
                            val end = (m.last + 1).coerceIn(start, hit.snippet.length)
                            append(hit.snippet.substring(0, start))
                            withStyle(SpanStyle(color = mark, fontWeight = FontWeight.Bold)) { append(hit.snippet.substring(start, end)) }
                            append(hit.snippet.substring(end))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("ai-search-snippet"),
                )
            }
        }
    }
}
