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

    /**
     * Calls the backend from another thread while the main looper keeps running. Blocking the main
     * thread here can deadlock with a UI action (delete, leave) that holds the backend and has to
     * resume on the main thread.
     */
    private fun <T> backend(block: suspend () -> T): T {
        val result = java.util.concurrent.atomic.AtomicReference<Result<T>?>(null)
        Thread { result.set(runCatching { runBlocking { block() } }) }.start()
        await { result.get() != null }
        return result.get()!!.getOrThrow()
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

    /**
     * PLAN-1.7.4 on the real backend: 更多 › AI 讨论 groups both kinds by source, renames in place,
     * opens the exact discussion under its source, goes to the original and back, and deletes.
     */
    @Test
    fun theAiWorkbenchGroupsOpensGoesToTheSourceAndDeletesOnTheRealBackend() {
        ui.runOnIdle { vm.quickNotes.importPackage(linux) }
        await { !vm.quickNotes.state.working && vm.quickNotes.state.documents.isNotEmpty() }
        val doc = vm.quickNotes.state.documents.single()
        val q = vm.questions.single { it.id == "sw-q" }
        val facade = vm.aiConversations
        await { facade.state.value.phase == ServicePhase.READY }
        val asked = backend { facade.createSession(java.util.UUID.randomUUID().toString(), QuestionOwner(q.id, q.contentHash)) }
        val questionSession = (asked as ResultOf.Ok).value.session
        val read = backend { facade.createDocumentSession(java.util.UUID.randomUUID().toString(), doc) }
        val docSession = (read as ResultOf.Ok).value.session

        ui.onNodeWithContentDescription("导航到更多").performClick()
        await { vm.page == Page.MORE }
        ui.onNodeWithTag("more-list").performScrollToNode(hasText("AI 费用估算"))
        // The entry sits right above the cost estimate.
        assertTrue(ui.onNodeWithText("AI 讨论").fetchSemanticsNode().boundsInRoot.bottom <=
            ui.onNode(hasText("AI 费用估算") and hasClickAction()).fetchSemanticsNode().boundsInRoot.top + 0.5f)
        ui.onNodeWithText("AI 讨论").performClick()
        await { vm.page == Page.AI_DISCUSSIONS && ui.onAllNodesWithTag("ai-group").fetchSemanticsNodes().size == 2 }
        ui.onNodeWithTag("sub-page-bar").assertExists()
        assertFalse(exists(hasContentDescription("导航到首页")))
        ui.onAllNodesWithTag("ai-recent-card").assertCountEquals(2)
        val questionGroup = hasTestTag("ai-group-head") and hasText("中断上下文能否睡眠？")
        val docGroup = hasTestTag("ai-group-head") and hasText(doc.title)
        ui.onNode(questionGroup).assert(hasText("题目 · 1 条讨论"))
        ui.onNode(docGroup).assert(hasText("速记 · 1 条讨论"))

        // Rename inside the group; the original entry sees the same name.
        ui.onNode(questionGroup).performClick()
        await { exists(hasTestTag("ai-child")) }
        ui.onNodeWithContentDescription("更多操作：" + questionSession.title).performClick()
        ui.onNodeWithTag("ai-child-rename").performClick()
        ui.onNodeWithTag("ai-list-rename-field").performTextReplacement("  中断里能睡吗  ")
        ui.onNodeWithTag("ai-rename-confirm").performClick()
        await { exists(hasTestTag("ai-all-info")) }
        val listed = backend { facade.sessions(q.id, true, null, 20) } as ResultOf.Ok
        assertEquals("中断里能睡吗", listed.value.items.single { it.sessionId == questionSession.sessionId }.title)

        // The exact discussion opens on this page under its source; no practice starts and nothing is sent.
        ui.onNode(hasTestTag("ai-child") and hasText("中断里能睡吗")).performClick()
        await { exists(hasTestTag("ai-title")) && facade.state.value.selectedSessionId == questionSession.sessionId }
        ui.onNodeWithTag("ai-source-title").assertTextEquals("中断上下文能否睡眠？")
        assertEquals(Page.AI_DISCUSSIONS, vm.page)
        assertTrue(vm.queue.isEmpty())
        // The original opens read only from the bank, over the discussion.
        ui.onNodeWithTag("ai-source-open").performClick()
        await { exists(hasTestTag("source-question")) }
        back() // original -> discussion
        await { !exists(hasTestTag("source-question")) && exists(hasTestTag("ai-title")) }
        assertEquals(questionSession.sessionId, facade.state.value.selectedSessionId)
        assertTrue(vm.queue.isEmpty())
        back() // discussion -> workbench
        await { exists(hasTestTag("ai-group")) && facade.state.value.selectedSessionId == null }
        back() // workbench -> 更多
        await { vm.page == Page.MORE }

        // Coming back keeps the open group; a 速记 discussion goes to its document and comes back.
        ui.onNodeWithTag("more-list").performScrollToNode(hasText("AI 讨论"))
        ui.onNodeWithText("AI 讨论").performClick()
        await { vm.page == Page.AI_DISCUSSIONS && exists(hasTestTag("ai-child") and hasText("中断里能睡吗")) }
        ui.onNode(docGroup).performClick()
        await { exists(hasTestTag("ai-child") and hasText(docSession.title)) }
        ui.onNode(hasTestTag("ai-child") and hasText(docSession.title)).performClick()
        await { exists(hasTestTag("ai-title")) && facade.state.value.selectedSessionId == docSession.sessionId }
        ui.onNodeWithTag("ai-source-open").assert(hasText("原文")).performClick()
        await { vm.quickNotes.state.reader?.summary?.id == doc.id && exists(hasTestTag("quick-note-reader")) }
        assertNull(facade.state.value.selectedSessionId)
        back() // reader -> the same discussion
        await { !vm.quickNotes.state.visible && exists(hasTestTag("ai-title")) && facade.state.value.selectedSessionId == docSession.sessionId }
        assertEquals(Page.AI_DISCUSSIONS, vm.page)

        // Delete it from its own menu: back on the workbench without it or its now empty source.
        ui.onNodeWithContentDescription("讨论操作").performClick()
        ui.onNodeWithTag("ai-detail-delete").performClick()
        ui.onNodeWithTag("ai-delete-source").assertTextEquals("来源：速记 · " + doc.title)
        ui.onNodeWithTag("ai-delete-confirm").performClick()
        await { exists(hasTestTag("ai-workbench")) && ui.onAllNodesWithTag("ai-group").fetchSemanticsNodes().size == 1 }
        val left = backend { facade.documentSessions(doc, true, null, 20) } as ResultOf.Ok
        assertTrue(left.value.items.isEmpty())
        assertNull(facade.state.value.selectedSessionId)
        assertEquals(Page.AI_DISCUSSIONS, vm.page)
    }

    /** PLAN-1.7.4 trial path 4 on the real backend: a free conversation is saved and reopens; a temporary chat leaves nothing. */
    @Test
    fun freeConversationsAreSavedAndTemporaryChatsLeaveNoHistoryOnTheRealBackend() {
        val facade = vm.aiConversations
        await { facade.state.value.phase == ServicePhase.READY }
        ui.onNodeWithContentDescription("导航到更多").performClick()
        await { vm.page == Page.MORE }
        ui.onNodeWithTag("more-list").performScrollToNode(hasText("AI 讨论"))
        ui.onNodeWithText("AI 讨论").performClick()
        await { vm.page == Page.AI_DISCUSSIONS && exists(hasTestTag("ai-workbench")) }

        // The right ＋: saved on the first send (no API key here, so the reply fails visibly).
        ui.onNodeWithContentDescription("新建自由对话").performClick()
        await { exists(hasTestTag("ai-new-free-pane")) }
        ui.onNodeWithTag("ai-free-field").performTextInput("RTOS 选型怎么考虑？")
        ui.onNodeWithTag("ai-free-send").performClick()
        await { exists(hasTestTag("ai-title")) && facade.state.value.selectedSessionId != null }
        val free = facade.state.value.selectedSessionId!!
        await { lastReply()?.run?.status == RunStatus.FAILED }
        back() // discussion -> workbench
        await { exists(hasTestTag("ai-workbench")) && facade.state.value.selectedSessionId == null }
        ui.onNodeWithTag("ai-filter-FREE").performClick()
        await { exists(hasTestTag("ai-group-head") and hasText("RTOS 选型怎么考虑？")) }
        ui.onNode(hasTestTag("ai-group-head") and hasText("RTOS 选型怎么考虑？")).performClick()
        await { exists(hasTestTag("ai-child")) }
        ui.onNode(hasTestTag("ai-child") and hasText("RTOS 选型怎么考虑？")).performClick()
        await { exists(hasTestTag("ai-title")) && facade.state.value.selectedSessionId == free }
        ui.onNodeWithTag("ai-source-hint").assertTextEquals("自由对话 · 未关联题目或速记")
        back()
        await { exists(hasTestTag("ai-workbench")) && facade.state.value.selectedSessionId == null }

        // The middle box: a temporary chat that is gone after leaving.
        ui.onNodeWithTag("ai-temp-field").performTextInput("一句临时的话")
        ui.onNodeWithTag("ai-temp-send").performClick()
        await { exists(hasTestTag("ai-temp-chat")) && facade.state.value.selectedSessionId != null }
        val temp = facade.state.value.selectedSessionId!!
        await { lastReply()?.run?.status == RunStatus.FAILED }
        back() // temporary chat -> workbench, discarded
        await { exists(hasTestTag("ai-workbench")) && facade.state.value.selectedSessionId == null }
        val recent = backend { facade.recentDiscussions(null, null, 50) } as ResultOf.Ok
        assertTrue(recent.value.items.any { it.discussion.sessionId == free })
        assertTrue(recent.value.items.none { it.discussion.sessionId == temp })
        val gone = backend { facade.searchDiscussions("一句临时", null, 20) } as ResultOf.Ok
        assertTrue(gone.value.items.isEmpty())
        val kept = backend { facade.searchDiscussions("RTOS", null, 20) } as ResultOf.Ok
        assertEquals(listOf(free), kept.value.items.map { it.summary.discussion.sessionId })
        assertTrue(backend { facade.open(temp) } is ResultOf.Rejected)
    }
}
