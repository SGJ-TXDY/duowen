package cn.embeddedquiz

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.room.withTransaction
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

data class ExamSnapshot(val ids: List<String>, val index: Int)

data class ExamResult(
    val total: Int,
    val answered: Int,
    val unanswered: Int,
    val correct: Int,
    val score: Int,
)

enum class ExamOperation { SUBMIT, ABANDON }
enum class ExamFailureStage { VALIDATION, RECORDS, SESSION_CLEAR }

sealed interface ExamOperationState {
    data object Idle : ExamOperationState
    data object Submitting : ExamOperationState
    data class Submitted(val result: ExamResult) : ExamOperationState
    data object Abandoning : ExamOperationState
    data object Abandoned : ExamOperationState
    data class Failed(
        val operation: ExamOperation,
        val stage: ExamFailureStage,
        val recordsCommitted: Boolean,
        val result: ExamResult?,
        val message: String,
    ) : ExamOperationState
}

/** Local fault-injection seams; no production scheduling or persistence policy is changed. */
internal class ExamOperationHooks {
    var beforeCritical: suspend () -> Unit = {}
    var beforeTransaction: suspend () -> Unit = {}
    var afterRecord: suspend (Int) -> Unit = {}
    var afterCommit: suspend () -> Unit = {}
    var beforeClear: suspend () -> Unit = {}
}

private class ExamValidationException(message: String) : IllegalArgumentException(message)
private data class ExamSubmission(val ids: List<String>, val answers: Map<String, Set<String>>)

class ExamStateHolder(private val prefs: SharedPreferences, private val repo: QuizRepository) {
    var deadline by mutableLongStateOf(0)
    var answers by mutableStateOf<Map<String, Set<String>>>(emptyMap())
    var finished by mutableStateOf(false)
    var result by mutableStateOf("")
    var operationState by mutableStateOf<ExamOperationState>(ExamOperationState.Idle)
        private set
    var summary by mutableStateOf<ExamResult?>(null)
        private set

    // A failed commit may already remove the prefs key from memory. It is not a session lock.
    private var sessionOpen = false
    @Volatile private var ownerCleared = false

    internal fun onOwnerCleared() { ownerCleared = true }
    private var submission: ExamSubmission? = null
    private var previousState: ExamOperationState = ExamOperationState.Idle
    private var operation = ExamOperation.SUBMIT
    internal val hooks = ExamOperationHooks()
    internal var sessionPreferences = prefs

    internal val acceptsAnswers: Boolean
        get() = sessionOpen && operationState == ExamOperationState.Idle && !finished

    internal fun startSession(isExam: Boolean) {
        sessionOpen = isExam
        submission = null
        operationState = ExamOperationState.Idle
        summary = null
        finished = false
        result = ""
        answers = emptyMap()
        deadline = 0
    }

    fun restore(): ExamSnapshot? {
        val saved = sessionPreferences.getString("exam", null) ?: return null
        return runCatching {
                val root = JSONObject(saved)
                val ids = root.getJSONArray("ids").let { a -> (0 until a.length()).map(a::getString) }
                deadline = root.getLong("deadline")
                val values = root.getJSONObject("answers")
                answers = values.keys().asSequence().associateWith { id ->
                    val a = values.getJSONArray(id)
                    (0 until a.length()).map(a::getString).toSet()
                }
                sessionOpen = true
                ExamSnapshot(ids, root.getInt("index").coerceIn(0, (ids.size - 1).coerceAtLeast(0)))
            }
            .getOrElse {
                clear()
                null
            }
    }

    fun save(ids: List<String>, index: Int, mode: String) {
        if (mode != "模拟考试" || !acceptsAnswers) return
        val root = JSONObject().put("ids", JSONArray(ids)).put("index", index).put("deadline", deadline)
        val values = JSONObject()
        answers.forEach { (id, selected) -> values.put(id, JSONArray(selected.toList())) }
        root.put("answers", values)
        sessionPreferences.writeDurably { putString("exam", root.toString()) }
    }

