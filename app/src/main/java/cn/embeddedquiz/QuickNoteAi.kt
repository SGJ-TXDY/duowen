package cn.embeddedquiz

import kotlinx.coroutines.CoroutineScope

/**
 * How the 速记 reader starts discussions about the open Markdown document (PLAN-1.7.1). GPT's
 * ViewModel entry provides it; without one the reader shows no AI button.
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
    /** Null where document replies cannot be saved as sticky notes. */
    val noteSaver: AiNoteSaver? = null,
)

/** What the page says it will cite before anything is sent; the reply then names what it actually used. */
internal fun quickNoteAiContext(document: QuickNoteDocument, section: QuickNoteHeading?) =
    AiSourceContext(
        title = "文档：" + document.summary.title,
        scope =
            if (section != null) "以当前章节「${section.title}」为主，按需引用相关段落；只在你发送时提交。"
            else "从文档开头起按需引用相关段落；只在你发送时提交。",
    )

/** Adds the reader's document and section to a source that does not describe them itself. */
internal class ContextualAiSource(private val base: AiChatSource, override val context: AiSourceContext) :
    AiChatSource by base

/** The heading the reader's saved position points at, if it is one of this document's headings. */
internal fun quickNoteSection(document: QuickNoteDocument, anchor: String?): QuickNoteHeading? =
    anchor?.takeIf { it.isNotBlank() }?.let { a -> document.rendered.headings.firstOrNull { it.id == a } }
