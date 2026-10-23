package cn.embeddedquiz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class LearningDatesAndReadingTest {
    @Test
    fun streakUsesLocalDaysAcrossLeapDayAndMonthBoundary() {
        val dates =
            listOf(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1))
        assertEquals(3, LearningDates.streak(dates.map(LocalDate::toEpochDay), dates.last()))
        assertEquals(
            3,
            LearningDates.streak(dates.map(LocalDate::toEpochDay), LocalDate.of(2024, 3, 2)),
        )
        assertEquals(
            0,
            LearningDates.streak(dates.map(LocalDate::toEpochDay), LocalDate.of(2024, 3, 3)),
        )
    }

    @Test
    fun streakHandlesYearBoundaryAndDuplicateAttempts() {
        val today = LocalDate.of(2027, 1, 1)
        val days = listOf(today.minusDays(1).toEpochDay(), today.toEpochDay(), today.toEpochDay())
        assertEquals(2, LearningDates.streak(days, today))
        assertEquals(0, LearningDates.streak(emptyList(), today))
    }

    @Test
    fun reminderFollowsWallClockAcrossDstAndMidnight() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2024, 3, 9, 21, 0, 0, 0, zone)
        val next = LearningDates.nextReminder(now, 8 * 60 + 30)
        assertEquals(LocalDate.of(2024, 3, 10), next.toLocalDate())
        assertEquals(LocalTime.of(8, 30), next.toLocalTime())
        assertEquals(ZoneOffset.ofHours(-4), next.offset)
        val midnight =
            LearningDates.nextReminder(
                ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, ZoneId.of("Asia/Tokyo")),
                0,
            )
        assertEquals(LocalDate.of(2027, 1, 1), midnight.toLocalDate())
    }

    @Test
    fun sourceReadingKeepsOriginalWhitespaceAndOnlyIndentUsesCodeStyle() {
        val original = "题干正文\n    int value = 1;\n\treturn value;\n\n继续解释"
        val blocks = ReadingBlocks.parse(original)
        assertEquals(original, blocks.joinToString("") { it.text })
        assertFalse(blocks.first().code)
        assertTrue(blocks.any { it.code && it.text.contains("    int") })
        assertFalse(blocks.last().code)
    }

    @Test
    fun markdownIsLocalAndPreservesFencedCode() {
        val text = "# 标题\n- **重点**和`指针`\n```c\n    int x;\n\treturn x;\n```"
        val blocks = SimpleMarkdown.parse(text)
        assertTrue(blocks.first() is MarkdownBlock.Text)
        assertEquals("    int x;\n\treturn x;", (blocks.last() as MarkdownBlock.Code).value)
        assertEquals("重点和指针", SimpleMarkdown.inline("**重点**和`指针`").text)
        assertTrue(SimpleMarkdown.parse("```\nx").last() is MarkdownBlock.Code)
    }

    @Test
    fun lightAndDarkTextAndVerificationColorsMeetContrast() {
        fun contrast(a: Color, b: Color): Double {
            val x = a.luminance().toDouble()
            val y = b.luminance().toDouble()
            return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
        }
        for (dark in listOf(false, true)) {
            val colors = appColors(dark)
            assertTrue(contrast(colors.onSurface, colors.surface) >= 4.5)
            assertTrue(contrast(colors.error, colors.background) >= 4.5)
            assertTrue(contrast(colors.onErrorContainer, colors.errorContainer) >= 4.5)
            assertTrue(contrast(colors.onTertiaryContainer, colors.tertiaryContainer) >= 4.5)
        }
    }
}
