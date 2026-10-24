package cn.embeddedquiz

import android.content.Context
import androidx.compose.runtime.*
import androidx.room.withTransaction
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class LearningStateHolder(
    private val scope: CoroutineScope,
    private val repo: QuizRepository,
    private val context: Context,
) {
    var settings by mutableStateOf(LearningSettings())
        private set

    var answers by mutableStateOf<Map<String, PersonalAnswer>>(emptyMap())
        private set

    var metadata by mutableStateOf<Map<String, QuestionPersonal>>(emptyMap())
        private set

    var daily by mutableStateOf<List<DailyCount>>(emptyList())
        private set

    var categories by mutableStateOf<List<CategoryCount>>(emptyList())
        private set

    var upcoming by mutableStateOf<List<DueCount>>(emptyList())
        private set

    var learnedDays by mutableStateOf<List<Long>>(emptyList())
        private set

    var costs by mutableStateOf<List<CostCount>>(emptyList())
        private set

    var totals by mutableStateOf(Totals(0, 0, 0, 0, 0, 0))
        private set

    var todaySummaryState by mutableStateOf(TodaySummaryState())
        private set

    val filters = mutableStateMapOf<Page, BrowseFilter>()
    private val today = MutableStateFlow(LocalDate.now().toEpochDay())
    private val summaryRevision = MutableStateFlow(0L)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(today, summaryRevision) { day, revision -> day to revision }
                .flatMapLatest { (day, _) ->
                    repo
                        .observeTodaySummary(day)
                        .map { TodaySummaryState(value = it, loading = false) }
                        .onStart { emit(TodaySummaryState()) }
                        .catch { error ->
                            if (error is CancellationException) throw error
                            withContext(Dispatchers.IO) {
                                LocalErrorLog(context).record(LogEvent.LOAD, error)
                            }
                            emit(TodaySummaryState(loading = false, error = "首页统计加载失败，请重试"))
                        }
                }
                .collect { todaySummaryState = it }
        }
        scope.launch { repo.dao.settings().collect { settings = it ?: LearningSettings() } }
        scope.launch {
            var wasEnabled = false
            repo.dao
                .settings()
                .map {
                    (it ?: LearningSettings()).let { s -> s.reminderEnabled to s.reminderMinute }
                }
                .distinctUntilChanged()
                .collect { (enabled, minute) ->
                    if (enabled || wasEnabled)
                        withContext(Dispatchers.IO) {
                            ReviewReminders.schedule(
                                context,
                                LearningSettings(reminderEnabled = enabled, reminderMinute = minute),
                            )
                        }
                    wasEnabled = enabled
                }
        }
        scope.launch {
            repo.dao.personalAnswers().collect {
                answers = it.associateBy(PersonalAnswer::questionId)
            }
        }
        scope.launch {
            repo.dao.personalMetadata().collect {
                metadata = it.associateBy(QuestionPersonal::questionId)
            }
        }
        scope.launch {
            today.flatMapLatest { repo.dao.dailyCounts(it - 29) }.collect { daily = it }
        }
        scope.launch {
            repo.dao.categoryCounts().collect { categories = it.sortedBy(CategoryCount::rate) }
        }
        scope.launch {
            today.flatMapLatest { repo.dao.dueCounts(it, it + 6) }.collect { upcoming = it }
        }
        scope.launch { repo.dao.learnedDays().collect { learnedDays = it } }
        scope.launch { repo.dao.costs().collect { costs = it } }
        scope.launch { repo.dao.totals().collect { totals = it } }
    }

    fun refreshDate(date: LocalDate) {
        today.value = date.toEpochDay()
    }

    fun retryTodaySummary() {
        summaryRevision.value++
    }

    suspend fun updateSettings(transform: (LearningSettings) -> LearningSettings) {
        repo.db.withTransaction {
            val value = transform(repo.dao.getSettings() ?: LearningSettings())
            LearningCodec.parseSettings(LearningCodec.settings(value))
            repo.dao.putSettings(value)
        }
        val value = repo.dao.getSettings() ?: LearningSettings()
        ReviewReminders.schedule(context, value)
    }

    fun personal(q: Question) = answers[q.id]?.takeIf { it.validFor(q) }

    fun canGrade(q: Question) = q.autoGradable || personal(q) != null

    fun canGrade(q: QuestionSummary): Boolean {
        if (q.autoGradable) return true
        val answer = answers[q.id] ?: return false
        val keys = q.optionKeys.split(',').filter { it.isNotBlank() }.toSet()
        return q.objective &&
            keys.size >= 2 &&
            answer.questionHash == q.contentHash &&
            answer.selected.isNotEmpty() &&
            answer.selected.all { it in keys } &&
            (q.kind == Kind.MULTIPLE || answer.selected.size == 1)
    }

    fun answerSource(q: Question) =
        if (personal(q) != null) "个人核对" else if (q.autoGradable) "原题库" else "无可自动核对答案"
}
