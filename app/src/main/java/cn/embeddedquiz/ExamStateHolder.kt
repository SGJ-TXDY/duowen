package cn.embeddedquiz

import android.content.SharedPreferences
import androidx.compose.runtime.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

// The public navigation snapshot remains deliberately small.
data class ExamSnapshot(val ids: List<String>, val index: Int)
data class ExamResult(val total: Int, val answered: Int, val unanswered: Int, val correct: Int, val score: Int)
enum class ExamOperation { SUBMIT, ABANDON }
enum class ExamFailureStage { VALIDATION, RECORDS, SESSION_CLEAR }
sealed interface ExamOperationState {
    data object Idle : ExamOperationState
    data object Submitting : ExamOperationState
    data class Submitted(val result: ExamResult) : ExamOperationState
    data object Abandoning : ExamOperationState
    data object Abandoned : ExamOperationState
    data class Failed(val operation: ExamOperation, val stage: ExamFailureStage,
        val recordsCommitted: Boolean, val result: ExamResult?, val message: String) : ExamOperationState
}
internal class ExamOperationHooks {
    var beforeCritical: suspend () -> Unit = {}
    var beforeTransaction: suspend () -> Unit = {}
    var afterRecord: suspend (Int) -> Unit = {}
    var afterCommit: suspend () -> Unit = {}
    var beforeClear: suspend () -> Unit = {}
}
internal class ExamValidationException(message: String) : IllegalArgumentException(message)

class ExamStateHolder(private val prefs: SharedPreferences, private val repo: QuizRepository) {
    var deadline by mutableLongStateOf(0)
    var answers by mutableStateOf<Map<String, Set<String>>>(emptyMap())
    var finished by mutableStateOf(false)
    var result by mutableStateOf("")
    var operationState by mutableStateOf<ExamOperationState>(ExamOperationState.Idle)
        private set
    var summary by mutableStateOf<ExamResult?>(null)
        private set
    internal var sessionPreferences = prefs
    internal val persistence = ExamPersistence { sessionPreferences }
    val saveState: ExamSaveState get() = persistence.state
    private var sessionOpen = false
    private var restoring = false
    @Volatile private var ownerCleared = false
    private var sessionId = ""
    private var revision = 0L
    private var legacyHash: String? = null
    private var submission: DurableExamSnapshot? = null
    private var terminal: ExamTermination? = null
    private var previousState: ExamOperationState = ExamOperationState.Idle
    private var operation = ExamOperation.SUBMIT
    internal val hooks = ExamOperationHooks()
    internal val acceptsAnswers: Boolean
        get() = !ownerCleared && sessionOpen && operationState == ExamOperationState.Idle && !finished

    internal fun startSession(isExam: Boolean): Boolean {
        if (ownerCleared || sessionOpen) return false
        if (isExam && !repo.db.examAccess.claim()) return false
        sessionOpen = isExam
        submission = null; terminal = null
        operationState = ExamOperationState.Idle; summary = null; finished = false; result = ""
        answers = emptyMap(); deadline = 0; revision = 0; legacyHash = null
        sessionId = if (isExam) UUID.randomUUID().toString() else ""
        if (isExam) persistence.begin(sessionId)
        return true
    }

    internal fun reserveForRestore() {
        check(repo.db.examAccess.claim()) { "考试恢复与数据替换冲突" }
        restoring = true
    }

    suspend fun restore(): ExamSnapshot? {
        if (!restoring && !sessionOpen) reserveForRestore()
        val saved = try {
            withContext(Dispatchers.IO) {
                sessionPreferences.getString("exam", null)?.let(DurableExamSnapshot::decode)
            }
        } catch (e: Exception) {
            // An unreadable snapshot is not proof that no exam exists. Keep the startup guard.
            throw e
        }
        if (saved == null) { restoring = false; repo.db.examAccess.release(); return null }
        sessionId = saved.sessionId; revision = saved.revision; legacyHash = saved.legacyHash
        deadline = saved.deadline; answers = saved.answers; sessionOpen = true; restoring = false
        persistence.begin(sessionId)
        terminal = withContext(Dispatchers.IO) { repo.dao.examTermination(sessionId, legacyHash) }
        if (terminal != null) {
            submission = saved
            summary = terminal!!.result()
            operation = if (summary != null) ExamOperation.SUBMIT else ExamOperation.ABANDON
            persistence.terminated()
            fail(ExamFailureStage.SESSION_CLEAR)
        } else {
            // Reconfirm durability even if SharedPreferences' cache was changed by commit(false).
            persistence.enqueue(saved)
        }
        return ExamSnapshot(saved.ids, saved.index)
    }

    private fun snapshot(ids: List<String>, index: Int) = DurableExamSnapshot(
        sessionId, ++revision, ids.toList(), index, deadline,
        answers.mapValues { it.value.toSet() }, legacyHash,
    )
    fun save(ids: List<String>, index: Int, mode: String) {
        if (mode == "模拟考试" && acceptsAnswers) persistence.enqueue(snapshot(ids, index))
    }
    fun retrySave() { if (acceptsAnswers) persistence.retry() }
    suspend fun flushSave(): Boolean = persistence.flush()

