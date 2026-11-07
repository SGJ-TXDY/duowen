package cn.embeddedquiz

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 1.7.1 reader entry: discussions about the open Markdown document open over the reader and close
 * back onto the same, still-loaded page. The document backend is GPT's; a fake facade stands in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp")
class QuickNoteReaderAiTest {
    /**
     * An earlier test can leave the Compose main dispatcher's callback removed by Robolectric's queue
     * reset (probed in validation/UI-171-IDLE-20261108: AiChatScreenTest then EnhancedAiTest, or
     * StartupTest). The compose rule waits for idle as it starts, so the original callback is
     * delivered in an outer rule, before it.
     */
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
    private val scope = MainScope()
    private val facade = FakeAiConversationFacade()
    private val asked = mutableListOf<Pair<String, QuickNoteHeading?>>()
    private val handle = QuickNoteReaderHandle()
    /** Cleared in cleanup so the reader and its WebView leave before the data is deleted. */
    private var shown by mutableStateOf(true)
    private val linux =
        QuickNotePackage(
            "linux.md",
            "linux.md",
            requireNotNull(javaClass.classLoader!!.getResourceAsStream("quicknotes/linux.md")).use { it.readBytes() },
        )

    /** Discussions of the document keyed by id and content hash, through the fake facade. */
    private inner class DocSource(doc: QuickNoteDocument) : AiChatSource {
        val owner = QuestionOwner("doc:" + doc.summary.id, doc.summary.contentHash)
        override val quickTypes = listOf(AiRequestType.EXPLANATION)
        override val staleLabel = "旧版文档"

        override suspend fun sessions(facade: AiConversationFacade, cursor: PageCursor?, limit: Int) =
            facade.sessions(owner.questionId, true, cursor, limit)

        override suspend fun create(facade: AiConversationFacade, commandId: String) = facade.createSession(commandId, owner)

        override fun current(session: SessionView) = session.question == owner

        override suspend fun send(facade: AiConversationFacade, request: AiSendRequest) =
            facade.send(
                SendCommand(request.commandId, request.session.sessionId, owner, request.session.contextRevision,
                    request.draftToken, request.savedDraftRevision, request.input, emptySet(), "", request.type, "m")
            )
    }

