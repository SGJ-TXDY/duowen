package cn.embeddedquiz

import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal class AiPageReader(private val dao:AiConversationDao,private val repo:QuizRepository,private val epoch:()->String) {
    private val secret=ByteArray(32).also(SecureRandom()::nextBytes)
    private val enc=Base64.getUrlEncoder().withoutPadding()
    private val dec=Base64.getUrlDecoder()
    private data class Boundary(val at:Long,val tie:String)
    private fun sign(bytes:ByteArray)=Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret,"HmacSHA256")) }.doFinal(bytes)
    private fun cursor(query:String,b:Boundary):PageCursor {
        val body=AiJson.canonical(JSONObject().put("epoch",epoch()).put("q",query).put("at",b.at).put("tie",b.tie)).toByteArray(Charsets.UTF_8)
        return PageCursor(enc.encodeToString(body)+"."+enc.encodeToString(sign(body)))
    }
    private fun boundary(query:String,after:PageCursor?):Boundary? {
        if(after==null) return null
        return try {
            require(after.opaque.length<=4096);val parts=after.opaque.split('.');require(parts.size==2)
            val body=dec.decode(parts[0]);require(MessageDigest.isEqual(sign(body),dec.decode(parts[1])))
            val o=JSONObject(body.toString(Charsets.UTF_8));require(o.getString("epoch")==epoch() && o.getString("q")==query)
            Boundary(AiJson.long(o,"at"),AiJson.string(o,"tie"))
        } catch(_:Exception) { throw AiStoreFailure(RejectCode.STALE_CURSOR) }
    }
    private fun filter(vararg fields:Any)=DeepSeekClient.hash(AiJson.canonical(org.json.JSONArray(fields.toList())))
    fun token(row:AiSessionRow)=row.lastEditorEpoch?.let { DraftToken(it,requireNotNull(row.lastLocalEditVersion)) }
    fun session(row:AiSessionRow,hash:String?)=SessionView(row.sessionId,row.owner,row.title,
        row.sessionRevision,row.contextRevision,DraftView(row.draftText,row.draftRevision,false,token(row)),
        row.contentHash!=hash,row.questionSnapshotVersion,row.questionSnapshot)
    private data class CurrentSource(val hash: String, val label: String, val type: AiDiscussionType)

    private suspend fun currentSource(owner: QuestionOwner): CurrentSource? = when (owner.kind) {
        AiOwnerKind.QUESTION -> repo.question(owner.questionId)?.let {
            CurrentSource(it.contentHash, it.stem, questionType(it.kind.name))
        }
        AiOwnerKind.QUICK_NOTE -> try {
            repo.db.quickNotesRepository.aiSummary(owner.questionId)?.let {
                CurrentSource(it.contentHash, it.title, AiDiscussionType.QUICK_NOTE)
            }
        } catch (e: AiStoreFailure) {
            if (e.code != RejectCode.STORAGE_FAILED) throw e
            null
        }
        AiOwnerKind.FREE, AiOwnerKind.TEMPORARY -> null
    }

    private fun questionType(kind: String) =
        if (kind == Kind.PROGRAMMING.name) AiDiscussionType.PROGRAMMING else AiDiscussionType.QUESTION

    private suspend fun currentHash(owner: QuestionOwner): String? =
        if (owner.kind == AiOwnerKind.FREE) owner.contentHash else currentSource(owner)?.hash

    suspend fun session(row:AiSessionRow)=session(row,currentHash(row.owner))

    suspend fun allSessions(after: PageCursor?, limit: Int): AiPage<AiDiscussionListItem> {
        require(limit in 1..100)
        val query = filter("discussions-created-desc")
        val boundary = boundary(query, after)
        val rows = dao.discussionPage(boundary?.at, boundary?.tie.orEmpty(), limit + 1)
        val page = rows.take(limit)
        val sources = mutableMapOf<Pair<AiOwnerKind, String>, CurrentSource?>()
        val items = page.map { row ->
            val owner = row.owner
            val key = owner.kind to owner.questionId
            if (key !in sources) sources[key] = currentSource(owner)
            listItem(row, source(row, sources[key]))
        }
        val next = if (rows.size > limit) page.last().let {
            cursor(query, Boundary(it.createdAt, it.sessionId))
        } else null
        return AiPage(items, next)
    }

    private fun source(row: AiSessionRow, current: CurrentSource?, forGroup: Boolean = false): AiDiscussionSource {
        val owner = row.owner
        val groupId = AiDiscussionGroupId(owner.kind, owner.questionId)
        if (owner.kind == AiOwnerKind.FREE) {
            val snapshot = AiFreeSnapshot.read(row.questionSnapshot, owner)
            return AiDiscussionSource(groupId, AiDiscussionType.FREE, snapshot.topic, AiSourceStatus.INDEPENDENT,
                null, snapshot.reference?.summary)
        }
        val status = when {
            current == null -> AiSourceStatus.DELETED
            current.hash == owner.contentHash -> AiSourceStatus.CURRENT
            else -> AiSourceStatus.UPDATED
        }
        val snapshot = if (forGroup && current != null || status == AiSourceStatus.CURRENT) null else JSONObject(row.questionSnapshot)
        val question = snapshot?.optJSONObject("question")
        val label = question?.getString("stem") ?: snapshot?.getString("title") ?: requireNotNull(current).label
        val type = current?.type ?: question?.let { questionType(it.getString("kind")) } ?: AiDiscussionType.QUICK_NOTE
        return AiDiscussionSource(groupId, type, if (owner.kind == AiOwnerKind.QUESTION) boundedAiText(label.trim(), 160) else label,
            status, current?.let { owner.copy(contentHash = it.hash) })
    }

    private fun listItem(row: AiSessionRow, source: AiDiscussionSource) = AiDiscussionListItem(
        row.sessionId, row.owner, row.title, row.sessionRevision, row.createdAt, source.label,
        source.status == AiSourceStatus.DELETED || source.status == AiSourceStatus.UPDATED,
    )

    suspend fun discussionSource(row: AiSessionRow) = source(row, currentSource(row.owner))

    suspend fun discussionGroups(type: AiDiscussionType?, after: PageCursor?, limit: Int, generation: Long): AiPage<AiDiscussionGroup> =
        filteredPage(filter("workbench-groups", generation, type?.name.orEmpty()), after, limit,
            { b, size -> dao.workbenchGroups(b?.at, b?.tie.orEmpty(), size) },
            { Boundary(it.lastActivityAt, it.session.sessionId) }) { row ->
            val source = source(row.session, currentSource(row.session.owner), forGroup = true)
            if (type == null || type == source.type)
                AiDiscussionGroup(source.copy(reference = null), row.sessionCount, row.lastActivityAt)
            else null
        }

    suspend fun groupDiscussions(groupId: AiDiscussionGroupId, after: PageCursor?, limit: Int, generation: Long): AiPage<AiDiscussionSummary> {
        val version = groupId.ownerKind.savedSnapshotVersion
        require(groupId.sourceId.isNotBlank())
        return filteredPage(filter("workbench-group", generation, version, groupId.sourceId), after, limit,
            { b, size -> dao.workbenchPage(version, groupId.sourceId, null, b?.at, b?.tie.orEmpty(), size) },
            ::activityBoundary) { summary(it) }
    }

    suspend fun recentDiscussions(type: AiDiscussionType?, after: PageCursor?, limit: Int, generation: Long): AiPage<AiDiscussionSummary> =
        filteredPage(filter("workbench-recent", generation, type?.name.orEmpty()), after, limit,
            { b, size -> dao.workbenchPage(null, null, null, b?.at, b?.tie.orEmpty(), size) },
            ::activityBoundary) { row -> summary(row).takeIf { type == null || type == it.source.type } }

    suspend fun searchDiscussions(input: String, after: PageCursor?, limit: Int, generation: Long): AiPage<AiDiscussionSearchHit> {
        val query = input.trim()
        require(query.codePointCount(0, query.length) in 1..120)
        return filteredPage(filter("workbench-search", generation, query), after, limit,
            { b, size -> dao.workbenchPage(null, null, query, b?.at, b?.tie.orEmpty(), size) },
            ::activityBoundary) { row ->
            val source = discussionSource(row.session)
            val snippet = when {
                row.session.title.contains(query, ignoreCase = true) -> row.session.title
                source.label.contains(query, ignoreCase = true) -> source.label
                else -> row.messageSnippet
            }
            snippet?.let { AiDiscussionSearchHit(AiDiscussionSummary(listItem(row.session, source), source, row.lastMessageAt), it) }
        }
    }

    private suspend fun summary(row: AiWorkbenchRow): AiDiscussionSummary {
        val source = discussionSource(row.session)
        return AiDiscussionSummary(listItem(row.session, source), source, row.lastMessageAt)
    }

    private fun activityBoundary(row: AiWorkbenchRow) = Boundary(row.lastMessageAt ?: row.session.createdAt, row.session.sessionId)

    private suspend fun <Row, Item> filteredPage(
        query: String, after: PageCursor?, limit: Int,
        fetch: suspend (Boundary?, Int) -> List<Row>, key: (Row) -> Boundary, transform: suspend (Row) -> Item?,
    ): AiPage<Item> {
        require(limit in 1..100)
        var next = boundary(query, after)
        var lastMatch: Boundary? = null
        val items = mutableListOf<Item>()
        val batchSize = maxOf(32, limit + 1)
        while (true) {
            val batch = fetch(next, batchSize)
            for (row in batch) {
                currentCoroutineContext().ensureActive()
                val item = transform(row) ?: continue
                if (items.size == limit) return AiPage(items, cursor(query, requireNotNull(lastMatch)))
                items += item
                lastMatch = key(row)
            }
            if (batch.size < batchSize) return AiPage(items, null)
            next = key(batch.last())
        }
    }
    suspend fun sessions(qid:String,archived:Boolean,after:PageCursor?,limit:Int):AiPage<SessionView> =
        sessions(QuestionOwner(qid, ""),archived,after,limit)
    suspend fun sessions(owner:QuestionOwner,archived:Boolean,after:PageCursor?,limit:Int):AiPage<SessionView> {
        val qid = owner.questionId
        require(limit in 1..100 && qid.isNotBlank())
        val hash = currentHash(owner)
        val version = if (owner.kind == AiOwnerKind.QUICK_NOTE) AiSnapshotCodec.DOCUMENT_VERSION else AiSnapshotCodec.VERSION
        val q=filter("sessions-desc",qid,archived,version,owner.contentHash,hash.orEmpty());val b=boundary(q,after)
        val visibleHash = if (owner.kind == AiOwnerKind.QUICK_NOTE && owner.contentHash != hash) null else hash
        val rows=dao.sessionPage(qid,visibleHash.orEmpty(),archived,b?.at,b?.tie.orEmpty(),limit+1,version);val page=rows.take(limit)
        return AiPage(page.map { session(it,visibleHash) },if(rows.size>limit) page.last().let { cursor(q,Boundary(it.updatedAt,it.sessionId)) } else null)
    }
    fun run(row:AiRunRow,question:QuestionOwner,canRetry:Boolean)=RunView(RunOwner(question,row.sessionId,row.requestId),
        RunStatus.valueOf(row.status),RunSource.valueOf(row.source),row.retryOf,row.sourceRequestId,
        UsageView(row.inputTokens,row.outputTokens,row.hitTokens,UsageState.valueOf(row.usageStatus),row.estimatedUsd?.let(::BigDecimal),row.currency,row.priceVersion),
        row.errorCode ?: row.terminalReason,canRetry && row.errorCode != "PROVIDER_CHANGED",
        if (question.kind == AiOwnerKind.QUICK_NOTE) AiSnapshotCodec.documentReferences(row.contextSnapshot) else emptyList(),
        if (row.contextSnapshotVersion == AiSnapshotCodec.DOCUMENT_REQUEST_VERSION ||
            row.contextSnapshotVersion == AiSnapshotCodec.DOCUMENT_AGENT_VERSION) AiSnapshotCodec.documentCoverage(row.contextSnapshot)
        else null)
    suspend fun messages(sid:String,after:PageCursor?,limit:Int,busy:Boolean):AiPage<MessageView> {
        require(limit in 1..100);val s=dao.session(sid) ?: throw AiStoreFailure(RejectCode.NOT_FOUND)
        val q=filter("messages-before",sid);val b=boundary(q,after)
        val rows=dao.messagePage(sid,b?.at,limit+1);val page=rows.take(limit)
        val newest=dao.maxSeq(sid);val owner=s.owner
        val writable=currentHash(owner)==s.contentHash
        return AiPage(page.asReversed().map { m -> MessageView(m.messageId,sid,m.seq,MessageRole.valueOf(m.role),m.text,TextState.valueOf(m.completion),
            if(m.role=="ASSISTANT") dao.runForAssistant(m.messageId)?.let { r->
                run(r,owner,writable && !busy && m.seq==newest && r.status in setOf("FAILED","CANCELLED") && dao.completedAttempts(r.userMessageId)==0)
            } else null) },if(rows.size>limit) cursor(q,Boundary(page.last().seq,"")) else null)
    }
    suspend fun legacy(f:HistoryFilter,after:PageCursor?,limit:Int):AiPage<LegacyEntryView> {
        require(limit in 1..100)
        val (id,mode,hash)=when(f) {
            is HistoryFilter.Exact -> { require(f.question.kind == AiOwnerKind.QUESTION); AiJson.hash(f.question.contentHash);Triple(f.question.questionId,0,f.question.contentHash) }
            is HistoryFilter.UnknownHash -> Triple(f.questionId,1,"")
            is HistoryFilter.AllVersions -> Triple(f.questionId,2,"")
        }
        val q=filter("legacy-desc",id,mode,hash);val b=boundary(q,after)
        val rows=dao.legacyPage(id,mode,hash,b?.at,b?.tie.orEmpty(),limit+1);val page=rows.take(limit)
        return AiPage(page.map { LegacyEntryView(it.cacheKey,it.questionId,it.contentHash.ifEmpty { null },it.prompt,it.response,
            it.created,it.model,BigDecimal.valueOf(it.estimatedUsd)) },if(rows.size>limit) page.last().let { cursor(q,Boundary(it.created,it.cacheKey)) } else null)
    }
}
