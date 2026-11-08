package cn.embeddedquiz

import java.util.Locale
import org.json.JSONObject

data class QuickNoteAiReference(
    val headingId: String,
    val headingTitle: String,
    val firstParagraph: Int,
    val lastParagraph: Int,
)

enum class QuickNoteAiScope { SEARCH, SECTION, WHOLE_DOCUMENT }

data class QuickNoteAiCoverage(
    val scope: QuickNoteAiScope,
    val partial: Boolean,
    val includedSections: Int,
    val totalSections: Int,
) {
    internal fun json() = JSONObject().put("scope", scope.name).put("partial", partial)
        .put("includedSections", includedSections).put("totalSections", totalSections)
}

class QuickNoteAiContext internal constructor(
    internal val excerpts: List<QuickNoteAiExcerpt>,
    val coverage: QuickNoteAiCoverage = excerpts.map { it.reference.headingId }.distinct().size.let {
        QuickNoteAiCoverage(QuickNoteAiScope.SEARCH, false, it, it)
    },
    internal val outline: List<String> = emptyList(),
) {
    val references: List<QuickNoteAiReference> get() = excerpts.map { it.reference }
}

internal data class QuickNoteAiDocument(val headings: List<QuickNoteHeading>, val paragraphs: List<QuickNoteAiParagraph>)
internal data class QuickNoteAiParagraph(val heading: QuickNoteHeading, val number: Int, val text: String, val code: Boolean)
internal data class QuickNoteAiTurn(val question: String, val references: List<QuickNoteAiReference>)
internal data class QuickNoteAiExcerpt(val reference: QuickNoteAiReference, val text: String) {
    fun json(): JSONObject = JSONObject().put("headingId", reference.headingId)
        .put("headingTitle", reference.headingTitle).put("firstParagraph", reference.firstParagraph)
        .put("lastParagraph", reference.lastParagraph).put("text", text)
}

internal const val QUICK_NOTE_CONTEXT_BYTES = 12 * 1024L
internal const val QUICK_NOTE_OUTLINE_BYTES = 3 * 1024L
internal const val QUICK_NOTE_MAX_EXCERPTS = 6
internal const val QUICK_NOTE_WHOLE_MAX_EXCERPTS = 48

internal fun boundedAiText(text: String, max: Int): String {
    if (text.length <= max) return text
    if (max <= 0) return ""
    val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
    return text.substring(0, end)
}

private val wholeDocument = Regex("整[份篇个](?:文档|笔记|速记|文章)|全文|全篇|通篇|整文|(?:这份|这篇|本文)(?:笔记|文档|文章)?(?:都)?(?:讲|包含)|whole document|entire (?:document|note)")
private val nearby = Regex("(?:本|当前|这一|这)(?:章|节|段|段代码|个例子)|(?:上面|上文|下面|下文)(?:这|那)?(?:段|个|的)?|this (?:section|example|code)|code above")
private val followUp = Regex("它|其(?:中|有|的|能)|这个|这种|这些|上述|前者|后者|\\b(?:it|that|those|they)\\b")
private val chapterMention = Regex("第\\s*([0-9一二两三四五六七八九十]+)\\s*([章节])")
private val genericWords = Regex("只按笔记|按笔记|笔记里|文档里|请问|是什么|什么意思|有什么|怎么|如何|哪些|为什么|总结|解释|比较|介绍|分析|说明|回答|告诉我|一下|以及|还是|能否|\\b(?:what|how|the|and|about|explain|compare|please|only|from|notes?|document|does|with|has|have)\\b")
private val termsPattern = Regex("[a-z_][a-z0-9_]*|\\p{sc=Han}+")
private val quotedSymbol = Regex("`([a-z_][a-z0-9_]*)`")

private fun wordPattern(word: String) = Regex("(?<![a-z0-9_])" + Regex.escape(word) + "(?![a-z0-9_])")

private fun terms(query: String): List<String> =
    (quotedSymbol.findAll(query).map { it.groupValues[1] } +
        termsPattern.findAll(genericWords.replace(query, " ")).flatMap { match ->
        val word = match.value
        when {
            word.first().code < 128 -> sequenceOf(word).filter { it.length >= 2 }
            word.length < 2 -> emptySequence()
            word.length == 2 -> sequenceOf(word)
            else -> sequenceOf(word) + word.windowedSequence(2)
        }
    }).filterNot { it in setOf("笔记", "文档", "这一", "一节", "限制", "有什么") }.distinct().take(32).toList()