    /** Loads and validates the entire frozen paper before writing any learning rows. */
    suspend fun grade(ids: List<String>, answersSnapshot: Map<String, Set<String>>): ExamResult =
        repo.db.withTransaction {
            if (ids.isEmpty() || ids.distinct().size != ids.size)
                throw ExamValidationException("考试队列无效")
            val questions = ids.map { id ->
                repo.question(id) ?: throw ExamValidationException("考试题目不存在")
            }
            val grades = questions.map { q ->
                val selected = answersSnapshot[q.id].orEmpty()
                if (!q.daily) throw ExamValidationException("考试题目不可判分")
                Grader.grade(q, selected, repo.dao.personalAnswer(q.id))
                    ?: throw ExamValidationException("考试题目不可判分")
            }
            var recorded = 0
            questions.forEach { q ->
                val selected = answersSnapshot[q.id].orEmpty()
                if (selected.isNotEmpty()) {
                    repo.record(q, selected.joinToString(), selected, "模拟考试")
                    hooks.afterRecord(++recorded)
                }
            }
            val answered = questions.count { answersSnapshot[it.id].orEmpty().isNotEmpty() }
            val correct = questions.indices.count {
                answersSnapshot[questions[it].id].orEmpty().isNotEmpty() && grades[it]
            }
            ExamResult(ids.size, answered, ids.size - answered, correct, correct * 100 / ids.size)
        }

    internal fun accept(operation: ExamOperation, ids: List<String>): Boolean {
        if (!sessionOpen) return false
        val state = operationState
        val canAbandonFailure = state is ExamOperationState.Failed &&
            state.operation == ExamOperation.SUBMIT && !state.recordsCommitted &&
            state.stage in setOf(ExamFailureStage.VALIDATION, ExamFailureStage.RECORDS)
        if (state != ExamOperationState.Idle && !(operation == ExamOperation.ABANDON && canAbandonFailure))
            return false
        if (submission == null)
            submission = ExamSubmission(ids.toList(), answers.mapValues { it.value.toSet() })
        beginOperation(operation)
        return true
    }

    internal fun acceptRetry(): Boolean {
        val failed = operationState as? ExamOperationState.Failed ?: return false
        if (submission == null || !sessionOpen) return false
        beginOperation(failed.operation)
        return true
    }

    private fun beginOperation(value: ExamOperation) {
        previousState = operationState
        operation = value
        operationState = if (value == ExamOperation.SUBMIT) ExamOperationState.Submitting
            else ExamOperationState.Abandoning
    }

    /** Called on Main. Only the transaction/known outcome/one cleanup attempt resist cancellation. */
    internal suspend fun executeOperation() {
        try {
            hooks.beforeCritical()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                if (operation == ExamOperation.SUBMIT && summary == null) {
                    try {
                        val frozen = checkNotNull(submission)
                        summary = withContext(Dispatchers.IO) {
                            hooks.beforeTransaction()
                            check(!ownerCleared) { "考试所属页面会话已结束" }
                            grade(frozen.ids, frozen.answers)
                        }
                    } catch (e: Exception) {
                        fail(if (e is ExamValidationException) ExamFailureStage.VALIDATION else ExamFailureStage.RECORDS)
                        return@withContext
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        if (operation == ExamOperation.SUBMIT) hooks.afterCommit()
                        hooks.beforeClear()
                        sessionPreferences.writeDurably { remove("exam") }
                    }
                } catch (e: Exception) {
                    fail(ExamFailureStage.SESSION_CLEAR)
                    return@withContext
                }
                sessionOpen = false
                if (operation == ExamOperation.SUBMIT) {
                    val completed = checkNotNull(summary)
                    finished = true
                    result = "答对 ${completed.correct} / ${completed.total}，得分 ${completed.score}"
                    operationState = ExamOperationState.Submitted(completed)
                } else {
                    answers = emptyMap()
                    deadline = 0
                    summary = null
                    finished = false
                    result = ""
                    operationState = ExamOperationState.Abandoned
                }
            }
        } catch (e: CancellationException) {
            // A pre-critical cancellation must not leave busy/operation ownership stranded.
            operationState = previousState
            if (previousState == ExamOperationState.Idle) submission = null
            throw e
        } catch (e: Exception) {
            fail(if (operation == ExamOperation.SUBMIT) ExamFailureStage.RECORDS else ExamFailureStage.SESSION_CLEAR)
        }
    }

    private fun fail(stage: ExamFailureStage) {
        operationState = ExamOperationState.Failed(
            operation, stage, summary != null, summary,
            when (stage) {
                ExamFailureStage.VALIDATION -> "考试题目发生变化，无法交卷；请重试或放弃本次考试"
                ExamFailureStage.RECORDS -> "考试记录未能保存；请重试或放弃本次考试"
                ExamFailureStage.SESSION_CLEAR -> if (summary != null)
                    "成绩已保存，但考试会话未能清理；请重试清理" else "考试尚未放弃；请重试清理"
            },
        )
    }

    fun clear() {
        sessionPreferences.writeDurably { remove("exam") }
        startSession(false)
    }

    fun active() = sessionOpen
}
