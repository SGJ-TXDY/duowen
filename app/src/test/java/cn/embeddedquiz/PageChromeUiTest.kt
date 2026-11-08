package cn.embeddedquiz

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
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

/**
 * UI-02: one back target per page (system and top bar), the tab bar only on first-level pages,
 * and the last item of each list fully inside the visible area on a ~360dp wide phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp")
class PageChromeUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"pc-one","stem":"页面框架测试题一","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"correct":["A"],"reviewRequired":false},
        {"id":"pc-two","stem":"页面框架测试题二","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"correct":["B"],"reviewRequired":false}]}
    """
    private val linux =
        QuickNotePackage(
            "linux.md",
            "linux.md",
            requireNotNull(javaClass.classLoader!!.getResourceAsStream("quicknotes/linux.md")).use { it.readBytes() },
        )

    @Before
    fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        File(app.filesDir, "quicknotes-v1").deleteRecursively()
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
            if (!vm.ready || vm.questions.none { it.id == "pc-two" }) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
        ui.setContent { QuizApp(vm) }
        await { !vm.quickNotes.state.loading }
    }

    @After
    fun cleanup() {
        ui.runOnUiThread { store.clear() }
        // 更多 › AI 讨论 starts the AI runtime, which closes asynchronously with the ViewModel; delete
        // the database only once it is really closed (as QuestionAiEntryUiTest does).
        val deadline = System.currentTimeMillis() + 20_000
        while (vm.examResourcesClosed?.isCompleted != true) {
            check(System.currentTimeMillis() < deadline) { "ViewModel resources did not close within 20s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        app.deleteDatabase("embeddedquiz.db")
        File(app.filesDir, "quicknotes-v1").deleteRecursively()
    }

    private fun await(condition: () -> Boolean) {
        try {
            ui.waitUntil(20000) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Throwable) {
            throw AssertionError("page=${vm.page}; notice=${vm.notice}\n${ui.onRoot().printToString()}", e)
        }
    }

    private fun systemBack() {
        // The back handler is only enabled once the new page has been composed.
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private fun topBack() {
        ui.onNode(hasContentDescription("返回") and hasAnyAncestor(hasTestTag("sub-page-bar"))).performClick()
        ui.waitForIdle()
    }

    private val inMore = hasAnyAncestor(hasTestTag("more-list"))

    private fun openMore() {
        ui.onNodeWithContentDescription("导航到更多").performClick()
        await { vm.page == Page.MORE }
    }

    private fun openFromMore(title: String, page: Page) {
        ui.onNodeWithTag("more-list").performScrollToNode(hasText(title))
        ui.onNode(hasText(title) and inMore).performClick()
        await { vm.page == page }
    }

    private fun tabBarShown() = ui.onAllNodesWithContentDescription("导航到首页").fetchSemanticsNodes().isNotEmpty()

    private fun assertSubPageChrome(title: String) {
        assertFalse("tab bar must be hidden on ${vm.page}", tabBarShown())
        ui.onNode(hasText(title) and hasAnyAncestor(hasTestTag("sub-page-bar"))).assertExists()
    }

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    private fun rootBottom() = ui.onRoot().fetchSemanticsNode().boundsInRoot.bottom

    /** Swipes until the list reports it cannot scroll any further. */
    private fun scrollToEnd(tag: String) {
        val list = ui.onNodeWithTag(tag)
        repeat(80) {
            val range = list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            if (range == null || range.value() >= range.maxValue()) return
            list.performTouchInput { swipeUp() }
            ui.waitForIdle()
        }
        fail("$tag never reached its end")
    }

    /** The node lies completely inside the list and above the tab bar (or the window edge). */
    private fun assertFullyVisible(node: SemanticsNodeInteraction, listTag: String) {
        val item = bounds(node)
        val list = bounds(ui.onNodeWithTag(listTag))
        val limit =
            ui.onAllNodesWithContentDescription("导航到首页").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
                ?: rootBottom()
        assertTrue("item $item must end inside list $list", item.bottom <= list.bottom + 0.5f)
        assertTrue("list $list must end above $limit", list.bottom <= limit + 0.5f)
        assertTrue("item $item must start inside list $list", item.top >= list.top - 0.5f)
    }

    @Test
    fun policyKeepsFirstLevelTabsAndHidesThemOnSubPagesAndTheReader() {
        Page.entries.forEach { page ->
            assertEquals(page.name, !PagePolicy.isSubPage(page), PagePolicy.showsTabBar(page, false, false, false))
            // 速记 decides for itself, whatever page lies beneath it.
            assertTrue(page.name, PagePolicy.showsTabBar(page, true, false, false))
            assertFalse(page.name, PagePolicy.showsTabBar(page, true, true, false))
            assertFalse(page.name, PagePolicy.showsTabBar(page, false, false, true))
        }
        listOf(Page.HOME, Page.EXAM, Page.MISTAKES, Page.MORE, Page.BROWSE, Page.PROGRAMMING).forEach {
            assertFalse(it.name, PagePolicy.isSubPage(it))
        }
        assertEquals(Page.MORE, PagePolicy.parent(Page.REVIEW, Page.MORE))
        assertEquals(Page.MORE, PagePolicy.parent(Page.STATISTICS, Page.MORE))
        assertEquals(Page.MORE, PagePolicy.parent(Page.AI_DISCUSSIONS, Page.MORE))
        assertEquals(Page.SETTINGS, PagePolicy.parent(Page.ERROR_LOG, Page.MORE))
        assertEquals(Page.SETTINGS, PagePolicy.parent(Page.COSTS, Page.SETTINGS))
        assertEquals(Page.MORE, PagePolicy.parent(Page.COSTS, Page.MORE))
        assertEquals(Page.HOME, PagePolicy.parent(Page.BROWSE, Page.MORE))

        val trail = PageTrail(Page.MORE)
        trail.observe(Page.SETTINGS)
        trail.observe(Page.COSTS)
        assertEquals(Page.SETTINGS, trail.costsFrom)
        trail.observe(Page.MORE)
        trail.observe(Page.COSTS)
        assertEquals(Page.MORE, trail.costsFrom)
        val restored = PageTrail.saver.restore("${trail.last.name},${trail.costsFrom.name}")!!
        assertEquals(Page.COSTS, restored.last)
        assertEquals(Page.MORE, restored.costsFrom)
    }

    @Test
    fun reviewFromMoreReturnsToMoreByBothBacksAndKeepsItsScroll() {
        openMore()
        ui.onNodeWithTag("more-list").performScrollToNode(hasText("设置"))
        ui.waitForIdle()
        val before = bounds(ui.onNode(hasText("设置") and inMore))

        openFromMore("智能复习", Page.REVIEW)
        assertSubPageChrome("智能复习")
        systemBack()
        assertEquals(Page.MORE, vm.page)
        assertTrue(tabBarShown())
        assertEquals("更多 keeps its scroll position", before, bounds(ui.onNode(hasText("设置") and inMore)))

        openFromMore("智能复习", Page.REVIEW)
        topBack()
        assertEquals(Page.MORE, vm.page)
        assertEquals(before, bounds(ui.onNode(hasText("设置") and inMore)))
    }

    @Test
    fun everyMoreSubPageHidesTheTabBarAndReturnsToMore() {
        openMore()
        listOf(
                Triple("全局标签 / 便签", Page.NOTES, "全局标签"),
                Triple("待核对工作台", Page.WORKBENCH, "待核对工作台"),
                Triple("历史统计", Page.STATISTICS, "学习统计与历史"),
                Triple("AI 讨论", Page.AI_DISCUSSIONS, "AI 讨论"),
                Triple("AI 费用估算", Page.COSTS, "AI 费用估算"),
                Triple("设置", Page.SETTINGS, "设置"),
            )
            .forEachIndexed { index, (row, page, title) ->
                openFromMore(row, page)
                assertSubPageChrome(title)
                if (index % 2 == 0) systemBack() else topBack()
                assertEquals(row, Page.MORE, vm.page)
                assertTrue(tabBarShown())
            }
    }

    @Test
    fun settingsChildrenReturnToSettings() {
        openMore()
        openFromMore("设置", Page.SETTINGS)
        ui.onNodeWithTag("settings-list").performScrollToNode(hasText("查看按日、按模型费用估算"))
        ui.onNodeWithText("查看按日、按模型费用估算").performClick()
        await { vm.page == Page.COSTS }
        assertSubPageChrome("AI 费用估算")
        topBack()
        assertEquals(Page.SETTINGS, vm.page)

        ui.onNodeWithTag("settings-list").performScrollToNode(hasText("查看本地错误记录"))
        ui.onNodeWithText("查看本地错误记录").performClick()
        await { vm.page == Page.ERROR_LOG }
        assertSubPageChrome("本地错误记录")
        systemBack()
        assertEquals(Page.SETTINGS, vm.page)
        systemBack()
        assertEquals(Page.MORE, vm.page)
    }

    @Test
    fun stickyNotesCloseTheEditorBeforeTheListAndReturnToTheirSource() {
        openMore()
        openFromMore("全局标签 / 便签", Page.NOTES)
        ui.onNodeWithText("新建便签").performClick()
        await { vm.noteDraft != null }
        ui.onNodeWithText("便签标题").assertExists()
        topBack()
        assertNull(vm.noteDraft)
        assertEquals(Page.NOTES, vm.page)
        systemBack()
        assertEquals(Page.MORE, vm.page)

        // Opened from a question: back returns to the same question and keeps the queue.
        ui.runOnIdle { vm.start(listOf("pc-two", "pc-one").map { id -> vm.questions.first { it.id == id } }, "功能验证") }
        await { vm.page == Page.QUESTION }
        val queue = vm.queue
        ui.runOnIdle { vm.openStickyNotes() }
        await { vm.page == Page.NOTES }
        assertSubPageChrome("全局标签")
        ui.onNodeWithText("继续答题").assertExists()
        topBack()
        assertEquals(Page.QUESTION, vm.page)
        assertEquals(queue, vm.queue)
    }

    @Test
    fun workbenchEditorClosesBeforeLeavingAndTheLastCardIsReachable() {
        openMore()
        openFromMore("待核对工作台", Page.WORKBENCH)
        // The bundled bank is loaded too, so jump close to the end before the final swipes.
        val pending = vm.questions.count { it.objective && (it.reviewRequired || it.correctKeys.isBlank()) }
        assertTrue("the bundled bank has questions awaiting verification", pending > 0)
        // Four lead items (two explanations, the import/export row, the count) precede the cards.
        ui.onNodeWithTag("workbench-list").performScrollToIndex(3 + pending)
        scrollToEnd("workbench-list")
        val cards = ui.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("workbench-list")))
        val last = cards[cards.fetchSemanticsNodes().lastIndex]
        assertFullyVisible(last, "workbench-list")
        last.performClick()
        await { ui.onAllNodesWithTag("workbench-editor").fetchSemanticsNodes().isNotEmpty() }
        topBack()
        await { ui.onAllNodesWithTag("workbench-list").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Page.WORKBENCH, vm.page)
        systemBack()
        assertEquals(Page.MORE, vm.page)
    }

    @Test
    fun statisticsAndReviewEndAboveTheWindowEdge() {
        val today = java.time.LocalDate.now().toEpochDay()
        runBlocking {
            vm.repo.dao.putProgress(Progress("pc-one", seen = 1, due = today, wrong = true))
            vm.repo.dao.putProgress(Progress("pc-two", seen = 1, due = today + 3))
        }
        openMore()
        openFromMore("历史统计", Page.STATISTICS)
        scrollToEnd("statistics-list")
        assertFullyVisible(ui.onNodeWithContentDescription("上一页"), "statistics-list")
        systemBack()

        openFromMore("智能复习", Page.REVIEW)
        await { ui.onAllNodesWithText("页面框架测试题二", substring = true).fetchSemanticsNodes().isNotEmpty() }
        scrollToEnd("review-list")
        val last = ui.onNode(hasText("页面框架测试题二", substring = true) and hasClickAction())
        assertFullyVisible(last, "review-list")
        last.performClick()
        await { vm.page == Page.QUESTION && vm.queue == listOf("pc-two") }
    }

    @Test
    fun quickNotesKeepTabsOnTheListOnlyEvenAboveAMoreSubPage() {
        openMore()
        openFromMore("历史统计", Page.STATISTICS)
        assertFalse(tabBarShown())
        // 速记 overlays the statistics page underneath; its list is still a first-level entry.
        ui.runOnIdle { vm.openQuickNotes() }
        await { vm.quickNotes.state.visible }
        assertTrue(tabBarShown())
        scrollToEnd("quick-notes-list")
        assertFullyVisible(ui.onNodeWithText("速记单独存放", substring = true), "quick-notes-list")

        ui.runOnIdle { vm.quickNotes.importPackage(linux) }
        await { !vm.quickNotes.state.working && vm.quickNotes.state.documents.size == 1 }
        val doc = vm.quickNotes.state.documents.single()
        ui.onNodeWithTag("quick-notes-list").performScrollToNode(hasText(doc.title))
        ui.onNodeWithText(doc.title).performClick()
        await { vm.quickNotes.state.reader != null && !vm.quickNotes.state.working }
        assertFalse("the reader hides the tab bar", tabBarShown())
        val reader = bounds(ui.onNodeWithTag("quick-note-reader"))
        assertTrue("reader $reader stays inside the window", reader.bottom <= rootBottom() + 0.5f && reader.height > 0f)

        ui.onNodeWithContentDescription("目录").performClick()
        val headings = vm.quickNotes.state.reader!!.rendered.headings
        ui.onNodeWithTag("quick-note-toc").performScrollToNode(hasText(headings.last().title))
        ui.onNode(hasText(headings.last().title) and hasAnyAncestor(hasTestTag("quick-note-toc"))).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("quick-note-toc").assertDoesNotExist()

        systemBack()
        await { vm.quickNotes.state.reader == null && !vm.quickNotes.state.working }
        assertTrue(tabBarShown())
        // Leaving the list keeps its original exit.
        systemBack()
        await { !vm.quickNotes.state.visible && !vm.quickNotes.state.working }
        assertEquals(Page.HOME, vm.page)
    }

    @Test
    fun emptyQuickNotesListEndsAboveTheTabBar() {
        ui.onNodeWithContentDescription("导航到速记").performClick()
        await { vm.quickNotes.state.visible }
        scrollToEnd("quick-notes-list")
        assertFullyVisible(ui.onNodeWithText("速记单独存放", substring = true), "quick-notes-list")
        assertFullyVisible(ui.onNodeWithText("还没有速记文档"), "quick-notes-list")
    }
}
