package cn.embeddedquiz

import java.time.LocalDate
import kotlin.math.roundToInt

enum class Kind(val label: String) { SINGLE("单选"), MULTIPLE("多选"), TRUE_FALSE("判断"), CODE_READING("代码阅读"), SHORT("简答 / 填空"), PROGRAMMING("编程实现") }
data class Option(val key: String, val text: String)
data class Picture(val name: String, val data: String)
data class Question(
    val id: String, val stem: String, val kind: Kind, val source: String,
    val company: String = "未注明", val category: String = "未分类", val difficulty: String = "未标注",
    val options: List<Option> = emptyList(), val answer: String = "", val correct: Set<String> = emptySet(),
    val explanation: String = "", val reviewRequired: Boolean = true,
    val images: List<Picture> = emptyList(), val examples: String = "", val constraints: String = "",
    val approach: String = "", val referenceCode: String = "", val locator: String = "",
    val duplicateOf: String = "", val raw: String = ""
) {
    val objective get() = kind in setOf(Kind.SINGLE, Kind.MULTIPLE, Kind.TRUE_FALSE)
    val daily get() = kind != Kind.PROGRAMMING
    val autoGradable get() = objective && !reviewRequired && correct.isNotEmpty()
}
object Grader {
    // Exact set matching: extra choices and missing choices are both wrong.
    fun grade(q: Question, selected: Set<String>): Boolean? =
        if (!q.autoGradable) null else selected == q.correct
}
data class Schedule(val repetitions: Int = 0, val interval: Int = 0, val ease: Double = 2.5, val due: Long = 0, val lapses: Int = 0)
enum class Feedback(val label: String, val quality: Int) { AGAIN("忘记了", 1), HARD("困难", 3), GOOD("记住了", 4), EASY("简单", 5) }
enum class Assessment(val label: String) { CORRECT("正确"), WRONG("错误"), UNKNOWN("不会") }
object Scheduler {
    fun next(old: Schedule, feedback: Feedback, today: LocalDate): Schedule {
        val quality = feedback.quality
        val ease = (old.ease + 0.1 - (5-quality)*(0.08+(5-quality)*0.02)).coerceIn(1.3, 3.5)
        if (feedback == Feedback.AGAIN) return Schedule(0, 1, ease, today.plusDays(1).toEpochDay(), old.lapses+1)
        val days = when {
            old.repetitions == 0 -> if (feedback == Feedback.EASY) 4 else 1
            old.repetitions == 1 -> if (feedback == Feedback.HARD) 3 else 6
            else -> (old.interval * ease * if (feedback == Feedback.HARD) 0.8 else if (feedback == Feedback.EASY) 1.3 else 1.0).roundToInt().coerceAtLeast(old.interval+1)
        }.coerceAtMost(3650)
        return Schedule(old.repetitions+1, days, ease, today.plusDays(days.toLong()).toEpochDay(), old.lapses)
    }
}
// Extension point only: no user code is executed on the phone in version 1.
interface JudgeService { suspend fun submit(question: Question, language: String, code: String): JudgeResult }
data class JudgeResult(val status: String, val output: String)
class UnavailableJudge : JudgeService {
    override suspend fun submit(question: Question, language: String, code: String) = JudgeResult("UNAVAILABLE", "第一版不提供在线判题，请自行运行并核对。")
}
