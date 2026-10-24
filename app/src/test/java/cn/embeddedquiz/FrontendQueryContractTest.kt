package cn.embeddedquiz

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 只验证业务契约，不挂载或修改 Compose 页面。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FrontendQueryContractTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private var vm: QuizViewModel? = null
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"a","stem":"pointer lifetime 100%","source":"测试","category":"1 C语言基础与内存管理","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"stack"},{"key":"B","text":"heap"}],"correct":["A"]},
        {"id":"b","stem":"边界条件简答","source":"测试","kind":"SHORT"},
        {"id":"c","stem":"pointer 第二题","source":"另一来源","kind":"SHORT"},
        {"id":"code","stem":"pointer 编程","source":"测试","kind":"PROGRAMMING"}]}
        """

    @Before
    fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings", 0).edit().clear().commit()
    }

    @After
    fun cleanup() {
        store.clear()
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings", 0).edit().clear().commit()
    }

    private fun model(): QuizViewModel {
        runBlocking {
            val db = QuizDatabase.open(app)
            try {
                QuizRepository(db).importBank(bank)
            } finally {
                db.close()
            }
        }
        app.getSharedPreferences("settings", 0).edit().putInt("bankRevision", 4).commit()
        val value =
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
                QuizViewModel::class.java]
        vm = value
        await { value.ready && value.questions.size == 4 && value.todaySummary != null }
        return value
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("等待业务状态超时：${vm?.notice}", condition())
    }

    @Test
    fun emptyBankIsSuccessfulZeroSummary() = runBlocking {
        val db = QuizDatabase.open(app)
        try {
            val day = LocalDate.of(2024, 2, 29).toEpochDay()
            assertEquals(TodaySummary(day, 0, 0, 0, 0), db.dao().todaySummary(day).first())
        } finally {
            db.close()
        }
    }

    @Test
    fun summaryCountsMatchExistingRulesAcrossLeapDayMonthAndYear() = runBlocking {
        val db = QuizDatabase.open(app)
        try {
            val repo = QuizRepository(db)
            repo.importBank(bank)
            val leapDay = LocalDate.of(2024, 2, 29).toEpochDay()
            repo.dao.putProgress(Progress("a", seen = 2, due = leapDay - 1))
            repo.dao.putProgress(Progress("b", seen = 1, due = leapDay + 1))
            // 未学、编程及悬空进度都不能增加到期数。
            repo.dao.putProgress(Progress("c", seen = 0, due = 0))
            repo.dao.putProgress(Progress("code", seen = 3, due = 0))
            repo.dao.putProgress(Progress("deleted", seen = 3, due = 0))
            assertEquals(
                TodaySummary(leapDay, 1, 1, 3, 1),
                repo.observeTodaySummary(leapDay).first(),
            )
            val march = LocalDate.of(2024, 3, 1).toEpochDay()
            assertEquals(2, repo.observeTodaySummary(march).first().dueCount)
            val yearEnd = LocalDate.of(2026, 12, 31).toEpochDay()
            repo.dao.putProgress(Progress("b", seen = 1, due = yearEnd + 1))
            assertEquals(1, repo.observeTodaySummary(yearEnd).first().dueCount)
            assertEquals(2, repo.observeTodaySummary(yearEnd + 1).first().dueCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun summaryFlowUpdatesAfterImportProgressAndReset() = runBlocking {
        val db = QuizDatabase.open(app)
        val values = Channel<TodaySummary>(Channel.UNLIMITED)
        val repo = QuizRepository(db)
        val day = LocalDate.of(2026, 10, 24).toEpochDay()
        val collector =
            launch(Dispatchers.IO) { repo.observeTodaySummary(day).collect { values.send(it) } }
        suspend fun next(predicate: (TodaySummary) -> Boolean): TodaySummary =
            withTimeout(10000) {
                var value = values.receive()
                while (!predicate(value)) value = values.receive()
                value
            }
        try {
            assertEquals(0, next { true }.dailyCount)
            repo.importBank(bank)
            assertEquals(3, next { it.dailyCount == 3 }.unseenCount)
            repo.dao.putProgress(Progress("a", seen = 1, due = day))
            assertEquals(2, next { it.dueCount == 1 }.unseenCount)
            repo.reset()
            assertEquals(3, next { it.dueCount == 0 && it.unseenCount == 3 }.dailyCount)
        } finally {
            collector.cancelAndJoin()
            values.close()
            db.close()
        }
    }

    @Test
    fun viewModelSearchPreservesFiltersSnippetsAndPageSemantics() {
        val model = model()
        runBlocking {
            model.repo.update("a") { it.copy(notes = "生命周期", favorite = true) }
            model.repo.record(model.repo.question("a")!!, "B", setOf("B"), "契约测试")
            model.repo.dao.putPersonalMetadata(QuestionPersonal("a", tags = "嵌入式"))
            model.repo.dao.putStickyNote(StickyNote("memo", "指针 栈", "生命周期", 1, 2))
            val filter = BrowseFilter(search = "pointer 生命周期 嵌入式", source = "测试")
            val matches = model.searchQuestions(filter, Page.BROWSE)
            assertEquals(listOf("a"), matches.map { it.id })
            assertTrue(matches.single().matchSnippet.isNotBlank())
            assertEquals(model.repo.search(filter, false, false), matches)
            assertEquals(
                listOf("code"),
                model.searchQuestions(BrowseFilter(), Page.PROGRAMMING).map { it.id },
            )
            assertEquals(
                listOf("a"),
                model.searchQuestions(BrowseFilter(), Page.MISTAKES).map { it.id },
            )
            assertEquals(
                listOf("a"),
                model.searchQuestions(BrowseFilter(group = "收藏"), Page.MISTAKES).map { it.id },
            )
            // 模拟考试页展示日常题全集，开始考试时才筛选可判分题，保持旧接口行为。
            assertEquals(
                listOf("a", "b", "c"),
                model.searchQuestions(BrowseFilter(), Page.EXAM).map { it.id },
            )
            assertEquals("memo", model.searchNotes("栈 生命周期").single().id)
            assertTrue(
                model.searchQuestions(BrowseFilter(search = "pointer 不存在"), Page.BROWSE).isEmpty()
            )
            assertTrue(
                model.searchQuestions(BrowseFilter(search = "' OR 1=1"), Page.BROWSE).isEmpty()
            )
        }
    }

    @Test
    fun searchRejectsUnsupportedPagesAndPropagatesCancellationWithoutChangingState() {
        val model = model()
        val before = Triple(model.page, model.busy, model.notice)
        val filters = model.learning.filters.toMap()
        runBlocking {
            val error =
                runCatching { model.searchQuestions(BrowseFilter(), Page.HOME) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            val cancelled = Job().apply { cancel() }
            val cancellation =
                runCatching {
                        withContext(cancelled) {
                            model.searchQuestions(BrowseFilter(), Page.BROWSE)
                        }
                    }
                    .exceptionOrNull()
            assertTrue(cancellation is CancellationException)
        }
        assertEquals(before, Triple(model.page, model.busy, model.notice))
        assertEquals(filters, model.learning.filters.toMap())
    }

    @Test
    fun viewModelSummaryRefreshAndCurrentQuestionSurviveNotesRoundTrip() {
        val model = model()
        assertNull(model.currentQuestionSummary)
        val date = LocalDate.of(2024, 2, 29)
        runBlocking {
            model.repo.dao.putProgress(Progress("a", seen = 1, due = date.toEpochDay() + 1))
        }
        model.learning.refreshDate(date)
        await { model.todaySummary?.day == date.toEpochDay() }
        assertEquals(0, model.todaySummary!!.dueCount)
        model.learning.refreshDate(date.plusDays(1))
        await {
            model.todaySummary?.day == date.toEpochDay() + 1 && model.todaySummary?.dueCount == 1
        }
        assertFalse(model.todaySummaryState.loading)
        assertEquals("", model.todaySummaryState.error)
        model.retryTodaySummary()
        await {
            !model.todaySummaryState.loading && model.todaySummary?.day == date.toEpochDay() + 1
        }
        model.start(model.questions.filter { it.daily }, "契约测试")
        assertEquals("a", model.currentQuestionSummary!!.id)
        model.index = 1
        assertEquals("b", model.currentQuestionSummary!!.id)
        model.openStickyNotes()
        assertEquals("b", model.currentQuestionSummary!!.id)
        model.leaveStickyNotes()
        assertEquals(Page.QUESTION, model.page)
        assertEquals("b", model.currentQuestionSummary!!.id)
        model.index = 100
        assertNull(model.currentQuestionSummary)
        model.index = 0
        model.queue = listOf("deleted")
        assertNull(model.currentQuestionSummary)
    }

    @Test
    fun difficultyCommandPublishesChangesAndPreservesUnrelatedFields() {
        val model = model()
        runBlocking {
            model.repo.dao.putPersonalMetadata(QuestionPersonal("a", tags = "旧版标签", verifiedCode = "solution", codeVerified = true))
        }
        model.savePerceivedDifficulty("a", "难")
        await { !model.busy && model.learning.metadata["a"]?.perceivedDifficulty == "难" }
        assertEquals(QuestionPersonal("a", "旧版标签", "难", "solution", true), model.learning.metadata["a"])
        model.savePerceivedDifficulty("a", "无效")
        await { !model.busy }
        assertEquals("难", model.learning.metadata["a"]!!.perceivedDifficulty)
        assertTrue(model.notice.startsWith("操作未完成"))
    }

    @Test
    fun relocatedEntryTargetsPreservePracticeDraftAndActiveExam() {
        val model = model()
        model.start(model.questions.filter { it.daily }, "顺序练习")
        val question = runBlocking { model.loadQuestion("a")!! }
        val draft = model.questionDraft(question)
        draft.notes.value = "尚未保存的题目笔记"
        val queue = model.queue
        val session = model.practiceSession
        listOf(Page.BROWSE, Page.PROGRAMMING, Page.EXAM).forEach { page ->
            model.navigate(page)
            assertEquals(page, model.page)
            assertEquals(queue, model.queue)
            assertEquals(session, model.practiceSession)
            assertSame(draft, model.questionDraft(question))
            assertEquals("尚未保存的题目笔记", model.questionDraft(question).notes.value)
        }
        model.startExam(model.questions, 10, 30)
        val examQueue = model.queue
        val deadline = model.examDeadline
        model.navigate(Page.HOME)
        model.navigate(Page.EXAM)
        assertEquals(examQueue, model.queue)
        assertEquals(deadline, model.examDeadline)
        model.start(model.questions.filter { it.daily }, "随机练习")
        assertEquals(Page.QUESTION, model.page)
        assertEquals("模拟考试", model.mode)
        assertEquals(examQueue, model.queue)
        assertEquals(deadline, model.examDeadline)
    }
}
