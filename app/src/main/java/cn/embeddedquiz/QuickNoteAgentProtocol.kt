package cn.embeddedquiz

import org.json.JSONArray
import org.json.JSONObject

/** v5 keeps the accepted request immutable; bounded execution evidence lives outside its payload.
 * Its identity/hash is the canonical initial envelope, so retry restarts and cache keys stay stable.
 * Every continuation is deterministically rebuilt from that payload plus paired call/result steps.
 */
internal object QuickNoteAgentProtocol {
    fun empty() = JSONObject().put("steps", JSONArray()).put("usage", JSONArray()).put("dispatches", 0)
    fun initial(snapshot: String): String {
        val root = JSONObject(snapshot)
        if (root.getInt("version") != AiSnapshotCodec.DOCUMENT_AGENT_VERSION) return snapshot
        root.put("execution", empty()).put("executionHash", DeepSeekClient.hash(AiJson.canonical(empty())))
        return AiJson.canonical(root)
    }
    fun digest(snapshot: String) = DeepSeekClient.hash(initial(snapshot))
    fun currentPolicy(snapshot: String) = JSONObject(snapshot).getString("systemVersion") == "2"
    fun withExecution(snapshot: String, execution: JSONObject): String =
        AiJson.canonical(JSONObject(snapshot).put("execution", execution)
            .put("executionHash", DeepSeekClient.hash(AiJson.canonical(execution))))
    private val noteOnly = Regex("只按(?:这份)?(?:笔记|速记)|仅按(?:这份)?(?:笔记|速记)|只根据(?:这份)?(?:笔记|速记)|only from (?:the )?notes?", RegexOption.IGNORE_CASE)
    private val explicitReading = Regex("原文|全文|整篇|整份|逐[章节段]|(?:对照|比较|比对).{0,40}[章节]|full (?:text|note)|compare.{0,40}sections", RegexOption.IGNORE_CASE)
    private val noteMention = Regex("这份|本文|笔记|速记|文档|the (?:note|document)", RegexOption.IGNORE_CASE)

    fun requiresNoteEvidence(input: String) = noteOnly.containsMatchIn(input)
    fun needsTools(input: String, context: QuickNoteAiContext) =
        requiresNoteEvidence(input) || explicitReading.containsMatchIn(input) ||
            (noteMention.containsMatchIn(input) && (context.excerpts.isEmpty() || context.coverage.partial))

    private val clauseBreak = Regex("[，,。！？!?；;\\n]+|(?<=\\.)\\s+")
    private val opening = Regex("^(?:好的?|可以|当然|okay|ok|sure)[。.! ]*$", RegexOption.IGNORE_CASE)
    private val plan = Regex("^(?:(?:我(?:(?:会|将|要)(?:先)?|先|去|来)|让我先?|先|接下来(?:我(?:会|将|要)?)?|然后(?:我(?:会|将|要)?)?再?|再)\\s*(?:去|来)?\\s*(?:查阅|查看|阅读|检索|搜索|浏览|找到|找(?!到)|查(?!阅|看)|看|读)(?!了|到|过|完)|(?:i['’]ll|i will|let me|then i['’]ll|first i['’]ll)\\s+(?:first\\s+)?(?:check|read|review|search|look)|请稍等|please wait)", RegexOption.IGNORE_CASE)
    private val planEnding = Regex("^(?:(?:然后|之后|再|最后)(?:我(?:会|将)?)?(?:给出|整理|回答|说明|总结).{0,30}|(?:and |then )?(?:provide|give|summarize) (?:the |an? )?(?:answer|summary|findings)[. ]*)$", RegexOption.IGNORE_CASE)

    fun planningOnly(text: String): Boolean {
        val clauses = clauseBreak.split(text).map { it.trim().trim('*', '#', '>', '-', ' ', '。') }.filter(String::isNotEmpty)
        return clauses.any { plan.containsMatchIn(it) } && clauses.all {
            opening.matches(it) || plan.containsMatchIn(it) || planEnding.matches(it)
        }
    }

    private fun nudgeAfter(execution: JSONObject): Long? = when {
        execution.has("nudgeAfter") -> AiJson.long(execution, "nudgeAfter")
        execution.has("nudged") -> 1L.also { require(AiJson.boolean(execution, "nudged")) }
        else -> null
    }

    fun hasNudge(execution: JSONObject) = nudgeAfter(execution) != null

