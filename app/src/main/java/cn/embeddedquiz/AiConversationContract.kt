package cn.embeddedquiz

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal

enum class AiOwnerKind { QUESTION, QUICK_NOTE, FREE, TEMPORARY }
data class QuestionOwner(val questionId: String, val contentHash: String, val kind: AiOwnerKind = AiOwnerKind.QUESTION)
data class RunOwner(val question: QuestionOwner, val sessionId: String, val requestId: String)
data class DraftToken(val editorEpoch: String, val localEditVersion: Long)
data class DraftView(val text: String, val revision: Long, val pending: Boolean, val savedToken: DraftToken?)
data class SessionView(
    val sessionId: String, val question: QuestionOwner, val title: String,
    val sessionRevision: Long, val contextRevision: Long, val draft: DraftView,
    val readOnly: Boolean, val snapshotVersion: Int, val questionSnapshot: String,
)
enum class RunStatus { PREPARING, GENERATING, COMPLETED, CANCELLED, FAILED }
enum class TextState { EMPTY, PARTIAL, COMPLETE }
enum class UsageState { KNOWN, PARTIAL, UNKNOWN, INVALID, NOT_APPLICABLE }
enum class RunSource { NETWORK, CACHE }
enum class TransportPhase { PREPARING, CONNECTING, READING, CLEANING }
enum class ServicePhase { INITIALIZING, READY, REPLACING, STORAGE_ERROR, CLOSING, CLOSED }
data class StorageFault(val code: String, val recoverByRestart: Boolean)
data class BusyRun(val owner: RunOwner, val phase: TransportPhase)
data class UsageView(
    val input: Long?, val output: Long?, val cacheHit: Long?, val state: UsageState,
    val estimated: BigDecimal?, val currency: String, val priceVersion: String?,
)
data class RunView(
    val owner: RunOwner, val status: RunStatus, val source: RunSource,
    val retryOf: String?, val sourceRequestId: String?, val usage: UsageView,
    val errorCode: String?, val canRetry: Boolean,
    val documentReferences: List<QuickNoteAiReference> = emptyList(),
    val documentCoverage: QuickNoteAiCoverage? = null,
)
data class MessageView(
    val messageId: String, val sessionId: String, val seq: Long, val role: MessageRole,
    val text: String, val completion: TextState, val run: RunView?,
)
enum class MessageRole { USER, ASSISTANT }
data class PageCursor(val opaque: String)
data class AiPage<T>(val items: List<T>, val next: PageCursor?)

// readOnly describes message and draft permissions. Archived discussions can still be renamed.
data class AiDiscussionListItem(
    val sessionId: String,
    val owner: QuestionOwner,
    val title: String,
    val sessionRevision: Long,
    val createdAt: Long,
    val sourceLabel: String,
    val readOnly: Boolean,
)
/** Use null for the workbench's “全部” filter. Programming is a presentation type of question owner. */
enum class AiDiscussionType { QUESTION, PROGRAMMING, QUICK_NOTE, FREE, TEMPORARY }
enum class AiSourceStatus { CURRENT, UPDATED, DELETED, INDEPENDENT }

data class AiDiscussionGroupId(val ownerKind: AiOwnerKind, val sourceId: String)
data class AiDiscussionReference(val sessionId: String, val title: String, val partial: Boolean)
data class AiDiscussionSource(
    val groupId: AiDiscussionGroupId,
    val type: AiDiscussionType,
    val label: String,
    val status: AiSourceStatus,
    val currentOwner: QuestionOwner?,
    val reference: AiDiscussionReference? = null,
)
data class AiDiscussionGroup(
    val source: AiDiscussionSource,
    val sessionCount: Int,
    val lastActivityAt: Long,
)
data class AiDiscussionSummary(
    val discussion: AiDiscussionListItem,
    val source: AiDiscussionSource,
    val lastMessageAt: Long?,
)
data class AiDiscussionSearchHit(val summary: AiDiscussionSummary, val snippet: String)
data class AiFreeDiscussionCommand(
    val commandId: String,
    val input: String,
    val model: String,
    val groupId: String? = null,
    val referenceSessionId: String? = null,
)

