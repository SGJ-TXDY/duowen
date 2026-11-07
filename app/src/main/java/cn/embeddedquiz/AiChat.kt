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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * AI conversation UI on the frozen AiConversationFacade (AI_02_CONTRACT_FREEZE_20261106): session
 * list, title, messages, input draft, copy and "jump to the latest reply". Sending, retry and cancel
 * are not part of this batch. Nothing here is mounted in the app yet; the host wires a real facade
 * once the backend is ready.
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

@Stable
internal class AiChatController(
    val facade: AiConversationFacade,
    val question: QuestionOwner,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
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

    private val saveLock = Mutex()
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
        when (val r = facade.sessions(question.questionId, includeArchived = true, cursor, AI_PAGE_SIZE)) {
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

    fun create() =
        scope.launch {
            notice = null
            when (val r = facade.createSession(newId(), question)) {
                is ResultOf.Ok -> {
                    loadSessions(reset = true)
                    openNow(r.value.session.sessionId)
                }
                is ResultOf.Rejected -> notice = aiRejectionText(r.reason)
            }
        }

    fun open(sessionId: String) = scope.launch { openNow(sessionId) }

    private suspend fun openNow(sessionId: String) {
        notice = null
        if (opened != null && !leaveNow()) return
        when (val r = facade.open(sessionId)) {
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

    // ---- open session ----

    fun back() = scope.launch { leaveNow() }

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
        when (val r = facade.deselect()) {
            is ResultOf.Ok -> Unit
            is ResultOf.Rejected -> {
                notice = aiRejectionText(r.reason)
                return false
            }
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

    fun rename(title: String) =
        scope.launch {
            notice = null
            val s = current() ?: return@launch
            val t = title.trim()
            if (t.codePointCount(0, t.length) !in 1..AI_TITLE_MAX) {
                notice = "标题需为 1～$AI_TITLE_MAX 个字。"
                return@launch
            }
            when (val r = facade.rename(newId(), s.sessionId, s.sessionRevision, t)) {
                is ResultOf.Ok -> {
                    opened = opened?.copy(title = r.value.title, sessionRevision = r.value.sessionRevision)
                    sessions =
                        sessions.map {
                            if (it.sessionId == s.sessionId)
                                it.copy(title = r.value.title, sessionRevision = r.value.sessionRevision)
                            else it
                        }
                }
                is ResultOf.Rejected -> notice = aiRejectionText(r.reason)
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
    }
}

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
        RunStatus.FAILED -> "失败" + (run.errorCode?.let { "（$it）" } ?: "")
    }

@Composable
internal fun rememberAiChatController(facade: AiConversationFacade, question: QuestionOwner): AiChatController {
    val scope = rememberCoroutineScope()
    val controller = remember(facade, question) { AiChatController(facade, question, scope) }
    LaunchedEffect(controller) { controller.loadSessions(reset = true) }
    return controller
}

/** The whole conversation page: the session list, or the open session with its messages and draft. */
@Composable
internal fun AiChatScreen(controller: AiChatController, modifier: Modifier = Modifier) {
    val state by controller.facade.state.collectAsState()
    val session = controller.current(state)
    BackHandler(enabled = session != null) { controller.back() }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        aiPhaseText(state.phase)?.let { InlineNote(it, Modifier.testTag("ai-phase")) }
        state.storageFault?.let {
            ErrorNotice(if (it.recoverByRestart) "AI 存储故障（${it.code}），请重启应用。" else "AI 存储故障（${it.code}）。")
        }
        controller.notice?.let { text ->
            Box(Modifier.testTag("ai-notice").clickable { controller.dismissNotice() }) { ErrorNotice(text) }
        }
        if (session == null) AiSessionList(controller, state, Modifier.weight(1f))
        else AiSessionPane(controller, state, session, Modifier.weight(1f))
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
private fun AiSessionList(controller: AiChatController, state: AiConversationUiState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        if (controller.sessions.isEmpty())
            InlineNote("还没有讨论。点“新建讨论”开始。", Modifier.testTag("ai-sessions-empty"))
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("ai-sessions"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(controller.sessions, key = { it.sessionId }) { s -> AiSessionRow(s, controller.question) { controller.open(s.sessionId) } }
            if (controller.hasMoreSessions)
                item(key = "more") {
                    TextButton(onClick = { controller.moreSessions() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("加载更多讨论")
                    }
                }
        }
    }
}

@Composable
private fun AiSessionRow(s: SessionView, question: QuestionOwner, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ai-session-row"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val tags =
                buildList {
                    if (s.readOnly) add("只读")
                    if (s.question.contentHash != question.contentHash) add("旧版题目")
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
        }
    }
}

@Composable
private fun AiSessionPane(
    controller: AiChatController,
    state: AiConversationUiState,
    session: SessionView,
    modifier: Modifier,
) {
    var renaming by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.back() }, modifier = Modifier.size(48.dp)) {
                Icon(AppIcons.Back, contentDescription = "返回讨论列表")
            }
            Text(
                session.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() }.testTag("ai-title"),
            )
            if (!renaming)
                IconButton(onClick = { renaming = true }, modifier = Modifier.size(48.dp)) {
                    Icon(AppIcons.Pencil, contentDescription = "重命名讨论")
                }
        }
        if (renaming)
            AiRenameEditor(session.title, onDismiss = { renaming = false }) {
                controller.rename(it)
                renaming = false
            }
        if (session.readOnly)
            InlineNote("题目已更新，这段讨论只能查看和复制。", Modifier.testTag("ai-read-only"))
        val busy = state.globalBusy
        if (busy != null)
            InlineNote(
                if (busy.owner.sessionId == session.sessionId) "正在生成回复…" else "AI 正在处理其他讨论。",
                Modifier.testTag("ai-busy"),
            )
        AiMessageList(controller, state, session, Modifier.weight(1f))
        AiDraftBox(controller, session)
    }
}

@Composable
private fun AiMessageList(
    controller: AiChatController,
    state: AiConversationUiState,
    session: SessionView,
    modifier: Modifier,
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
            items(messages, key = { it.messageId }) { m -> AiMessage(m) }
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
private fun AiMessage(m: MessageView) {
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
        m.run?.let(::aiUsageText)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = aiCostTextColor(MaterialTheme.colorScheme), modifier = Modifier.testTag("ai-usage"))
        }
        AiCopyButton(m.text, "复制回复")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
private fun AiDraftBox(controller: AiChatController, session: SessionView) {
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
