package cn.embeddedquiz

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal data class QuickNoteToolCall(val id: String, val name: String, val arguments: String) {
    fun json() = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments))
}

/** Exactly three capabilities, bound to a single immutable document in memory. No repository writer. */
internal class QuickNoteReadTools(private val markdown: String, private val document: QuickNoteAiDocument) {
    companion object {
        const val MAX_STEPS = 5
        const val PAGE_CHARS = 600
        const val RESULT_BYTES = 3 * 1024L
        const val LIMIT_NOTE = "已达到本次查阅步数上限，尚未读完的部分不能视为已读。"
        const val NO_EVIDENCE = "未找到匹配原文；不能据此编造笔记中的表述。可尝试原文关键词。"
        val names = setOf("list_sections", "read_section", "search_note")
        fun definitions(): JSONArray {
            fun tool(name: String, description: String, properties: JSONObject, required: List<String>) =
                JSONObject().put("type", "function").put("function", JSONObject().put("name", name)
                    .put("description", description).put("parameters", JSONObject().put("type", "object")
                        .put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)))
            fun string(description: String) = JSONObject().put("type", "string").put("description", description)
            return JSONArray().put(tool("list_sections", "查看本速记标题树，结果有大小上限，truncated 表示目录未全列出。", JSONObject(), emptyList()))
                .put(tool("read_section", "分页阅读一节的原始 Markdown（不含子节）。offset 是从 0 开始的 UTF-16 字符偏移，limit 最多 600；按 nextOffset 继续。", JSONObject()
                    .put("id", string("目录返回的节 id"))
                    .put("offset", JSONObject().put("type", "integer").put("minimum", 0))
                    .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", PAGE_CHARS)), listOf("id", "offset", "limit")))
                .put(tool("search_note", "在完整原始 Markdown 中进行不区分大小写的字面搜索，不做同义词推测；结果为有位置的原文片段。", JSONObject()
                    .put("query", string("1 到 120 字符的原文关键词")), listOf("query")))
        }
    }
    private data class Section(val heading: QuickNoteHeading, val start: Int, val end: Int, val sourceId: String = heading.id)
    private val sections: List<Section> = buildList {
        val headings = document.headings
        val first = headings.firstOrNull()?.let { document.headingOffsets.getValue(it.id).first } ?: markdown.length
        if (first > 0 || headings.isEmpty()) {
            var introId = "__intro__"
            while (headings.any { it.id == introId }) introId += "_"
            add(Section(QuickNoteHeading(introId, 0, "文档开头"), 0, first, ""))
        }
        headings.forEachIndexed { i, h ->
            val start = document.headingOffsets.getValue(h.id).last + 1
            val end = headings.getOrNull(i + 1)?.let { document.headingOffsets.getValue(it.id).first } ?: markdown.length
            val publicHeading = if (h.id.length > 200) h.copy(id = "section-" + DeepSeekClient.hash(h.id).take(16)) else h
            val displayHeading = publicHeading.copy(title = boundedAiText(h.title, 160).ifBlank { "无标题章节" })
            add(Section(displayHeading, start, end, h.id))
        }
    }
    fun outline(): JSONArray {
        val rows = JSONArray()
        val parents = mutableListOf<QuickNoteHeading>()
        for (section in sections) {
            val h = section.heading
            while (parents.isNotEmpty() && parents.last().level >= h.level) parents.removeAt(parents.lastIndex)
            val row = JSONObject().put("id", h.id).put("title", boundedAiText(h.title, 160)).put("level", h.level)
                .put("parentId", parents.lastOrNull()?.id ?: "")
                .put("paragraphs", document.paragraphs.count { it.heading.id == section.sourceId })
            rows.put(row)
            if (AiJson.encodedBytes(rows) > QUICK_NOTE_OUTLINE_BYTES - 200) { rows.remove(rows.length() - 1); break }
            if (h.level > 0) parents += h
        }
        return rows
    }
    fun initialContext(): QuickNoteAiContext {
        val rows = outline()
        val titles = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val text = AiJson.canonical(rows.getJSONObject(i))
            if (AiJson.encodedBytes(JSONArray(titles + text)) > QUICK_NOTE_OUTLINE_BYTES) break
            titles += text
        }
        return QuickNoteAiContext(emptyList(), QuickNoteAiCoverage(QuickNoteAiScope.WHOLE_DOCUMENT,
            true, 0, sections.size), titles)
    }