    private fun entry(preview: ResultOf<QuickNoteAiContext>? = null) =
        QuickNoteAiEntry(
            facade = { facade },
            source = { doc, section ->
                asked += doc.summary.id to section
                DocSource(doc)
            },
            scope = scope,
            preview = preview?.let { result -> { _, _ -> result } },
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
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.ready) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
    }

    @After
    fun cleanup() {
        // The discussions' scope first, then the reader: its WebView is released when it leaves.
        scope.cancel()
        ui.runOnUiThread { shown = false }
        ui.waitForIdle()
        ui.onAllNodesWithTag("quick-note-reader").assertCountEquals(0)
        ui.runOnUiThread { store.clear() }
        // The ViewModel closes the AI runtime and database asynchronously; delete test data only after.
        val deadline = System.currentTimeMillis() + 20_000
        while (vm.examResourcesClosed?.isCompleted != true) {
            check(System.currentTimeMillis() < deadline) { "ViewModel resources did not close within 20s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertFalse(vm.db.isOpen)
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
            throw AssertionError("state=${vm.quickNotes.state.copy(reader = null)}\n${ui.onRoot().printToString()}", e)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun back() {
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private fun openLinux(withAi: Boolean, preview: ResultOf<QuickNoteAiContext>? = null): QuickNoteDocument {
        val ai = if (withAi) entry(preview) else null
        ui.runOnIdle { vm.quickNotes.importPackage(linux) }
        await { !vm.quickNotes.state.working && vm.quickNotes.state.documents.isNotEmpty() }
        ui.setContent {
            MaterialTheme {
                if (shown) vm.quickNotes.state.reader?.let { QuickNoteReader(vm, it, handle, ai) }
            }
        }
        ui.runOnIdle { vm.quickNotes.openDocument(vm.quickNotes.state.documents.single().id) }
        await { vm.quickNotes.state.reader != null && !vm.quickNotes.state.working && exists(hasTestTag("quick-note-reader")) }
        return vm.quickNotes.state.reader!!
    }

    @Test
    fun theAssistantOpensOverTheDocumentAndBackLandsOnTheSamePage() {
        val doc = openLinux(withAi = true)
        ui.onNodeWithContentDescription("AI 学习助手").assertIsEnabled().performClick()
        await { exists(hasTestTag("quick-note-ai-page")) }

        // It names the document and what it will cite before anything is sent.
        ui.onNodeWithTag("ai-source-context").assertIsDisplayed()
        ui.onNodeWithText("文档：" + doc.summary.title).assertIsDisplayed()
        ui.onNodeWithText("从文档开头起按需引用相关段落；只在你发送时提交。").assertIsDisplayed()
        assertEquals(listOf(doc.summary.id to null as QuickNoteHeading?), asked)
        assertTrue(facade.sends.isEmpty())
        // The page underneath is the same one, not reloaded.
        ui.onNodeWithTag("quick-note-reader").assertExists()

        // A discussion here is about this document.
        ui.onNodeWithTag("ai-new-session").performClick()
        await { exists(hasTestTag("ai-title")) }
        assertEquals("doc:" + doc.summary.id, facade.state.value.selected!!.question.questionId)

        back() // discussion -> list
        await { exists(hasTestTag("ai-new-session")) }
        back() // list -> reader
        await { !exists(hasTestTag("quick-note-ai-page")) }
        assertEquals(doc.summary.id, vm.quickNotes.state.reader?.summary?.id)
        ui.onNodeWithTag("quick-note-reader").assertExists()
        // Leaving the discussion released it.
        assertNull(facade.state.value.selectedSessionId)
    }

    @Test
    fun withoutTheBackendEntryTheReaderOffersNoAssistant() {
        openLinux(withAi = false)
        ui.onAllNodesWithContentDescription("AI 学习助手").assertCountEquals(0)
        ui.onNodeWithContentDescription("目录").assertExists()
    }

    @Test
    fun theCitedScopeFollowsTheHeadingTheReaderWasAt() {
        val doc = openLinux(withAi = false)
        val heading = doc.rendered.headings[1]
        assertEquals(heading, quickNoteSection(doc, heading.id))
        assertNull(quickNoteSection(doc, ""))
        assertNull(quickNoteSection(doc, "not-a-heading"))
        val context = quickNoteAiContext(doc, heading)
        assertEquals("文档：" + doc.summary.title, context.title)
        assertTrue(context.scope, context.scope.startsWith("以当前章节「${heading.title}」为主"))
    }

    @Test
    fun thePreviewNamesTheParagraphsBeforeAnythingIsSentAndExplainsAStaleDocument() {
        val reference = QuickNoteAiReference("h-intro", "简介", 1, 2)
        openLinux(withAi = true, preview = ResultOf.Ok(QuickNoteAiContext(listOf(QuickNoteAiExcerpt(reference, "正文")))))
        ui.onNodeWithContentDescription("AI 学习助手").performClick()
        await { exists(hasText("预计引用：「简介」第 1～2 段（发送时按问题再选取）", substring = true)) }
        assertTrue(facade.sends.isEmpty())
    }

    @Test
    fun aDocumentChangedSinceOpeningSaysSoInsteadOfCitingIt() {
        openLinux(withAi = true, preview = ResultOf.Rejected(Rejection(RejectCode.STALE_OWNER, false, RecoveryAction.REOPEN)))
        ui.onNodeWithContentDescription("AI 学习助手").performClick()
        await { exists(hasText("文档内容已更新，请返回列表重新打开后再提问。", substring = true)) }
    }
}
