package cn.embeddedquiz

import org.json.JSONObject

internal data class AiDataset(
    val sessions: List<AiSessionRow> = emptyList(), val messages: List<AiMessageRow> = emptyList(),
    val runs: List<AiRunRow> = emptyList(), val commands: List<AiCommandRow> = emptyList(),
    val cache: List<AiCacheRow> = emptyList(),
)

/** AI-only canonical segment. Full streaming backup and legacy data are deliberately not here. */
internal object AiConversationCodec {
    const val CAPACITY = 32L * 1024 * 1024
    const val TEXT_BYTES = 1024L * 1024
    const val MESSAGE_LIMIT = 100000
    const val RECEIPT_BYTES = 16L * 1024
    const val RUN_RESERVE = 6L * 1024 * 1024 + 256L * 1024
    private fun json(d: AiDataset): JSONObject = JSONObject().apply {
        put("aiConversationVersion", 1)
        put("aiSessions", AiJson.array(d.sessions.sortedBy { it.sessionId }.map(AiRowCodec::encode)))
        put("aiMessages", AiJson.array(d.messages.sortedBy { it.messageId }.map(AiRowCodec::encode)))
        put("aiRuns", AiJson.array(d.runs.sortedBy { it.requestId }.map(AiRowCodec::encode)))
        put("aiCommands", AiJson.array(d.commands.sortedBy { it.commandId }.map(AiRowCodec::encode)))
        put("aiResponseCache", AiJson.array(d.cache.sortedBy { it.cacheKey }.map(AiRowCodec::encode)))
    }
    fun encode(d: AiDataset): String = AiJson.canonical(json(d))
    fun decode(text: String): AiDataset {
        val o = JSONObject(text)
        require(AiJson.long(o, "aiConversationVersion") == 1L)
        val d = AiDataset(AiJson.objects(o, "aiSessions").map(AiRowCodec::decodeSession),
            AiJson.objects(o, "aiMessages").map(AiRowCodec::decodeMessage),
            AiJson.objects(o, "aiRuns").map(AiRowCodec::decodeRun),
            AiJson.objects(o, "aiCommands").map(AiRowCodec::decodeCommand),
            AiJson.objects(o, "aiResponseCache").map(AiRowCodec::decodeCache))
        require(encode(d) == AiJson.canonical(o)) { "UNKNOWN_OR_MISSING_FIELDS" }
        validate(d)
        return d
    }
    fun encodedBytes(d: AiDataset): Long = AiJson.encodedBytes(json(d))
    fun reserved(d: AiDataset): Long = d.runs.fold(0L) { n, r -> Math.addExact(n, r.reservedBytes) }
    fun capacity(d: AiDataset, limit: Long = CAPACITY) {
        require(d.messages.size <= MESSAGE_LIMIT) { "MESSAGE_LIMIT" }
        require(Math.addExact(encodedBytes(d), reserved(d)) <= limit) { "CAPACITY_EXCEEDED" }
    }
    private fun fields(o: JSONObject) {
        o.keys().forEach { key -> val v = o.get(key); if (v is String) require(AiJson.utf8(v) <= TEXT_BYTES) { "TEXT_LIMIT" } }
    }
    fun validate(d: AiDataset, limit: Long = CAPACITY, unchangedRuns:Set<AiRunRow> = emptySet()) {
        fun <T> unique(items: List<T>, id: (T) -> String) { require(items.map(id).toSet().size == items.size) { "DUPLICATE_ID" } }
        unique(d.sessions) { it.sessionId }; unique(d.messages) { it.messageId }; unique(d.runs) { it.requestId }
        unique(d.commands) { it.commandId }; unique(d.cache) { it.cacheKey }
        val sessions = d.sessions.associateBy { it.sessionId }; val messages = d.messages.associateBy { it.messageId }; val runs = d.runs.associateBy { it.requestId }
        val commands = d.commands.associateBy { it.commandId }; val assistants = d.runs.associateBy { it.assistantMessageId }
        d.sessions.forEach { s ->
            fields(AiRowCodec.encode(s)); AiJson.uuid(s.sessionId); AiJson.hash(s.contentHash)
            require(s.questionSnapshotVersion in 1..AiSnapshotCodec.DOCUMENT_VERSION && s.createdAt >= 0 && s.updatedAt >= s.createdAt)
            require(s.sessionRevision >= 0 && s.contextRevision >= 0 && s.draftRevision >= 0)
            require(s.title.codePointCount(0, s.title.length) in 1..80)
            require((s.lastEditorEpoch == null) == (s.lastLocalEditVersion == null))
            s.lastEditorEpoch?.let(AiJson::uuid); s.lastLocalEditVersion?.let { require(it >= 0) }
            if (s.owner.kind == AiOwnerKind.QUICK_NOTE) AiSnapshotCodec.readDocument(s.questionSnapshot, s.owner)
            else AiSnapshotCodec.readQuestion(s.questionSnapshot, s.owner)
        }
        require(d.messages.map { it.sessionId to it.seq }.toSet().size == d.messages.size) { "DUPLICATE_SEQ" }
        d.messages.forEach { m ->
            fields(AiRowCodec.encode(m)); AiJson.uuid(m.messageId); require(m.sessionId in sessions && m.seq >= 0 && m.createdAt >= 0)
            val role = MessageRole.valueOf(m.role); val completion = TextState.valueOf(m.completion)
            require(role != MessageRole.USER || completion == TextState.COMPLETE)
            require((completion == TextState.EMPTY) == m.text.isEmpty())
        }
        require(d.runs.map { it.assistantMessageId }.toSet().size == d.runs.size)
        require(d.runs.map { it.commandId }.toSet().size == d.runs.size)
        require(d.runs.count { it.status in setOf("PREPARING", "GENERATING") } <= 1) { "BUSY" }
        d.runs.forEach { r ->
            if (r in unchangedRuns) return@forEach
            fields(AiRowCodec.encode(r)); AiJson.uuid(r.requestId); AiJson.uuid(r.commandId); AiJson.uuid(r.coordinatorEpoch)
            val s = requireNotNull(sessions[r.sessionId]); val u = requireNotNull(messages[r.userMessageId]); val a = requireNotNull(messages[r.assistantMessageId])
            require(u.sessionId == s.sessionId && a.sessionId == s.sessionId && u.role == "USER" && a.role == "ASSISTANT" && a.seq > u.seq)
            val status = RunStatus.valueOf(r.status); val source = RunSource.valueOf(r.source)
            require(r.createdAt >= 0 && r.eventSeq >= 0 && r.reservedBytes in 0..RUN_RESERVE)
            require(r.contextSnapshotVersion == s.questionSnapshotVersion && r.promptVersion == "1")
            require(r.model in AiSnapshotCodec.MODELS_V1); AiRequestType.valueOf(r.requestType)
            AiJson.hash(r.contextDigest); AiJson.hash(r.cacheKey)
            require(DeepSeekClient.hash(r.contextSnapshot) == r.contextDigest)
            require(r.cacheKey == DeepSeekClient.hash("${s.questionSnapshotVersion}\n${s.sessionId}\n${s.contentHash}\n${r.contextDigest}"))
            AiSnapshotCodec.validateRequest(r.contextSnapshot, s.owner, r.model, r.requestType)
            val frozenData = JSONObject(r.contextSnapshot).getJSONObject("data")
            val sourceField = if (s.owner.kind == AiOwnerKind.QUICK_NOTE) "document" else "question"
            require(AiJson.canonical(frozenData.getJSONObject(sourceField)) == s.questionSnapshot)
            val input = frozenData.getString("input")
            require(u.text == if (input.isBlank()) AiRequestType.valueOf(r.requestType).label else input)
            var previousSeq = -1L
            for (h in AiJson.objects(frozenData,"history")) {
                require(h.keys().asSequence().toSet() == setOf("userMessageId","assistantMessageId","user","assistant"))
                val hu = requireNotNull(messages[h.getString("userMessageId")])
                val ha = requireNotNull(messages[h.getString("assistantMessageId")])
                val hr = requireNotNull(assistants[ha.messageId])
                require(hu.sessionId == s.sessionId && ha.sessionId == s.sessionId && hr.userMessageId == hu.messageId)
                require(hr.status == "COMPLETED" && ha.completion == "COMPLETE" && hu.role == "USER")
                require(ha.seq > previousSeq && ha.seq < u.seq && h.getString("user") == hu.text && h.getString("assistant") == ha.text)
                previousSeq = ha.seq
            }
            require(AiJson.canonical(JSONObject(r.effectiveParameters)) == AiJson.canonical(JSONObject(r.contextSnapshot).getJSONObject("parameters")))
            val terminal = status in setOf(RunStatus.COMPLETED, RunStatus.CANCELLED, RunStatus.FAILED)
            require(terminal == (r.finishedAt != null)); r.finishedAt?.let { require(it >= r.createdAt) }
            require(!terminal || r.reservedBytes == 0L)
            require(terminal || r.reservedBytes >= requiredClosingReserve(r)) { "INSUFFICIENT_CLOSING_RESERVE" }
            require((status == RunStatus.COMPLETED) == (a.completion == "COMPLETE"))
            require(status != RunStatus.COMPLETED || a.text.isNotBlank())
            require(status != RunStatus.PREPARING || a.completion == "EMPTY")
            r.usageCutoffSeq?.let { require(it in 0..r.eventSeq) }
            r.retryOf?.let { id ->
                val prior = requireNotNull(runs[id]); require(prior.sessionId == r.sessionId && prior.userMessageId == r.userMessageId)
                require(prior.contextSnapshot == r.contextSnapshot && prior.model == r.model && prior.effectiveParameters == r.effectiveParameters)
                require(prior.status in setOf("FAILED", "CANCELLED"))
                require(messages.getValue(prior.assistantMessageId).seq < a.seq)
            }
            r.sourceRequestId?.let { id ->
                val origin = requireNotNull(runs[id]); require(origin.sessionId == r.sessionId && origin.source == "NETWORK" && origin.status == "COMPLETED" && origin.cacheKey == r.cacheKey)
                require(messages.getValue(origin.assistantMessageId).seq < a.seq)
            }
            require((source == RunSource.CACHE) == (r.sourceRequestId != null))
            if (source == RunSource.CACHE) require(!r.networkDispatched && status == RunStatus.COMPLETED && r.usageStatus == "NOT_APPLICABLE" && r.estimatedUsd != null && AiJson.decimal(r.estimatedUsd).signum() == 0)
            validateUsage(r)
            val zone=java.time.ZoneId.of(r.billingZone)
            require(r.billingDay == java.time.Instant.ofEpochMilli(r.createdAt).atZone(zone).toLocalDate().toEpochDay()) { "BILLING_DAY_MISMATCH" }
        }
        d.messages.filter { it.role == "ASSISTANT" }.forEach { a -> require(assistants.containsKey(a.messageId)) }
        d.runs.groupBy { it.userMessageId }.values.forEach { attempts -> require(attempts.count { it.status == "COMPLETED" } <= 1) }
        // Strict increasing assistant sequence on both edges above makes retry/source cycles impossible.
        d.cache.forEach { c ->
            fields(AiRowCodec.encode(c)); AiJson.hash(c.cacheKey); val r = requireNotNull(runs[c.sourceRequestId])
            require(c.protocolVersion == 1 && c.createdAt >= 0 && r.source == "NETWORK" && r.status == "COMPLETED" && c.cacheKey == r.cacheKey)
            require(messages.getValue(r.assistantMessageId).completion == "COMPLETE")
        }
        d.commands.forEach { c -> validateCommand(c, sessions, messages, runs) }
        d.runs.forEach { r -> require(commands[r.commandId]?.let { it.requestId == r.requestId && it.kind in setOf("SEND", "RETRY") } == true) }
        capacity(d, limit)
    }
    fun validateUsage(r: AiRunRow) {
        val state = UsageState.valueOf(r.usageStatus)
        listOf(r.inputTokens, r.outputTokens, r.hitTokens).forEach { it?.let { n -> require(n >= 0) } }
        if (r.hitTokens != null) require(r.inputTokens != null && r.hitTokens <= r.inputTokens)
        if (state == UsageState.KNOWN) require(r.inputTokens != null && r.outputTokens != null && r.hitTokens != null)
        if (state == UsageState.UNKNOWN || state == UsageState.NOT_APPLICABLE) require(r.inputTokens == null && r.outputTokens == null && r.hitTokens == null)
        if (state == UsageState.PARTIAL) require(listOf(r.inputTokens,r.outputTokens,r.hitTokens).any { it == null } && listOf(r.inputTokens,r.outputTokens,r.hitTokens).any { it != null })
        r.estimatedUsd?.let(AiJson::decimal)
        listOf(r.missRate,r.hitRate,r.outputRate).forEach { it?.let(AiJson::decimal) }
        require(r.currency.matches(Regex("[A-Z]{3}")))
        require((r.source == "CACHE") == (state == UsageState.NOT_APPLICABLE))
        val expected = AiEstimateCodec.expected(r)
        if (expected == null) require(r.estimatedUsd == null) { "UNKNOWN_ESTIMATE" }
        else require(r.estimatedUsd != null && AiJson.decimal(r.estimatedUsd).compareTo(AiJson.decimal(expected)) == 0) { "ESTIMATE_MISMATCH" }

    }
    private fun validateCommand(c: AiCommandRow, sessions: Map<String,AiSessionRow>, messages: Map<String,AiMessageRow>, runs: Map<String,AiRunRow>) {
        fields(AiRowCodec.encode(c)); AiJson.uuid(c.commandId); AiJson.hash(c.payloadDigest)
        require(c.kind in setOf("CREATE","RENAME","DRAFT","SEND","RETRY","CANCEL") && c.createdAt >= 0 && AiJson.utf8(c.receiptJson) <= RECEIPT_BYTES)
        val s = requireNotNull(sessions[c.sessionId]); val o = JSONObject(c.receiptJson); AiJson.noCredentials(o)
        require(o.keys().asSequence().toSet() == setOf("kind","sessionId","requestId","sessionRevision","contextRevision","draftRevision","userMessageId","assistantMessageId","editorEpoch","localEditVersion","draftCleared","title","status"))
        require(o.getString("kind") == c.kind && o.getString("sessionId") == s.sessionId)
        require(AiJson.long(o,"sessionRevision") in 0..s.sessionRevision && AiJson.long(o,"contextRevision") in 0..s.contextRevision && AiJson.long(o,"draftRevision") in 0..s.draftRevision)
        AiJson.boolean(o,"draftCleared"); val title = AiJson.string(o,"title"); require(title.codePointCount(0,title.length) in 1..80)
        if (!o.isNull("editorEpoch")) { AiJson.uuid(o.getString("editorEpoch")); require(AiJson.long(o,"localEditVersion") >= 0) } else require(o.isNull("localEditVersion"))
        if (c.requestId != null) {
            val r = requireNotNull(runs[c.requestId]); require(r.sessionId == s.sessionId && o.getString("requestId") == r.requestId)
            require(o.getString("userMessageId") == r.userMessageId && o.getString("assistantMessageId") == r.assistantMessageId)
            require(messages.getValue(r.userMessageId).role == "USER")
            if (c.kind in setOf("SEND","RETRY")) require(r.commandId == c.commandId && o.getString("status") == "PREPARING")
            else require(c.kind == "CANCEL" && o.getString("status") == "CANCELLED" && r.status == "CANCELLED")
        } else require(c.kind in setOf("CREATE","RENAME","DRAFT") && o.isNull("requestId") && o.isNull("userMessageId") && o.isNull("assistantMessageId") && o.isNull("status"))
        when(c.kind) {
            "CREATE" -> require(AiJson.long(o,"sessionRevision") == 0L && AiJson.long(o,"contextRevision") == 0L && AiJson.long(o,"draftRevision") == 0L && !o.getBoolean("draftCleared"))
            "DRAFT" -> require(!o.isNull("editorEpoch") && AiJson.long(o,"draftRevision") > 0 && !o.getBoolean("draftCleared"))
            "RENAME" -> require(AiJson.long(o,"sessionRevision") > 0 && !o.getBoolean("draftCleared"))
            "SEND" -> require(AiJson.long(o,"contextRevision") > 0)
        }
        require(AiJson.canonical(o) == c.receiptJson)
    }
    private val maximumCancelCommandBytes: Long by lazy {
        val uuid="00000000-0000-0000-0000-000000000000"
        // A valid receipt is <=16KiB raw UTF-8. A control character is the worst 6-byte JSON escape.
        // This unpersisted counting specimen intentionally is not a receipt to restore.
        Math.addExact(AiJson.encodedBytes(AiRowCodec.encode(AiCommandRow(uuid,"CANCEL","0".repeat(64),
            "\u0001".repeat(RECEIPT_BYTES.toInt()),Long.MAX_VALUE,uuid,uuid))),1L) // extra array separator
    }
    /** Conservative encoded bound for interrupt/cancel plus a necessary cancel receipt; no text loss. */
    fun requiredClosingReserve(run: AiRunRow): Long {
        val base=AiJson.encodedBytes(AiRowCodec.encode(run.copy(reservedBytes=0)))
        val interruption=run.copy(status="FAILED",reservedBytes=0,finishedAt=Long.MAX_VALUE,
            terminalReason="PROCESS_INTERRUPTED",usageCutoffSeq=Long.MAX_VALUE)
        val cancellation=run.copy(status="CANCELLED",reservedBytes=0,finishedAt=Long.MAX_VALUE,
            terminalReason="USER_STOP",usageCutoffSeq=Long.MAX_VALUE)
        val growth=maxOf(AiJson.encodedBytes(AiRowCodec.encode(interruption)),
            AiJson.encodedBytes(AiRowCodec.encode(cancellation)))-base
        return Math.addExact(growth.coerceAtLeast(0),maximumCancelCommandBytes)
    }
    /** Consume only incremental storage. Binary search includes reservedBytes' own decimal digits. */
    fun consume(before: AiDataset, proposed: AiDataset, requestId: String, terminal: Boolean, extraEncodedDelta:Long=0): AiDataset {
        val old = before.runs.single { it.requestId == requestId }
        require(old.status in setOf("PREPARING","GENERATING") && old.reservedBytes >= requiredClosingReserve(old)) { "INSUFFICIENT_CLOSING_RESERVE" }
        val ceiling = Math.addExact(encodedBytes(before), reserved(before))
        fun candidate(n: Long) = proposed.copy(runs = proposed.runs.map { if (it.requestId == requestId) it.copy(reservedBytes = n) else it })
        fun fits(n: Long): Boolean { val d = candidate(n); return Math.addExact(Math.addExact(encodedBytes(d), reserved(d)),extraEncodedDelta) <= ceiling }
        require(fits(0)) { "RESERVATION_EXHAUSTED" }
        if (terminal) return candidate(0)
        val closingFloor=requiredClosingReserve(proposed.runs.single { it.requestId==requestId })
        require(closingFloor <= old.reservedBytes && fits(closingFloor)) { "CLOSING_RESERVE_EXHAUSTED" }
        var low = closingFloor; var high = old.reservedBytes
        while (low < high) { val mid = low + (high-low+1)/2; if (fits(mid)) low = mid else high = mid-1 }
        return candidate(low)
    }
}

