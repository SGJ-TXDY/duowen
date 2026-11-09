package cn.embeddedquiz

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Disposable disk-indexed preflight. No production database is opened or replaced here. */
internal class AiBackupStage private constructor(
    private val helper:SupportSQLiteOpenHelper,private val file:File,val version:Int,
) : AutoCloseable {
    private val db:SupportSQLiteDatabase get()=helper.writableDatabase
    private var sealed=false
    private var closed=false
    fun requireSealed() { check(sealed && !closed) { "UNVALIDATED_BACKUP" } }
    companion object {
        val aiSections=linkedMapOf("aiSessions" to "ai_sessions","aiMessages" to "ai_messages","aiRuns" to "ai_runs","aiCommands" to "ai_commands","aiResponseCache" to "ai_response_cache")
        suspend fun prepare(context:Context,dir:File,input:InputStream,resource:(Long)->Unit={},now:()->Long=System::currentTimeMillis):AiBackupStage {
            var prepared:AiBackupStage?=null
            try {
                input.use { prepared=prepareCore(context,dir,it,resource,now) }
                return requireNotNull(prepared)
            } catch(e:Throwable) {
                try { prepared?.close() } catch(close:Throwable) { e.addSuppressed(close) }
                throw e
            }
        }
        private suspend fun prepareCore(context:Context,dir:File,input:InputStream,resource:(Long)->Unit,now:()->Long):AiBackupStage {
            require(dir.isDirectory || dir.mkdirs()) { "NO_WORKSPACE" }
            val spool=File.createTempFile("ai-input-",".json",dir)
            var helper:SupportSQLiteOpenHelper?=null;var stageFile:File?=null
            try {
                input.let { source -> spool.outputStream().use { out ->
                    val buffer=ByteArray(8192);var total=0L
                    while(true) { currentCoroutineContext().ensureActive();val n=source.read(buffer);if(n<0) break
                        total=Math.addExact(total,n.toLong());resource(total);out.write(buffer,0,n) }
                } }
                currentCoroutineContext().ensureActive()
                stageFile=File.createTempFile("as-",".db",dir)
                helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(stageFile.absolutePath).callback(object:SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db:SupportSQLiteDatabase) {
                            AiConversationMigration.statements.forEach(db::execSQL)
                            db.execSQL("CREATE TABLE legacy (section TEXT NOT NULL, rowKey TEXT NOT NULL, ordinal INTEGER NOT NULL, wire TEXT NOT NULL, validated TEXT, PRIMARY KEY(section,rowKey), UNIQUE(section,ordinal))")
                            db.execSQL("CREATE TABLE note_sources (requestId TEXT PRIMARY KEY NOT NULL, commandId TEXT UNIQUE NOT NULL)")
                        }
                        override fun onConfigure(db:SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(false) }
                        override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) { error("TEMP_VERSION") }
                    }).build())
                val database=helper.writableDatabase
                database.beginTransaction()
                val version=try {
                    val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    val headers=mutableMapOf<String,Long>();val seen=mutableSetOf<String>()
                    var aiBytes=AiConversationCodec.encodedBytes(AiDataset());var reservations=0L
                    val counts=mutableMapOf<String,Long>()
                    JsonReader(InputStreamReader(spool.inputStream(),decoder)).use { reader ->
                        reader.isLenient=false;reader.beginObject()
                        while(reader.hasNext()) {
                            currentCoroutineContext().ensureActive();val key=reader.nextName();require(seen.add(key)) { "DUPLICATE_SECTION" }
                            when {
                                key in setOf("schemaVersion","backupVersion","aiConversationVersion") -> headers[key]=readLong(reader)
                                key in AiLegacyBackupCodec.tables || key in aiSections || key == AI_DELETED_COST_SECTION -> {
                                    if(key=="learningSettings") {
                                        val row=readValue(reader,0) as? JSONObject ?: error("INVALID_SETTINGS")
                                        database.execSQL("INSERT INTO legacy(section,rowKey,ordinal,wire) VALUES (?,?,?,?)",arrayOf(key,"1",0,row.toString()))
                                    } else {
                                        reader.beginArray();var ordinal=0L
                                        while(reader.hasNext()) { currentCoroutineContext().ensureActive()
                                            val row=readValue(reader,0) as? JSONObject ?: error("INVALID_ROW")
                                            val table=aiSections[key]
                                            if(table!=null) {
                                                if(table=="ai_messages") require(ordinal<AiConversationCodec.MESSAGE_LIMIT) { "MESSAGE_LIMIT" }
                                                row.keys().forEach { field -> (row.get(field) as? String)?.let { require(AiJson.utf8(it)<=AiConversationCodec.TEXT_BYTES) { "TEXT_LIMIT" } } }
                                                val normalized=AiSqlAccess.normalized(table,row)
                                                aiBytes=Math.addExact(aiBytes,Math.addExact(normalized.getLong("encodedBytes"),if((counts[table] ?: 0)>0) 1L else 0L))
                                                counts[table]=Math.addExact(counts[table] ?: 0,1L)
                                                if(table=="ai_runs") { val reserved=AiJson.long(row,"reservedBytes");require(reserved in 0..AiConversationCodec.RUN_RESERVE);reservations=Math.addExact(reservations,reserved) }
                                                require(Math.addExact(aiBytes,reservations)<=AiConversationCodec.CAPACITY) { "CAPACITY_EXCEEDED" }
                                                AiSqlAccess.insert(database,table,normalized)
                                            } else {
                                                val rowKey = if (key == AI_DELETED_COST_SECTION) {
                                                    require(ordinal < 100_000) { "COST_LEDGER_LIMIT" }
                                                    AiDeletedCostLedger.key(row)
                                                } else AiLegacyBackupCodec.key(key,row,ordinal)
                                                database.execSQL("INSERT INTO legacy(section,rowKey,ordinal,wire) VALUES (?,?,?,?)",
                                                    arrayOf(key,rowKey,ordinal,row.toString()))
                                            }
                                            ordinal=Math.addExact(ordinal,1L);resource(spool.length())
                                        };reader.endArray()
                                    }
                                }
                                else -> error("UNKNOWN_BACKUP_FIELD")
                            }
                        };reader.endObject();require(reader.peek()==JsonToken.END_DOCUMENT)
                    }
                    require(headers["schemaVersion"]==1L && headers["backupVersion"] in 1L..AiFullBackupCodec.LATEST_VERSION.toLong()) { "UNSUPPORTED_BACKUP_VERSION" }
                    val v=headers.getValue("backupVersion").toInt()
                    require(seen.containsAll(setOf("questions","progress","attempts","ai"))) { "MISSING_LEGACY_SECTION" }
                    if(v>=2) require("stickyNotes" in seen)
                    if(v>=3) require(seen.containsAll(setOf("learningSettings","personalAnswers","questionPersonal")))
                    if(v>=4) require(headers["aiConversationVersion"]==1L && seen.containsAll(aiSections.keys)) { "MISSING_AI_SECTION" }
                    else require(headers["aiConversationVersion"]==null && seen.none { it in aiSections }) { "AI_IN_LEGACY_FORMAT" }
                    require((AI_DELETED_COST_SECTION in seen) == (v >= 6)) { "INVALID_COST_LEDGER_SECTION" }
                    val stage=AiBackupStage(helper,stageFile,v)
                    stage.validateAll(context,now)
                    database.setTransactionSuccessful();v
                } finally { database.endTransaction() }
                database.execSQL("PRAGMA query_only=ON")
                return AiBackupStage(helper,stageFile,version).also { it.sealed=true }
            } catch(e:Throwable) {
                try { helper?.close() } catch(close:Throwable) { e.addSuppressed(close) };stageFile?.let(::deleteFiles);throw e
            } finally { spool.delete() }
        }
        private fun readLong(r:JsonReader):Long { require(r.peek()==JsonToken.NUMBER);return r.nextString().toLongOrNull() ?: error("INTEGER_OVERFLOW") }
        private fun readValue(r:JsonReader,depth:Int):Any {
            require(depth<=64) { "JSON_DEPTH" }
            return when(r.peek()) {
                JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                    r.beginObject();while(r.hasNext()) { val key=r.nextName();require(!has(key)) { "DUPLICATE_FIELD" };put(key,readValue(r,depth+1)) };r.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JSONArray().apply { r.beginArray();while(r.hasNext()) put(readValue(r,depth+1));r.endArray() }
                JsonToken.STRING -> r.nextString()
                JsonToken.BOOLEAN -> r.nextBoolean()
                JsonToken.NULL -> { r.nextNull();JSONObject.NULL }
                JsonToken.NUMBER -> { val text=r.nextString();if(text.matches(Regex("-?(0|[1-9][0-9]*)"))) text.toLongOrNull() ?: error("INTEGER_OVERFLOW")
                    else text.toDouble().also { require(it.isFinite()) } }
                else -> error("INVALID_JSON")
            }
        }
        private fun deleteFiles(f:File) { listOf(f,File(f.path+"-journal"),File(f.path+"-wal"),File(f.path+"-shm")).forEach { it.delete() } }
    }
    fun legacy(section:String,key:String,validated:Boolean=true):JSONObject? = db.query("SELECT ${if(validated) "validated" else "wire"} FROM legacy WHERE section=? AND rowKey=?",arrayOf(section,key)).use {
        if(it.moveToFirst() && !it.isNull(0)) JSONObject(it.getString(0)) else null
    }
    suspend fun forLegacy(section:String,action:suspend(JSONObject)->Unit) {
        db.query("SELECT validated FROM legacy WHERE section=? ORDER BY ordinal",arrayOf(section)).use { c ->
            while(c.moveToNext()) { currentCoroutineContext().ensureActive();action(JSONObject(c.getString(0))) }
        }
    }
    suspend fun forAi(table:String,order:String=AiSqlAccess.keys.getValue(table),action:suspend(JSONObject)->Unit) {
        require(table in aiSections.values)
        require(order==AiSqlAccess.keys.getValue(table) || table=="ai_runs" && order=="assistantSeq")
        val sql=if(order=="assistantSeq") "SELECT r.* FROM ai_runs r JOIN ai_messages m ON r.assistantMessageId=m.messageId ORDER BY m.seq,r.requestId" else "SELECT * FROM `$table` ORDER BY `$order`"
        db.query(sql).use { c -> while(c.moveToNext()) { currentCoroutineContext().ensureActive();action(AiSqlAccess.wire(table,AiSqlAccess.record(c))) } }
    }
    private suspend fun validateAll(context:Context,now:()->Long) {
        CategoryMap.load(context)
        val sections = AiLegacyBackupCodec.tables.keys + if (version >= 6) listOf(AI_DELETED_COST_SECTION) else emptyList()
        for(section in sections) {
            if(version<2 && section=="stickyNotes" || version<3 && section in setOf("learningSettings","personalAnswers","questionPersonal")) continue
            db.query("SELECT rowKey,wire FROM legacy WHERE section=? ORDER BY ordinal",arrayOf(section)).use { c ->
                while(c.moveToNext()) { currentCoroutineContext().ensureActive()
                    val wire = JSONObject(c.getString(1))
                    val row = if (section == AI_DELETED_COST_SECTION) {
                        require(version >= 7 || wire.getString("model") in AiSnapshotCodec.MODELS_V1) {
                            "CUSTOM_AI_REQUIRES_BACKUP_VERSION_7"
                        }
                        AiDeletedCostLedger.validate(wire)
                    }
                        else AiLegacyBackupCodec.validate(section,wire,version) { legacy("questions",it) }
                    if (section == "stickyNotes" && !row.isNull("sourceRequestId")) {
                        db.execSQL("INSERT INTO note_sources VALUES (?,?)",
                            arrayOf(row.getString("sourceRequestId"), row.getString("sourceCommandId")))
                    }
                    db.execSQL("UPDATE legacy SET validated=? WHERE section=? AND rowKey=?",arrayOf(row.toString(),section,c.getString(0)))
                }
            }
        }
        if (version < 5) require(AiSqlAccess(db).scalar(
            "SELECT COUNT(*) FROM ai_sessions WHERE questionSnapshotVersion<>1") == 0L) { "DOCUMENT_AI_IN_LEGACY_FORMAT" }
        if (version < 6) require(AiSqlAccess(db).scalar(
            "SELECT COUNT(*) FROM ai_runs WHERE contextSnapshotVersion>2") == 0L) { "UPDATED_DOCUMENT_AI_IN_LEGACY_FORMAT" }
        if (version < 7) {
            val builtIns = AiSnapshotCodec.MODELS_V1.joinToString(",") { "'$it'" }
            val access = AiSqlAccess(db)
            require(access.scalar("SELECT COUNT(*) FROM ai_runs WHERE model NOT IN ($builtIns) OR contextSnapshotVersion>4") == 0L) {
                "NEW_AI_REQUIRES_BACKUP_VERSION_7"
            }
        }
        AiStorageValidation.verifyAndRecover(db,now)
    }
    override fun close() { if(!closed) { closed=true;try { helper.close() } finally { deleteFiles(file) } } }
}
