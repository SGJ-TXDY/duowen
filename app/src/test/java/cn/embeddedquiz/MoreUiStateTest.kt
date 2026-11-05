package cn.embeddedquiz

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MoreUiStateTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val day = today.toEpochDay()

    private fun summary(id: String, kind: Kind = Kind.SINGLE, review: Boolean = false, keys: String = "A") =
        QuestionSummary(id, "题干 $id", kind, "测试", "", "", "", "", review, keys, keys.isNotBlank(), "h-$id", "A,B")

    private fun cost(day: Long, usd: Double) = CostCount(day, "flash", 0, 0, usd)

    private fun state(
        summary: TodaySummary? = TodaySummary(day, 3, 0, 10, 0),
        questions: List<QuestionSummary> = emptyList(),
        graded: Set<String> = emptySet(),
        costs: List<CostCount> = emptyList(),
        progress: List<Progress> = emptyList(),
    ) =
        moreUiState(
            today,
            learnedDays = listOf(day, day - 1, day - 2, day - 5),
            totals = Totals(9, 7, 0, 0, 0, 0),
            progress = progress,
            summary = summary,
            questions = questions,
            canGrade = { it.id in graded },
            costs = costs,
            daily = listOf(DailyCount(day, 4, 0, 0, 0, 0, 0, 0, 0)),
            dailyGoal = 20,
        )

    @Test
    fun pendingVerifyMatchesWorkbenchMinusPersonallySettled() {
        val questions =
            listOf(
                summary("ok"),
                summary("flagged", review = true),
                summary("missing", keys = ""),
                summary("settled", review = true),
                summary("short", kind = Kind.SHORT, review = true),
            )
        val s = state(questions = questions, graded = setOf("settled"))
        assertEquals(2, s.pendingVerify)
        assertEquals("2 条", s.verifyHint)
        assertNull(state(questions = listOf(summary("ok"))).verifyHint)
    }

    @Test
    fun monthCostOnlyCountsThisMonthAndHidesWhenZero() {
        val lastMonth = LocalDate.of(2026, 10, 31).toEpochDay()
        val lastYear = LocalDate.of(2025, 11, 5).toEpochDay()
        val s = state(costs = listOf(cost(day, 0.2), cost(day - 4, 0.12), cost(lastMonth, 5.0), cost(lastYear, 5.0)))
        assertEquals(0.32, s.monthUsd, 1e-9)
        assertEquals("本月 $0.32", s.costHint)
        assertNull(state(costs = listOf(cost(lastMonth, 1.0))).costHint)
        assertEquals("本月 <$0.01", state(costs = listOf(cost(day, 0.001))).costHint)
    }

    @Test
    fun dueHintNeedsTodaysSummaryAndAPositiveCount() {
        assertEquals("今日待复习 3", state().dueHint)
        assertNull(state(summary = null).due)
        assertNull(state(summary = null).dueHint)
        // A snapshot from yesterday is not trusted.
        assertNull(state(summary = TodaySummary(day - 1, 3, 0, 10, 0)).dueHint)
        assertNull(state(summary = TodaySummary(day, 0, 0, 10, 0)).dueHint)
    }

    @Test
    fun profileChipsComeFromExistingCounts() {
        val s = state(progress = listOf(Progress("a", wrong = true), Progress("b"), Progress("c", wrong = true)))
        assertEquals(3, s.streak)
        assertEquals(7, s.studied)
        assertEquals(2, s.mistakes)
        assertEquals(4, s.todayDone)
        assertEquals(20, s.dailyGoal)
    }
}
