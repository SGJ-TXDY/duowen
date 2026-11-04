package cn.embeddedquiz

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CodeDraftSaveState {
    data object Inactive : CodeDraftSaveState
    data class Saving(val revision: Long) : CodeDraftSaveState
    data class Saved(val revision: Long) : CodeDraftSaveState
    data class Error(val revision: Long, val message: String) : CodeDraftSaveState
}

internal interface CodeDraftClock {
    fun now(): Long
    suspend fun until(deadline: Long)
}
private object MonotonicDraftClock : CodeDraftClock {
    override fun now() = System.nanoTime() / 1_000_000
    override suspend fun until(deadline: Long) { delay((deadline - now()).coerceAtLeast(0)) }
}

/** Independent lifetime: one actual write plus a replaceable latest version per question. */
internal class CodeDraftPersistence(private val save: suspend (CodeDraft) -> Unit) {
    private data class Version(val value: CodeDraft, val revision: Long, val generation: Long, val first: Long)
    private class Entry(var latest: Version) {
        var saved = 0L
        var failed = 0L
        var first: Long? = latest.first
        var last = latest.first
        var scheduled = true
        var forced = false
        var inFlight: Long? = null
        fun deadline() = if (forced) Long.MIN_VALUE else minOf(last + 300, (first ?: last) + 2000)
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val changed = MutableStateFlow(0L)
    private val entries = linkedMapOf<String, Entry>()
    private val states = mutableStateMapOf<String, CodeDraftSaveState>()
    val drafts = mutableStateMapOf<String, CodeDraft>()
    private val writeGate = Mutex()
    private val boundaryGate = Mutex()
    private var generation = 0L
    private var closed = false
    private var stopped = false
    private var timer: Job? = null
    var editingBlocked by mutableStateOf(false)
        private set
    @Volatile internal var clock: CodeDraftClock = MonotonicDraftClock
    @Volatile internal var beforeWrite: suspend (CodeDraft) -> Unit = {}
    @Volatile internal var afterWrite: suspend (CodeDraft) -> Unit = {}
    @Volatile internal var beforeBoundary: suspend () -> Unit = {}
    @Volatile internal var closeBudgetMillis = 2500L
    internal val pendingQuestions get() = synchronized(lock) { entries.count { it.value.latest.revision > it.value.saved } }
    val error: String get() = synchronized(lock) { states.values.filterIsInstance<CodeDraftSaveState.Error>().firstOrNull()?.message.orEmpty() }
    fun state(id: String): CodeDraftSaveState = synchronized(lock) { states[id] ?: CodeDraftSaveState.Inactive }
    private fun changed() { changed.value += 1 }

