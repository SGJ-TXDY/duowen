package cn.embeddedquiz

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

enum class StartupStage { DATABASE, VALIDATION, BANK, INDEX, EXAM }
sealed interface StartupState {
    data class Loading(val attempt: Int, val stage: StartupStage, val message: String) : StartupState
    data class Ready(val attempt: Int, val examRestored: Boolean) : StartupState
    data class Error(val attempt: Int, val stage: StartupStage, val message: String,
        val code: String, val canRestoreBackup: Boolean) : StartupState
}
sealed interface StartupRecoveryResult {
    data object Completed : StartupRecoveryResult
    data class Rejected(val message: String) : StartupRecoveryResult
    data class Failed(val message: String) : StartupRecoveryResult
}

/** File-only diagnostics: no SQLite open, recovery, checkpoint, credential read, or source mutation. */
internal class StartupRecovery(private val context: Context, private val prefs: SharedPreferences) {
    private val database get() = context.getDatabasePath("embeddedquiz.db")
    private fun files() = listOf(database, File(database.path + "-wal"), File(database.path + "-journal"))
        .filter { it.isFile }
    fun describe(state: StartupState, exceptionType: String): String = JSONObject()
        .put("diagnosticVersion", 1).put("roomVersion", 8).put("backupVersion", AiFullBackupCodec.LATEST_VERSION).put("aiConversationVersion", 1)
        .put("state", state.javaClass.simpleName)
        .put("attempt", when (state) {
            is StartupState.Loading -> state.attempt
            is StartupState.Ready -> state.attempt
            is StartupState.Error -> state.attempt
        })
        .put("stage", when (state) {
            is StartupState.Loading -> state.stage.name
            is StartupState.Error -> state.stage.name
            else -> "READY"
        })
        .put("code", (state as? StartupState.Error)?.code ?: "")
        .put("exceptionType", exceptionType)
        .put("databaseExists", database.isFile)
        .put("databaseBytes", if (database.isFile) database.length() else 0)
        .put("canRestoreBackup", (state as? StartupState.Error)?.canRestoreBackup ?: false)
        .toString(2)

    private fun validateDestination(uri: Uri) {
        if (uri.scheme == "file") {
            val target = File(requireNotNull(uri.path)).canonicalFile.toPath()
            require(!target.startsWith(File(context.applicationInfo.dataDir).canonicalFile.toPath())) {
                "诊断目的地不能覆盖应用自身数据"
            }
        }
    }
    private suspend fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    suspend fun export(uri: Uri, description: String) {
        validateDestination(uri)
        val sources = files()
        val hashes = linkedMapOf<File, String>()
        for (file in sources) hashes[file] = digest(file)
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入诊断文件")
        ZipOutputStream(output).use { zip ->
            val entries = JSONArray()
            for (file in sources) {
                currentCoroutineContext().ensureActive()
                val name = "database/" + when (file.name) {
                    "embeddedquiz.db" -> "main.sqlite"
                    "embeddedquiz.db-wal" -> "main.sqlite-wal"
                    else -> "main.sqlite-journal"
                }
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
                entries.put(JSONObject().put("entry", name).put("sha256", hashes[file]))
            }
            // This cached exam value is a diagnostic aid, never proof of a successful prefs commit.
            val exam = prefs.getString("exam", null)
            if (exam != null) {
                zip.putNextEntry(ZipEntry("exam-cache.json"))
                zip.write(exam.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            check(sources == files() && sources.all { it.isFile && digest(it) == hashes[it] }) {
                "诊断期间源文件发生变化"
            }
            val manifest = JSONObject(description).put("files", entries)
                .put("diagnosticOnly", true).put("examSource", "PREFERENCES_CACHE_NOT_DURABILITY_PROOF")
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
    }
}