package cn.embeddedquiz

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Module-owned loading/errors and writing queue; does not touch quiz busy, pages or sessions. */
class QuickNotesStateHolder(
    private val scope: CoroutineScope,
    private val repository: QuickNotesRepository,
    private val context: Context,
) {
    var state by mutableStateOf(QuickNotesState())
        private set
    private var pendingImport: QuickNotePackage? = null
    private data class PositionWrite(val id: String, val position: QuickNotePosition)
    private val positions = OrderedWriter<PositionWrite>(scope, { it.id }) { write ->
        repository.savePosition(write.id, write.position)
        withContext(Dispatchers.Main) {
            state = state.copy(
                documents = state.documents.map { if (it.id == write.id) it.copy(position = write.position) else it },
                reader = state.reader?.let { if (it.summary.id == write.id) it.copy(summary = it.summary.copy(position = write.position)) else it },
            )
        }
    }
    val pendingPositionWrites get() = positions.pending
    val positionSaveError get() = positions.error

    init { reload() }

    fun enter() { state = state.copy(visible = true) }
    fun leave(onLeft: () -> Unit = {}) = work {
        positions.flush()
        pendingImport = null
        state = state.copy(visible = false, conflict = emptyList())
        onLeft()
    }
    fun closeDocument() = work { positions.flush(); state = state.copy(reader = null) }

    fun reload() {
        if (state.working) return
        state = state.copy(loading = true, working = true, error = "")
        scope.launch {
            try { refresh() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
            finally { state = state.copy(loading = false, working = false) }
        }
    }
    private suspend fun refresh() {
        val documents = repository.listDocuments()
        state = state.copy(documents = documents, reader = state.reader?.takeIf { r -> documents.any { it.id == r.summary.id } })
    }
    private fun fail(error: Exception) {
        state = state.copy(error = if (error is IllegalArgumentException || error is IllegalStateException) error.message ?: "速记操作失败，请重试" else "无法读取或保存速记，请检查文件后重试")
    }
    private fun work(action: suspend () -> Unit) {
        if (state.loading || state.working) { state = state.copy(error = "速记正在处理，请稍后重试"); return }
        state = state.copy(working = true, error = "")
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
            finally { state = state.copy(working = false) }
        }
    }
    private suspend fun readUri(uri: Uri, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开所选文件")
        input.use { QuickNotesRepository.readBounded(it, limit) }
    }
    private suspend fun importPack(pack: QuickNotePackage, keepBoth: Boolean) {
        when (val result = repository.importDocument(pack, keepBoth)) {
            is QuickNoteImportResult.Imported -> {
                pendingImport = null
                state = state.copy(conflict = emptyList())
                refresh()
            }
            is QuickNoteImportResult.Conflict -> {
                pendingImport = pack
                state = state.copy(conflict = result.existing)
            }
        }
    }
    fun importUri(uri: Uri) = work {
        val name = withContext(Dispatchers.IO) {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } ?: error("无法确定文件名，请选择 .md 或 .zip 文件")
        val pack = withContext(Dispatchers.IO) { QuickNotesRepository.parsePackage(name, readUri(uri, QuickNotesRepository.MAX_PACKAGE)) }
        importPack(pack, false)
    }
    /** Also permits direct package input in business tests or future non-UI import adapters. */
    fun importPackage(pack: QuickNotePackage) = work { importPack(pack, false) }
    fun confirmKeepBoth() = work { importPack(requireNotNull(pendingImport) { "没有待处理的重复文档" }, true) }
    fun cancelImport() { if (!state.working) { pendingImport = null; state = state.copy(conflict = emptyList()) } }
    fun openDocument(id: String) = work { positions.flush(); state = state.copy(reader = repository.openDocument(id)) }
    fun deleteDocument(id: String) = work {
        positions.flush()
        repository.deleteDocument(id)
        refresh()
    }
    fun savePosition(id: String, position: QuickNotePosition) {
        if (!state.working && state.documents.any { it.id == id }) positions.enqueue(PositionWrite(id, position))
    }
    suspend fun flushPositions() = positions.flush()
    fun retryPositionWrites() = positions.retry()
    suspend fun readAsset(id: String, encodedPath: String) = repository.readAsset(id, encodedPath)
    fun copyCode(blockId: String): Boolean {
        val code = state.reader?.rendered?.codeBlocks?.get(blockId) ?: return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("代码", code))
        return true
    }
    fun exportBackupUri(uri: Uri) = work {
        positions.flush()
        val bytes = repository.exportBackup()
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入速记备份")
            output.use { it.write(bytes) }
        }
    }
    fun restoreBackupUri(uri: Uri, replace: Boolean = false) = work {
        positions.flush()
        repository.restoreBackup(readUri(uri, QuickNotesRepository.MAX_BACKUP), replace)
        pendingImport = null
        state = state.copy(reader = null, conflict = emptyList())
        refresh()
    }
    fun shutdown() = positions.close()
}
