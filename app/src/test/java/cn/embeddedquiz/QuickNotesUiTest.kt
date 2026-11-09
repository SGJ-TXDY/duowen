package cn.embeddedquiz

import android.app.Application
import android.graphics.Color
import android.os.Looper
import androidx.activity.ComponentActivity
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

/** 速记 tab, document list, conflicts, deletion and the reader chrome (the WebView itself is not rendered here). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickNotesUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"qn-one","stem":"速记隔离题一","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"correct":["A"],"reviewRequired":false},
        {"id":"qn-two","stem":"速记隔离题二","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"correct":["B"],"reviewRequired":false}]}
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
            if (!vm.ready || vm.questions.none { it.id == "qn-two" }) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
        ui.setContent { QuizApp(vm) }
        await { !vm.quickNotes.state.loading }
    }

    @After
    fun cleanup() {
        ui.runOnUiThread { store.clear() }
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
            throw AssertionError(
                "state=${vm.quickNotes.state.copy(reader = null)}; page=${vm.page}\n${ui.onRoot().printToString()}",
                e,
            )
        }
    }

    private fun back() {
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private fun inList(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.onNodeWithTag("quick-notes-list").performScrollToNode(matcher)
        return ui.onNode(matcher)
    }

    private fun importLinux() {
        ui.runOnIdle { vm.quickNotes.importPackage(linux) }
        await { !vm.quickNotes.state.working }
    }

    private fun openNotes() {
        ui.onNodeWithContentDescription("导航到速记").performClick()
        await { vm.quickNotes.state.visible }
    }

    @Test
    fun tabReplacesReviewAndLeavingKeepsTheExamRunning() {
        ui.onNodeWithContentDescription("导航到复习").assertDoesNotExist()
        ui.runOnIdle {
            vm.startExam(vm.questions.filter { it.id.startsWith("qn-") }, 2, 30)
            vm.answerExam(vm.queue.first(), setOf("A"))
            vm.page = Page.HOME
        }
        val deadline = vm.examDeadline
        val queue = vm.queue
        val answers = vm.examAnswers

        openNotes()
        inList(hasText("还没有速记")).assertExists()
        assertEquals(Page.HOME, vm.page)

        ui.onNodeWithContentDescription("导航到错题").performClick()
        await { !vm.quickNotes.state.visible && vm.page == Page.MISTAKES }
        assertEquals(deadline, vm.examDeadline)
        assertEquals(queue, vm.queue)
        assertEquals(answers, vm.examAnswers)
        assertFalse(vm.examFinished)

        // The review page left the tab bar but is still reachable.
        ui.onNodeWithContentDescription("导航到更多").performClick()
        ui.onNodeWithTag("more-list").performScrollToNode(hasText("智能复习"))
        ui.onNodeWithText("智能复习").performClick()
        await { vm.page == Page.REVIEW }
    }

    @Test
    fun documentOpensWithOutlineAndWarningsAndBackReturnsToList() {
        openNotes()
        importLinux()
        val doc = vm.quickNotes.state.documents.single()
        inList(hasText(doc.title)).performClick()
        await { vm.quickNotes.state.reader != null && !vm.quickNotes.state.working }
        val rendered = vm.quickNotes.state.reader!!.rendered
        ui.onNodeWithTag("quick-note-reader").assertExists()
        ui.onNodeWithText("${rendered.warnings.size} 条显示提示").assertExists()

        ui.onNodeWithContentDescription("目录").performClick()
        val second = rendered.headings[1]
        ui.onNodeWithTag("quick-note-toc").performScrollToNode(hasText(second.title))
        ui.onNodeWithText(second.title).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("quick-note-toc").assertDoesNotExist()

        back()
        await { vm.quickNotes.state.reader == null && !vm.quickNotes.state.working }
        inList(hasText(doc.title)).assertExists()
        // Back on the list leaves 速记 for the home screen.
        back()
        await { !vm.quickNotes.state.visible && !vm.quickNotes.state.working }
        assertEquals(Page.HOME, vm.page)
    }

    @Test
    fun duplicateImportAsksBeforeKeepingBoth() {
        openNotes()
        importLinux()
        importLinux()
        ui.onNodeWithText("已有相同的文档").assertExists()
        ui.onNodeWithText("取消导入").performClick()
        await { vm.quickNotes.state.conflict.isEmpty() }
        assertEquals(1, vm.quickNotes.state.documents.size)

        importLinux()
        ui.onNodeWithText("保留两份").performClick()
        await { !vm.quickNotes.state.working && vm.quickNotes.state.documents.size == 2 }
        ui.onNodeWithText("已有相同的文档").assertDoesNotExist()
    }

    @Test
    fun deleteExplainsWhatIsRemoved() {
        openNotes()
        importLinux()
        val doc = vm.quickNotes.state.documents.single()
        // Deleting moved into each note's ⋯ menu (PLAN-1.7.5).
        inList(hasContentDescription("更多操作：${doc.title}")).performClick()
        ui.onNodeWithText("删除").performClick()
        ui.onNodeWithText("将删除这份文档的正文、图片和阅读位置，无法撤销。刷题数据不受影响。").assertExists()
        ui.onNodeWithText("删除").performClick()
        await { !vm.quickNotes.state.working && vm.quickNotes.state.documents.isEmpty() }
        inList(hasText("还没有速记")).assertExists()
    }

    @Test
    fun readingPageLocksScriptsAndImagesAndParsesPositions() {
        val colors =
            QuickNoteColors(Color.BLACK, Color.WHITE, Color.GRAY, Color.BLUE, Color.DKGRAY, Color.DKGRAY, Color.YELLOW, Color.BLACK, true)
        val html = quickNoteHtml("<p>正文</p>", colors, "n0nce")
        assertTrue(html.contains("script-src 'nonce-n0nce'"))
        assertTrue(html.contains("img-src https://appassets.androidplatform.net;"))
        assertTrue(html.contains("<script nonce=\"n0nce\">"))
        assertTrue(html.contains("<p>正文</p>"))
        assertTrue(html.contains("color-scheme:dark"))
        assertTrue(html.contains(".qn-table-scroll{overflow-x:auto;max-width:100%"))
        assertTrue(html.contains("white-space:pre"))

        val anchors = setOf("启动流程")
        assertEquals(
            QuickNotePosition("启动流程", 120.0, 0.25),
            parsePosition("""{"anchor":"启动流程","offset":120,"fraction":0.25}""", anchors),
        )
        // Unknown anchors fall back to the scroll fraction; values are clamped to the contract.
        assertEquals(QuickNotePosition("", 0.0, 1.0), parsePosition("""{"anchor":"x","offset":5,"fraction":3}""", anchors))
        assertNull(parsePosition("null", anchors))
        assertNull(parsePosition("""{"anchor":"","offset":"NaN","fraction":0}""", anchors))
    }
}
