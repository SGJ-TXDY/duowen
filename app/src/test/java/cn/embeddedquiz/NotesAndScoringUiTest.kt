package cn.embeddedquiz

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotesAndScoringUiTest {
    @get:Rule val ui = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"ui-choice","stem":"选择测试题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"选项甲"},{"key":"B","text":"选项乙"}],"correct":["A"],"reviewRequired":false},
        {"id":"ui-short","stem":"简答测试题","source":"UI test","kind":"SHORT","reviewRequired":true},
        {"id":"ui-pending","stem":"待核对测试题","source":"UI test","kind":"SINGLE","reviewRequired":true,"options":[{"key":"A","text":"选项甲"},{"key":"B","text":"选项乙"}]},
        {"id":"ui-code","stem":"编程草稿测试题","source":"UI test","kind":"PROGRAMMING","reviewRequired":true}]}
    """

    @Before
    fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        // Saving settings reschedules reminders; without this the test depended on class order.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        ui.runOnUiThread {
            vm =
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
                    QuizViewModel::class.java]
        }
        runBlocking { vm.repo.importBank(bank) }
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.ready || vm.questions.none { it.id == "ui-choice" }) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
        ui.setContent { QuizApp(vm) }
    }

    @After
    fun cleanup() {
        ui.runOnUiThread { store.clear() }
        app.deleteDatabase("embeddedquiz.db")
    }

    private fun start(id: String) =
        ui.runOnIdle { vm.start(listOf(vm.questions.first { it.id == id }), "功能验证") }

    private fun await(condition: () -> Boolean) {
        try {
            ui.waitUntil(20000) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Throwable) {
            throw AssertionError(
                "notice=${vm.notice}; goal=${vm.learning.settings.dailyGoal}; page=${vm.page}; noteError=${vm.noteSaveError}; writes=${vm.pendingNoteWrites}; busy=${vm.busy}",
                e,
            )
        }
    }

    private fun waitForRecord() {
        await { vm.attempts.isNotEmpty() && !vm.busy }
    }

    private fun questionNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // Full JSON is loaded on demand; preserve existing assertions and await that boundary.
        await {
            ui.onAllNodes(
                    hasTestTag("question-list") and
                        hasContentDescription("题目${vm.queue.getOrNull(vm.index)}")
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // The top bar and action bar sit outside the scrolling list.
        val inList = hasAnyAncestor(hasTestTag("question-list"))
        if (ui.onAllNodes(matcher and !inList).fetchSemanticsNodes().isEmpty())
            ui.onNodeWithTag("question-list").performScrollToNode(matcher)
        return ui.onNode(matcher)
    }

    private fun questionText(text: String) = questionNode(hasText(text))

    private fun option(text: String) = questionNode(hasText(text) and hasClickAction())

    private fun navButton(label: String) =
        questionNode(hasContentDescription(label) or hasText(label))

    @Test
    fun choiceSurvivesNotesAndSubmissionRecordsExactlyOnce() {
        start("ui-choice")
        option("选项乙").performClick()
        navButton("打开全局标签").performClick()
        ui.onNodeWithText("新建便签").performClick()
        ui.onNodeWithText("便签标题").performTextInput("复习要点")
        ui.onNodeWithText("记下思路、易错点或待办").performTextInput("检查指针的生命周期")
        ui.onNodeWithText("继续答题").performClick()
        questionNode(hasText("选项乙") and isSelected()).assertExists()
        questionText("提交答案").performClick()
        waitForRecord()
        questionText("回答错误").assertExists()
        ui.onNodeWithText("这次答得怎么样？").assertDoesNotExist()
        ui.onNodeWithText("提交答案").assertDoesNotExist()
        assertEquals(1, vm.attempts.size)
        assertEquals(false, vm.attempts.single().correct)
        assertEquals("AGAIN", vm.attempts.single().feedback)
        // Returning again must retain the submitted state rather than permit another record.
        ui.runOnIdle { vm.openStickyNotes() }
        ui.onNodeWithText("记下思路、易错点或待办").assertTextContains("检查指针的生命周期")
        ui.onNodeWithText("继续答题").performClick()
        questionText("回答错误").assertExists()
        assertEquals(1, vm.attempts.size)
        await { vm.pendingNoteWrites == 0 && vm.stickyNotes.any { it.title == "复习要点" } }
        assertEquals("检查指针的生命周期", vm.stickyNotes.single().content)
    }

    @Test
    fun textDraftAndManualAssessmentSurviveNotesAndQuestionNavigation() {
        ui.runOnIdle {
            vm.start(
                listOf(
                    vm.questions.first { it.id == "ui-short" },
                    vm.questions.first { it.id == "ui-choice" },
                ),
                "功能验证",
            )
        }
        questionText("先写下自己的回答").performTextInput("尚未完成的回答")
        ui.runOnIdle { vm.openStickyNotes() }
        ui.onNodeWithText("继续答题").performClick()
        questionText("先写下自己的回答").assertTextContains("尚未完成的回答")
        navButton("下一题").performClick()
        navButton("上一题").performClick()
        questionText("先写下自己的回答").assertTextContains("尚未完成的回答")
        questionText("查看参考答案").performClick()
        questionText("不会").performClick()
        waitForRecord()
        assertEquals("UNKNOWN", vm.attempts.single().assessment)
        assertEquals("尚未完成的回答", vm.attempts.single().response)
        assertTrue(vm.progress["ui-short"]!!.wrong)
        questionText("自评：不会").assertExists()
    }

    @Test
    fun correctAnswerIsRecordedWithoutExtraFeedback() {
        start("ui-choice")
        option("选项甲").performClick()
        questionText("提交答案").performClick()
        waitForRecord()
        assertEquals(true, vm.attempts.single().correct)
        assertEquals("GOOD", vm.attempts.single().feedback)
        assertEquals("", vm.attempts.single().assessment)
        questionText("回答正确").assertExists()
        ui.onNodeWithText("简单").assertDoesNotExist()
        ui.onNodeWithText("困难").assertDoesNotExist()
        ui.onNodeWithText("记住了").assertDoesNotExist()
    }

    @Test
    fun examExpiresAndRecordsWhileNotesAreOpen() {
        ui.runOnIdle {
            vm.startExam(listOf(vm.questions.first { it.id == "ui-choice" }), 1, 1)
            vm.answerExam("ui-choice", setOf("A"))
            vm.openStickyNotes()
            vm.examDeadline = System.currentTimeMillis() - 1
        }
        await { vm.examFinished && !vm.busy }
        assertEquals(Page.NOTES, vm.page)
        assertEquals(1, vm.attempts.size)
        assertEquals(true, vm.attempts.single().correct)
        ui.onNodeWithText("继续答题").performClick()
        questionText("答对 1 / 1，得分 100").assertExists()
    }

    @Test
    fun rapidNoteEditsAreSavedInOrderAndDeletionWins() {
        ui.runOnIdle {
            vm.openStickyNotes()
            vm.newStickyNote()
            repeat(60) { vm.updateStickyNote("标题", "第 $it 版") }
        }
        await { vm.pendingNoteWrites == 0 && vm.stickyNotes.singleOrNull()?.content == "第 59 版" }
        ui.runOnIdle {
            val note = requireNotNull(vm.noteDraft)
            vm.updateStickyNote(note.title, "最后修改")
            vm.deleteStickyNote(requireNotNull(vm.noteDraft))
        }
        await { vm.pendingNoteWrites == 0 && vm.stickyNotes.isEmpty() }
        assertNull(vm.noteDraft)
        assertEquals("", vm.noteSaveError)
    }

    @Test
    fun bottomNavigationAndDarkModeAndGoalPersist() {
        ui.onNodeWithContentDescription("导航到更多").performClick()
        ui.onNodeWithTag("more-list").performScrollToNode(hasText("设置"))
        ui.onNodeWithText("设置").performClick()
        // 设置 › 外观 (PLAN-1.7.5): the theme is one tap in its group.
        ui.onNode(hasText("外观") and hasClickAction()).performClick()
        await { vm.page == Page.SETTINGS_APPEARANCE }
        ui.onNode(hasText("深色") and hasAnyAncestor(hasTestTag("theme-choices"))).performClick()
        await { vm.learning.settings.theme == "DARK" && !vm.busy }
        val topBack = hasContentDescription("返回") and hasAnyAncestor(hasTestTag("sub-page-bar"))
        ui.onNode(topBack).performClick()
        await { vm.page == Page.SETTINGS }
        ui.onNode(hasText("复习设置") and hasClickAction()).performClick()
        await { vm.page == Page.SETTINGS_REVIEW }
        ui.onNodeWithText("每日目标（1–1000 题）").performTextReplacement("35")
        ui.onNodeWithText("保存每日目标").performClick()
        await { vm.learning.settings.dailyGoal == 35 && !vm.busy }
        // Sections return to 设置, and 设置 (a 更多 sub-page without tabs) returns to 更多.
        ui.onNode(topBack).performClick()
        await { vm.page == Page.SETTINGS }
        ui.onNode(hasText("每日 35 题 · 提醒关闭", substring = true)).assertExists()
        ui.onNode(topBack).performClick()
        ui.waitForIdle()
        assertEquals(Page.MORE, vm.page)
        ui.onNodeWithContentDescription("导航到首页").performClick()
        ui.onNodeWithContentDescription("今日目标 0 / 35 题").assertExists()
        assertEquals("DARK", runBlocking { vm.repo.dao.getSettings()!!.theme })
        ui.runOnIdle { vm.page = Page.STATISTICS }
        ui.onNodeWithTag("statistics-list").performScrollToNode(hasText("近 30 天答题量与正确率"))
        ui.onNodeWithText("近 30 天答题量与正确率").assertExists()
    }

    @Test
    fun personalWorkbenchAnswerEnablesAutomaticGradingWithoutChangingQuestion() {
        ui.runOnIdle { vm.page = Page.WORKBENCH }
        ui.onNodeWithTag("workbench-list").performScrollToNode(hasText("待核对测试题"))
        ui.onNodeWithText("待核对测试题").performClick()
        await { ui.onAllNodesWithTag("workbench-editor").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("workbench-editor").performScrollToNode(hasText("□ 选项乙"))
        ui.onNodeWithText("□ 选项乙").performClick()
        ui.onNodeWithTag("workbench-editor").performScrollToNode(hasText("保存个人核对答案"))
        ui.onNodeWithText("保存个人核对答案").performClick()
        await { vm.learning.answers.containsKey("ui-pending") && !vm.busy }
        // The confirmation snackbar is transient; let it close before tapping underneath it.
        await { vm.notice.isEmpty() }
        // Tags are global now: the workbench only keeps the per-question difficulty.
        ui.onNodeWithText("自定义标签（逗号分隔）").assertDoesNotExist()
        val medium = hasText("中") and hasClickAction()
        ui.onNodeWithTag("workbench-editor").performScrollToNode(medium)
        ui.onNode(medium).performClick()
        await { vm.learning.metadata["ui-pending"]?.perceivedDifficulty == "中" && !vm.busy }
        await { vm.notice.isEmpty() }
        start("ui-pending")
        option("选项乙").performClick()
        questionText("提交答案").performClick()
        waitForRecord()
        assertEquals("PERSONAL", vm.attempts.single().answerSource)
        assertEquals(true, vm.attempts.single().correct)
        val original = runBlocking { vm.repo.question("ui-pending")!! }
        assertTrue(original.reviewRequired)
        assertTrue(original.correct.isEmpty())
    }

    @Test
    fun codeDraftAutoSavesIndentAndSurvivesNotesRoundTrip() {
        start("ui-code")
        questionText("我的代码（自动保存草稿）").assertExists()
        ui.onNodeWithTag("code-editor").performTextInput("int value;")
        questionText("Tab 缩进").performClick()
        await {
            vm.codeDrafts.writer.pending == 0 && vm.progress["ui-code"]?.code == "int value;    "
        }
        ui.runOnIdle { vm.openStickyNotes() }
        ui.onNodeWithText("继续答题").performClick()
        questionText("我的代码（自动保存草稿）").assertExists()
        ui.onNodeWithTag("code-editor").assertTextContains("int value;    ")
    }
}
