package cn.embeddedquiz

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.util.UUID
import org.json.JSONObject

internal class AiStoreFailure(val code: RejectCode) : IllegalStateException(code.name)
internal data class AiUsageEvidence(
    val input: Long? = null, val output: Long? = null, val hit: Long? = null,
    val state: UsageState = UsageState.UNKNOWN, val priceVersion: String? = null,
    val missRate: String? = null, val hitRate: String? = null, val outputRate: String? = null,
    val estimated: String? = null, // Compatibility input only; never trusted for storage.
)

/** Disconnected transactional foundation. It has no HTTP client and implements no UI sender. */
internal class AiStore(
    private val db: RoomDatabase, private val dao: AiConversationDao,
    private val currentQuestion: suspend (String) -> Question?,
    private val now: () -> Long = System::currentTimeMillis,
    private val businessZone: () -> java.time.ZoneId = java.time.ZoneId::systemDefault,
    private val epoch: String = UUID.randomUUID().toString(),
    private val capacity: Long = AiConversationCodec.CAPACITY,
    private val beforeCommit: () -> Unit = {},
    private val ownedByCoordinator:Boolean=false,
    private val useCache:Boolean=false,
    private val quickNotes: QuickNotesRepository? = (db as? QuizDatabase)?.quickNotesRepository,
) {
    private suspend fun <T> transaction(block:suspend () -> T):T =
        if(db is QuizDatabase && !ownedByCoordinator) { QuizRepository(db).ensureAiStorage(); db.aiLifecycle.foundationWrite { db.withTransaction { block() } } }
        else db.withTransaction { block() }
    private val editors = mutableMapOf<String,String>()
    private fun id() = UUID.randomUUID().toString()
    private fun refuse(code: RejectCode): Nothing = throw AiStoreFailure(code)
    suspend fun dataset(): AiDataset = transaction { load() }
    private suspend fun raw() = AiDataset(dao.allSessions(),dao.allMessages(),dao.allRuns(),dao.allCommands(),dao.allCaches())
    private suspend fun load() = AiMeter.plain(raw())
    private suspend fun working(sessionId:String,kind:String,requestId:String?=null):AiDataset {
        if(kind in setOf("DRAFT","RENAME","SESSION")) return AiDataset(sessions=listOfNotNull(dao.session(sessionId)?.copy(encodedBytes=0)))
        val roots=if(requestId!=null) listOfNotNull(dao.run(requestId)) else dao.recentCompleted(sessionId)
        val cached=roots.firstOrNull()?.let { dao.cache(it.cacheKey) }
        return AiWorkingSet(dao).read(sessionId,roots,dao.latestMessage(sessionId),cached)
    }
    private suspend fun consume(before:AiDataset,after:AiDataset,id:String,terminal:Boolean):AiDataset =
        AiConversationCodec.consume(before,after,id,terminal,AiMeter.correction(dao.measures(),before,after))
    suspend fun audit():Unit = transaction {
        val d=raw();AiMeter.verify(d);AiConversationCodec.validate(AiMeter.plain(d),capacity)
        require(AiMeter.total(dao.measures())==AiConversationCodec.encodedBytes(d)) { "INVALID_BYTE_TOTAL" }
    }
    private suspend fun writable(s: AiSessionRow) {
        val hash = when (s.owner.kind) {
            AiOwnerKind.QUESTION -> currentQuestion(s.questionId)?.contentHash
            AiOwnerKind.QUICK_NOTE -> quickNotes?.aiSummary(s.questionId)?.contentHash
            AiOwnerKind.FREE -> s.contentHash
            AiOwnerKind.TEMPORARY -> refuse(RejectCode.INVALID)
        }
        if (hash != s.contentHash) refuse(RejectCode.READ_ONLY)
    }
    private fun session(d: AiDataset, id: String) = d.sessions.singleOrNull { it.sessionId == id } ?: refuse(RejectCode.NOT_FOUND)
    private fun withSession(d: AiDataset, s: AiSessionRow) = d.copy(sessions=d.sessions.map { if (it.sessionId==s.sessionId) s else it })
    private suspend fun busy(d: AiDataset) { if (dao.activeCount()>0) refuse(RejectCode.BUSY) }
    private suspend fun persist(before: AiDataset, after: AiDataset) {
        AiConversationCodec.validate(after,Long.MAX_VALUE,before.runs.toSet().intersect(after.runs.toSet()))
        val stats=dao.measures();val countsBefore=AiMeter.counts(before);val countsAfter=AiMeter.counts(after)
        val bytes=Math.addExact(AiMeter.total(stats),Math.addExact(AiConversationCodec.encodedBytes(after)-AiConversationCodec.encodedBytes(before),AiMeter.correction(stats,before,after)))
        val reservation=Math.addExact(dao.reservedTotal(),AiConversationCodec.reserved(after)-AiConversationCodec.reserved(before))
        require(Math.addExact(bytes,reservation)<=capacity) { "CAPACITY_EXCEEDED" }
        val messagesCount=stats.single { it.tableName=="ai_messages" }.rowCount+countsAfter[1]-countsBefore[1]
        require(messagesCount<=AiConversationCodec.MESSAGE_LIMIT) { "MESSAGE_LIMIT" }
        val sessions=before.sessions.associateBy { it.sessionId }; val messages=before.messages.associateBy { it.messageId }
        val runs=before.runs.associateBy { it.requestId }; val cache=before.cache.associateBy { it.cacheKey }
        val commands=before.commands.map { it.commandId }.toSet()
        val messagesAfter=after.messages.associateBy { it.messageId }
        for (s in after.sessions) if (s.sessionId !in sessions) dao.insertSession(AiMeter.stored(s)) else if (sessions[s.sessionId]!=s) dao.updateSession(AiMeter.stored(s))
        for (m in after.messages) if (m.messageId !in messages) dao.insertMessage(AiMeter.stored(m)) else if (messages[m.messageId]!=m) dao.updateMessage(AiMeter.stored(m))
        for (r in after.runs.sortedBy { row -> messagesAfter.getValue(row.assistantMessageId).seq }) if (r.requestId !in runs) dao.insertRun(AiMeter.stored(r)) else if (runs[r.requestId]!=r) dao.updateRun(AiMeter.stored(r))
        for (c in after.cache) if (c.cacheKey !in cache) dao.insertCache(AiMeter.stored(c)) else if (cache[c.cacheKey]!=c) dao.updateCache(AiMeter.stored(c))
        for (c in after.commands) if (c.commandId !in commands) dao.insertCommand(AiMeter.stored(c))
        beforeCommit()
    }
    private data class Change(val data: AiDataset, val sessionId: String, val requestId: String?=null,
        val token: DraftToken?=null, val draftCleared: Boolean=false,val baseline:AiDataset?=null)
    private suspend fun command(commandId: String, kind: String, args: JSONObject, newSession: Boolean = false,
        action: suspend (AiDataset, Long) -> Change): AiCommandRow = transaction {
        AiJson.uuid(commandId)
        val digest = DeepSeekClient.hash(AiJson.canonical(args.put("kind",kind)))
        dao.command(commandId)?.let {
            if (it.kind!=kind || it.payloadDigest!=digest) refuse(RejectCode.IDEMPOTENCY_CONFLICT)
            return@transaction it.copy(encodedBytes=0)
        }
        val sid=args.optString("sessionId").takeIf { it.isNotBlank() }
        val before=if(sid==null || newSession) AiDataset() else working(sid,kind,args.optString("requestId").takeIf { it.isNotBlank() })
        val acceptedAt = now()
        val change = action(before, acceptedAt); val basis=change.baseline ?: before; val s = session(change.data,change.sessionId)
        val r = change.requestId?.let { rid -> change.data.runs.single { it.requestId==rid } }
        val receipt = JSONObject().put("kind",kind).put("sessionId",s.sessionId).put("requestId",r?.requestId ?: JSONObject.NULL)
            .put("sessionRevision",s.sessionRevision).put("contextRevision",s.contextRevision).put("draftRevision",s.draftRevision)
            .put("userMessageId",r?.userMessageId ?: JSONObject.NULL).put("assistantMessageId",r?.assistantMessageId ?: JSONObject.NULL)
            .put("editorEpoch",change.token?.editorEpoch ?: JSONObject.NULL).put("localEditVersion",change.token?.localEditVersion ?: JSONObject.NULL)
            .put("draftCleared",change.draftCleared).put("title",s.title).put("status",if(kind in setOf("SEND","RETRY")) "PREPARING" else r?.status ?: JSONObject.NULL)
        val result = AiCommandRow(commandId,kind,digest,AiJson.canonical(receipt),acceptedAt,s.sessionId,r?.requestId)
        var after = change.data.copy(commands=change.data.commands+result)
        if (kind=="CANCEL") after=consume(basis,after,r!!.requestId,true)
        persist(basis,after)
        result
    }
    suspend fun create(commandId: String, q: Question): AiCommandRow = command(commandId,"CREATE",JSONObject().put("snapshot",AiSnapshotCodec.question(q))) { d, acceptedAt ->
        val current=currentQuestion(q.id)
        if (current==null || current.contentHash!=q.contentHash) refuse(RejectCode.READ_ONLY)
        val s=AiSessionRow(id(),q.id,q.contentHash,questionSnapshot=AiSnapshotCodec.question(q),title="新讨论",createdAt=acceptedAt,updatedAt=acceptedAt)
        Change(d.copy(sessions=d.sessions+s),s.sessionId)
    }
    suspend fun createDocument(commandId: String, owner: QuestionOwner, snapshot: String): AiCommandRow =
        command(commandId, "CREATE", JSONObject().put("snapshot", snapshot)) { d, acceptedAt ->
            val document = quickNotes?.aiSummary(owner.questionId) ?: refuse(RejectCode.NOT_FOUND)
            if (document.contentHash != owner.contentHash) refuse(RejectCode.STALE_OWNER)
            AiSnapshotCodec.readDocument(snapshot, owner)
            val s = AiSessionRow(id(), document.id, document.contentHash,
                questionSnapshotVersion = AiSnapshotCodec.DOCUMENT_VERSION, questionSnapshot = snapshot,
                title = "新讨论", createdAt = acceptedAt, updatedAt = acceptedAt)
            Change(d.copy(sessions = d.sessions + s), s.sessionId)
        }

    suspend fun deleteSession(sessionId: String, owner: QuestionOwner): Boolean {
        val deleted = transaction {
            val s = dao.session(sessionId) ?: return@transaction false
            if (s.owner != owner) refuse(RejectCode.STALE_OWNER)
            val sql = db.openHelper.writableDatabase
            val args = arrayOf(sessionId)
            sql.query("SELECT 1 FROM ai_runs WHERE sessionId=? AND status IN ('PREPARING','GENERATING') LIMIT 1", args)
                .use { if (it.moveToFirst()) refuse(RejectCode.BUSY) }
            AiDeletedCostLedger.archiveSession(sql, sessionId)
            sql.execSQL("DELETE FROM ai_response_cache WHERE sourceRequestId IN (SELECT requestId FROM ai_runs WHERE sessionId=?)", args)
            sql.execSQL("DELETE FROM ai_commands WHERE sessionId=?", args)
            // Retries and cache hits refer to earlier runs. RESTRICT checks each row at deletion time.
            while (true) {
                val runs = sql.query("SELECT r.requestId FROM ai_runs r JOIN ai_messages m ON m.messageId=r.assistantMessageId " +
                    "WHERE r.sessionId=? ORDER BY m.seq DESC LIMIT 256", args).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
                if (runs.isEmpty()) break
                runs.forEach { sql.execSQL("DELETE FROM ai_runs WHERE requestId=?", arrayOf(it)) }
            }
            sql.execSQL("DELETE FROM ai_messages WHERE sessionId=?", args)
            sql.execSQL("DELETE FROM ai_sessions WHERE sessionId=?", args)
            beforeCommit()
            true
        }
        if (deleted) editors.remove(sessionId)
        return deleted
    }

    suspend fun openEditor(sessionId: String): String = transaction {
        session(working(sessionId,"SESSION"),sessionId)
        id().also { check(dao.grantEditor(sessionId,it)==1);editors[sessionId]=it }
    }
    suspend fun revokeEditor(sessionId:String,editorEpoch:String):Unit = transaction {
        dao.revokeEditor(sessionId,editorEpoch)
        if(editors[sessionId]==editorEpoch) editors.remove(sessionId)
    }
    suspend fun rename(commandId: String, sessionId: String, revision: Long, title: String): AiCommandRow = command(commandId,"RENAME",
        JSONObject().put("sessionId",sessionId).put("revision",revision).put("title",title)) { d, acceptedAt ->
        val s=session(d,sessionId); if (s.sessionRevision!=revision) refuse(RejectCode.STALE_SESSION)
        val normalizedTitle = title.trim()
        require(normalizedTitle.codePointCount(0, normalizedTitle.length) in 1..80)
        val renamed = s.copy(title = normalizedTitle, sessionRevision = Math.addExact(revision, 1),
            updatedAt = maxOf(s.updatedAt, acceptedAt))
        Change(withSession(d, renamed), sessionId)
    }
    suspend fun saveDraft(c: DraftCommand): AiCommandRow = command(c.commandId,"DRAFT",JSONObject().put("sessionId",c.sessionId)
        .put("revision",c.expectedDraftRevision).put("editorEpoch",c.token.editorEpoch).put("localEditVersion",c.token.localEditVersion).put("text",c.text)) { d, acceptedAt ->
        val s=session(d,c.sessionId); writable(s)
        if (editors[s.sessionId]!=c.token.editorEpoch || s.activeEditorEpoch!=c.token.editorEpoch || s.draftRevision!=c.expectedDraftRevision ||
            (s.lastEditorEpoch==c.token.editorEpoch && c.token.localEditVersion <= (s.lastLocalEditVersion ?: -1))) refuse(RejectCode.STALE_DRAFT)
        require(c.token.localEditVersion>=0)
        val next=s.copy(draftText=c.text,draftRevision=Math.addExact(s.draftRevision,1),lastEditorEpoch=c.token.editorEpoch,
            lastLocalEditVersion=c.token.localEditVersion,updatedAt=maxOf(s.updatedAt,acceptedAt))
        Change(withSession(d,next),s.sessionId,token=c.token)
    }
    suspend fun flushDraft(sessionId: String, throughRevision: Long): Long = transaction {
        val s=session(working(sessionId,"SESSION"),sessionId); require(throughRevision in 0..s.draftRevision); s.draftRevision
    }
    private fun sendArgs(c: SendCommand, start: AiFreeDiscussionCommand?) = JSONObject().put("sessionId",c.sessionId).put("questionId",c.question.questionId).put("contentHash",c.question.contentHash)
        .put("contextRevision",c.expectedContextRevision).put("input",c.input).put("choices",org.json.JSONArray(c.choices.sorted()))
        .put("code",c.code).put("requestType",c.requestType.name).put("model",c.model)
        .put("editorEpoch",c.draftToken?.editorEpoch ?: JSONObject.NULL).put("localEditVersion",c.draftToken?.localEditVersion ?: JSONObject.NULL)
        .put("savedDraftRevision",c.savedDraftRevision ?: JSONObject.NULL).withOwnerKind(c.question)
        .apply {
            if (c.question.kind == AiOwnerKind.QUICK_NOTE || c.documentSectionId != null)
                put("documentSectionId", c.documentSectionId ?: JSONObject.NULL)
            if (start != null) put("newFreeDiscussion", true)
                .put("freeGroupId", start.groupId ?: JSONObject.NULL)
                .put("referenceSessionId", start.referenceSessionId ?: JSONObject.NULL)
        }

    private fun JSONObject.withOwnerKind(owner: QuestionOwner): JSONObject = apply {
        if (owner.kind != AiOwnerKind.QUESTION) put("ownerKind", owner.kind.name)
    }
    private fun history(d: AiDataset, sessionId: String): List<JSONObject> {
        val m=d.messages.associateBy { it.messageId }
        return d.runs.filter { it.sessionId==sessionId && it.status=="COMPLETED" && m.getValue(it.assistantMessageId).completion=="COMPLETE" }
            .sortedBy { m.getValue(it.assistantMessageId).seq }.takeLast(4).map {
                JSONObject().put("userMessageId",it.userMessageId).put("assistantMessageId",it.assistantMessageId)
                    .put("user",m.getValue(it.userMessageId).text).put("assistant",m.getValue(it.assistantMessageId).text)
            }
    }
    suspend fun acceptedHistory(sessionId: String): List<JSONObject> = transaction { history(working(sessionId,"HISTORY"),sessionId) }
    suspend fun startFreeDiscussion(start: AiFreeDiscussionCommand): AiCommandRow {
        val pendingOwner = QuestionOwner(start.groupId ?: start.commandId, "", AiOwnerKind.FREE)
        val send = SendCommand(start.commandId, start.commandId, pendingOwner, 0, null, null, start.input,
            emptySet(), "", AiRequestType.FOLLOW_UP, start.model)
        return accept(send, start)
    }

    private suspend fun newFreeSession(start: AiFreeDiscussionCommand, acceptedAt: Long): AiSessionRow {
        if (dao.session(start.commandId) != null) refuse(RejectCode.IDEMPOTENCY_CONFLICT)
        require(start.input.isNotBlank() && AiJson.utf8(start.input) <= AiSnapshotCodec.INPUT_BYTES)
        val groupId = start.groupId ?: id()
        AiJson.uuid(groupId)
        val existing = start.groupId?.let {
            dao.latestDiscussionInGroup(AiSnapshotCodec.FREE_VERSION, it) ?: refuse(RejectCode.NOT_FOUND)
        }
        require(start.referenceSessionId == null || existing != null)
        val reference = start.referenceSessionId?.let { dao.freezeDiscussionReference(groupId, it) }
        val title = boundedAiText(start.input.lineSequence().first { it.isNotBlank() }.trim(), 80)
        val topic = existing?.let { AiFreeSnapshot.read(it.questionSnapshot, it.owner).topic } ?: title
        val snapshot = AiFreeSnapshot(groupId, topic, reference).encode()
        return AiSessionRow(start.commandId, groupId, DeepSeekClient.hash(snapshot), AiSnapshotCodec.FREE_VERSION,
            snapshot, title, acceptedAt, acceptedAt)
    }

    suspend fun accept(c: SendCommand, start: AiFreeDiscussionCommand? = null): AiCommandRow = command(
        c.commandId, "SEND", sendArgs(c, start), newSession = start != null,
    ) { before, acceptedAt ->
        val d = if (start == null) before else before.copy(sessions = listOf(newFreeSession(start, acceptedAt)))
        val s=session(d,c.sessionId); writable(s); busy(d)
        if (start == null && c.question != s.owner) refuse(RejectCode.STALE_OWNER)
        if (s.contextRevision!=c.expectedContextRevision) refuse(RejectCode.STALE_CONTEXT)
        require(c.requestType!=AiRequestType.FOLLOW_UP || c.input.isNotBlank())
        var rounds=if(c.requestType==AiRequestType.FOLLOW_UP) history(d,s.sessionId) else emptyList()
        val documentContext = if (s.owner.kind == AiOwnerKind.QUICK_NOTE) {
            require(c.requestType == AiRequestType.FOLLOW_UP && c.choices.isEmpty() && c.code.isEmpty())
            require(c.documentSectionId == null || c.documentSectionId.length in 1..2048)
            try {
                val last = rounds.lastOrNull()
                val previous = last?.let { turn ->
                    val run = d.runs.first { it.assistantMessageId == turn.getString("assistantMessageId") }
                    QuickNoteAiTurn(turn.getString("user"), AiSnapshotCodec.documentReferences(run.contextSnapshot))
                }
                requireNotNull(quickNotes).aiContext(s.questionId, s.contentHash, c.documentSectionId, c.input, previous)
            } catch (e: AiStoreFailure) {
                if (e.code in setOf(RejectCode.NOT_FOUND, RejectCode.STALE_OWNER)) refuse(RejectCode.READ_ONLY)
                throw e
            }
        } else {
            require(c.documentSectionId == null)
            null
        }
        val free = if (s.owner.kind == AiOwnerKind.FREE) {
            require(c.requestType == AiRequestType.FOLLOW_UP && c.choices.isEmpty() && c.code.isEmpty())
            AiFreeSnapshot.read(s.questionSnapshot, s.owner)
        } else null
        // Drop only whole oldest rounds until the frozen local byte budget is satisfied.
        var snapshot: String
        while(true) {
            try {
                snapshot = when {
                    documentContext != null -> AiSnapshotCodec.documentRequest(s.questionSnapshot, c.model, c.input, rounds, documentContext)
                    free != null -> AiSnapshotCodec.freeRequest(free.reference, c.model, c.input, rounds)
                    else -> AiSnapshotCodec.request(s.questionSnapshot, c.model, c.requestType, c.input, c.choices, c.code, rounds)
                }
                break
            }
            catch(e:IllegalArgumentException) { if(e.message!="INPUT_LIMIT" || rounds.isEmpty()) throw e; rounds=rounds.drop(1) }
        }
        val zone = businessZone()
        val user=AiMessageRow(id(),s.sessionId,nextSeq(d,s.sessionId),"USER",c.input.ifBlank { c.requestType.label },"COMPLETE",acceptedAt)
        val assistant=AiMessageRow(id(),s.sessionId,Math.addExact(user.seq,1),"ASSISTANT","","EMPTY",acceptedAt)
        val clear=c.draftToken!=null && c.savedDraftRevision==s.draftRevision && c.draftToken.editorEpoch==s.activeEditorEpoch && c.draftToken.editorEpoch==s.lastEditorEpoch && c.draftToken.localEditVersion==s.lastLocalEditVersion
        val next=s.copy(contextRevision=Math.addExact(s.contextRevision,1),updatedAt=maxOf(s.updatedAt,acceptedAt),draftText=if(clear) "" else s.draftText,
            draftRevision=if(clear) Math.addExact(s.draftRevision,1) else s.draftRevision)
        val digest=DeepSeekClient.hash(snapshot)
        val run=AiRunRow(id(),c.commandId,s.sessionId,user.messageId,assistant.messageId,c.model,c.requestType.name,epoch,snapshot,digest,
            DeepSeekClient.hash("${s.questionSnapshotVersion}\n${s.sessionId}\n${s.contentHash}\n$digest"),acceptedAt,java.time.Instant.ofEpochMilli(acceptedAt).atZone(zone).toLocalDate().toEpochDay(),zone.id,
            contextSnapshotVersion = if (documentContext == null) s.questionSnapshotVersion else AiSnapshotCodec.DOCUMENT_REQUEST_VERSION,
            effectiveParameters=AiJson.canonical(JSONObject(snapshot).getJSONObject("parameters")),reservedBytes=AiConversationCodec.RUN_RESERVE)
        cached(before,Change(withSession(d,next).copy(messages=d.messages+user+assistant,runs=d.runs+run),s.sessionId,run.requestId,c.draftToken,clear),run)
    }
    private suspend fun nextSeq(d: AiDataset, sessionId: String) = Math.addExact(dao.maxSeq(sessionId),1L)
    suspend fun retry(commandId: String, owner: RunOwner): AiCommandRow = command(commandId,"RETRY",JSONObject().put("sessionId",owner.sessionId)
        .put("requestId",owner.requestId).put("questionId",owner.question.questionId).put("contentHash",owner.question.contentHash).withOwnerKind(owner.question)) { d, acceptedAt ->
        val s=session(d,owner.sessionId); writable(s); busy(d)
        val old=d.runs.singleOrNull { it.requestId==owner.requestId && it.sessionId==owner.sessionId } ?: refuse(RejectCode.STALE_OWNER)
        if(owner.question!=s.owner) refuse(RejectCode.STALE_OWNER)
        if(old.status !in setOf("FAILED","CANCELLED")) refuse(RejectCode.ALREADY_TERMINAL)
        val latest=d.messages.filter { it.sessionId==s.sessionId }.maxByOrNull { it.seq }
        if(latest?.messageId!=old.assistantMessageId || dao.completedAttempts(old.userMessageId)>0) refuse(RejectCode.STALE_ATTEMPT)
        val zone = businessZone()
        val a=AiMessageRow(id(),s.sessionId,nextSeq(d,s.sessionId),"ASSISTANT","","EMPTY",acceptedAt)
        val r=old.copy(requestId=id(),commandId=commandId,assistantMessageId=a.messageId,retryOf=old.requestId,status="PREPARING",source="NETWORK",sourceRequestId=null,
            coordinatorEpoch=epoch,createdAt=acceptedAt,billingDay=java.time.Instant.ofEpochMilli(acceptedAt).atZone(zone).toLocalDate().toEpochDay(),billingZone=zone.id,finishedAt=null,terminalReason=null,eventSeq=0,usageCutoffSeq=null,networkDispatched=false,
            reservedBytes=AiConversationCodec.RUN_RESERVE,inputTokens=null,outputTokens=null,hitTokens=null,usageStatus="UNKNOWN",estimatedUsd=null,
            priceVersion=null,missRate=null,hitRate=null,outputRate=null,errorCode=null,finishReason=null,providerResponseId=null)
        cached(d,Change(d.copy(messages=d.messages+a,runs=d.runs+r),s.sessionId,r.requestId),r)
    }
    private suspend fun cached(before:AiDataset,change:Change,run:AiRunRow):Change {
        if(!useCache) return change
        val cache=dao.cache(run.cacheKey) ?: return change
        val source=requireNotNull(dao.run(cache.sourceRequestId))
        val data=AiWorkingSet(dao).read(run.sessionId,listOf(source),extraCache=cache)
        fun <T> merge(a:List<T>,b:List<T>,id:(T)->String)=(a+b).distinctBy(id)
        fun extend(d:AiDataset)=d.copy(messages=merge(d.messages,data.messages,AiMessageRow::messageId),
            runs=merge(d.runs,data.runs,AiRunRow::requestId),commands=merge(d.commands,data.commands,AiCommandRow::commandId),
            cache=merge(d.cache,data.cache,AiCacheRow::cacheKey))
        val origin=data.messages.single { it.messageId==source.assistantMessageId }
        check(source.source=="NETWORK" && source.status=="COMPLETED" && origin.completion=="COMPLETE")
        val base=extend(before);val proposed=extend(change.data);val session=session(proposed,run.sessionId)
        val after=withSession(proposed,session.copy(contextRevision=Math.addExact(session.contextRevision,1),updatedAt=maxOf(session.updatedAt,run.createdAt)))
            .copy(runs=proposed.runs.map { if(it.requestId==run.requestId) it.copy(source="CACHE",sourceRequestId=source.requestId,
                status="COMPLETED",finishedAt=run.createdAt,usageCutoffSeq=0,usageStatus="NOT_APPLICABLE",estimatedUsd="0",reservedBytes=0) else it },
                messages=proposed.messages.map { if(it.messageId==run.assistantMessageId) it.copy(text=origin.text,completion="COMPLETE") else it })
        return change.copy(data=after,baseline=base)
    }
    suspend fun dispatched(requestId:String,price:AiUsageEvidence):Long = transaction {
        val root=requireNotNull(dao.run(requestId));val before=working(root.sessionId,"CHECKPOINT",requestId)
        val old=before.runs.single { it.requestId==requestId }
        check(old.coordinatorEpoch==epoch && old.status=="PREPARING" && !old.networkDispatched)
        val r=old.copy(status="GENERATING",networkDispatched=true,eventSeq=Math.addExact(old.eventSeq,1),
            priceVersion=price.priceVersion,missRate=price.missRate,hitRate=price.hitRate,outputRate=price.outputRate)
        val after=before.copy(runs=before.runs.map { if(it.requestId==requestId) r else it })
        persist(before,consume(before,after,requestId,false));r.eventSeq
    }
    suspend fun cancel(commandId: String, owner: RunOwner): AiCommandRow = command(commandId,"CANCEL",JSONObject().put("sessionId",owner.sessionId)
        .put("requestId",owner.requestId).put("questionId",owner.question.questionId).put("contentHash",owner.question.contentHash).withOwnerKind(owner.question)) { d, acceptedAt ->
        val s=session(d,owner.sessionId)
        val r=d.runs.singleOrNull { it.requestId==owner.requestId && it.sessionId==owner.sessionId } ?: refuse(RejectCode.STALE_OWNER)
        if(owner.question!=s.owner) refuse(RejectCode.STALE_OWNER)
        if(r.status !in setOf("PREPARING","GENERATING")) refuse(RejectCode.ALREADY_TERMINAL)
        val next=r.copy(status="CANCELLED",finishedAt=maxOf(acceptedAt,r.createdAt),terminalReason="USER_STOP",usageCutoffSeq=r.eventSeq)
        Change(d.copy(runs=d.runs.map { if(it.requestId==r.requestId) next else it }),s.sessionId,r.requestId)
    }
    /** Injected persistence events only; a future runtime coordinator owns transport/cleanup. */
    suspend fun checkpoint(requestId: String, eventSeq: Long, text: String, terminal: RunStatus?=null,
        usage: AiUsageEvidence?=null, expectedEpoch: String=epoch,
        errorCode:String?=null,finishReason:String?=null,providerResponseId:String?=null): Unit = transaction {
        val root=requireNotNull(dao.run(requestId));val before=working(root.sessionId,"CHECKPOINT",requestId); val old=before.runs.single { it.requestId==requestId }
        require(old.coordinatorEpoch==expectedEpoch && expectedEpoch==epoch) { "STALE_EPOCH" }
        if(eventSeq<=old.eventSeq) return@transaction
        require(old.status in setOf("PREPARING","GENERATING")) { "ALREADY_TERMINAL" }
        require(terminal==null || terminal in setOf(RunStatus.COMPLETED,RunStatus.CANCELLED,RunStatus.FAILED))
        val completion=if(terminal==RunStatus.COMPLETED) "COMPLETE" else if(text.isEmpty()) "EMPTY" else "PARTIAL"
        val evidence=usage ?: AiUsageEvidence(old.inputTokens,old.outputTokens,old.hitTokens,UsageState.valueOf(old.usageStatus),old.priceVersion,
            old.missRate,old.hitRate,old.outputRate,old.estimatedUsd)
        val run=old.copy(status=terminal?.name ?: "GENERATING",eventSeq=eventSeq,finishedAt=if(terminal!=null) maxOf(now(),old.createdAt) else null,
            errorCode=errorCode,finishReason=finishReason,providerResponseId=providerResponseId,
            usageCutoffSeq=if(terminal!=null) eventSeq else null,terminalReason=if(terminal==RunStatus.FAILED) errorCode ?: "INTERRUPTED" else null,
            inputTokens=evidence.input,outputTokens=evidence.output,hitTokens=evidence.hit,usageStatus=evidence.state.name,estimatedUsd=null,
            priceVersion=evidence.priceVersion,missRate=evidence.missRate,hitRate=evidence.hitRate,outputRate=evidence.outputRate).let { it.copy(estimatedUsd=AiEstimateCodec.expected(it)) }
        var after=before.copy(runs=before.runs.map { if(it.requestId==requestId) run else it },
            messages=before.messages.map { if(it.messageId==run.assistantMessageId) it.copy(text=text,completion=completion) else it })
        if(terminal==RunStatus.COMPLETED) {
            val s=session(before,run.sessionId)
            after=withSession(after,s.copy(contextRevision=Math.addExact(s.contextRevision,1),updatedAt=maxOf(now(),s.updatedAt)))
            after=after.copy(cache=after.cache.filterNot { it.cacheKey==run.cacheKey }+AiCacheRow(run.cacheKey,run.requestId,now()))
        }
        after=consume(before,after,requestId,terminal!=null)
        persist(before,after)
    }
    suspend fun recoverInterrupted(): Unit = transaction {
        audit()
        var data=load()
        AiConversationCodec.validate(data,capacity)
        for(old in data.runs.filter { it.status in setOf("PREPARING","GENERATING") }) {
            val next=old.copy(status="FAILED",finishedAt=maxOf(now(),old.createdAt),terminalReason="PROCESS_INTERRUPTED",usageCutoffSeq=old.eventSeq)
            val proposed=data.copy(runs=data.runs.map { if(it.requestId==old.requestId) next else it })
            val after=AiConversationCodec.consume(data,proposed,old.requestId,true)
            persist(data,after); data=after
        }
    }
    suspend fun importIntoEmpty(data: AiDataset): Unit = transaction {
        val before=load(); require(before==AiDataset())
        AiMeter.verify(data,allowUnspecified=true)
        persist(before,AiMeter.plain(data))
    }
}

/** Pure scheduler: caller owns one instance per session and performs due() outputs serially.
 * No per-keystroke receipt is allocated. Time is injected, so tests need no sleeping. */
internal class AiDraftCoalescer(private val clock: () -> Long) {
    private data class Pending(val first: Long, val last: Long, val token: DraftToken, val text: String)
    private var pending: Pending?=null
    fun offer(token: DraftToken,text: String) {
        val p=pending
        require(p==null || p.token.editorEpoch==token.editorEpoch && token.localEditVersion>p.token.localEditVersion)
        val time=clock(); pending=Pending(p?.first ?: time,time,token,text)
    }
    fun due(force: Boolean=false): Pair<DraftToken,String>? {
        val p=pending ?: return null; val time=clock()
        if(!force && time-p.last<300 && time-p.first<2000) return null
        pending=null; return p.token to p.text
    }
}
