package cn.embeddedquiz

import org.json.JSONObject

internal object AiRowCodec {
    fun encode(row: AiSessionRow): JSONObject = JSONObject().apply {
        put("sessionId", row.sessionId)
        put("questionId", row.questionId)
        put("contentHash", row.contentHash)
        put("questionSnapshotVersion", row.questionSnapshotVersion)
        put("questionSnapshot", row.questionSnapshot)
        put("title", row.title)
        put("createdAt", row.createdAt)
        put("updatedAt", row.updatedAt)
        put("sessionRevision", row.sessionRevision)
        put("contextRevision", row.contextRevision)
        put("draftText", row.draftText)
        put("draftRevision", row.draftRevision)
        put("lastEditorEpoch", row.lastEditorEpoch ?: JSONObject.NULL)
        put("lastLocalEditVersion", row.lastLocalEditVersion ?: JSONObject.NULL)
    }
    fun decodeSession(o: JSONObject): AiSessionRow = AiSessionRow(
        sessionId = AiJson.string(o, "sessionId"),
        questionId = AiJson.string(o, "questionId"),
        contentHash = AiJson.string(o, "contentHash"),
        questionSnapshotVersion = Math.toIntExact(AiJson.long(o, "questionSnapshotVersion")),
        questionSnapshot = AiJson.string(o, "questionSnapshot"),
        title = AiJson.string(o, "title"),
        createdAt = AiJson.long(o, "createdAt"),
        updatedAt = AiJson.long(o, "updatedAt"),
        sessionRevision = AiJson.long(o, "sessionRevision"),
        contextRevision = AiJson.long(o, "contextRevision"),
        draftText = AiJson.string(o, "draftText"),
        draftRevision = AiJson.long(o, "draftRevision"),
        lastEditorEpoch = if (o.isNull("lastEditorEpoch")) null else AiJson.string(o, "lastEditorEpoch"),
        lastLocalEditVersion = if (o.isNull("lastLocalEditVersion")) null else AiJson.long(o, "lastLocalEditVersion"),
    )
    fun encode(row: AiMessageRow): JSONObject = JSONObject().apply {
        put("messageId", row.messageId)
        put("sessionId", row.sessionId)
        put("seq", row.seq)
        put("role", row.role)
        put("text", row.text)
        put("completion", row.completion)
        put("createdAt", row.createdAt)
    }
    fun decodeMessage(o: JSONObject): AiMessageRow = AiMessageRow(
        messageId = AiJson.string(o, "messageId"),
        sessionId = AiJson.string(o, "sessionId"),
        seq = AiJson.long(o, "seq"),
        role = AiJson.string(o, "role"),
        text = AiJson.string(o, "text"),
        completion = AiJson.string(o, "completion"),
        createdAt = AiJson.long(o, "createdAt"),
    )
    fun encode(row: AiRunRow): JSONObject = JSONObject().apply {
        put("requestId", row.requestId)
        put("commandId", row.commandId)
        put("sessionId", row.sessionId)
        put("userMessageId", row.userMessageId)
        put("assistantMessageId", row.assistantMessageId)
        put("model", row.model)
        put("requestType", row.requestType)
        put("coordinatorEpoch", row.coordinatorEpoch)
        put("contextSnapshot", row.contextSnapshot)
        put("contextDigest", row.contextDigest)
        put("cacheKey", row.cacheKey)
        put("createdAt", row.createdAt)
        put("billingDay", row.billingDay)
        put("billingZone", row.billingZone)
        put("contextSnapshotVersion", row.contextSnapshotVersion)
        put("status", row.status)
        put("source", row.source)
        put("sourceRequestId", row.sourceRequestId ?: JSONObject.NULL)
        put("retryOf", row.retryOf ?: JSONObject.NULL)
        put("terminalReason", row.terminalReason ?: JSONObject.NULL)
        put("promptVersion", row.promptVersion)
        put("effectiveParameters", row.effectiveParameters)
        put("reservedBytes", row.reservedBytes)
        put("eventSeq", row.eventSeq)
        put("usageCutoffSeq", row.usageCutoffSeq ?: JSONObject.NULL)
        put("networkDispatched", row.networkDispatched)
        put("finishedAt", row.finishedAt ?: JSONObject.NULL)
        put("providerResponseId", row.providerResponseId ?: JSONObject.NULL)
        put("finishReason", row.finishReason ?: JSONObject.NULL)
        put("inputTokens", row.inputTokens ?: JSONObject.NULL)
        put("outputTokens", row.outputTokens ?: JSONObject.NULL)
        put("hitTokens", row.hitTokens ?: JSONObject.NULL)
        put("usageStatus", row.usageStatus)
        put("priceVersion", row.priceVersion ?: JSONObject.NULL)
        put("missRate", row.missRate ?: JSONObject.NULL)
        put("hitRate", row.hitRate ?: JSONObject.NULL)
        put("outputRate", row.outputRate ?: JSONObject.NULL)
        put("estimatedUsd", row.estimatedUsd ?: JSONObject.NULL)
        put("currency", row.currency)
        put("errorCode", row.errorCode ?: JSONObject.NULL)
    }
    fun decodeRun(o: JSONObject): AiRunRow = AiRunRow(
        requestId = AiJson.string(o, "requestId"),
        commandId = AiJson.string(o, "commandId"),
        sessionId = AiJson.string(o, "sessionId"),
        userMessageId = AiJson.string(o, "userMessageId"),
        assistantMessageId = AiJson.string(o, "assistantMessageId"),
        model = AiJson.string(o, "model"),
        requestType = AiJson.string(o, "requestType"),
        coordinatorEpoch = AiJson.string(o, "coordinatorEpoch"),
        contextSnapshot = AiJson.string(o, "contextSnapshot"),
        contextDigest = AiJson.string(o, "contextDigest"),
        cacheKey = AiJson.string(o, "cacheKey"),
        createdAt = AiJson.long(o, "createdAt"),
        billingDay = AiJson.long(o, "billingDay"),
        billingZone = AiJson.string(o, "billingZone"),
        contextSnapshotVersion = Math.toIntExact(AiJson.long(o, "contextSnapshotVersion")),
        status = AiJson.string(o, "status"),
        source = AiJson.string(o, "source"),
        sourceRequestId = if (o.isNull("sourceRequestId")) null else AiJson.string(o, "sourceRequestId"),
        retryOf = if (o.isNull("retryOf")) null else AiJson.string(o, "retryOf"),
        terminalReason = if (o.isNull("terminalReason")) null else AiJson.string(o, "terminalReason"),
        promptVersion = AiJson.string(o, "promptVersion"),
        effectiveParameters = AiJson.string(o, "effectiveParameters"),
        reservedBytes = AiJson.long(o, "reservedBytes"),
        eventSeq = AiJson.long(o, "eventSeq"),
        usageCutoffSeq = if (o.isNull("usageCutoffSeq")) null else AiJson.long(o, "usageCutoffSeq"),
        networkDispatched = AiJson.boolean(o, "networkDispatched"),
        finishedAt = if (o.isNull("finishedAt")) null else AiJson.long(o, "finishedAt"),
        providerResponseId = if (o.isNull("providerResponseId")) null else AiJson.string(o, "providerResponseId"),
        finishReason = if (o.isNull("finishReason")) null else AiJson.string(o, "finishReason"),
        inputTokens = if (o.isNull("inputTokens")) null else AiJson.long(o, "inputTokens"),
        outputTokens = if (o.isNull("outputTokens")) null else AiJson.long(o, "outputTokens"),
        hitTokens = if (o.isNull("hitTokens")) null else AiJson.long(o, "hitTokens"),
        usageStatus = AiJson.string(o, "usageStatus"),
        priceVersion = if (o.isNull("priceVersion")) null else AiJson.string(o, "priceVersion"),
        missRate = if (o.isNull("missRate")) null else AiJson.string(o, "missRate"),
        hitRate = if (o.isNull("hitRate")) null else AiJson.string(o, "hitRate"),
        outputRate = if (o.isNull("outputRate")) null else AiJson.string(o, "outputRate"),
        estimatedUsd = if (o.isNull("estimatedUsd")) null else AiJson.string(o, "estimatedUsd"),
        currency = AiJson.string(o, "currency"),
        errorCode = if (o.isNull("errorCode")) null else AiJson.string(o, "errorCode"),
    )
    fun encode(row: AiCommandRow): JSONObject = JSONObject().apply {
        put("commandId", row.commandId)
        put("kind", row.kind)
        put("payloadDigest", row.payloadDigest)
        put("receiptJson", row.receiptJson)
        put("createdAt", row.createdAt)
        put("sessionId", row.sessionId ?: JSONObject.NULL)
        put("requestId", row.requestId ?: JSONObject.NULL)
    }
    fun decodeCommand(o: JSONObject): AiCommandRow = AiCommandRow(
        commandId = AiJson.string(o, "commandId"),
        kind = AiJson.string(o, "kind"),
        payloadDigest = AiJson.string(o, "payloadDigest"),
        receiptJson = AiJson.string(o, "receiptJson"),
        createdAt = AiJson.long(o, "createdAt"),
        sessionId = if (o.isNull("sessionId")) null else AiJson.string(o, "sessionId"),
        requestId = if (o.isNull("requestId")) null else AiJson.string(o, "requestId"),
    )
    fun encode(row: AiCacheRow): JSONObject = JSONObject().apply {
        put("cacheKey", row.cacheKey)
        put("sourceRequestId", row.sourceRequestId)
        put("createdAt", row.createdAt)
        put("protocolVersion", row.protocolVersion)
    }
    fun decodeCache(o: JSONObject): AiCacheRow = AiCacheRow(
        cacheKey = AiJson.string(o, "cacheKey"),
        sourceRequestId = AiJson.string(o, "sourceRequestId"),
        createdAt = AiJson.long(o, "createdAt"),
        protocolVersion = Math.toIntExact(AiJson.long(o, "protocolVersion")),
    )
}
