package cn.embeddedquiz

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class NotesStateHolder(private val scope: CoroutineScope, private val repo: QuizRepository) {
    var stickyNotes by mutableStateOf<List<StickyNote>>(emptyList())
        private set

    var noteDraft by mutableStateOf<StickyNote?>(null)
        private set

    private data class Mutation(val note: StickyNote, val delete: Boolean = false)

    private val writer =
        OrderedWriter<Mutation>(scope, { it.note.id }) {
            if (it.delete) repo.dao.deleteStickyNote(it.note.id)
            else repo.dao.putStickyNote(it.note)
        }
    val pendingNoteWrites
        get() = writer.pending

    val noteSaveError
        get() = writer.error

    init {
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
        writer.enqueue(Mutation(requireNotNull(noteDraft)))
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
        writer.enqueue(Mutation(note))
    }

    fun retryStickyNotes() = writer.retry()

    fun deleteStickyNote(note: StickyNote) {
        if (noteDraft?.id == note.id) noteDraft = null
        writer.enqueue(Mutation(note, true))
    }

    suspend fun flushStickyNotes() = writer.flush()

    fun shutdown() = writer.close()
}
