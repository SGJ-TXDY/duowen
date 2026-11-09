package cn.embeddedquiz

import android.app.Application
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
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * PLAN-1.7.5 速记 on the real ViewModel and storage: a note written in the app is saved and reopens,
 * an imported note with code, a table and an image changes in exactly the edited paragraph, leaving
 * asks before dropping edits, and a draft never shows on another document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickNoteEditingUiTest {
    @get:Rule(order = 0)
    val strandedMainQueue = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                ComposeMainQueue.deliverStrandedCallbacks()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1) val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"qe-one","stem":"编辑隔离题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"correct":["A"],"reviewRequired":false}]}
    """
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=")
    private val syntaxText = resource("syntax.md")
    private val syntax = QuickNotePackage("syntax.md", "syntax.md", syntaxText.toByteArray(), mapOf("assets/示例图.png" to png))
    private val linux = QuickNotePackage("linux.md", "linux.md", resource("linux.md").toByteArray())

    private fun resource(name: String) =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("quicknotes/$name")).use { it.readBytes().toString(Charsets.UTF_8) }

    @Before
    fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        File(app.filesDir, "quicknotes-v1").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        ui.runOnUiThread { vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java] }
        runBlocking { vm.repo.importBank(bank) }
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.ready || vm.questions.none { it.id == "qe-one" }) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
        ui.setContent { QuizApp(vm) }
        await { !vm.quickNotes.state.loading }
    }

    @After
    fun cleanup() {
        ui.runOnUiThread { store.clear() }
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
                // The reader's position capture falls back after 400ms when the page does not answer.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                condition()
            }
        } catch (e: Throwable) {
            throw AssertionError("draft=${vm.quickNotes.draft}; state=${vm.quickNotes.state.copy(reader = null)}\n${ui.onRoot().printToString()}", e)
        }
    }

    private fun back() {
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    /** Storage calls from another thread, while the main looper keeps running. */
    private fun <T> storage(block: suspend QuickNotesRepository.() -> T): T {
        val result = java.util.concurrent.atomic.AtomicReference<Result<T>?>(null)
        Thread { result.set(runCatching { runBlocking { vm.repo.db.quickNotesRepository.block() } }) }.start()
        await { result.get() != null }
        return result.get()!!.getOrThrow()
    }

    private fun openNotes() {
        ui.onNodeWithContentDescription("导航到速记").performClick()
        await { vm.quickNotes.state.visible }
    }

    private fun import(pack: QuickNotePackage): QuickNoteSummary {
        val before = vm.quickNotes.state.documents.map { it.id }.toSet()
        ui.runOnIdle { vm.quickNotes.importPackage(pack) }
        await { !vm.quickNotes.state.working && vm.quickNotes.state.documents.size > before.size }
        return vm.quickNotes.state.documents.first { it.id !in before }
    }

    private fun open(doc: QuickNoteSummary) {
        ui.onNodeWithTag("quick-notes-list").performScrollToNode(hasText(doc.title))
        ui.onNode(hasTestTag("quick-note-row") and hasText(doc.title)).performClick()
        await { vm.quickNotes.state.reader?.summary?.id == doc.id && !vm.quickNotes.state.working }
    }

    private fun startEditing() {
        ui.onNodeWithTag("quick-note-edit").performClick()
        await { vm.quickNotes.draftShown() && !vm.quickNotes.state.working }
        ui.onNodeWithTag("quick-note-editor").assertExists()
    }

    private fun block(text: String): SemanticsNodeInteraction {
        val matcher = hasTestTag("quick-note-block") and hasText(text)
        ui.onNodeWithTag("quick-note-blocks").performScrollToNode(matcher)
        return ui.onNode(matcher)
    }

    private fun tabBarShown() = ui.onAllNodesWithContentDescription("导航到首页").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aNewNoteIsWrittenSavedAndReopens() {
        openNotes()
        ui.onNodeWithTag("quick-note-new").performClick()
        await { vm.quickNotes.draftShown() }
        ui.onNodeWithTag("quick-note-editor").assertExists()
        assertFalse("the document page hides the tab bar", tabBarShown())
        assertTrue(vm.quickNotes.state.documents.isEmpty())

        ui.onNodeWithTag("quick-note-title").performTextReplacement("中断笔记")
        ui.onAllNodesWithTag("quick-note-block").onFirst().performTextReplacement("上半部尽快返回，耗时工作交给下半部。")
        // A new line under the paragraph, turned into a to-do from the toolbar (which converts the focused block).
        ui.onNodeWithTag("quick-note-append").performClick()
        ui.onNodeWithContentDescription("待办").performClick()
        ui.onAllNodesWithContentDescription("待办事项").onLast().performTextReplacement("复习 tasklet")
        ui.onNodeWithTag("quick-note-save").performClick()
        await { vm.quickNotes.draft == null && vm.quickNotes.state.reader != null && !vm.quickNotes.state.working }

        val saved = vm.quickNotes.state.reader!!.summary
        assertEquals("中断笔记", saved.title)
        val source = storage { editSource(saved.id) }
        assertEquals("上半部尽快返回，耗时工作交给下半部。\n\n- [ ] 复习 tasklet\n", source.text)

        // Back to the list: the note is there with its first line, and it reopens as written.
        back()
        await { vm.quickNotes.state.reader == null }
        ui.onNode(hasTestTag("quick-note-row") and hasText("中断笔记")).assert(hasText("上半部尽快返回", substring = true))
        ui.onNodeWithTag("quick-notes-count").assertTextEquals("1 篇速记")
        open(saved)
        startEditing()
        assertEquals("中断笔记", ui.onNodeWithTag("quick-note-title").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        block("上半部尽快返回，耗时工作交给下半部。").assertExists()
        // Nothing changed: back leaves without asking.
        back()
        await { !vm.quickNotes.draftShown() }
        ui.onAllNodesWithText("保存修改？").assertCountEquals(0)
        ui.onNodeWithTag("quick-note-reader").assertExists()
    }

    @Test
    fun anEmptyNewNoteLeavesNothingBehind() {
        openNotes()
        ui.onNodeWithTag("quick-note-new").performClick()
        await { vm.quickNotes.draftShown() }
        back()
        await { vm.quickNotes.draft == null }
        ui.onAllNodesWithText("保存修改？").assertCountEquals(0)
        assertTrue(vm.quickNotes.state.documents.isEmpty())
        assertTrue(storage { listDocuments() }.isEmpty())
        ui.onNodeWithTag("quick-notes-list").assertExists()
    }

    @Test
    fun editingOneParagraphOfAnImportedNoteKeepsEverythingElse() {
        openNotes()
        val doc = import(syntax)
        open(doc)
        val outline = vm.quickNotes.state.reader!!.rendered.headings.map { it.title }
        startEditing()
        // Tables, quotes and images stay as written; the paragraph is plain text here.
        ui.onAllNodesWithTag("quick-note-raw").fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        block("第一段。").performTextReplacement("第一段，改过一次。")
        ui.onNodeWithTag("quick-note-save").performClick()
        await { vm.quickNotes.draft == null && !vm.quickNotes.state.working }

        val source = storage { editSource(doc.id) }
        assertEquals(syntaxText.replace("第一段。", "第一段，改过一次。"), source.text)
        // The image is still there and the outline did not change.
        assertNotNull(storage { readAsset(doc.id, QuickNotePaths.encode("assets/示例图.png")) })
        assertEquals(outline, vm.quickNotes.state.reader!!.rendered.headings.map { it.title })
        assertNotEquals(doc.contentHash, vm.quickNotes.state.reader!!.summary.contentHash)
    }

    @Test
    fun leavingWithEditsAsksAndDiscardingKeepsTheSavedText() {
        openNotes()
        val doc = import(syntax)
        open(doc)
        startEditing()
        block("第二段。").performTextReplacement("不会保存的修改")
        back()
        ui.onNodeWithText("保存修改？").assertExists()
        ui.onNodeWithTag("quick-note-leave-discard").performClick()
        await { vm.quickNotes.draft == null }
        ui.onNodeWithTag("quick-note-reader").assertExists()
        assertEquals(syntaxText, storage { editSource(doc.id) }.text)
    }

    @Test
    fun aSaveAgainstAnOlderVersionStaysInTheEditorWithItsText() {
        openNotes()
        val doc = import(syntax)
        open(doc)
        startEditing()
        block("第一段。").performTextReplacement("我的修改")
        // Changed elsewhere after this edit was read.
        val read = storage { editSource(doc.id) }
        storage { updateDocument(QuickNoteUpdate(doc.id, read.version, read.summary.title, read.text + "\n别处追加\n")) }
        ui.onNodeWithTag("quick-note-save").performClick()
        await { vm.quickNotes.draft?.saving == false && vm.quickNotes.draft?.error?.isNotEmpty() == true }
        ui.onNodeWithTag("quick-note-save-error").assertExists()
        ui.onNodeWithTag("quick-note-editor").assertExists()
        block("我的修改").assertExists()
        assertFalse(storage { editSource(doc.id) }.text.contains("我的修改"))
    }

    @Test
    fun aDraftStaysWithItsOwnDocumentAcrossOtherWaysOut() {
        openNotes()
        val a = import(linux)
        val b = import(syntax)
        open(a)
        startEditing()
        ui.onNodeWithTag("quick-note-title").performTextReplacement("Linux 改了标题")
        // The document page has no tab bar; a notification or a page switch leaves 速记 this way. The draft stays.
        assertFalse(tabBarShown())
        ui.runOnIdle { vm.quickNotes.leave() }
        await { !vm.quickNotes.state.visible && !vm.quickNotes.state.working }
        assertNotNull(vm.quickNotes.draft)
        // Another document opened from elsewhere (e.g. AI 讨论) shows as it is, without A's edits.
        ui.runOnIdle { vm.quickNotes.openFrom(b.id) {} }
        await { vm.quickNotes.state.reader?.summary?.id == b.id && !vm.quickNotes.state.working }
        ui.onAllNodesWithTag("quick-note-editor").assertCountEquals(0)
        ui.onNodeWithTag("quick-note-reader").assertExists()
        // Editing B is refused while A has unsaved edits, so A's draft is never replaced.
        ui.onNodeWithTag("quick-note-edit").performClick()
        await { !vm.quickNotes.state.working && vm.quickNotes.state.error.isNotEmpty() }
        assertEquals(a.id, vm.quickNotes.draft?.documentId)

        // The library says A has unsaved edits and leads back to them.
        ui.runOnIdle { vm.quickNotes.closeDocument() }
        await { !vm.quickNotes.state.working }
        openNotes()
        ui.onNodeWithTag("quick-note-pending").assertExists()
        ui.onNodeWithText("继续编辑").performClick()
        await { vm.quickNotes.state.reader?.summary?.id == a.id && vm.quickNotes.draftShown() }
        assertEquals("Linux 改了标题", ui.onNodeWithTag("quick-note-title").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        ui.onNodeWithTag("quick-note-save").performClick()
        await { vm.quickNotes.draft == null && !vm.quickNotes.state.working }
        assertEquals("Linux 改了标题", vm.quickNotes.state.documents.first { it.id == a.id }.title)
        assertEquals(syntaxText, storage { editSource(b.id) }.text)
    }

    @Test
    fun tappingTheTextEditsThatBlockAndTheCursorLandsThere() {
        openNotes()
        val doc = import(syntax)
        open(doc)
        val lines = QuickNoteBlocks.parse(syntaxText)
        val second = lines.single { it.text == "第二段。" }.lines!!.first
        // What the page reports for a tap on that paragraph (quicknote-edit:<line>).
        ui.runOnIdle { vm.quickNotes.beginEdit(doc.id, line = second) }
        await { vm.quickNotes.draftShown() && !vm.quickNotes.state.working }
        await { runCatching { block("第二段。").assertIsFocused() }.isSuccess }
        block("第二段。").performTextReplacement("第二段（点按后修改）。")
        ui.onNodeWithTag("quick-note-save").performClick()
        await { vm.quickNotes.draft == null && !vm.quickNotes.state.working }
        assertEquals(syntaxText.replace("第二段。", "第二段（点按后修改）。"), storage { editSource(doc.id) }.text)

        // A tap on the table opens its source straight away.
        val table = lines.single { it.label == "表格" }.lines!!.first
        ui.runOnIdle { vm.quickNotes.beginEdit(doc.id, line = table + 1) }
        await { vm.quickNotes.draftShown() && !vm.quickNotes.state.working }
        ui.onNodeWithTag("quick-note-blocks").performScrollToNode(hasTestTag("quick-note-raw-field"))
        ui.onNodeWithTag("quick-note-raw-field").assert(hasText("| 左列 | 右列 |", substring = true))
    }

    @Test
    fun thePreviewIsTheReadingPageItWillBeAndUntouchedSourceStays() {
        openNotes()
        val doc = import(syntax)
        open(doc)
        startEditing()
        block("第一段。").performTextReplacement("第一段，预览后保存。")
        ui.onNodeWithTag("quick-note-preview").performClick()
        await { vm.quickNotes.draft?.previewing == true }
        ui.onNodeWithTag("quick-note-preview-web").assertExists()
        val preview = quickNotePreview(vm.quickNotes.draft!!)
        // The reader's page: table that scrolls, code with its copy button, the package image.
        assertTrue(preview.htmlBody.contains("qn-table-scroll"))
        assertTrue(preview.htmlBody.contains("quicknote-copy:code-0"))
        assertTrue(preview.htmlBody.contains("/quicknotes/${doc.id}/assets/"))
        assertTrue(preview.htmlBody.contains("第一段，预览后保存。"))

        ui.onNodeWithTag("quick-note-save").performClick()
        await { vm.quickNotes.draft == null && !vm.quickNotes.state.working && vm.quickNotes.state.reader != null }
        // After saving, the reading page is exactly what the preview showed.
        assertEquals(preview.htmlBody, vm.quickNotes.state.reader!!.rendered.htmlBody)
        assertEquals(syntaxText.replace("第一段。", "第一段，预览后保存。"), storage { editSource(doc.id) }.text)
    }
}
