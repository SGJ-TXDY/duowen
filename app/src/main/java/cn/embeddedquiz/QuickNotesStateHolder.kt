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

    /** Set while the reader was opened from elsewhere (AI 讨论's source card): closing it goes back there. */
    private var returnAfterReader: (() -> Unit)? = null

    /**
     * Opens one document straight in the reader from another page (PLAN-1.7.4). Closing the reader
     * then hides 速记 again and runs [onClosed], so back returns to where the user came from.
     */
    fun openFrom(id: String, onClosed: () -> Unit) {
        returnAfterReader = onClosed
        state = state.copy(visible = true)
        work {
            positions.flush()
            try {
                state = state.copy(reader = repository.openDocument(id))
            } catch (e: Exception) {
                returnAfterReader = null
                throw e
            }
        }
    }
    fun leave(onLeft: () -> Unit = {}) = work {
        positions.flush()
        returnAfterReader = null
        pendingImport = null
        state = state.copy(visible = false, conflict = emptyList())
        onLeft()
    }
    fun closeDocument() = work {
        positions.flush()
        val back = returnAfterReader
        returnAfterReader = null
        state = state.copy(reader = null, visible = state.visible && back == null)
        back?.invoke()
    }

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
        return copyText(code)
    }
    fun copyText(code: String): Boolean {
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

    // ---- the document page's draft (PLAN-1.7.5) ----

    /**
     * The one draft of the 速记 document page, bound to the document and the version it was read at
     * (null id: a new note). Leaving 速记 by a tab, a notification or the AI page keeps it; it shows
     * again where it belongs (the new note in place of the library, an edit over its own document),
     * so no path drops typed text and no document ever shows another one's edits.
     */
    var draft by mutableStateOf<QuickNoteDraft?>(null)
        private set
    private var nextKey = 1L

    /** True when the draft belongs on the page showing now. */
    fun draftShown(): Boolean {
        val d = draft ?: return false
        return if (d.documentId == null) state.reader == null else state.reader?.summary?.id == d.documentId
    }

    /** A new note: an empty title and first paragraph; nothing is stored until it is saved with content. */
    fun startNew() {
        if (draft?.dirty == true) return
        val first = QuickNoteBlock(nextKey++, QuickNoteBlockKind.PARAGRAPH, "")
        draft = QuickNoteDraft(null, java.util.UUID.randomUUID().toString(), null, "", "", "", listOf(first), focusTitle = true)
        state = state.copy(visible = true, reader = null, error = "")
    }

    /**
     * Edits the open document from the version on disk now, starting at the block holding source
     * [line] (a tap on the text) or else at heading [headingIndex] (where reading had got to).
     */
    fun beginEdit(id: String, headingIndex: Int = -1, line: Int = -1) = work {
        positions.flush()
        draft?.let {
            if (it.documentId == id) return@work
            // Never replace another note's unsaved edits.
            if (it.dirty) error("《${it.title.ifBlank { "新速记" }}》还有未保存的修改，请先保存或放弃")
        }
        val source = repository.editSource(id)
        val blocks = QuickNoteBlocks.parse(source.text, nextKey)
        nextKey += blocks.size + 1
        val tapped =
            if (line < 0) null
            else (blocks.firstOrNull { it.lines?.contains(line) == true } ?: blocks.lastOrNull { (it.lines?.first ?: Int.MAX_VALUE) <= line })?.key
        val at = tapped ?: if (headingIndex < 0) null else blocks.filter { it.heading }.getOrNull(headingIndex)?.key
        draft =
            QuickNoteDraft(
                id, java.util.UUID.randomUUID().toString(), source.version, source.summary.title, source.text, source.summary.title, blocks,
                scrollTo = at, focusBlock = tapped, markdownPath = source.markdownPath, imagePaths = source.imagePaths,
            )
    }

    fun editDraft(change: (QuickNoteDraft) -> QuickNoteDraft) {
        val d = draft ?: return
        if (d.saving) return
        draft = change(d).copy(error = "")
    }

    fun newBlockKey() = nextKey++

    fun discardDraft() {
        if (draft?.saving == true) return
        draft = null
    }

    /**
     * Saves the draft: a new note is created (once per command id), an edit replaces only its changed
     * blocks and is checked against the version it was read at. The reader then reopens the saved
     * document, so position, excerpt and outline follow what is stored. A failure keeps the draft,
     * its text and the reason.
     */
    fun saveDraft() {
        val d = draft ?: return
        if (d.saving || state.working) return
        draft = d.copy(saving = true, error = "")
        state = state.copy(working = true, error = "")
        scope.launch {
            try {
                positions.flush()
                val markdown = d.markdown()
                if (d.documentId == null) {
                    val created = repository.createDocument(QuickNoteCreate(d.commandId, d.title, markdown))
                    draft = null
                    refresh()
                    if (created != null) state = state.copy(reader = repository.openDocument(created.id))
                } else {
                    repository.updateDocument(QuickNoteUpdate(d.documentId, requireNotNull(d.version), d.title, markdown))
                    draft = null
                    state = state.copy(reader = repository.openDocument(d.documentId))
                    refresh()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason =
                    if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "速记保存失败，请重试"
                    else "无法保存速记，请重试"
                draft = draft?.copy(saving = false, error = reason)
            } finally {
                state = state.copy(working = false)
            }
        }
    }

    fun setPinned(id: String, pinned: Boolean) = work {
        repository.setPinned(id, pinned)
        refresh()
    }

    suspend fun search(query: String): List<QuickNoteSearchHit> = withContext(Dispatchers.IO) { repository.searchDocuments(query) }
}

/** The document page's draft; see [QuickNotesStateHolder.draft]. */
data class QuickNoteDraft(
    val documentId: String?,
    /** Creating twice with this id gives the same note. */
    val commandId: String,
    /** The version the edit was read at; saving against another version is refused. */
    val version: String?,
    val baseTitle: String,
    val baseMarkdown: String,
    val title: String,
    val blocks: List<QuickNoteBlock>,
    val focusTitle: Boolean = false,
    /** The block reading had reached when editing began. */
    val scrollTo: Long? = null,
    /** The block that was tapped to start editing: it takes the cursor. */
    val focusBlock: Long? = null,
    /** Where the Markdown sits in its package and which images it has, for the preview. */
    val markdownPath: String = "document.md",
    val imagePaths: Set<String> = emptySet(),
    val previewing: Boolean = false,
    val saving: Boolean = false,
    val error: String = "",
) {
    fun markdown(): String = QuickNoteBlocks.compose(baseMarkdown, blocks)

    val dirty: Boolean
        get() =
            title.trim() != baseTitle.trim() ||
                blocks.any { b -> if (b.lines == null) !b.deleted && b.hasContent else b.edited || b.deleted }

    /** A new note with nothing in it: leaving it stores nothing and asks nothing. */
    val blank: Boolean
        get() = documentId == null && title.isBlank() && blocks.none { !it.deleted && it.hasContent }
}
