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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 1.7.1 wiring on the real ViewModel and backend: the 速记 tab's reader offers the assistant, the
 * page shows the backend's citation preview, discussions are document discussions, and a send with
 * no API key ends as a visible, retryable failure. Sticky note sources map GPT's states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp")
class QuickNoteAiWiringUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"sw-q","stem":"中断上下文能否睡眠？","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"不能"},{"key":"B","text":"能"}],"correct":["A"],"reviewRequired":false}]}
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
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        ui.runOnUiThread {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        }
        runBlocking { vm.repo.importBank(bank) }
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.ready || vm.questions.none { it.id == "sw-q" }) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
        ui.setContent { QuizApp(vm) }
        await { !vm.quickNotes.state.loading }
    }

    @After
    fun cleanup() {
        ui.runOnUiThread { store.clear() }
        // The AI runtime closes asynchronously with the ViewModel; delete the database only after that.
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
            throw AssertionError("page=${vm.page}; notice=${vm.notice}\n${ui.onRoot().printToString()}", e)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun back() {
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private fun lastReply() =
        vm.aiConversations.state.value.firstMessages.items.lastOrNull { it.role == MessageRole.ASSISTANT }

    @Test
    fun theReaderAssistantRunsDocumentDiscussionsOnTheRealBackend() {
        ui.onNodeWithContentDescription("导航到速记").performClick()
        await { vm.quickNotes.state.visible }
        ui.runOnIdle { vm.quickNotes.importPackage(linux) }
        await { !vm.quickNotes.state.working && vm.quickNotes.state.documents.isNotEmpty() }
        val doc = vm.quickNotes.state.documents.single()
        ui.onNodeWithTag("quick-notes-list").performScrollToNode(hasText(doc.title))
        ui.onNodeWithText(doc.title).performClick()
        await { vm.quickNotes.state.reader != null && !vm.quickNotes.state.working && exists(hasTestTag("quick-note-reader")) }

        ui.onNodeWithContentDescription("AI 学习助手").performClick()
        await { exists(hasTestTag("quick-note-ai-page")) }
        // The backend's own preview of what it would cite.
        await { exists(hasText("预计提供：", substring = true) or hasText("这里没有找到直接相关的段落", substring = true)) }

        await { exists(hasTestTag("ai-new-session") and isEnabled()) }
        ui.onNodeWithTag("ai-new-session").performClick()
        await { exists(hasTestTag("ai-title")) }
        val session = vm.aiConversations.state.value.selected!!
        assertEquals(QuestionOwner(doc.id, doc.contentHash, AiOwnerKind.QUICK_NOTE), session.question)

        ui.onNodeWithTag("ai-draft").performTextInput("这份笔记讲了哪些命令？")
        await { exists(hasContentDescription("发送追问") and isEnabled()) }
        ui.onNodeWithContentDescription("发送追问").performClick()
        await { lastReply()?.run?.status == RunStatus.FAILED }
        assertEquals("CREDENTIALS_UNAVAILABLE", lastReply()!!.run!!.errorCode)
        await { vm.aiConversations.state.value.globalBusy == null && exists(hasTestTag("ai-retry")) }
        // Document replies are not offered for saving as notes.
        ui.onAllNodesWithTag("ai-save-note").assertCountEquals(0)

        back() // discussion -> list
        await { exists(hasTestTag("ai-new-session")) }
        // 1.7.2: the discussion can be deleted on the real backend once its request has settled.
        ui.onNodeWithContentDescription("更多操作：" + session.title).performClick()
        ui.onNodeWithTag("ai-session-delete").performClick()
        ui.onNodeWithTag("ai-delete-confirm").performClick()
        await { exists(hasTestTag("ai-info")) && !exists(hasTestTag("ai-session-row")) }
        val left = runBlocking { vm.aiConversations.documentSessions(doc, true, null, 20) }
        assertEquals(emptyList<SessionView>(), (left as ResultOf.Ok).value.items)

        back() // list -> reader
        await { !exists(hasTestTag("quick-note-ai-page")) }
        assertEquals(doc.id, vm.quickNotes.state.reader?.summary?.id)
        ui.onNodeWithTag("quick-note-reader").assertExists()
    }

    @Test
    fun noteSourcesMapTheBackendStatesAndOpenTheQuestionReadOnly() {
        val summary = vm.questions.single { it.id == "sw-q" }
        val sources = stickyNoteSources(vm)
        fun note(id: String?, hash: String?) =
            StickyNote("n-$id-$hash", "便签", "正文", 1, 1, sourceQuestionId = id, sourceQuestionHash = hash, sourceQuestionLabel = id?.let { "单选 · $it" })

        assertNull(sources.source(note(null, null)))
        assertEquals(NoteSourceView("单选 · sw-q", NoteSourceStatus.CURRENT), sources.source(note("sw-q", summary.contentHash)))
        assertEquals(NoteSourceStatus.CHANGED, sources.source(note("sw-q", "older-hash"))?.status)
        assertEquals(NoteSourceStatus.MISSING, sources.source(note("gone", "x"))?.status)

        runBlocking {
            val current = sources.open(note("sw-q", summary.contentHash)) as NoteSourceOpen.Ready
            assertEquals("中断上下文能否睡眠？", current.question.stem)
            assertFalse(current.changed)
            assertTrue((sources.open(note("sw-q", "older-hash")) as NoteSourceOpen.Ready).changed)
            assertEquals(NoteSourceOpen.Missing, sources.open(note("gone", "x")))
        }
        // Reading a source writes no answer or review record.
        assertTrue(vm.progress.isEmpty())
    }
}
