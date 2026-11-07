package cn.embeddedquiz

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope

/**
 * How the 速记 reader starts discussions about the open Markdown document (PLAN-1.7.1). Built from
 * the ViewModel by [quickNoteAiEntry]; without one the reader shows no AI button.
 */
internal class QuickNoteAiEntry(
    val facade: () -> AiConversationFacade,
    /**
     * The discussions of this document and content version. [section] is the heading the reader was
     * at when the user asked (null: the start of the document); the backend picks the cited context.
     */
    val source: (document: QuickNoteDocument, section: QuickNoteHeading?) -> AiChatSource,
    /** Outlives the reader, so leaving a discussion still saves and releases its draft. */
    val scope: CoroutineScope,
    /** Null where document replies cannot be saved as sticky notes (the backend saves question replies only). */
    val noteSaver: AiNoteSaver? = null,
    /** What the backend would cite for this section before anything is typed; null skips the preview. */
    val preview: (suspend (QuickNoteDocument, QuickNoteHeading?) -> ResultOf<QuickNoteAiContext>)? = null,
)

/** The reader's AI entry on the shared ViewModel: GPT's document sessions and context preview. */
internal fun quickNoteAiEntry(vm: QuizViewModel) =
    QuickNoteAiEntry(
        facade = { vm.aiConversations },
        source = { document, section -> DocumentAiSource(document.summary, section) { vm.model } },
        scope = vm.viewModelScope,
        preview = { document, section -> vm.quickNoteAiContext(document.summary, section) },
    )

/**
 * Discussions about one 速记 document version (GPT's interface in PLAN-1.7.1): listed and created per
 * document id and content hash, older versions read only. Every send is a follow-up about the
 * reader's section; the backend re-picks the cited paragraphs from the stored document.
 */
internal class DocumentAiSource(
    val document: QuickNoteSummary,
    private val section: QuickNoteHeading?,
    private val model: () -> String,
) : AiChatSource {
    val owner = QuestionOwner(document.id, document.contentHash, AiOwnerKind.QUICK_NOTE)
    // Explanation, suggested answer, variant and code review are about questions, not documents.
    override val quickTypes = emptyList<AiRequestType>()
    override val staleLabel = "旧版文档"

    override suspend fun sessions(facade: AiConversationFacade, cursor: PageCursor?, limit: Int) =
        facade.documentSessions(document, includeArchived = true, cursor, limit)

    override suspend fun create(facade: AiConversationFacade, commandId: String) =
        facade.createDocumentSession(commandId, document)

    override fun current(session: SessionView) = session.question == owner

    override suspend fun send(facade: AiConversationFacade, request: AiSendRequest): ResultOf<AcceptedReceipt> {
        val s = request.session
        return facade.send(
            SendCommand(
                commandId = request.commandId, sessionId = s.sessionId, question = s.question,
                expectedContextRevision = s.contextRevision, draftToken = request.draftToken,
                savedDraftRevision = request.savedDraftRevision, input = request.input, choices = emptySet(),
                code = "", requestType = AiRequestType.FOLLOW_UP, model = model(),
                documentSectionId = section?.id,
            )
        )
    }

    override fun citation(message: MessageView): String? =
        message.run?.documentReferences?.takeIf { it.isNotEmpty() }?.let(::quickNoteReferenceText)
}

/** "「进程」第 2～3 段；「调度」第 1 段", merging adjacent ranges of the same heading. */
internal fun quickNoteReferenceText(references: List<QuickNoteAiReference>): String =
    references
        .groupBy { it.headingId }
        .values
        .joinToString("；") { refs ->
            val ranges = mutableListOf<IntRange>()
            refs.sortedBy { it.firstParagraph }.forEach { r ->
                val last = ranges.lastOrNull()
                if (last != null && r.firstParagraph <= last.last + 1)
                    ranges[ranges.lastIndex] = last.first..maxOf(last.last, r.lastParagraph)
                else ranges += r.firstParagraph..r.lastParagraph
            }
            "「${refs.first().headingTitle}」" +
                ranges.joinToString("、") { if (it.first == it.last) "第 ${it.first} 段" else "第 ${it.first}～${it.last} 段" }
        }

/** What the page says it will cite before anything is sent; the reply then names what it actually used. */
internal fun quickNoteAiContext(
    document: QuickNoteDocument,
    section: QuickNoteHeading?,
    preview: ResultOf<QuickNoteAiContext>? = null,
): AiSourceContext {
    val base =
        if (section != null) "以当前章节「${section.title}」为主，按需引用相关段落；只在你发送时提交。"
        else "从文档开头起按需引用相关段落；只在你发送时提交。"
    val detail =
        when (preview) {
            null -> null
            is ResultOf.Ok ->
                preview.value.references.takeIf { it.isNotEmpty() }?.let { "预计引用：" + quickNoteReferenceText(it) + "（发送时按问题再选取）" }
                    ?: "这一节没有可引用的正文段落。"
            is ResultOf.Rejected ->
                when (preview.reason.code) {
                    RejectCode.STALE_OWNER -> "文档内容已更新，请返回列表重新打开后再提问。"
                    RejectCode.NOT_FOUND -> "这份文档已不可用。"
                    else -> aiRejectionText(preview.reason)
                }
        }
    return AiSourceContext("文档：" + document.summary.title, listOfNotNull(base, detail).joinToString("\n"))
}

/** Adds the reader's document and section to a source; the preview fills in once it arrives. */
internal class ContextualAiSource(private val base: AiChatSource, context: AiSourceContext) : AiChatSource by base {
    override var context: AiSourceContext? by mutableStateOf<AiSourceContext?>(context)
}

/** The heading the reader's saved position points at, if it is one of this document's headings. */
internal fun quickNoteSection(document: QuickNoteDocument, anchor: String?): QuickNoteHeading? =
    anchor?.takeIf { it.isNotBlank() }?.let { a -> document.rendered.headings.firstOrNull { it.id == a } }
