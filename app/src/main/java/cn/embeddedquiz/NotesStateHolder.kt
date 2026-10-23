package cn.embeddedquiz

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect

class NotesStateHolder(private val scope: CoroutineScope, private val repo: QuizRepository) {
    var stickyNotes by mutableStateOf<List<StickyNote>>(emptyList())
        private set

    var noteDraft by mutableStateOf<StickyNote?>(null)
        private set

    var pendingNoteWrites by mutableIntStateOf(0)
        private set

    var noteSaveError by mutableStateOf("")
        private set

    private data class NoteWrite(
        val note: StickyNote? = null,
        val delete: Boolean = false,
        val barrier: CompletableDeferred<Unit>? = null,
    )

    private val noteWrites = Channel<NoteWrite>(Channel.UNLIMITED)
    private val failedNoteWrites = mutableMapOf<String, NoteWrite>()

    init {
        // One ordered writer prevents an older keystroke from overwriting a newer edit or deletion.
        scope.launch {
            for (write in noteWrites) {
                if (write.barrier != null) {
                    write.barrier.complete(Unit)
                    continue
                }
                try {
                    withContext(Dispatchers.IO) {
                        val note = requireNotNull(write.note)
                        if (write.delete) repo.dao.deleteStickyNote(note.id)
                        else repo.dao.putStickyNote(note)
                    }
                    failedNoteWrites.remove(requireNotNull(write.note).id)
                    if (failedNoteWrites.isEmpty()) noteSaveError = ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedNoteWrites[requireNotNull(write.note).id] = write
                    noteSaveError = "便签保存失败，请重试：${e.message ?: "存储不可用"}"
                } finally {
                    pendingNoteWrites--
                }
            }
        }
        scope.launch { repo.dao.stickyNotes().collect { stickyNotes = it } }
    }

    fun editStickyNote(note: StickyNote) {
        noteDraft = note
    }

    fun closeStickyNote() {
        noteDraft = null
    }

    fun newStickyNote() {
        val now = System.currentTimeMillis()
        noteDraft = StickyNote(java.util.UUID.randomUUID().toString(), created = now, updated = now)
        enqueueNote(requireNotNull(noteDraft))
    }

    fun updateStickyNote(title: String, content: String) {
        val old = noteDraft ?: return
        val note =
            old.copy(
                title = title,
                content = content,
                updated = maxOf(old.created, System.currentTimeMillis()),
            )
        noteDraft = note
        enqueueNote(note)
    }

    fun retryStickyNotes() {
        failedNoteWrites.values.toList().forEach { enqueueNote(requireNotNull(it.note), it.delete) }
    }

    fun deleteStickyNote(note: StickyNote) {
        if (noteDraft?.id == note.id) noteDraft = null
        enqueueNote(note, delete = true)
    }

    private fun enqueueNote(note: StickyNote, delete: Boolean = false) {
        pendingNoteWrites++
        check(noteWrites.trySend(NoteWrite(note, delete)).isSuccess)
    }

    suspend fun flushStickyNotes() {
        val barrier = CompletableDeferred<Unit>()
        noteWrites.send(NoteWrite(barrier = barrier))
        barrier.await()
        check(noteSaveError.isEmpty()) { noteSaveError }
    }

    fun shutdown() {
        noteWrites.close()
    }
}