    fun seed(value: CodeDraft) = synchronized(lock) { if (value.id !in drafts) drafts[value.id] = value }
    fun update(value: CodeDraft): Boolean = synchronized(lock) {
        if (closed || editingBlocked) return false
        if (drafts[value.id] == value) return true
        drafts[value.id] = value
        val now = clock.now()
        val previous = entries[value.id]
        val revision = (previous?.latest?.revision ?: 0) + 1
        val version = Version(value, revision, generation, previous?.first ?: now)
        val entry = previous ?: Entry(version).also { entries[value.id] = it }
        entry.latest = version
        if (entry.first == null) entry.first = now
        entry.last = now
        entry.scheduled = true
        states[value.id] = CodeDraftSaveState.Saving(revision)
        changed(); signal.trySend(Unit)
        true
    }
    fun retry(id: String? = null): Unit = synchronized(lock) {
        if (closed || editingBlocked) return@synchronized
        entries.filterKeys { id == null || it == id }.forEach { (key, entry) ->
            if (states[key] is CodeDraftSaveState.Error) {
                entry.scheduled = true; entry.forced = true
                states[key] = CodeDraftSaveState.Saving(entry.latest.revision)
            }
        }
        changed(); signal.trySend(Unit)
        Unit
    }
    suspend fun flush(id: String? = null): Boolean {
        val targets = synchronized(lock) {
            entries.filterKeys { id == null || it == id }.mapValues { it.value.latest }.also {
                it.keys.forEach { key -> entries[key]?.takeIf { e -> e.scheduled }?.forced = true }
                signal.trySend(Unit)
            }
        }
        if (targets.isEmpty()) return true
        while (true) {
            val token = changed.value
            val result = synchronized(lock) {
                if (targets.values.any { it.generation != generation }) false
                else {
                    val waiting = targets.filter { (key, version) -> (entries[key]?.saved ?: 0) < version.revision }
                    when {
                        waiting.isEmpty() -> true
                        stopped || waiting.any { (key, version) ->
                            val entry = entries[key]
                            entry == null || (!entry.scheduled && entry.inFlight == null && entry.failed >= version.revision)
                        } -> false
                        else -> null
                    }
                }
            }
            if (result != null) return result
            changed.first { it != token }
        }
    }
    /** Block acceptance before capturing all latest revisions; never clear state on failure. */
    suspend fun <T> exclusive(action: suspend () -> T): T = boundaryGate.withLock {
        synchronized(lock) { check(!closed); editingBlocked = true }
        try {
            beforeBoundary()
            check(flush()) { "草稿保存失败，操作已中止，请先重试保存" }
            writeGate.withLock { action() }
        } finally { synchronized(lock) { editingBlocked = closed } }
    }
    /** Only inside a successfully committed exclusive replacement/reset. */
    fun advanceGeneration(retainText: Boolean) = synchronized(lock) {
        check(editingBlocked)
        generation++
        entries.clear(); states.clear()
        if (!retainText) drafts.clear()
        timer?.cancel(); changed()
    }
    private fun takeDue(): Version? = synchronized(lock) {
        val entry = entries.values.filter { it.scheduled && it.deadline() <= clock.now() }
            .minByOrNull { it.deadline() } ?: return null
        val next = entry.latest.copy(first = entry.first ?: entry.latest.first)
        entry.scheduled = false; entry.forced = false; entry.first = null
        entry.inFlight = next.revision
        next
    }
    private fun scheduleTimer() = synchronized(lock) {
        timer?.cancel()
        val deadline = entries.values.filter { it.scheduled }.minOfOrNull { it.deadline() } ?: return@synchronized
        timer = scope.launch { clock.until(deadline); signal.trySend(Unit) }
    }
    private val worker = scope.launch {
        for (ignored in signal) {
            while (true) {
                val next = takeDue() ?: break
                var ok = false
                try {
                    writeGate.withLock {
                        if (synchronized(lock) { generation == next.generation }) {
                            beforeWrite(next.value)
                            save(next.value)
                            ok = true
                            afterWrite(next.value)
                        }
                    }
                } catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { /* Retain the latest text; no automatic failure loop. */ }
                synchronized(lock) {
                    if (generation == next.generation) entries[next.value.id]?.let { entry ->
                        entry.inFlight = null
                        if (ok) entry.saved = maxOf(entry.saved, next.revision)
                        else {
                            entry.failed = maxOf(entry.failed, next.revision)
                            if (entry.first == null) entry.first = next.first
                        }
                        if (entry.latest.revision == next.revision) states[next.value.id] = if (ok)
                            CodeDraftSaveState.Saved(next.revision) else
                            CodeDraftSaveState.Error(next.revision, "草稿保存失败，最新文字尚未保存")
                        changed()
                    }
                }
            }
            scheduleTimer()
        }
    }.also { task ->
        task.invokeOnCompletion {
            synchronized(lock) {
                stopped = true
                entries.forEach { (id, entry) ->
                    if (entry.saved < entry.latest.revision) states[id] =
                        CodeDraftSaveState.Error(entry.latest.revision, "草稿保存失败，最新文字尚未保存")
                }
                changed()
            }
            scope.cancel()
        }
    }
    /** A 2.5s drain budget, then cooperative cancellation; join still waits for actual IO to exit. */
    fun shutdown(): Job = synchronized(lock) {
        if (!closed) {
            closed = true; editingBlocked = true
            timer?.cancel()
            entries.values.filter { it.scheduled }.forEach { it.forced = true }
            signal.trySend(Unit); signal.close()
            scope.launch { delay(closeBudgetMillis); worker.cancel(CancellationException("草稿收尾超时")) }
        }
        worker
    }
}