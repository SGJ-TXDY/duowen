package cn.embeddedquiz

/** Values describe external canonical row JSON, excluding this private SQL bookkeeping column. */
internal object AiMeter {
    val tables=listOf("ai_sessions","ai_messages","ai_runs","ai_commands","ai_response_cache")
    fun size(r:AiSessionRow)=AiJson.encodedBytes(AiRowCodec.encode(r))
    fun size(r:AiMessageRow)=AiJson.encodedBytes(AiRowCodec.encode(r))
    fun size(r:AiRunRow)=AiJson.encodedBytes(AiRowCodec.encode(r))
    fun size(r:AiCommandRow)=AiJson.encodedBytes(AiRowCodec.encode(r))
    fun size(r:AiCacheRow)=AiJson.encodedBytes(AiRowCodec.encode(r))
    fun stored(r:AiSessionRow)=r.copy(encodedBytes=size(r))
    fun stored(r:AiMessageRow)=r.copy(encodedBytes=size(r))
    fun stored(r:AiRunRow)=r.copy(encodedBytes=size(r))
    fun stored(r:AiCommandRow)=r.copy(encodedBytes=size(r))
    fun stored(r:AiCacheRow)=r.copy(encodedBytes=size(r))
    fun plain(d:AiDataset,external:Boolean=true)=AiDataset(d.sessions.map { it.copy(encodedBytes=0,activeEditorEpoch=if(external) null else it.activeEditorEpoch) },d.messages.map { it.copy(encodedBytes=0) },
        d.runs.map { it.copy(encodedBytes=0) },d.commands.map { it.copy(encodedBytes=0) },d.cache.map { it.copy(encodedBytes=0) })
    fun verify(d:AiDataset, allowUnspecified:Boolean=false) {
        fun check(actual:Long,expected:Long) { require(actual==expected || allowUnspecified && actual==0L) { "INVALID_BYTE_METADATA" } }
        d.sessions.forEach { check(it.encodedBytes,size(it));it.activeEditorEpoch?.let(AiJson::uuid) };d.messages.forEach { check(it.encodedBytes,size(it)) }
        d.runs.forEach { check(it.encodedBytes,size(it)) };d.commands.forEach { check(it.encodedBytes,size(it)) };d.cache.forEach { check(it.encodedBytes,size(it)) }
    }
    fun counts(d:AiDataset)=longArrayOf(d.sessions.size.toLong(),d.messages.size.toLong(),d.runs.size.toLong(),d.commands.size.toLong(),d.cache.size.toLong())
    private fun separators(n:Long)=(n-1).coerceAtLeast(0)
    fun correction(global:List<AiTableMeasure>,before:AiDataset,after:AiDataset):Long {
        val b=counts(before);val a=counts(after)
        return tables.indices.fold(0L) { sum,i ->
            val count=global.single { it.tableName==tables[i] }.rowCount
            val delta=Math.subtractExact(a[i],b[i])
            Math.addExact(sum,separators(Math.addExact(count,delta))-separators(count)-separators(a[i])+separators(b[i]))
        }
    }
    fun total(stats:List<AiTableMeasure>):Long = stats.fold(AiConversationCodec.encodedBytes(AiDataset())) { n,s ->
        Math.addExact(n,Math.addExact(s.encodedBytes,separators(s.rowCount)))
    }
}

data class AiTableMeasure(val tableName:String,val rowCount:Long,val encodedBytes:Long)

interface AiRowReader {
    suspend fun session(id:String):AiSessionRow?
    suspend fun message(id:String):AiMessageRow?
    suspend fun run(id:String):AiRunRow?
    suspend fun command(id:String):AiCommandRow?
    suspend fun cache(id:String):AiCacheRow?
    suspend fun runForAssistant(id:String):AiRunRow?
}

/** A root plus immediate immutable references, never a recursive load of unrelated payloads.
 * Store trusts unchanged committed rows; staging validates every root separately before use. */
internal class AiWorkingSet(private val reader:AiRowReader) {
    suspend fun read(sessionId:String,roots:List<AiRunRow>,extraMessage:AiMessageRow?=null,extraCache:AiCacheRow?=null):AiDataset {
        val s=requireNotNull(reader.session(sessionId));val runs=linkedMapOf<String,AiRunRow>()
        val messages=linkedMapOf<String,AiMessageRow>();val commands=linkedMapOf<String,AiCommandRow>()
        suspend fun add(r:AiRunRow) {
            require(r.sessionId==sessionId) { "CROSS_SESSION_REFERENCE" }; runs[r.requestId]=r
            for(id in listOf(r.userMessageId,r.assistantMessageId)) messages[id]=requireNotNull(reader.message(id))
            commands[r.commandId]=requireNotNull(reader.command(r.commandId))
        }
        for(r in roots) add(r)
        for(r in roots) {
            for(id in listOfNotNull(r.retryOf,r.sourceRequestId)) add(requireNotNull(reader.run(id)))
            val data=org.json.JSONObject(r.contextSnapshot).getJSONObject("data")
            for(h in AiJson.objects(data,"history")) {
                val a=requireNotNull(reader.runForAssistant(h.getString("assistantMessageId")));add(a)
                messages[h.getString("userMessageId")]=requireNotNull(reader.message(h.getString("userMessageId")))
            }
        }
        if(extraMessage!=null) {
            messages[extraMessage.messageId]=extraMessage
            if(extraMessage.role=="ASSISTANT") add(requireNotNull(reader.runForAssistant(extraMessage.messageId)))
        }
        if(extraCache!=null) add(requireNotNull(reader.run(extraCache.sourceRequestId)))
        return AiMeter.plain(AiDataset(listOf(s),messages.values.toList(),runs.values.toList(),commands.values.toList(),listOfNotNull(extraCache)),external=false)
    }
}