private fun chapterNumber(text: String): Int? {
    text.toIntOrNull()?.let { return it }
    val digits = "零一二三四五六七八九"
    val parts = text.replace('两', '二').split('十')
    fun digit(s: String) = s.singleOrNull()?.let(digits::indexOf)?.takeIf { it >= 0 }
    return if (parts.size == 1) digit(parts[0]) else if (parts.size == 2)
        (if (parts[0].isEmpty()) 1 else digit(parts[0]) ?: return null) * 10 +
            (if (parts[1].isEmpty()) 0 else digit(parts[1]) ?: return null)
    else null
}

private fun namedHeadings(headings: List<QuickNoteHeading>, query: String): Set<String> {
    val direct = headings.filter { h ->
        val title = h.title.trim().lowercase(Locale.ROOT)
        title.length >= 2 && if (title.all { it.code < 128 }) wordPattern(title).containsMatchIn(query) else title in query
    }.mapTo(linkedSetOf()) { it.id }
    val levels = headings.groupBy { it.level }
    val chapters = levels.entries.sortedBy { it.key }.firstOrNull { it.value.size > 1 }?.value ?: headings
    for (mention in chapterMention.findAll(query)) {
        val n = chapterNumber(mention.groupValues[1]) ?: continue
        val named = headings.filter { h ->
            chapterMention.find(h.title)?.let { it.groupValues[2] == mention.groupValues[2] && chapterNumber(it.groupValues[1]) == n } == true
        }
        if (named.isNotEmpty()) direct += named.map { it.id }
        else (if (mention.groupValues[2] == "章") chapters else headings).getOrNull(n - 1)?.let { direct += it.id }
    }
    return direct
}

private fun QuickNoteAiParagraph.excerpt(keywords: List<String> = emptyList()): QuickNoteAiExcerpt {
    val match = if (text.length <= 1200) 0 else keywords.map { text.indexOf(it, ignoreCase = true) }
        .filter { it >= 0 }.minOrNull() ?: 0
    val start = (match - 240).coerceAtLeast(0)
    val boundary = if (Character.isLowSurrogate(text[start])) start - 1 else start
    val reference = QuickNoteAiReference(heading.id, boundedAiText(heading.title, 160), number, number)
    return QuickNoteAiExcerpt(reference, boundedAiText(text.substring(boundary), 1200))
}

private fun <T> withinBudget(values: Iterable<T>, limit: Long, encoded: (T) -> Long): List<T> {
    var left = limit - 2
    return buildList {
        for (value in values) {
            val bytes = encoded(value) + if (isEmpty()) 0 else 1
            if (bytes <= left) {
                add(value)
                left -= bytes
            }
        }
    }
}