// limit 1..100；cursor 含 datasetEpoch/query/sort 绑定，恢复后失效；不使用裸 offset。
sealed interface HistoryFilter {
    data class Exact(val question: QuestionOwner) : HistoryFilter
    data class UnknownHash(val questionId: String) : HistoryFilter
    data class AllVersions(val questionId: String) : HistoryFilter
}
data class LegacyEntryView(
    val cacheKey: String, val questionId: String, val contentHash: String?,
    val prompt: String, val response: String, val createdAt: Long,
    val model: String, val originalEstimatedUsd: BigDecimal,
) // contentHash=null 表示旧空 hash，仅只读；列表稳定 key=cacheKey。
data class AiConversationUiState(
    val phase: ServicePhase, val selectedSessionId: String?,
    val selected: SessionView?, val firstMessages: AiPage<MessageView>,
    val selectedRun: RunView?, val globalBusy: BusyRun?,
    val storageFault: StorageFault?, val datasetEpoch: String, val activeEditorEpoch: String?,
) // selectedSessionId 只是页面选择，不能用来补全 RunOwner。

data class DraftCommand(
    val commandId: String, val sessionId: String, val expectedDraftRevision: Long,
    val token: DraftToken, val text: String,
)
data class SendCommand(
    val commandId: String, val sessionId: String, val question: QuestionOwner,
    val expectedContextRevision: Long, val draftToken: DraftToken?,
    val savedDraftRevision: Long?, val input: String, val choices: Set<String>,
    val code: String, val requestType: AiRequestType, val model: String,
    val documentSectionId: String? = null,
) // 实际 provider 参数由后端版本化配置冻结；不让 UI 传任意 HTTP 参数。
data class RetryCommand(val commandId: String, val owner: RunOwner)
data class CreateReceipt(val session: SessionView)
data class OpenReceipt(val session: SessionView, val editorEpoch: String)
data class RenameReceipt(val sessionId: String, val sessionRevision: Long, val title: String)
data class DraftReceipt(val sessionId: String, val draftRevision: Long, val token: DraftToken)
data class FlushReceipt(val sessionId: String, val throughDraftRevision: Long)
data class AcceptedReceipt(
    val commandId: String, val owner: RunOwner,
    val userMessageId: String, val assistantMessageId: String,
    val contextRevision: Long, val draftRevision: Long,
    val acceptedDraftToken: DraftToken?, val draftCleared: Boolean,
) // ACCEPTED=冻结快照/消息/run 的事务已提交，不表示网络已完成。
data class CancelReceipt(
    val owner: RunOwner, val terminal: RunStatus, val cleanupPending: Boolean,
)
data class IdleReceipt(val datasetEpoch: String)
data class ClosedReport(val storageFault: StorageFault?)
enum class RejectCode {
    NOT_READY, BUSY, READ_ONLY, NOT_FOUND, INVALID,
    STALE_CONTEXT, STALE_SESSION, STALE_DRAFT, STALE_OWNER, STALE_ATTEMPT,
    STALE_CURSOR, IDEMPOTENCY_CONFLICT, ALREADY_TERMINAL,
    CAPACITY_EXCEEDED, STORAGE_FAILED, REPLACING, CLOSING, CLOSED,
}
enum class RecoveryAction { WAIT_IDLE, REOPEN, RELOAD, EDIT, EXPORT_BACKUP, RESTART, NONE }
data class Rejection(
    val code: RejectCode, val retryable: Boolean, val recovery: RecoveryAction,
    val busy: BusyRun? = null, val currentSession: SessionView? = null,
    val terminal: RunStatus? = null,
)
sealed interface ResultOf<out T> {
    data class Ok<T>(val value: T) : ResultOf<T>
    data class Rejected(val reason: Rejection) : ResultOf<Nothing>
}
interface AiConversationFacade {
    val state: StateFlow<AiConversationUiState>
    suspend fun createSession(commandId: String, question: QuestionOwner): ResultOf<CreateReceipt>
    suspend fun open(sessionId: String): ResultOf<OpenReceipt> // 允许打开归档；不发网络。
    suspend fun deleteSession(sessionId: String, owner: QuestionOwner): ResultOf<Unit> =
        ResultOf.Rejected(Rejection(RejectCode.INVALID, false, RecoveryAction.NONE))
    suspend fun deselect(): ResultOf<Unit> // 先由 UI 保存/flush 草稿，再退出选择。
    suspend fun allSessions(cursor: PageCursor?, limit: Int): ResultOf<AiPage<AiDiscussionListItem>>

