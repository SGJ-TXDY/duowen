package cn.embeddedquiz

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Shared by sticky notes and code drafts: writes, deletes and flush barriers keep enqueue order.
 */
class OrderedWriter<T : Any>(
    scope: CoroutineScope,
    private val key: (T) -> String,
    private val save: suspend (T) -> Unit,
) {
    private data class Entry<T>(
        val value: T? = null,
        val barrier: CompletableDeferred<Unit>? = null,
    )

    private val channel = Channel<Entry<T>>(Channel.UNLIMITED)
    private val failures = linkedMapOf<String, T>()
    var pending by mutableIntStateOf(0)
        private set

    var error by mutableStateOf("")
        private set

    init {
        scope.launch {
            for (entry in channel) {
                if (entry.barrier != null) {
                    entry.barrier.complete(Unit)
                    continue
                }
                val value = requireNotNull(entry.value)
                try {
                    withContext(Dispatchers.IO) { save(value) }
                    failures.remove(key(value))
                    if (failures.isEmpty()) error = ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures[key(value)] = value
                    error = "本地保存失败，请重试"
                } finally {
                    pending--
                }
            }
        }
    }

    fun enqueue(value: T) {
        pending++
        check(channel.trySend(Entry(value)).isSuccess)
    }

    fun retry() {
        failures.values.toList().forEach(::enqueue)
    }

    suspend fun flush() {
        val barrier = CompletableDeferred<Unit>()
        channel.send(Entry(barrier = barrier))
        barrier.await()
        check(error.isEmpty()) { error }
    }

    fun close() {
        channel.close()
    }
}

data class CodeDraft(val id: String, val code: String, val notes: String)

class CodeDraftStateHolder(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope, repo: QuizRepository) {
    internal val persistence = CodeDraftPersistence { draft ->
        repo.update(draft.id) { it.copy(code = draft.code, notes = draft.notes) }
    }
    val drafts get() = persistence.drafts
    val editingBlocked get() = persistence.editingBlocked
    fun state(id: String) = persistence.state(id)
    fun update(id: String, code: String, notes: String) = persistence.update(CodeDraft(id, code, notes))
    internal fun seed(id: String, code: String, notes: String) = persistence.seed(CodeDraft(id, code, notes))
    fun retry(id: String? = null) = persistence.retry(id)
    suspend fun flush(id: String? = null) = persistence.flush(id)
    internal suspend fun <T> exclusive(action: suspend () -> T): T = persistence.exclusive(action)
    internal fun advanceGeneration(retainText: Boolean) = persistence.advanceGeneration(retainText)
    fun shutdown(): Job = persistence.shutdown()

    /** Temporary source compatibility; all paths use the same coalescing worker and barriers. */
    val writer = CompatibilityWriter()
    inner class CompatibilityWriter internal constructor() {
        val pending get() = persistence.pendingQuestions
        val error get() = persistence.error
        fun retry() = this@CodeDraftStateHolder.retry()
        suspend fun flush() { check(this@CodeDraftStateHolder.flush()) { error.ifEmpty { "草稿保存失败" } } }
        fun close() { shutdown() }
    }
}