    /** Compatibility test entry: still establishes a durable identity before the same transaction. */
    internal suspend fun grade(ids: List<String>, answersSnapshot: Map<String, Set<String>>): ExamResult {
        val frozen = DurableExamSnapshot(UUID.randomUUID().toString(), 1, ids.toList(), 0, deadline,
            answersSnapshot.mapValues { it.value.toSet() })
        return withContext(Dispatchers.IO) {
            persistence.gate.withLock {
                check(sessionPreferences.edit().putString("exam", frozen.encode()).commit())
                checkNotNull(repo.endExam(frozen, true, hooks.afterRecord).result())
            }
        }
    }

    internal fun accept(operation: ExamOperation, ids: List<String>, index: Int = 0): Boolean {
        if (!sessionOpen || ownerCleared) return false
        val state = operationState
        val abandonFailure = state is ExamOperationState.Failed &&
            state.operation == ExamOperation.SUBMIT && !state.recordsCommitted &&
            state.stage in setOf(ExamFailureStage.VALIDATION, ExamFailureStage.RECORDS)
        if (state != ExamOperationState.Idle && !(operation == ExamOperation.ABANDON && abandonFailure)) return false
        if (submission == null) submission = snapshot(ids, index)
        if (operation == ExamOperation.SUBMIT) persistence.enqueue(checkNotNull(submission))
        beginOperation(operation)
        return true
    }
    internal fun acceptRetry(): Boolean {
        val failed = operationState as? ExamOperationState.Failed ?: return false
        if (submission == null || !sessionOpen || ownerCleared) return false
        if (terminal == null && failed.operation == ExamOperation.SUBMIT)
            persistence.retryFrozen(checkNotNull(submission))
        beginOperation(failed.operation)
        return true
    }
    private fun beginOperation(value: ExamOperation) {
        previousState = operationState; operation = value
        operationState = if (value == ExamOperation.SUBMIT) ExamOperationState.Submitting else ExamOperationState.Abandoning
    }

    internal suspend fun executeOperation() {
        try {
            hooks.beforeCritical()
            currentCoroutineContext().ensureActive()
            if (operation == ExamOperation.SUBMIT && terminal == null && !persistence.flush()) {
                fail(ExamFailureStage.RECORDS, "考试保存失败，未提交成绩")
                return
            }
            withContext(NonCancellable) {
                if (operation == ExamOperation.ABANDON) persistence.isolate()
                persistence.gate.withLock {
                    if (terminal == null) {
                        try {
                            terminal = withContext(Dispatchers.IO) {
                                hooks.beforeTransaction()
                                check(!ownerCleared) { "考试所属页面会话已结束" }
                                repo.endExam(checkNotNull(submission), operation == ExamOperation.SUBMIT, hooks.afterRecord)
                            }
                            summary = terminal!!.result()
                            operation = if (summary != null) ExamOperation.SUBMIT else ExamOperation.ABANDON
                            // The database outcome is final. No further snapshot may be accepted.
                            persistence.terminated()
                        } catch (e: Exception) {
                            fail(if (operation == ExamOperation.ABANDON) ExamFailureStage.SESSION_CLEAR
                                else if (e is ExamValidationException) ExamFailureStage.VALIDATION else ExamFailureStage.RECORDS)
                            return@withLock
                        }
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            if (summary != null) hooks.afterCommit()
                            hooks.beforeClear()
                            check(sessionPreferences.edit().remove("exam").commit()) { "考试清理未落盘" }
                        }
                    } catch (e: Exception) { fail(ExamFailureStage.SESSION_CLEAR); return@withLock }
                    sessionOpen = false; repo.db.examAccess.release()
                    persistence.terminated()
                    if (summary != null) {
                        val completed = checkNotNull(summary)
                        finished = true
                        result = "答对 ${completed.correct} / ${completed.total}，得分 ${completed.score}"
                        operationState = ExamOperationState.Submitted(completed)
                    } else {
                        answers = emptyMap(); deadline = 0; finished = false; result = ""
                        operationState = ExamOperationState.Abandoned
                    }
                }
            }
        } catch (e: CancellationException) {
            operationState = previousState
            if (previousState == ExamOperationState.Idle) submission = null
            throw e
        } catch (e: Exception) {
            fail(if (operation == ExamOperation.SUBMIT) ExamFailureStage.RECORDS else ExamFailureStage.SESSION_CLEAR)
        }
    }
    private fun fail(stage: ExamFailureStage, message: String? = null) {
        operationState = ExamOperationState.Failed(operation, stage, summary != null, summary,
            message ?: when (stage) {
                ExamFailureStage.VALIDATION -> "考试题目发生变化，无法交卷；请重试或放弃本次考试"
                ExamFailureStage.RECORDS -> "考试记录未能保存；请重试或放弃本次考试"
                ExamFailureStage.SESSION_CLEAR -> if (summary != null)
                    "成绩已保存，但考试会话未能清理；请重试清理" else "考试尚未放弃；请重试清理"
            })
    }
    fun active() = sessionOpen || restoring

    /** Accepted writes finish on the independent writer; the DB outlives this owner's operation. */
    internal fun onOwnerCleared(): Job {
        ownerCleared = true
        return persistence.shutdown()
    }
}