    suspend fun discussionGroups(type: AiDiscussionType?, cursor: PageCursor?, limit: Int): ResultOf<AiPage<AiDiscussionGroup>> =
        unsupportedWorkbench()
    suspend fun groupDiscussions(groupId: AiDiscussionGroupId, cursor: PageCursor?, limit: Int): ResultOf<AiPage<AiDiscussionSummary>> =
        unsupportedWorkbench()
    suspend fun recentDiscussions(type: AiDiscussionType?, cursor: PageCursor?, limit: Int): ResultOf<AiPage<AiDiscussionSummary>> =
        unsupportedWorkbench()
    suspend fun discussionSource(sessionId: String): ResultOf<AiDiscussionSource> = unsupportedWorkbench()
    /** Search all saved discussions. The trimmed query accepts 1–120 Unicode code points. */
    suspend fun searchDiscussions(query: String, cursor: PageCursor?, limit: Int): ResultOf<AiPage<AiDiscussionSearchHit>> =
        unsupportedWorkbench()
    /** Start a question/programming/note discussion with the source's current version. */
    suspend fun createGroupDiscussion(commandId: String, groupId: AiDiscussionGroupId): ResultOf<CreateReceipt> =
        unsupportedWorkbench()
    /** Preview a same-group reference. Start freezes up to four turns within an 8 KiB budget. */
    suspend fun previewDiscussionReference(groupId: String, sessionId: String): ResultOf<AiDiscussionReference> =
        unsupportedWorkbench()
    /** First send atomically creates the saved discussion. Open the accepted owner by session ID. */
    suspend fun startFreeDiscussion(command: AiFreeDiscussionCommand): ResultOf<AcceptedReceipt> = unsupportedWorkbench()
    /** The returned session uses the same draft, send, message and stop operations as saved discussions. */
    suspend fun openTemporaryDiscussion(): ResultOf<OpenReceipt> = unsupportedWorkbench()
    /** Cancel and drain this temporary session before returning. Its content is then discarded. */
    suspend fun closeTemporaryDiscussion(sessionId: String): ResultOf<Unit> = unsupportedWorkbench()

    suspend fun sessions(questionId: String, includeArchived: Boolean,
        cursor: PageCursor?, limit: Int): ResultOf<AiPage<SessionView>>
    suspend fun documentSessions(document: QuickNoteSummary, includeArchived: Boolean,
        cursor: PageCursor?, limit: Int): ResultOf<AiPage<SessionView>> =
        ResultOf.Rejected(Rejection(RejectCode.INVALID, false, RecoveryAction.NONE))
    suspend fun createDocumentSession(commandId: String, document: QuickNoteSummary): ResultOf<CreateReceipt> =
        ResultOf.Rejected(Rejection(RejectCode.INVALID, false, RecoveryAction.NONE))
    suspend fun messages(sessionId: String, cursor: PageCursor?, limit: Int): ResultOf<AiPage<MessageView>>
    suspend fun legacy(filter: HistoryFilter, cursor: PageCursor?, limit: Int): ResultOf<AiPage<LegacyEntryView>>
    suspend fun rename(commandId: String, sessionId: String,
        expectedSessionRevision: Long, title: String): ResultOf<RenameReceipt>
    suspend fun saveDraft(command: DraftCommand): ResultOf<DraftReceipt>
    suspend fun flushDraft(sessionId: String, throughDraftRevision: Long): ResultOf<FlushReceipt>
    suspend fun send(command: SendCommand): ResultOf<AcceptedReceipt>
    suspend fun retry(command: RetryCommand): ResultOf<AcceptedReceipt>
    suspend fun cancel(commandId: String, owner: RunOwner): ResultOf<CancelReceipt>
    suspend fun awaitIdle(): ResultOf<IdleReceipt>
    fun shutdown() // 幂等、立即关闭 admission；不阻塞 UI，不依赖 viewModelScope。
    suspend fun awaitClosed(): ClosedReport // 所有任务退出；失败报告不等于仍有活跃 writer。
}
private fun unsupportedWorkbench() = ResultOf.Rejected(Rejection(RejectCode.INVALID, false, RecoveryAction.NONE))

// initialize/replacement lease 由 GPT 生命周期内部接口调用，不暴露 UI/DAO。

