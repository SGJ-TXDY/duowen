package cn.embeddedquiz

import android.content.Context
import androidx.room.*
import java.time.*
import org.json.JSONObject

enum class ThemeChoice(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class FontChoice(val label: String, val scale: Float) {
    SMALL("小", 0.9f),
    MEDIUM("中", 1f),
    LARGE("大", 1.2f),
}

@Entity(tableName = "learning_settings")
data class LearningSettings(
    @PrimaryKey val id: Int = 1,
    val dailyGoal: Int = 20,
    val theme: String = "SYSTEM",
    val fontSize: String = "MEDIUM",
    val reminderEnabled: Boolean = false,
    val reminderMinute: Int = 20 * 60,
    val notificationPermissionAsked: Boolean = false,
    val lastNotifiedDay: Long = -1,
)

@Entity(tableName = "personal_answers")
data class PersonalAnswer(
    @PrimaryKey val questionId: String,
    val choices: String,
    val notes: String,
    val questionHash: String,
    val updated: Long,
) {
    val selected
        get() = choices.split(',').filter { it.isNotBlank() }.toSet()

    fun validFor(q: Question) =
        q.objective &&
            q.options.size >= 2 &&
            questionHash == q.contentHash &&
            selected.isNotEmpty() &&
            selected.all { key -> q.options.any { it.key == key } } &&
            (q.kind == Kind.MULTIPLE || selected.size == 1)
}

@Entity(tableName = "question_personal")
data class QuestionPersonal(
    @PrimaryKey val questionId: String,
    val tags: String = "",
    val perceivedDifficulty: String = "",
    val verifiedCode: String = "",
    val codeVerified: Boolean = false,
)

data class QuestionSummary(
    val id: String,
    val stem: String,
    val kind: Kind,
    val source: String,
    val category: String,
    val normalizedCategory: String,
    val company: String,
    val difficulty: String,
    val reviewRequired: Boolean,
    val correctKeys: String,
    val hasAnswer: Boolean,
    val contentHash: String,
    val optionKeys: String,
    val matchSnippet: String = "",
) {
    val daily
        get() = kind != Kind.PROGRAMMING

    val objective
        get() = kind in setOf(Kind.SINGLE, Kind.MULTIPLE, Kind.TRUE_FALSE)

    val autoGradable
        get() = objective && !reviewRequired && correctKeys.isNotBlank()
}

data class CategoryRow(val id: String, val category: String)

object CategoryMap {
    private var mappings: Map<String, String> = emptyMap()
    var fingerprint: String = ""
        private set

    @Synchronized
    fun load(context: Context) {
        val text = context.assets.open("category-map.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        fingerprint = DeepSeekClient.hash(text)
        val values = root.getJSONObject("mapping")
        mappings = values.keys().asSequence().associateWith { values.getString(it) }
    }

    fun normalize(raw: String): String =
        mappings[raw] ?: raw.replace(Regex("^\\s*\\d+[.、\\s]*"), "").ifBlank { "未分类" }
}

object QuestionIndex {
    fun row(q: Question, json: String) =
        QuestionRow(
            q.id,
            json,
            q.kind.name,
            q.source,
            q.category,
            CategoryMap.normalize(q.category),
            q.company,
            q.difficulty,
            q.reviewRequired,
            q.stem.take(180),
            q.correct.sorted().joinToString(","),
            listOf(
                    q.stem,
                    q.options.joinToString(" ") { it.text },
                    q.answer,
                    q.explanation,
                    q.referenceCode,
                )
                .joinToString(" "),
            q.answer.isNotBlank() || q.correct.isNotEmpty(),
            q.contentHash,
            q.options.joinToString(",") { it.key },
        )
}

data class DailyCount(
    val day: Long,
    val answered: Int,
    val automatic: Int,
    val autoCorrect: Int,
    val manual: Int,
    val manualCorrect: Int,
    val ungraded: Int,
    val newAnswered: Int,
    val reviewAnswered: Int,
)

data class CategoryCount(
    val category: String,
    val answered: Int,
    val automatic: Int,
    val autoCorrect: Int,
    val manual: Int,
    val manualCorrect: Int,
    val mastered: Int,
) {
    val rate
        get() =
            if (automatic + manual == 0) 0.0
            else (autoCorrect + manualCorrect).toDouble() / (automatic + manual)
}

data class DueCount(val day: Long, val count: Int)

data class CostCount(
    val day: Long,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedUsd: Double,
)

data class Totals(
    val attempts: Int,
    val studied: Int,
    val automatic: Int,
    val autoCorrect: Int,
    val manual: Int,
    val manualCorrect: Int,
)

object LearningDates {
    fun streak(days: Collection<Long>, today: LocalDate): Int {
        val learned = days.toSet()
        var day = today.toEpochDay()
        if (day !in learned) day--
        var count = 0
        while (day in learned) {
            count++
            day--
        }
        return count
    }

    fun nextReminder(now: ZonedDateTime, minute: Int): ZonedDateTime {
        require(minute in 0..1439)
        var next = now.toLocalDate().atTime(minute / 60, minute % 60).atZone(now.zone)
        if (!next.isAfter(now))
            next = now.toLocalDate().plusDays(1).atTime(minute / 60, minute % 60).atZone(now.zone)
        return next
    }
}

data class BrowseFilter(
    val search: String = "",
    val source: String = "全部",
    val company: String = "全部",
    val category: String = "全部",
    val kind: String = "全部",
    val difficulty: String = "全部",
    val group: String = "错题",
)

object SearchTerms {
    fun split(text: String) =
        text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct().take(16)

    fun like(term: String) =
        "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
