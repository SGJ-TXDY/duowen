package cn.embeddedquiz

import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Production transaction body; caller holds shared AI, draft and exam replacement leases. */
internal class AiBackupRestorer(private val host:QuizDatabase,private val resource:()->Unit={},private val beforeCommit:()->Unit={}) {
    suspend fun replace(stage:AiBackupStage) = host.withTransaction {
        stage.requireSealed();val target=host.openHelper.writableDatabase
        val version=stage.version
        // Old backups contain no new conversations: preserve them, including their usage ledger.
        if(version>=4) {
            target.execSQL("DELETE FROM ai_response_cache");target.execSQL("DELETE FROM ai_commands")
            while(true) {
                currentCoroutineContext().ensureActive();resource()
                val batch=target.query("SELECT r.requestId FROM ai_runs r JOIN ai_messages m ON m.messageId=r.assistantMessageId ORDER BY m.seq DESC,r.requestId DESC LIMIT 256").use { c ->
                    buildList { while(c.moveToNext()) add(c.getString(0)) }
                }
                if(batch.isEmpty()) break
                for(id in batch) target.execSQL("DELETE FROM ai_runs WHERE requestId=?",arrayOf(id))
            }
            target.execSQL("DELETE FROM ai_messages");target.execSQL("DELETE FROM ai_sessions")
        }
        if(version>=3) {
            val asked=target.query("SELECT notificationPermissionAsked FROM learning_settings WHERE id=1").use { it.moveToFirst() && it.getInt(0)!=0 }
            target.execSQL("DELETE FROM personal_answers");target.execSQL("DELETE FROM question_personal")
            stage.forLegacy("learningSettings") { row -> if(asked) row.put("notificationPermissionAsked",true);target.execSQL("DELETE FROM learning_settings WHERE id=1");AiSqlAccess.insert(target,"learning_settings",row) }
            stage.forLegacy("personalAnswers") { AiSqlAccess.insert(target,"personal_answers",it) }
            stage.forLegacy("questionPersonal") { AiSqlAccess.insert(target,"question_personal",it) }
        }
        target.execSQL("DELETE FROM attempts");target.execSQL("DELETE FROM progress");target.execSQL("DELETE FROM ai");target.execSQL("DELETE FROM questions")
        if(version>=2) target.execSQL("DELETE FROM sticky_notes")
        for(section in listOf("questions","progress","attempts","ai")+if(version>=2) listOf("stickyNotes") else emptyList()) {
            stage.forLegacy(section) { currentCoroutineContext().ensureActive();resource();AiSqlAccess.insert(target,AiLegacyBackupCodec.tables.getValue(section),it) }
        }
        if(version>=4) {
            for(table in listOf("ai_sessions","ai_messages","ai_runs","ai_commands","ai_response_cache")) {
                stage.forAi(table,if(table=="ai_runs") "assistantSeq" else AiSqlAccess.keys.getValue(table)) { row ->
                    currentCoroutineContext().ensureActive();resource();AiSqlAccess.insert(target,table,AiSqlAccess.normalized(table,row))
                }
            }
        }
        AiStorageValidation.verifyAndRecover(target,resource=resource)
        target.execSQL("UPDATE ai_sessions SET activeEditorEpoch=NULL")
        beforeCommit()
    }
}
