package cn.embeddedquiz

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * 更多 › AI 讨论 (PLAN-1.7.3): every saved discussion of every question and 速记 document, newest
 * first, from GPT's allSessions. A row opens that exact discussion on this page with the same
 * AiChatScreen and controller as the original entries, through the question or document adapter;
 * back returns to this list, then to 更多. Renaming reuses the original entries' call and checks.
 */

/**
 * The adapter a discussion would have on its original page. The current question or document is
 * used when it still exists; otherwise the owner and label from the list keep the old discussion
 * readable (the backend marks it read only). There is no reading anchor here, so a document
 * discussion has no section, as PLAN-1.7.3 asks.
 */
internal fun aiDiscussionSource(
    item: AiDiscussionListItem,
    question: (String) -> QuestionSummary?,
    document: (String) -> QuickNoteSummary?,
    model: () -> String,
    savedCode: (String) -> String,
): AiChatSource =
    when (item.owner.kind) {
        AiOwnerKind.QUESTION -> {
            val current = question(item.owner.questionId)
            val owner = current?.let { QuestionOwner(it.id, it.contentHash) } ?: item.owner
            // Only this question's own saved code goes with a code review; no answer choices are guessed.
            QuestionAiSource(owner, programming = current?.daily == false) {
                AiSendContext(emptySet(), savedCode(owner.questionId), model())
            }
        }
        AiOwnerKind.QUICK_NOTE -> {
            val summary =
                document(item.owner.questionId)
                    ?: QuickNoteSummary(item.owner.questionId, item.sourceLabel, "", item.owner.contentHash, 0)
            DocumentAiSource(summary, null, model)
        }
    }

internal fun aiDiscussionKind(item: AiDiscussionListItem) =
    if (item.owner.kind == AiOwnerKind.QUICK_NOTE) "速记" else "题目"

@Stable
internal class AiDiscussionsController(
    /** Resolved on first use, like the discussion pages: the list reads the same facade. */
    facadeProvider: () -> AiConversationFacade,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val facade: AiConversationFacade by lazy(facadeProvider)

    var items by mutableStateOf<List<AiDiscussionListItem>>(emptyList())
        private set
    private var next: PageCursor? = null
    var hasMore by mutableStateOf(false)
        private set
    /** False until the first page has answered, so an empty list is not claimed too early. */
    var loaded by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    /** Set when the list itself could not be read; the page offers a retry. */
    var loadError by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set
    var renaming by mutableStateOf(false)
        private set

    /** The discussion shown on this page; null shows the list. */
    var opened by mutableStateOf<AiDiscussionListItem?>(null)
        private set

    private suspend fun awaitStarted() {
        facade.state.first { it.phase != ServicePhase.INITIALIZING }
    }

    /**
     * Reads pages until at least [atLeast] items are loaded (or none remain), so coming back keeps
     * what was on screen with current titles. A cursor made stale by a restore or deletion starts over.
     */
    private suspend fun reload(atLeast: Int, include: String? = null) {
        loading = true
        try {
            var list = emptyList<AiDiscussionListItem>()
            var cursor: PageCursor? = null
            do {
                when (val r = facade.allSessions(cursor, AI_PAGE_SIZE)) {
                    is ResultOf.Ok -> {
                        list = (list + r.value.items).distinctBy { it.sessionId }
                        cursor = r.value.next
                    }
                    is ResultOf.Rejected -> {
                        loadError = aiRejectionText(r.reason)
                        return
                    }
                }
            } while (cursor != null && (list.size < atLeast || (include != null && list.none { it.sessionId == include })))
            items = list
            next = cursor
            hasMore = cursor != null
            loadError = null
            loaded = true
        } finally {
            loading = false
        }
    }

    suspend fun refresh() {
        awaitStarted()
        reload(atLeast = maxOf(items.size, 1))
    }

    fun retry() = scope.launch { refresh() }

    fun more() =
        scope.launch {
            val cursor = next ?: return@launch
            if (loading) return@launch
            loading = true
            try {
                when (val r = facade.allSessions(cursor, AI_PAGE_SIZE)) {
                    is ResultOf.Ok -> {
                        items = (items + r.value.items).distinctBy { it.sessionId }
                        next = r.value.next
                        hasMore = r.value.next != null
                    }
                    is ResultOf.Rejected ->
                        if (r.reason.code == RejectCode.STALE_CURSOR) {
                            loading = false
                            notice = aiRejectionText(r.reason)
                            reload(atLeast = items.size + AI_PAGE_SIZE)
                        } else notice = aiRejectionText(r.reason)
                }
            } finally {
                loading = false
            }
        }

    fun open(item: AiDiscussionListItem) {
        notice = null
        info = null
        opened = item
    }

    /** Back from a discussion: the list returns as it was, with titles as they are now. */
    fun closeOpened() {
        opened = null
        scope.launch { refresh() }
    }

    /**
     * Renames from the list. [onDone] runs only on success, so a failed rename keeps the editor
     * and what was typed; a stale revision reloads the list, after which saving again is confirmed
     * against the current name.
     */
    fun rename(sessionId: String, title: String, onDone: () -> Unit) =
        scope.launch {
            if (renaming) return@launch
            val item = items.firstOrNull { it.sessionId == sessionId } ?: return@launch
            renaming = true
            notice = null
            info = null
            try {
                awaitStarted()
                when (val r = aiRename(facade, newId(), item.sessionId, item.sessionRevision, title)) {
                    is ResultOf.Ok -> {
                        items =
                            items.map {
                                if (it.sessionId == r.value.sessionId)
                                    it.copy(title = r.value.title, sessionRevision = r.value.sessionRevision)
                                else it
                            }
                        info = "已重命名为「${r.value.title}」。"
                        onDone()
                    }
                    is ResultOf.Rejected -> {
                        notice = aiRenameRejectionText(r.reason, listed = true)
                        if (r.reason.code == RejectCode.STALE_SESSION || r.reason.code == RejectCode.NOT_FOUND)
                            reload(atLeast = items.size, include = item.sessionId)
                    }
                }
            } finally {
                renaming = false
            }
        }

    fun dismissNotice() {
        notice = null
        info = null
    }
}

