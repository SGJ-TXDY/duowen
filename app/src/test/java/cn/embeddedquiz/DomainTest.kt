package cn.embeddedquiz

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private fun q(kind: Kind, correct: Set<String>) =
        Question(
            "q",
            "test",
            kind,
            "test",
            options = listOf(Option("A", "a"), Option("B", "b"), Option("C", "c")),
            correct = correct,
            reviewRequired = false,
        )

    @Test
    fun singleAndMultipleAreExactSets() {
        assertEquals(true, Grader.grade(q(Kind.SINGLE, setOf("A")), setOf("A")))
        assertEquals(false, Grader.grade(q(Kind.SINGLE, setOf("A")), emptySet()))
        val multi = q(Kind.MULTIPLE, setOf("A", "B"))
        assertEquals(true, Grader.grade(multi, setOf("B", "A")))
        assertEquals(false, Grader.grade(multi, setOf("A")))
        assertEquals(false, Grader.grade(multi, setOf("A", "B", "C")))
    }

    @Test
    fun judgmentAndUncertainAnswers() {
        assertEquals(true, Grader.grade(q(Kind.TRUE_FALSE, setOf("A")), setOf("A")))
        assertNull(Grader.grade(q(Kind.SINGLE, setOf("A")).copy(reviewRequired = true), setOf("A")))
        assertNull(Grader.grade(q(Kind.SHORT, emptySet()), setOf("A")))
    }

    @Test
    fun datesHandleLeapYearAndYearBoundary() {
        assertEquals(
            LocalDate.of(2024, 2, 29).toEpochDay(),
            Scheduler.next(Schedule(), Feedback.GOOD, LocalDate.of(2024, 2, 28)).due,
        )
        assertEquals(
            LocalDate.of(2027, 1, 1).toEpochDay(),
            Scheduler.next(Schedule(), Feedback.AGAIN, LocalDate.of(2026, 12, 31)).due,
        )
    }

    @Test
    fun successfulReviewsGrowAndLapseResets() {
        val day = LocalDate.of(2026, 10, 21)
        val first = Scheduler.next(Schedule(), Feedback.GOOD, day)
        val second = Scheduler.next(first, Feedback.GOOD, day.plusDays(1))
        val third = Scheduler.next(second, Feedback.EASY, day.plusDays(7))
        assertEquals(1, first.interval)
        assertEquals(6, second.interval)
        assertTrue(third.interval > second.interval)
        val lapse = Scheduler.next(third, Feedback.AGAIN, day.plusDays(8))
        assertEquals(0, lapse.repetitions)
        assertEquals(1, lapse.interval)
        assertEquals(1, lapse.lapses)
        var state = Schedule()
        repeat(100) { state = Scheduler.next(state, Feedback.AGAIN, day) }
        assertEquals(1.3, state.ease, 0.001)
    }

    @Test
    fun programmingNeverDaily() {
        assertFalse(q(Kind.PROGRAMMING, emptySet()).daily)
        assertTrue(q(Kind.CODE_READING, emptySet()).daily)
    }
}
