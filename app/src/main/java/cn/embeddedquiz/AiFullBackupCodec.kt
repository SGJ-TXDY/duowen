package cn.embeddedquiz

import android.content.Context
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class AiBackupReceipt(val bytes:Long,val sha256:String)

/** Single-file consistent backup, shared by production streams and compatibility callers. */
internal class AiFullBackupCodec(private val context:Context,private val workDir:File) {
    companion object {
        const val LATEST_VERSION = 7
    }

    suspend fun prepare(input:InputStream,resource:(Long)->Unit={}):AiBackupStage = AiBackupStage.prepare(context,workDir,input,resource)
    suspend fun export(database:RoomDatabase,openNewDestination:()->OutputStream,resource:(Long)->Unit={}):AiBackupReceipt {
        require(workDir.isDirectory || workDir.mkdirs())
        val temp=File.createTempFile("ai-export-",".json",workDir)
        try {
            database.withTransaction {
                val sql=database.openHelper.readableDatabase
                val builtIns = AiSnapshotCodec.MODELS_V1.joinToString(",") { "'$it'" }
                val requiresNewFormat = sql.query(
                    "SELECT 1 FROM ai_runs WHERE contextSnapshotVersion>4 OR model NOT IN ($builtIns) " +
                        "UNION ALL SELECT 1 FROM ai_deleted_costs WHERE model NOT IN ($builtIns) LIMIT 1"
                ).use { it.moveToFirst() }
                val version = if (requiresNewFormat) LATEST_VERSION else 6
                java.io.BufferedWriter(java.io.OutputStreamWriter(temp.outputStream(),Charsets.UTF_8),8192).use { out ->
                    out.write("{\"schemaVersion\":1,\"backupVersion\":$version,\"aiConversationVersion\":1")
                    val tables = AiLegacyBackupCodec.tables + (AI_DELETED_COST_SECTION to "ai_deleted_costs")
                    for((section,table) in tables) {
                        currentCoroutineContext().ensureActive()
                        out.write(","+JSONObject.quote(section)+":")
                        if(section=="learningSettings") {
                            val value=sql.query("SELECT * FROM `$table` WHERE id=1").use { if(it.moveToFirst()) AiLegacyBackupCodec.wire(section,AiSqlAccess.record(it)) else LearningCodec.settings(LearningSettings()) }
                            out.write(value.toString())
                        } else {
                            out.write("[");var first=true
                            val order = when (section) {
                                "questions", "stickyNotes", "attempts" -> "id"
                                "ai" -> "cacheKey"
                                AI_DELETED_COST_SECTION -> "billingDay,model,currency"
                                else -> "questionId"
                            }
                            sql.query("SELECT * FROM `$table` ORDER BY $order").use { c -> while(c.moveToNext()) {
                                currentCoroutineContext().ensureActive();if(!first) out.write(",");first=false
                                out.write(AiLegacyBackupCodec.wire(section,AiSqlAccess.record(c)).toString());resource(temp.length())
                            } };out.write("]")
                        }
                    }
                    for((section,table) in AiBackupStage.aiSections) {
                        out.write(","+JSONObject.quote(section)+":[");var first=true
                        sql.query("SELECT * FROM `$table` ORDER BY `${AiSqlAccess.keys.getValue(table)}`").use { c -> while(c.moveToNext()) {
                            currentCoroutineContext().ensureActive();if(!first) out.write(",");first=false
                            val stored=AiSqlAccess.record(c);val wire=AiSqlAccess.wire(table,stored)
                            require(AiJson.long(stored,"encodedBytes")==AiJson.encodedBytes(wire)) { "INVALID_BYTE_METADATA" }
                            out.write(AiJson.canonical(wire));resource(temp.length())
                        } };out.write("]")
                    }
                    out.write("}")
                }
            }
            // The same parser and indexed validation used by restore, before opening a destination.
            prepare(temp.inputStream(),resource).use { it.requireSealed() }
            currentCoroutineContext().ensureActive()
            val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
            openNewDestination().use { target -> temp.inputStream().use { source ->
                val buffer=ByteArray(8192)
                while(true) { currentCoroutineContext().ensureActive();val n=source.read(buffer);if(n<0) break
                    target.write(buffer,0,n);digest.update(buffer,0,n);bytes=Math.addExact(bytes,n.toLong())
                };target.flush()
            } }
            currentCoroutineContext().ensureActive()
            return AiBackupReceipt(bytes,digest.digest().joinToString("") { "%02x".format(it) })
        } finally { temp.delete() }
    }
}
