package cn.embeddedquiz

import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
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
    private suspend fun currentHash(owner: QuestionOwner): String? = when (owner.kind) {
        AiOwnerKind.QUESTION -> repo.question(owner.questionId)?.contentHash
        AiOwnerKind.QUICK_NOTE -> try {
            repo.db.quickNotesRepository.aiSummary(owner.questionId)?.contentHash
        } catch (e: AiStoreFailure) {
            if (e.code != RejectCode.STORAGE_FAILED) throw e
            null
        }
    }
    suspend fun session(row:AiSessionRow)=session(row,currentHash(row.owner))
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
        row.errorCode ?: row.terminalReason,canRetry,
        if (question.kind == AiOwnerKind.QUICK_NOTE) AiSnapshotCodec.documentReferences(row.contextSnapshot) else emptyList(),
        if (row.contextSnapshotVersion == AiSnapshotCodec.DOCUMENT_REQUEST_VERSION) AiSnapshotCodec.documentCoverage(row.contextSnapshot)
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
