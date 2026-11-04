package cn.embeddedquiz

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

sealed interface ExamSaveState {
    data object Inactive : ExamSaveState
    data class Saving(val revision: Long) : ExamSaveState
    data class Saved(val revision: Long) : ExamSaveState
    data class Error(val revision: Long, val message: String) : ExamSaveState
}

internal data class DurableExamSnapshot(
    val sessionId: String,
    val revision: Long,
    val ids: List<String>,
    val index: Int,
    val deadline: Long,
    val answers: Map<String, Set<String>>,
    val legacyHash: String? = null,
) {
    fun encode(): String {
        val values = JSONObject()
        answers.forEach { (id, selected) -> values.put(id, JSONArray(selected.sorted())) }
        return JSONObject().put("sessionId", sessionId).put("revision", revision)
            .put("ids", JSONArray(ids)).put("index", index).put("deadline", deadline)
            .put("answers", values).put("legacyHash", legacyHash ?: JSONObject.NULL).toString()
    }
    companion object {
        fun decode(text: String): DurableExamSnapshot {
            val root = JSONObject(text)
            val ids = root.getJSONArray("ids").let { a -> (0 until a.length()).map(a::getString) }
            val values = root.getJSONObject("answers")
            val legacy = root.optString("sessionId").isBlank()
            val hash = if (legacy) MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
                else if (root.isNull("legacyHash")) null else root.optString("legacyHash").ifBlank { null }
            return DurableExamSnapshot(
                if (legacy) UUID.randomUUID().toString() else root.getString("sessionId"),
                root.optLong("revision", 1).coerceAtLeast(1), ids,
                root.getInt("index").coerceIn(0, (ids.size - 1).coerceAtLeast(0)),
                root.getLong("deadline"), values.keys().asSequence().associateWith { id ->
                    values.getJSONArray(id).let { a -> (0 until a.length()).map(a::getString).toSet() }
                }, hash,
            )
        }
    }
}

/** One in-flight write plus one replaceable pending snapshot. No caller owns the writer's Job. */
internal class ExamPersistence(private val preferences: () -> SharedPreferences) {
    val gate = Mutex()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val changed = MutableStateFlow(0L)
    private var sessionId: String? = null
    private var latest: DurableExamSnapshot? = null
    private var pending: DurableExamSnapshot? = null
    private var savedRevision = 0L
    private var failedRevision = 0L
    private var ending = false
    private var closed = false
    var state by mutableStateOf<ExamSaveState>(ExamSaveState.Inactive)
        private set
    internal var beforeWrite: suspend (DurableExamSnapshot) -> Unit = {}
    private fun notifyChange() { changed.value += 1 }

    private val worker = scope.launch {
        for (ignored in signal) {
            while (true) {
                val next = gate.withLock {
                    val snapshot = synchronized(lock) {
                        if (ending) { pending = null; null } else pending.also { pending = null }
                    } ?: return@withLock false
                    val ok = try {
                        beforeWrite(snapshot)
                        preferences().edit().putString("exam", snapshot.encode()).commit()
                    } catch (e: Exception) { false }
                    synchronized(lock) {
                        if (sessionId == snapshot.sessionId && !ending) {
                            if (ok) savedRevision = maxOf(savedRevision, snapshot.revision)
                            else failedRevision = maxOf(failedRevision, snapshot.revision)
                            if (latest?.revision == snapshot.revision) state = if (ok)
                                ExamSaveState.Saved(snapshot.revision) else
                                ExamSaveState.Error(snapshot.revision, "保存失败，最新修改尚未保存")
                            notifyChange()
                        }
                    }
                    true
                }
                if (!next) break
            }
        }
    }

    fun begin(id: String) = synchronized(lock) {
        check(!closed)
        sessionId = id; latest = null; pending = null
        savedRevision = 0; failedRevision = 0; ending = false
        state = ExamSaveState.Inactive; notifyChange()
    }
    fun enqueue(snapshot: DurableExamSnapshot) = synchronized(lock) {
        if (closed || ending || sessionId != snapshot.sessionId) return@synchronized
        latest = snapshot; pending = snapshot; failedRevision = 0
        state = ExamSaveState.Saving(snapshot.revision)
        notifyChange(); signal.trySend(Unit)
    }
    fun retryFrozen(snapshot: DurableExamSnapshot) = synchronized(lock) {
        if (sessionId == snapshot.sessionId && savedRevision < snapshot.revision) enqueue(snapshot)
    }
    fun retry() = synchronized(lock) {
        if (!closed && !ending) latest?.let { enqueue(it) }
    }
    suspend fun flush(): Boolean {
        val target = synchronized(lock) { if (ending) null else latest } ?: return true
        while (true) {
            val token = changed.value
            val outcome = synchronized(lock) {
                when {
                    sessionId != target.sessionId || ending -> false
                    savedRevision >= target.revision -> true
                    failedRevision >= target.revision && (latest?.revision ?: 0) <= failedRevision -> false
                    else -> null
                }
            }
            if (outcome != null) return outcome
            changed.first { it != token }
        }
    }
    /** Caller has frozen inputs. An old writer already inside gate finishes before termination. */
    fun isolate() = synchronized(lock) {
        ending = true; pending = null; notifyChange()
    }
    fun terminated() = synchronized(lock) {
        ending = true; pending = null; latest = null
        state = ExamSaveState.Inactive; notifyChange()
    }
    fun shutdown(): Job {
        synchronized(lock) { closed = true }
        signal.close()
        worker.invokeOnCompletion { scope.cancel() }
        return worker
    }
}