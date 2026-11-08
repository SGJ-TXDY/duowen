package cn.embeddedquiz
import org.json.JSONObject
/** Convert a current backup fixture into the actual old wire envelope, without AI4 sections. */
internal fun JSONObject.asLegacyBackup(version:Int):JSONObject {
    put("backupVersion",version)
    remove("aiConversationVersion")
    remove(AI_DELETED_COST_SECTION)
    AiBackupStage.aiSections.keys.forEach(::remove)
    return this
}