    fun continuation(snapshot: String, execution: JSONObject, final: Boolean): String {
        val payload = JSONObject(AiSnapshotCodec.payload(snapshot))
        val messages = payload.getJSONArray("messages")
        val nudge = nudgeAfter(execution)
        var corrected = false
        fun correct() {
            messages.put(JSONObject().put("role", "user").put("content",
                "刚才的文字只是查阅计划。请查阅回答所需的证据，或现在给出完整回答。只按笔记时没有证据请如实说明。"))
            corrected = true
        }
        for ((hop, batch) in AiJson.objects(execution, "steps").groupBy { it.getLong("hop") }) {
            if (nudge != null && nudge < hop && !corrected) correct()
            messages.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("tool_calls", JSONArray(batch.map { it.getJSONObject("call") })))
            for (step in batch) messages.put(JSONObject().put("role", "tool")
                .put("tool_call_id", step.getJSONObject("call").getString("id"))
                .put("content", AiJson.canonical(step.getJSONObject("result"))))
        }
        if (nudge != null && !corrected) correct()
        if (!final && execution.getJSONArray("steps").length() == 0 &&
            JSONObject(snapshot).getString("system").contains("首次回复必须调用")) {
            payload.put("tool_choice", "required")
        }
        if (final) {
            payload.put("tool_choice", "none")
            messages.put(JSONObject().put("role", "user").put("content",
                "查阅已结束。现在必须给出最终回答。未读到的内容不得假装读过；证据不足请直说。"))
        }
        val body = AiJson.canonical(payload)
        require(AiJson.utf8(body) <= AiSnapshotCodec.INPUT_BYTES) { "INPUT_LIMIT" }
        return body
    }
    fun references(execution: JSONObject): List<QuickNoteAiReference> = AiJson.objects(execution, "steps")
        .flatMap { AiJson.objects(it.getJSONObject("result"), "excerpts") }.map {
            QuickNoteAiReference(it.getString("headingId"), it.getString("headingTitle"), it.getInt("firstParagraph"), it.getInt("lastParagraph"))
        }.distinct()

    fun usageJson(u: AiUsageEvidence) = JSONObject().put("input", u.input ?: JSONObject.NULL)
        .put("output", u.output ?: JSONObject.NULL).put("hit", u.hit ?: JSONObject.NULL).put("state", u.state.name)
    fun usage(execution: JSONObject, price: AiUsageEvidence): AiUsageEvidence {
        val rows = AiJson.objects(execution, "usage")
        val pending = execution.getInt("dispatches") > rows.size
        fun sum(field: String): Long? = if (rows.isEmpty() || rows.any { it.isNull(field) }) null
            else rows.fold(0L) { n, row -> Math.addExact(n, AiJson.long(row, field)) }
        val i = sum("input"); val o = if (pending) null else sum("output"); val h = sum("hit")
        val invalid = rows.any { it.getString("state") == UsageState.INVALID.name }
        val state = if (invalid) UsageState.INVALID else when (listOf(i, o, h).count { it != null }) {
            0 -> UsageState.UNKNOWN
            3 -> UsageState.KNOWN
            else -> UsageState.PARTIAL
        }
        return price.copy(input = i, output = o, hit = h, state = state)
    }
    fun validate(execution: JSONObject) {
        val fields = setOf("steps", "usage", "dispatches")
        require(execution.keys().asSequence().toSet() in listOf(fields, fields + "nudged", fields + "nudgeAfter"))
        val nudge = nudgeAfter(execution)
        val skipped = if (nudge == null) 0 else 1
        val steps = AiJson.objects(execution, "steps")
        val usage = AiJson.objects(execution, "usage")
        val dispatches = AiJson.long(execution, "dispatches")
        require(steps.size <= QuickNoteReadTools.MAX_STEPS && steps.size <= usage.size * QuickNoteReadTools.MAX_STEPS)
        require(dispatches in usage.size.toLong()..(usage.size + 1L) && dispatches <= QuickNoteReadTools.MAX_STEPS + 1 + skipped)
        require(usage.size <= QuickNoteReadTools.MAX_STEPS + 1 + skipped && dispatches <= steps.size + 1L + skipped)
        val hops = steps.map { AiJson.long(it, "hop") }
        require(hops == hops.sorted() && hops.all { it in 1L..usage.size.toLong() })
        require(nudge == null || nudge in 1L..usage.size.toLong() && nudge !in hops)
        val completedHops = (hops.distinct() + listOfNotNull(nudge)).sorted()
        require(completedHops == (1L..(completedHops.lastOrNull() ?: 0L)).toList())
        require(usage.size <= completedHops.size + 1 && dispatches <= completedHops.size + 1L)
        var bytes = 0L
        val ids = mutableSetOf<String>()
        steps.forEach { step ->
            require(step.keys().asSequence().toSet() == setOf("call", "result", "hop"))
            val call = readCall(step.getJSONObject("call"))
            require(AiJson.canonical(call.json()) == AiJson.canonical(step.getJSONObject("call")) && ids.add(call.id))
            val result = step.getJSONObject("result")
            val size = AiJson.encodedBytes(result)
            require(size <= QuickNoteReadTools.RESULT_BYTES)
            bytes = Math.addExact(bytes, size)
            require(bytes <= QUICK_NOTE_CONTEXT_BYTES)
            val excerpts = AiJson.objects(result, "excerpts")
            val keys = result.keys().asSequence().toSet()
            if (result.has("error")) {
                require(keys == setOf("error", "excerpts") && excerpts.isEmpty())
                require(AiJson.string(result, "error").length in 1..200)
            } else when (call.name) {
                "list_sections" -> {
                    require(keys == setOf("sections", "truncated", "excerpts") && excerpts.isEmpty())
                    AiJson.boolean(result, "truncated")
                    for (section in AiJson.objects(result, "sections")) {
                        require(section.keys().asSequence().toSet() == setOf("id", "title", "level", "parentId", "paragraphs"))
                        require(AiJson.string(section, "id").length in 1..2048 && AiJson.string(section, "parentId").length <= 2048)
                        require(AiJson.string(section, "title").length in 1..160)
                        require(AiJson.long(section, "level") in 0..6 && AiJson.long(section, "paragraphs") >= 0)
                    }
                }
                "read_section" -> {
                    require(keys == setOf("excerpts", "nextOffset", "done") && excerpts.size <= 1)
                    require(AiJson.long(result, "nextOffset") in 0..QuickNotesRepository.MAX_MARKDOWN)
                    AiJson.boolean(result, "done")
                }
                "search_note" -> {
                    require(keys == setOf("excerpts", "matches", "truncated", "note"))
                    val matches = AiJson.long(result, "matches")
                    require(matches >= excerpts.size && (matches != 0L || excerpts.isEmpty()))
                    AiJson.boolean(result, "truncated")
                    require(AiJson.string(result, "note").length in 1..200)
                }
            }
            require(excerpts.size <= 2)
            excerpts.forEach { e ->
                require(e.keys().asSequence().toSet() == setOf("headingId", "headingTitle", "firstParagraph", "lastParagraph", "text"))
                require(AiJson.string(e, "headingId").length <= 2048 && AiJson.string(e, "headingTitle").length <= 160)
                val start = AiJson.long(e, "firstParagraph"); val end = AiJson.long(e, "lastParagraph")
                require(start in 1..Int.MAX_VALUE && end in start..Int.MAX_VALUE)
                require(AiJson.string(e, "text").length in 1..QuickNoteReadTools.PAGE_CHARS)
            }
        }
        usage.forEach { u ->
            require(u.keys().asSequence().toSet() == setOf("input", "output", "hit", "state"))
            for (key in listOf("input", "output", "hit")) if (!u.isNull(key)) require(AiJson.long(u, key) >= 0)
            require(u.isNull("hit") || !u.isNull("input") && u.getLong("hit") <= u.getLong("input"))
            val state = UsageState.valueOf(AiJson.string(u, "state"))
            val fields = listOf("input", "output", "hit").count { !u.isNull(it) }
            require(when (state) { UsageState.KNOWN -> fields == 3; UsageState.PARTIAL -> fields in 1..2
                UsageState.UNKNOWN, UsageState.INVALID -> fields == 0; else -> false })
        }
    }
    fun readCall(o: JSONObject): QuickNoteToolCall {
        // Bound the original wire object before dropping optional provider metadata. Extra numeric
        // metadata may be fractional; it is never fed to the integer-only persisted JSON codec.
        require(AiJson.utf8(o.toString()) <= 1536) { "TOOL_CALL_LIMIT" }
        require(!o.has("type") || AiJson.string(o, "type") == "function")
        val id = AiJson.string(o, "id")
        require(id.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        val f = o.getJSONObject("function")
        val name = AiJson.string(f, "name")
        require(name in QuickNoteReadTools.names)
        val rawArguments = f.get("arguments")
        val args = when (rawArguments) {
            is String -> {
                require(AiJson.utf8(rawArguments) <= 4096)
                JSONObject(rawArguments)
            }
            is JSONObject -> rawArguments
            else -> error("INVALID_TOOL_ARGUMENTS")
        }
        // Keep only documented inputs. The executor's exact-key checks stay strict, and unknown
        // fields cannot become capabilities, survive into stored calls, or be echoed to the model.
        val normalized = JSONObject()
        when (name) {
            "list_sections" -> Unit
            "read_section" -> {
                val section = AiJson.string(args, "id")
                val offset = AiJson.long(args, "offset"); val limit = AiJson.long(args, "limit")
                require(section.length in 1..2048 && offset in 0..Int.MAX_VALUE)
                require(limit in 1..QuickNoteReadTools.PAGE_CHARS)
                normalized.put("id", section).put("offset", offset).put("limit", limit)
            }
            "search_note" -> {
                val query = AiJson.string(args, "query")
                require(query.trim().length in 1..120)
                normalized.put("query", query)
            }
        }
        return QuickNoteToolCall(id, name, AiJson.canonical(normalized))
    }
}
