package cn.embeddedquiz

import java.util.Locale
import org.json.JSONObject

data class QuickNoteAiReference(
    val headingId: String,
    val headingTitle: String,
    val firstParagraph: Int,
    val lastParagraph: Int,
)

class QuickNoteAiContext internal constructor(internal val excerpts: List<QuickNoteAiExcerpt>) {
    val references: List<QuickNoteAiReference> get() = excerpts.map { it.reference }
}

internal data class QuickNoteAiParagraph(val heading: QuickNoteHeading, val number: Int, val text: String)
internal data class QuickNoteAiExcerpt(val reference: QuickNoteAiReference, val text: String) {
    fun json(): JSONObject = JSONObject().put("headingId", reference.headingId)
        .put("headingTitle", reference.headingTitle).put("firstParagraph", reference.firstParagraph)
        .put("lastParagraph", reference.lastParagraph).put("text", text)
}

internal const val QUICK_NOTE_CONTEXT_BYTES = 12 * 1024L
internal const val QUICK_NOTE_MAX_EXCERPTS = 6

internal fun boundedAiText(text: String, max: Int): String {
    if (text.length <= max) return text
    val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
    return text.substring(0, end)
}

private val quickNoteTerms = Regex("[a-z0-9_]{2,}|\\p{sc=Han}+")

internal fun selectQuickNoteAiContext(
    paragraphs: List<QuickNoteAiParagraph>,
    sectionId: String?,
    query: String,
): QuickNoteAiContext {
    val terms = quickNoteTerms.findAll(boundedAiText(query, 2048).lowercase(Locale.ROOT)).flatMap { match ->
        val text = match.value
        if (text.first().code < 128 || text.length <= 2) sequenceOf(text)
        else text.windowedSequence(2)
    }.distinct().take(32).toList()
    val current = paragraphs.asSequence().filter { it.heading.id == sectionId.orEmpty() }.take(3).toList()
        .ifEmpty { if (sectionId == null) paragraphs.take(1) else emptyList() }
    val related = if (terms.isEmpty()) emptyList() else paragraphs.asSequence()
        .filterNot { it in current }
        .map { paragraph ->
            val text = (paragraph.heading.title + " " + paragraph.text).lowercase(Locale.ROOT)
            paragraph to terms.count(text::contains)
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(QUICK_NOTE_MAX_EXCERPTS).map { it.first }.toList()
    var budget = QUICK_NOTE_CONTEXT_BYTES - 2
    val selected = mutableListOf<QuickNoteAiExcerpt>()
    for (paragraph in (current + related).take(QUICK_NOTE_MAX_EXCERPTS)) {
        val reference = QuickNoteAiReference(paragraph.heading.id, boundedAiText(paragraph.heading.title, 160),
            paragraph.number, paragraph.number)
        val text = paragraph.text
        val match = if (paragraph in current || text.length <= 1200) 0 else terms
            .map { text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (match - 240).coerceAtLeast(0)
        val boundary = if (Character.isLowSurrogate(text[start])) start - 1 else start
        val excerpt = QuickNoteAiExcerpt(reference, boundedAiText(text.substring(boundary), 1200))
        val bytes = AiJson.encodedBytes(excerpt.json()) + 1
        if (bytes <= budget) {
            selected += excerpt
            budget -= bytes
        }
    }
    return QuickNoteAiContext(selected)
}
