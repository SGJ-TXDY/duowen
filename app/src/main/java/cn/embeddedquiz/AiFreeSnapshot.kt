package cn.embeddedquiz

import org.json.JSONObject

private const val REFERENCE_BYTES = 8L * 1024
private const val REFERENCE_TEXT = 500

internal data class AiReferenceTurn(val user: String, val assistant: String) {
    fun json() = JSONObject().put("user", user).put("assistant", assistant)
}

internal data class AiFrozenReference(val summary: AiDiscussionReference, val turns: List<AiReferenceTurn>) {
    fun payload() = JSONObject().put("title", summary.title).put("partial", summary.partial)
        .put("turns", AiJson.array(turns.map(AiReferenceTurn::json)))

    fun json() = payload().put("sessionId", summary.sessionId)

    companion object {
        fun read(o: JSONObject): AiFrozenReference {
            val id = AiJson.string(o, "sessionId").also(AiJson::uuid)
            val title = AiJson.string(o, "title")
            require(title.codePointCount(0, title.length) in 1..80)
            val turns = AiJson.objects(o, "turns").map {
                AiReferenceTurn(AiJson.string(it, "user"), AiJson.string(it, "assistant")).also { turn ->
                    require(turn.user.length in 1..REFERENCE_TEXT && turn.assistant.length in 1..REFERENCE_TEXT)
                }
            }
            val ref = AiFrozenReference(AiDiscussionReference(id, title, AiJson.boolean(o, "partial")), turns)
            require(turns.size <= 4 && AiJson.encodedBytes(o) <= REFERENCE_BYTES)
            require(AiJson.canonical(ref.json()) == AiJson.canonical(o))
            return ref
        }
    }
}

internal data class AiFreeSnapshot(val groupId: String, val topic: String, val reference: AiFrozenReference?) {
    fun encode(): String = AiJson.canonical(JSONObject().put("version", AiSnapshotCodec.FREE_VERSION)
        .put("groupId", groupId).put("topic", topic).put("reference", reference?.json() ?: JSONObject.NULL))

    companion object {
        fun read(text: String, owner: QuestionOwner): AiFreeSnapshot {
            val o = JSONObject(text)
            val groupId = AiJson.string(o, "groupId").also(AiJson::uuid)
            val topic = AiJson.string(o, "topic")
            require(owner.kind == AiOwnerKind.FREE && groupId == owner.questionId)
            require(topic.isNotBlank() && topic.length <= 160)
            require(owner.contentHash == DeepSeekClient.hash(text))
            val snapshot = AiFreeSnapshot(groupId, topic,
                if (o.isNull("reference")) null else AiFrozenReference.read(o.getJSONObject("reference")))
            require(snapshot.encode() == text)
            return snapshot
        }
    }
}

internal suspend fun AiConversationDao.freezeDiscussionReference(groupId: String, sessionId: String): AiFrozenReference {
    AiJson.uuid(groupId)
    AiJson.uuid(sessionId)
    val source = session(sessionId) ?: throw AiStoreFailure(RejectCode.NOT_FOUND)
    if (source.owner.kind != AiOwnerKind.FREE || source.questionId != groupId) throw AiStoreFailure(RejectCode.STALE_OWNER)
    val runs = recentCompleted(sessionId).asReversed()
    var partial = maxSeq(sessionId) >= runs.size * 2L
    var turns = runs.map { run ->
        val user = requireNotNull(message(run.userMessageId)).text
        val assistant = requireNotNull(message(run.assistantMessageId)).text
        val turn = AiReferenceTurn(boundedAiText(user, REFERENCE_TEXT), boundedAiText(assistant, REFERENCE_TEXT))
        if (turn.user != user || turn.assistant != assistant) partial = true
        turn
    }
    while (true) {
        val ref = AiFrozenReference(AiDiscussionReference(sessionId, source.title, partial), turns)
        if (AiJson.encodedBytes(ref.json()) <= REFERENCE_BYTES) return ref
        partial = true
        turns = turns.drop(1)
    }
}