internal fun selectQuickNoteAiContext(
    document: QuickNoteAiDocument,
    sectionId: String?,
    query: String,
    previous: QuickNoteAiTurn? = null,
): QuickNoteAiContext {
    val text = boundedAiText(query, 2048).lowercase(Locale.ROOT)
    val sections = document.paragraphs.groupBy { it.heading.id }
    fun sectionRows(id: String): List<QuickNoteAiParagraph> {
        val index = document.headings.indexOfFirst { it.id == id }
        if (index < 0) return sections[id].orEmpty()
        val heading = document.headings[index]
        val range = listOf(heading) + document.headings.drop(index + 1).takeWhile { it.level > heading.level }
        return range.flatMap { sections[it.id].orEmpty() }
    }
    fun result(scope: QuickNoteAiScope, selected: List<QuickNoteAiExcerpt>, partial: Boolean = false,
        outline: List<String> = emptyList()) =
        QuickNoteAiContext(selected, QuickNoteAiCoverage(scope, partial,
            selected.map { it.reference.headingId }.distinct().size, sections.size), outline)
    fun excerpts(rows: List<QuickNoteAiParagraph>, keywords: List<String> = emptyList()) =
        withinBudget(rows.distinct().take(QUICK_NOTE_MAX_EXCERPTS).map { it.excerpt(keywords) }, QUICK_NOTE_CONTEXT_BYTES) {
            AiJson.encodedBytes(it.json())
        }

    if (wholeDocument.containsMatchIn(text)) {
        val headings = document.headings.map { boundedAiText(it.title, 160) }
        val outline = withinBudget(headings, QUICK_NOTE_OUTLINE_BYTES, AiJson::encodedBytes)
        val fits = document.paragraphs.sumOf { it.text.length.toLong() } <= QUICK_NOTE_CONTEXT_BYTES
        val complete = if (fits) sections.values.map { rows ->
            val ref = rows.first().excerpt().reference.copy(lastParagraph = rows.last().number)
            QuickNoteAiExcerpt(ref, rows.joinToString("\n\n") { it.text })
        } else emptyList()
        if (fits && complete.size <= QUICK_NOTE_WHOLE_MAX_EXCERPTS &&
            withinBudget(complete, QUICK_NOTE_CONTEXT_BYTES) { AiJson.encodedBytes(it.json()) }.size == complete.size)
            return result(QuickNoteAiScope.WHOLE_DOCUMENT, complete, partial = outline.size < headings.size, outline = outline)
        val groups = sections.values.toList()
        val count = minOf(QUICK_NOTE_MAX_EXCERPTS, groups.size)
        val representatives = List(count) { index -> groups[if (count == 1) 0 else index * (groups.size - 1) / (count - 1)].first() }
        return result(QuickNoteAiScope.WHOLE_DOCUMENT, excerpts(representatives), partial = true, outline = outline)
    }

    val named = namedHeadings(document.headings, text)
    val keywords = terms(text)
    val current = sectionRows(sectionId.orEmpty())
    if (named.isEmpty() && (nearby.containsMatchIn(text) || text.isBlank())) {
        val local = current.ifEmpty { if (sectionId == null) sections.values.firstOrNull().orEmpty() else emptyList() }
        val codeAt = if (Regex("代码|运行|code").containsMatchIn(text)) local.indexOfFirst { it.code } else -1
        val chosen = if (codeAt < 0) local.take(QUICK_NOTE_MAX_EXCERPTS)
            else local.subList((codeAt - 1).coerceAtLeast(0), (codeAt + 2).coerceAtMost(local.size))
        val selected = excerpts(chosen)
        return result(QuickNoteAiScope.SECTION, selected, selected.size < local.size || chosen.any { it.text.length > 1200 })
    }

    fun ranked(words: List<String>): List<QuickNoteAiParagraph> {
        if (words.isEmpty()) return emptyList()
        val patterns = words.associateWith { if (it.first().code < 128) wordPattern(it) else null }
        return document.paragraphs.mapIndexedNotNull { index, p ->
            val body = (p.heading.title + "\n" + p.text).lowercase(Locale.ROOT)
            val score = patterns.entries.sumOf { (word, regex) ->
                if (regex?.containsMatchIn(body) ?: (word in body)) word.length else 0
            }
            if (score == 0) null else Triple(p, score, index)
        }.sortedWith(compareByDescending<Triple<QuickNoteAiParagraph, Int, Int>> { it.second }
            .thenByDescending { it.first.heading.id == sectionId.orEmpty() }.thenBy { it.third }).map { it.first }
    }

    val explicit = named.mapNotNull { sectionRows(it).firstOrNull() }
    val relevant = ranked(keywords)
    if (previous != null && followUp.containsMatchIn(text)) {
        val referenced = previous.references.flatMap { ref ->
            sections[ref.headingId].orEmpty().filter { it.number in ref.firstParagraph..ref.lastParagraph }
        }
        val priorTerms = terms(previous.question.lowercase(Locale.ROOT))
        val antecedents = referenced.ifEmpty { ranked(priorTerms) }
        val preferred = explicit + antecedents.take(2) + relevant + antecedents.drop(2)
        return result(QuickNoteAiScope.SEARCH, excerpts(preferred, keywords + priorTerms))
    }
    return result(QuickNoteAiScope.SEARCH, excerpts(explicit + relevant, keywords))
}
