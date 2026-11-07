package cn.embeddedquiz

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** A bounded row/root at a time; used for untrusted cold files as well as backup staging. */
internal object AiStorageValidation {
    suspend fun verifyAndRecover(db:SupportSQLiteDatabase, now:()->Long=System::currentTimeMillis,resource:()->Unit={}) {
        val access=AiSqlAccess(db)
        db.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "BROKEN_AI_REFERENCE" } }
        require(access.scalar("SELECT COUNT(*) FROM ai_runs WHERE status IN ('PREPARING','GENERATING')")<=1) { "BUSY" }
        require(access.scalar("SELECT COUNT(*) FROM (SELECT userMessageId FROM ai_runs WHERE status='COMPLETED' GROUP BY userMessageId HAVING COUNT(*)>1)")==0L)
        resource()
        forRow(db,"ai_sessions") { row -> resource(); AiConversationCodec.validate(AiDataset(sessions=listOf(AiRowCodec.decodeSession(row))),Long.MAX_VALUE) }
        resource()
        forRow(db,"ai_messages") { row -> resource();
            val m=AiRowCodec.decodeMessage(row)
            if(m.role=="USER") AiConversationCodec.validate(AiDataset(sessions=listOf(requireNotNull(access.session(m.sessionId))),messages=listOf(m)),Long.MAX_VALUE)
            else require(m.role=="ASSISTANT" && access.runForAssistant(m.messageId)!=null) { "ORPHAN_ASSISTANT" }
        }
        resource()
        forRow(db,"ai_runs") { row -> resource(); val root=AiRowCodec.decodeRun(row);val d=access.component(root)
            AiConversationCodec.validate(d,Long.MAX_VALUE,d.runs.filter { it.requestId!=root.requestId }.toSet()) }
        resource()
        forRow(db,"ai_commands") { row -> resource(); val c=AiRowCodec.decodeCommand(row)
            val d=if(c.requestId==null) AiDataset(sessions=listOf(requireNotNull(access.session(requireNotNull(c.sessionId)))),commands=listOf(c))
                else access.component(requireNotNull(access.run(c.requestId)),extraCommand=c)
            AiConversationCodec.validate(d,Long.MAX_VALUE,d.runs.toSet())
        }
        resource()
        forRow(db,"ai_response_cache") { row -> resource(); val c=AiRowCodec.decodeCache(row);val d=access.component(requireNotNull(access.run(c.sourceRequestId)),extraCache=c)
            AiConversationCodec.validate(d,Long.MAX_VALUE,d.runs.toSet()) }
        fun checkCapacity() {
            require(access.scalar("SELECT COUNT(*) FROM ai_messages")<=AiConversationCodec.MESSAGE_LIMIT)
            val bytes=AiMeter.total(access.measures());val reserved=access.scalar("SELECT COALESCE(SUM(reservedBytes),0) FROM ai_runs")
            require(Math.addExact(bytes,reserved)<=AiConversationCodec.CAPACITY) { "CAPACITY_EXCEEDED" }
        }
        checkCapacity()
        // Preflight has accepted the original floor before any recovery rewrite.
        resource()
        forRow(db,"ai_runs") { row -> resource(); val old=AiRowCodec.decodeRun(row)
            if(old.status in setOf("PREPARING","GENERATING")) {
                val before=access.component(old);val next=old.copy(status="FAILED",finishedAt=maxOf(now(),old.createdAt),terminalReason="PROCESS_INTERRUPTED",usageCutoffSeq=old.eventSeq)
                val after=before.copy(runs=before.runs.map { if(it.requestId==old.requestId) next else it })
                val normalized=AiConversationCodec.consume(before,after,old.requestId,true).runs.single { it.requestId==old.requestId }
                val stored=AiSqlAccess.normalized("ai_runs",AiRowCodec.encode(normalized))
                update(db,"ai_runs",stored,old.requestId)
            }
        }
        checkCapacity()
    }
    private suspend fun forRow(db:SupportSQLiteDatabase,table:String,action:suspend(JSONObject)->Unit) {
        db.query("SELECT * FROM `$table` ORDER BY `${AiSqlAccess.keys.getValue(table)}`").use { c ->
            while(c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val stored=AiSqlAccess.record(c)
                val wire=AiSqlAccess.wire(table,stored)
                require(AiJson.long(stored,"encodedBytes")==AiJson.encodedBytes(wire)) { "INVALID_BYTE_METADATA" }
                if(table=="ai_sessions" && !stored.isNull("activeEditorEpoch")) AiJson.uuid(stored.getString("activeEditorEpoch"))
                action(wire)
            }
        }
    }
    private fun update(db:SupportSQLiteDatabase,table:String,row:JSONObject,id:String) {
        val columns=row.keys().asSequence().toList();val values=columns.map { k -> val v=row.get(k);when(v) { JSONObject.NULL -> null;is Boolean -> if(v) 1L else 0L;else -> v } }.toTypedArray()
        db.execSQL("UPDATE `$table` SET "+columns.joinToString(",") { "`$it`=?" }+" WHERE `${AiSqlAccess.keys.getValue(table)}`=?",Array<Any?>(values.size+1) { if(it==values.size) id else values[it] })
    }
}