@Composable
fun AiDiscussionsScreen(vm: QuizViewModel) {
    val scope = rememberCoroutineScope()
    val controller = remember(vm) { AiDiscussionsController({ vm.aiConversations }, scope) }
    AiDiscussionsContent(controller) { item, onLeave -> AiDiscussionPage(vm, item, onLeave) }
}

/** One discussion, opened by id with its original adapter, as on the question or 速记 page. */
@Composable
private fun AiDiscussionPage(vm: QuizViewModel, item: AiDiscussionListItem, onLeave: () -> Unit) {
    val controller =
        remember(item.sessionId) {
            val source =
                aiDiscussionSource(
                    item,
                    question = { id -> vm.questions.firstOrNull { it.id == id } },
                    document = { id -> vm.quickNotes.state.documents.firstOrNull { it.id == id } },
                    model = { vm.model },
                    savedCode = { id -> vm.p(id).code },
                )
            val noteSaver =
                if (item.owner.kind == AiOwnerKind.QUESTION) AiNoteSaver { commandId, reply -> vm.saveAiReplyAsStickyNote(commandId, reply) } else null
            // The ViewModel's scope, so leaving the page still saves and releases the draft.
            AiChatController({ vm.aiConversations }, source, vm.viewModelScope, noteSaver)
        }
    AiDiscussionChat(controller, item, onLeave) { note ->
        controller.leave {
            vm.openStickyNotes()
            vm.editStickyNote(note)
        }
    }
}

/** Opens [item] in [controller] and shows it alone; leaving it returns to the list. */
@Composable
internal fun AiDiscussionChat(
    controller: AiChatController,
    item: AiDiscussionListItem,
    onLeave: () -> Unit,
    onOpenNote: ((StickyNote) -> Unit)? = null,
) {
    LaunchedEffect(controller) { controller.open(item.sessionId) }
    DisposableEffect(controller) { onDispose { controller.back() } }
    AiChatScreen(
        controller,
        Modifier.testTag("ai-all-chat"),
        onOpenNote = if (controller.canSaveNotes) onOpenNote else null,
        onLeaveSession = onLeave,
    )
}

@Composable
internal fun AiDiscussionsContent(
    controller: AiDiscussionsController,
    discussion: @Composable (AiDiscussionListItem, onLeave: () -> Unit) -> Unit,
) {
    // Kept while a discussion is open, so back lands where the list was.
    val listState = rememberLazyListState()
    val opened = controller.opened
    if (opened != null) {
        discussion(opened) { controller.closeOpened() }
        return
    }
    val state by controller.facade.state.collectAsState()
    LaunchedEffect(controller) { controller.refresh() }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        aiPhaseText(state.phase)?.let { AiDiscussionsNote(it, Modifier.testTag("ai-phase")) }
        controller.notice?.let { text ->
            Box(Modifier.testTag("ai-all-notice").clickable { controller.dismissNotice() }) { ErrorNotice(text) }
        }
        controller.info?.let { AiDiscussionsNote(it, Modifier.testTag("ai-all-info")) }
        renameTarget?.let { id -> controller.items.firstOrNull { it.sessionId == id } }?.let { target ->
            AiListRenameEditor(target.sessionId, target.title, controller.renaming, onDismiss = { renameTarget = null }) {
                controller.rename(target.sessionId, it) { renameTarget = null }
            }
        }
        controller.loadError?.let { error ->
            Box(Modifier.testTag("ai-all-error")) { ErrorNotice("讨论列表读取失败：$error") }
            TextButton(onClick = { controller.retry() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
        }
        when {
            !controller.loaded && controller.loadError == null ->
                AiDiscussionsNote("正在读取讨论…", Modifier.testTag("ai-all-loading"))
            controller.loaded && controller.items.isEmpty() ->
                AiDiscussionsNote(
                    "还没有 AI 讨论。在题目页或速记阅读页打开 AI 学习助手即可开始。",
                    Modifier.testTag("ai-all-empty"),
                )
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("ai-all-sessions"),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(controller.items, key = { it.sessionId }) { item ->
                AiDiscussionRow(item, onRename = { renameTarget = item.sessionId }) { controller.open(item) }
            }
            if (controller.hasMore)
                item(key = "more") {
                    TextButton(
                        onClick = { controller.more() },
                        enabled = !controller.loading,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (controller.loading) "正在读取…" else "加载更多讨论")
                    }
                }
        }
    }
}

@Composable
private fun AiDiscussionsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun AiDiscussionRow(item: AiDiscussionListItem, onRename: () -> Unit, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ai-all-row"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    aiDiscussionKind(item) + " · " + item.sourceLabel.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("ai-all-origin"),
                )
                if (item.readOnly)
                    Text(
                        if (item.owner.kind == AiOwnerKind.QUICK_NOTE) "文档已更新或删除 · 只读" else "题目已更新或删除 · 只读",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("ai-all-menu")) {
                    Icon(AppIcons.MoreVert, contentDescription = "更多操作：" + item.title)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menu = false
                            onRename()
                        },
                        modifier = Modifier.testTag("ai-all-rename"),
                    )
                }
            }
        }
    }
}
