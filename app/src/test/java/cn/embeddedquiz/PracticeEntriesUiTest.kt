package cn.embeddedquiz

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Home practice entries, the exam tab and the merged notes / difficulty section. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PracticeEntriesUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"pe-one","stem":"第一道选择题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲一"},{"key":"B","text":"乙一"}],"correct":["A"],"reviewRequired":false},
        {"id":"pe-two","stem":"第二道选择题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲二"},{"key":"B","text":"乙二"}],"correct":["B"],"reviewRequired":false},
        {"id":"pe-code","stem":"编程入口测试题","source":"UI test","kind":"PROGRAMMING","reviewRequired":true}]}
    """

    @Before
    fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings", 0).edit().clear().commit()
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
            if (!vm.ready || vm.questions.none { it.id == "pe-two" }) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
        ui.setContent { QuizApp(vm) }
    }

    @After
    fun cleanup() {
        ui.runOnUiThread { store.clear() }
        app.deleteDatabase("embeddedquiz.db")
    }

    private fun await(condition: () -> Boolean) {
        try {
            ui.waitUntil(20000) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Throwable) {
            throw AssertionError(
                "notice=${vm.notice}; page=${vm.page}; busy=${vm.busy}\n${ui.onRoot().printToString()}",
                e,
            )
        }
    }

    private fun back() {
        // The back handler is only enabled once the new page has been composed.
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private fun questionNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        await {
            ui.onAllNodes(
                    hasTestTag("question-list") and
                        hasContentDescription("题目${vm.queue.getOrNull(vm.index)}")
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val inList = hasAnyAncestor(hasTestTag("question-list"))
        if (ui.onAllNodes(matcher and !inList).fetchSemanticsNodes().isEmpty())
            try {
                ui.onNodeWithTag("question-list").performScrollToNode(matcher)
            } catch (e: AssertionError) {
                throw AssertionError(ui.onRoot().printToString(), e)
            }
        return ui.onNode(matcher)
    }

    private fun questionText(text: String) = questionNode(hasText(text))

    /** A save confirmation snackbar overlays the bottom of the list, where scrolling leaves targets. */
    private fun dismissSnackbar() {
        ui.onAllNodesWithText("关闭").fetchSemanticsNodes().forEach { _ ->
            ui.onAllNodesWithText("关闭")[0].performClick()
        }
        ui.waitForIdle()
    }

    private fun expandNotes() {
        dismissSnackbar()
        questionNode(hasContentDescription("展开笔记与难度感受")).performClick()
        await {
            ui.onAllNodesWithContentDescription("收起笔记与难度感受").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun homeEntry(text: String): SemanticsNodeInteraction {
        ui.onNodeWithTag("home-list").performScrollToNode(hasText(text))
        return ui.onNode(hasText(text) and hasAnyAncestor(hasTestTag("home-list")))
    }

    @Test
    fun homeEntriesOpenBanksAndTabBarOffersExam() {
        ui.onNodeWithContentDescription("导航到模拟考试").assertExists()
        ui.onNodeWithContentDescription("导航到刷题").assertDoesNotExist()

        homeEntry("分类练习").performClick()
        await { vm.page == Page.BROWSE }
        back()
        assertEquals(Page.HOME, vm.page)

        homeEntry("编程专区").performClick()
        await { vm.page == Page.PROGRAMMING }
        back()
        assertEquals(Page.HOME, vm.page)

        ui.onNodeWithContentDescription("导航到更多").performClick()
        val inMore = hasAnyAncestor(hasTestTag("more-list"))
        ui.onNode(hasText("全局标签 / 便签") and inMore).assertExists()
        listOf("分类", "编程专区", "模拟考试").forEach {
            ui.onNode(hasText(it) and inMore).assertDoesNotExist()
        }
    }

    @Test
    fun morePageShowsHintsOnlyWhenThereIsSomethingToDo() {
        val inMore = hasAnyAncestor(hasTestTag("more-list"))
        ui.onNodeWithContentDescription("导航到更多").performClick()
        await { vm.page == Page.MORE }
        ui.onNode(hasText("智能复习") and inMore).assertExists()
        ui.onNode(hasText("今日待复习", substring = true) and inMore).assertDoesNotExist()
        ui.onNode(hasText("本月", substring = true) and inMore).assertDoesNotExist()
        ui.onNode(hasText("错题 0") and inMore).assertExists()
        val dot = hasContentDescription("有待处理")
        ui.onNode(hasText("智能复习") and inMore).assert(!dot)
        // The bundled bank may also be loaded, so the workbench count is checked against the data.
        val pending =
            vm.questions.count {
                it.objective && (it.reviewRequired || it.correctKeys.isBlank()) && !vm.learning.canGrade(it)
            }
        val workbench = ui.onNode(hasText("待核对工作台") and inMore)
        if (pending > 0) workbench.assert(hasText("$pending 条") and dot)
        else workbench.assert(!dot)

        val today = java.time.LocalDate.now().toEpochDay()
        runBlocking {
            vm.repo.dao.putProgress(Progress("pe-one", seen = 1, due = today, wrong = true))
        }
        await {
            ui.onAllNodes(hasText("今日待复习 1") and inMore).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNode(hasText("错题 1") and inMore).assertExists()
        ui.onNode(hasText("智能复习") and inMore).assert(dot)

        // Tapping the mascot plays its jump and never leaves the page.
        ui.onNodeWithContentDescription("吉祥物").assertHasClickAction().performClick()
        ui.waitForIdle()
        assertEquals(Page.MORE, vm.page)

        ui.onNode(hasText("历史统计") and inMore).performClick()
        await { vm.page == Page.STATISTICS }
        back()
        assertEquals(Page.MORE, vm.page)
    }

    @Test
    fun examTabResumesUnfinishedExamInsteadOfRestarting() {
        ui.runOnIdle {
            vm.startExam(vm.questions.filter { it.id.startsWith("pe-") }, 2, 30)
            vm.page = Page.HOME
        }
        val deadline = vm.examDeadline
        val queue = vm.queue
        ui.onNodeWithContentDescription("导航到模拟考试").performClick()
        await { vm.page == Page.EXAM }
        ui.onNodeWithText("开始考试").assertDoesNotExist()
        ui.onNodeWithText("继续考试").performClick()
        await { vm.page == Page.QUESTION }
        assertEquals(deadline, vm.examDeadline)
        assertEquals(queue, vm.queue)
        assertFalse(vm.examFinished)
    }

    @Test
    fun savingVerifiedSolutionKeepsLatestDifficulty() {
        ui.runOnIdle { vm.start(listOf(vm.questions.first { it.id == "pe-code" }), "编程专区") }
        questionText("编辑个人参考解法").performClick()
        // Change difficulty after the editor has read its snapshot; the solution save must not undo it.
        ui.runOnIdle { vm.savePerceivedDifficulty("pe-code", "难") }
        await { vm.learning.metadata["pe-code"]?.perceivedDifficulty == "难" && !vm.busy }
        questionText("我已自行核对此解法")
        // The solution editor is the last one; the work area editor may already be scrolled away.
        val editors = ui.onAllNodesWithTag("code-editor")
        editors[editors.fetchSemanticsNodes().lastIndex].performTextInput("int ok;")
        dismissSnackbar()
        // The focused editor keeps pulling itself into view in the small test window, so use the
        // click action rather than tapping coordinates that may sit below the viewport.
        questionText("保存个人参考解法").performSemanticsAction(SemanticsActions.OnClick)
        await { vm.notice.isNotEmpty() && !vm.busy }
        assertEquals("个人参考解法已保存", vm.notice)
        await { vm.learning.metadata["pe-code"]?.verifiedCode == "int ok;" }
        assertEquals("难", vm.learning.metadata["pe-code"]?.perceivedDifficulty)
    }

    @Test
    fun notesAndDifficultyShareOneSectionAndStayPerQuestion() {
        ui.runOnIdle {
            // The bundled bank is loaded too, so name the two questions explicitly.
            vm.start(listOf("pe-two", "pe-one").map { id -> vm.questions.first { it.id == id } }, "功能验证")
        }
        expandNotes()
        ui.onNodeWithText("标签与难度感受").assertDoesNotExist()
        ui.onNodeWithText("自定义标签（逗号分隔）").assertDoesNotExist()
        questionNode(hasText("难") and hasClickAction()).performClick()
        await { vm.learning.metadata["pe-two"]?.perceivedDifficulty == "难" && !vm.busy }
        questionText("个人笔记（手动保存）").performTextInput("第二题笔记")
        questionText("保存笔记").performClick()
        await { !vm.busy }

        questionNode(hasContentDescription("下一题")).performClick()
        expandNotes()
        questionNode(hasText("未设置") and isSelected()).assertExists()
        questionText("个人笔记（手动保存）").assertExists()
        ui.onAllNodesWithText("第二题笔记", substring = true).assertCountEquals(0)
        assertTrue(vm.learning.metadata["pe-one"]?.perceivedDifficulty.isNullOrBlank())

        // The top bar opens the one global list; leaving it restores the same question.
        questionNode(hasContentDescription("打开全局标签")).performClick()
        await { vm.page == Page.NOTES }
        ui.onNodeWithText("全局标签").assertExists()
        ui.onNodeWithText("继续答题").performClick()
        await { vm.page == Page.QUESTION }
        assertEquals("pe-one", vm.queue[vm.index])

        questionNode(hasContentDescription("上一题")).performClick()
        expandNotes()
        questionNode(hasText("难") and isSelected()).assertExists()
        questionText("个人笔记（手动保存）").assertTextContains("第二题笔记")
    }
}