    private fun reference(at: Int, until: Int, heading: QuickNoteHeading): QuickNoteAiReference {
        val sourceId = sections.first { it.heading.id == heading.id }.sourceId
        val rows = document.paragraphs.filter { it.heading.id == sourceId &&
            it.sourceEnd > at && it.sourceStart < until }
        val nearest = document.paragraphs.lastOrNull { it.sourceStart <= at && it.heading.id == sourceId }?.number ?: 1
        return QuickNoteAiReference(heading.id, boundedAiText(heading.title, 160), rows.firstOrNull()?.number ?: nearest,
            rows.lastOrNull()?.number ?: nearest)
    }
    private fun excerpt(at: Int, end: Int, heading: QuickNoteHeading) =
        QuickNoteAiExcerpt(reference(at, end, heading), markdown.substring(at, end)).json()

    suspend fun invoke(call: QuickNoteToolCall): JSONObject {
        currentCoroutineContext().ensureActive()
        require(call.name in names) { "INVALID_TOOL_CALL" }
        val args = JSONObject(call.arguments)
        val result = when (call.name) {
            "list_sections" -> {
                require(args.length() == 0)
                val rows = outline()
                JSONObject().put("sections", rows).put("truncated", rows.length() < sections.size).put("excerpts", JSONArray())
            }
            "read_section" -> {
                require(args.keys().asSequence().toSet() == setOf("id", "offset", "limit"))
                val section = sections.firstOrNull { it.heading.id == AiJson.string(args, "id") }
                if (section == null) JSONObject().put("error", "节 id 不存在，请先查看目录或搜索").put("excerpts", JSONArray()) else {
                    val offset = Math.toIntExact(AiJson.long(args, "offset"))
                    val limit = Math.toIntExact(AiJson.long(args, "limit"))
                    require(offset >= 0 && limit in 1..PAGE_CHARS)
                    var start = section.start + offset.coerceAtMost(section.end - section.start)
                    if (start < section.end && Character.isLowSurrogate(markdown[start])) start--
                    var end = minOf(section.end, start + limit)
                    if (end < section.end && end > start && Character.isHighSurrogate(markdown[end - 1])) end--
                    if (end == start && start < section.end) end = minOf(section.end, start + 2)
                    JSONObject().put("excerpts", JSONArray().apply { if (end > start) put(excerpt(start, end, section.heading)) })
                        .put("nextOffset", end - section.start).put("done", end == section.end)
                }
            }
            else -> {
                require(args.keys().asSequence().toSet() == setOf("query"))
                val query = AiJson.string(args, "query").trim()
                require(query.length in 1..120)
                val snippets = JSONArray()
                var total = 0
                var from = 0
                // Scan the WHOLE raw source, including links, code, HTML and text after the output cap.
                while (from < markdown.length) {
                    currentCoroutineContext().ensureActive()
                    val at = markdown.indexOf(query, from, ignoreCase = true)
                    if (at < 0) break
                    total++
                    if (snippets.length() < 2) {
                        val section = sections.lastOrNull { (document.headingOffsets[it.sourceId]?.first ?: it.start) <= at } ?: sections.first()
                        val sectionStart = document.headingOffsets[section.sourceId]?.first ?: section.start
                        var start = (at - 80).coerceAtLeast(sectionStart)
                        if (Character.isLowSurrogate(markdown[start])) start--
                        val end = minOf(section.end, start + boundedAiText(markdown.substring(start), 240).length)
                        snippets.put(excerpt(start, end, section.heading))
                    }
                    from = at + query.length
                }
                JSONObject().put("excerpts", snippets).put("matches", total).put("truncated", total > snippets.length())
                    .put("note", if (total == 0) NO_EVIDENCE else "仅列出本次实际匹配的原文片段")
            }
        }
        return if (AiJson.encodedBytes(result) <= RESULT_BYTES) result else
            JSONObject().put("error", "结果超过单次读取上限，请缩短查询或分页读取；本次未提供原文")
                .put("excerpts", JSONArray())
    }
}
