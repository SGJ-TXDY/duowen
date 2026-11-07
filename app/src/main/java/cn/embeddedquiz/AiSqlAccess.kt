package cn.embeddedquiz

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** SQL adapter for bounded row-at-a-time validation, including disposable backup staging. */
internal class AiSqlAccess(val db:SupportSQLiteDatabase) : AiRowReader {
    companion object {
        val keys=mapOf("ai_sessions" to "sessionId","ai_messages" to "messageId","ai_runs" to "requestId","ai_commands" to "commandId","ai_response_cache" to "cacheKey")
        fun record(c:Cursor):JSONObject = JSONObject().apply {
            for(i in 0 until c.columnCount) put(c.getColumnName(i),when(c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                else -> error("UNEXPECTED_BLOB")
            })
        }
        fun wire(table:String,record:JSONObject):JSONObject {
            val row=JSONObject(record.toString())
            if(table=="ai_runs") {
                val value=AiJson.long(row,"networkDispatched");require(value in 0..1)
                row.put("networkDispatched",value==1L)
            }
            return when(table) {
                "ai_sessions" -> AiRowCodec.encode(AiRowCodec.decodeSession(row))
                "ai_messages" -> AiRowCodec.encode(AiRowCodec.decodeMessage(row))
                "ai_runs" -> AiRowCodec.encode(AiRowCodec.decodeRun(row))
                "ai_commands" -> AiRowCodec.encode(AiRowCodec.decodeCommand(row))
                "ai_response_cache" -> AiRowCodec.encode(AiRowCodec.decodeCache(row))
                else -> error("UNKNOWN_AI_TABLE")
            }
        }
        fun normalized(table:String,wire:JSONObject):JSONObject {
            val result=when(table) {
                "ai_sessions" -> AiRowCodec.encode(AiRowCodec.decodeSession(wire))
                "ai_messages" -> AiRowCodec.encode(AiRowCodec.decodeMessage(wire))
                "ai_runs" -> AiRowCodec.encode(AiRowCodec.decodeRun(wire))
                "ai_commands" -> AiRowCodec.encode(AiRowCodec.decodeCommand(wire))
                "ai_response_cache" -> AiRowCodec.encode(AiRowCodec.decodeCache(wire))
                else -> error("UNKNOWN_AI_TABLE")
            }
            require(AiJson.canonical(result)==AiJson.canonical(wire)) { "UNKNOWN_AI_ROW_FIELD" }
            result.put("encodedBytes",AiJson.encodedBytes(result))
            return result
        }
        fun insert(db:SupportSQLiteDatabase,table:String,row:JSONObject) {
            require(table in keys || table in AiLegacyBackupCodec.tables.values)
            val columns=row.keys().asSequence().toList()
            require(columns.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*")) })
            val values=columns.map { k -> val v=row.get(k);when(v) { JSONObject.NULL -> null;is Boolean -> if(v) 1L else 0L;else -> v } }.toTypedArray()
            db.execSQL("INSERT INTO `$table` ("+columns.joinToString(",") { "`$it`" }+") VALUES ("+columns.joinToString(",") { "?" }+")",values)
        }
    }
    private fun row(table:String,id:String,column:String=keys.getValue(table)):JSONObject? =
        db.query("SELECT * FROM `$table` WHERE `$column`=?",arrayOf(id)).use { if(it.moveToFirst()) wire(table,record(it)) else null }
    override suspend fun session(id:String)=row("ai_sessions",id)?.let(AiRowCodec::decodeSession)
    override suspend fun message(id:String)=row("ai_messages",id)?.let(AiRowCodec::decodeMessage)
    override suspend fun run(id:String)=row("ai_runs",id)?.let(AiRowCodec::decodeRun)
    override suspend fun command(id:String)=row("ai_commands",id)?.let(AiRowCodec::decodeCommand)
    override suspend fun cache(id:String)=row("ai_response_cache",id)?.let(AiRowCodec::decodeCache)
    override suspend fun runForAssistant(id:String)=row("ai_runs",id,"assistantMessageId")?.let(AiRowCodec::decodeRun)
    fun scalar(sql:String):Long=db.query(sql).use { check(it.moveToFirst());it.getLong(0) }
    fun measures():List<AiTableMeasure> = AiMeter.tables.map { table ->
        db.query("SELECT COUNT(*),COALESCE(SUM(encodedBytes),0) FROM `$table`").use { check(it.moveToFirst());AiTableMeasure(table,it.getLong(0),it.getLong(1)) }
    }
    suspend fun component(run:AiRunRow,extraCommand:AiCommandRow?=null,extraCache:AiCacheRow?=null):AiDataset {
        var d=AiWorkingSet(this).read(run.sessionId,listOf(run),extraCache=extraCache)
        if(extraCommand!=null) d=d.copy(commands=(d.commands+extraCommand).distinctBy { it.commandId })
        return d
    }
}
