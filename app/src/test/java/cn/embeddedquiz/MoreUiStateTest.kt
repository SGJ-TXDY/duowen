package cn.embeddedquiz

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MoreUiStateTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val day = today.toEpochDay()

    private fun summary(id: String, kind: Kind = Kind.SINGLE, review: Boolean = false, keys: String = "A") =
        QuestionSummary(id, "题干 $id", kind, "测试", "", "", "", "", review, keys, keys.isNotBlank(), "h-$id", "A,B")

    private fun month(date: LocalDate, usd: String, unknown: Long = 0, currency: String = "USD") =
        CostMonthUi(date.year, date.monthValue, currency, java.math.BigDecimal(usd), unknown, 0, 0, 1, 0)

    private fun state(
        summary: TodaySummary? = TodaySummary(day, 3, 0, 10, 0),
        questions: List<QuestionSummary> = emptyList(),
        graded: Set<String> = emptySet(),
        months: List<CostMonthUi> = emptyList(),
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
            costMonths = months,
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
        val lastMonth = LocalDate.of(2026, 10, 31)
        val lastYear = LocalDate.of(2025, 11, 5)
        val s =
            state(
                months =
                    listOf(
                        month(today, "0.2"), month(today, "0.12"), month(lastMonth, "5"), month(lastYear, "5"),
                        month(today, "9", currency = "CNY"),
                    )
            )
        assertEquals(0, java.math.BigDecimal("0.32").compareTo(s.monthEstimated))
        assertEquals("本月 $0.32", s.costHint)
        assertNull(state(months = listOf(month(lastMonth, "1"))).costHint)
        assertEquals("本月 <$0.01", state(months = listOf(month(today, "0.001"))).costHint)
    }

    @Test
    fun unknownRequestsAreNeverShownAsFree() {
        assertEquals("本月费用未知", state(months = listOf(month(today, "0", unknown = 2))).costHint)
        assertEquals("本月 $0.32（另 2 次未知）", state(months = listOf(month(today, "0.32", unknown = 2))).costHint)
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
