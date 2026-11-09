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
 * The question page's AI entries on the real ViewModel and conversation runtime. No API key and no
 * network here: an accepted request must still end as a visible, retryable failure, which proves the
 * buttons reach the new send path after the send right moved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp")
class QuestionAiEntryUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"qa-code","stem":"实现环形缓冲区","source":"UI test","kind":"PROGRAMMING","reviewRequired":true},
        {"id":"qa-single","stem":"volatile 的作用","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"禁止优化"},{"key":"B","text":"加锁"}],"correct":["A"],"reviewRequired":false}]}
    """

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
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        }
        runBlocking { vm.repo.importBank(bank) }
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.ready || vm.questions.none { it.id == "qa-single" }) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
        ui.setContent { QuizApp(vm) }
        await { !vm.quickNotes.state.loading }
    }

    @After
    fun cleanup() {
        ui.runOnUiThread { store.clear() }
        // These tests start the AI runtime, which closes asynchronously with the ViewModel. The
        // database can only be deleted once it is really closed (as AiProductionIntegrationTest
        // does); deleting an open file would leave its state to the next test class.
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
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Throwable) {
            throw AssertionError("page=${vm.page}; notice=${vm.notice}\n${ui.onRoot().printToString()}", e)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun systemBack() {
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private fun start(id: String, mode: String) {
        ui.runOnIdle { vm.start(listOf(vm.questions.first { it.id == id }), mode) }
        await { vm.page == Page.QUESTION && exists(hasTestTag("question-list")) }
    }

    private fun lastAssistant() =
        vm.aiConversations.state.value.firstMessages.items.lastOrNull { it.role == MessageRole.ASSISTANT }

    @Test
    fun codeReviewStillSendsAfterTheSwitchAndEndsAsAVisibleRetryableFailure() {
        start("qa-code", "编程练习")
        ui.onNodeWithTag("question-list").performScrollToNode(hasTestTag("code-editor"))
        val code = "int main(void) { return 0; }"
        ui.onNodeWithTag("code-editor").performTextInput(code)
        ui.onNodeWithTag("question-list").performScrollToNode(hasText("AI 代码审查"))
        ui.onNodeWithText("AI 代码审查").assertIsEnabled().performClick()

        await { exists(hasTestTag("ai-page")) }
        await { lastAssistant()?.run?.status == RunStatus.FAILED }
        val messages = vm.aiConversations.state.value.firstMessages.items
        assertEquals(AiRequestType.CODE_REVIEW.label, messages.single { it.role == MessageRole.USER }.text)
        assertEquals("CREDENTIALS_UNAVAILABLE", lastAssistant()!!.run!!.errorCode)
        // A failed run keeps the slot while it cleans up; retry opens only after that.
        await { vm.aiConversations.state.value.globalBusy == null }
        await { exists(hasTestTag("ai-retry")) }
        ui.onNodeWithTag("ai-retry").assertIsEnabled()

        // Back: from the discussion to its list, then to the question.
        systemBack()
        await { exists(hasTestTag("ai-new-session")) }

        // 1.7.2: the settled discussion is deleted on the real backend; the question's progress is untouched.
        await { vm.progress["qa-code"]?.code == code }
        val progressBefore = runBlocking { vm.repo.dao.allProgress().associateBy { it.questionId } }
        val title = ui.onNodeWithTag("ai-session-row").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        ui.onNodeWithContentDescription("更多操作：$title").performClick()
        ui.onNodeWithTag("ai-session-delete").performClick()
        ui.onNodeWithTag("ai-delete-confirm").performClick()
        await { exists(hasTestTag("ai-info")) && !exists(hasTestTag("ai-session-row")) }
        val owner = QuestionOwner("qa-code", vm.questions.single { it.id == "qa-code" }.contentHash)
        val left = kotlinx.coroutines.runBlocking { vm.aiConversations.sessions(owner.questionId, true, null, 20) }
        assertEquals(emptyList<SessionView>(), (left as ResultOf.Ok).value.items)
        assertEquals(progressBefore, runBlocking { vm.repo.dao.allProgress().associateBy { it.questionId } })

        systemBack()
        await { exists(hasTestTag("question-list")) && !exists(hasTestTag("ai-page")) }
        assertEquals(Page.QUESTION, vm.page)
    }

    @Test
    fun theAssistantEntryOpensDiscussionsAndAFollowUpIsAccepted() {
        start("qa-single", "顺序练习")
        // Showing the entry does not take the facade; only using it does.
        // The mascot floats over the page (PLAN-1.7.5); tapping it only opens the menu.
        ui.onNodeWithTag("ai-mascot").performClick()
        ui.onNodeWithTag("ai-mascot-menu").assertExists()
        assertFalse(exists(hasTestTag("ai-page")))
        ui.onNodeWithTag("ai-open").performClick()

        await { exists(hasTestTag("ai-new-session")) && vm.aiConversations.state.value.phase == ServicePhase.READY }
        ui.onNodeWithTag("ai-new-session").assertIsEnabled().performClick()
        await { exists(hasTestTag("ai-title")) }
        ui.onNodeWithTag("ai-draft").performTextInput("为什么不是加锁？")
        await { exists(hasContentDescription("发送追问") and isEnabled()) }
        ui.onNodeWithContentDescription("发送追问").performClick()

        await { lastAssistant()?.run?.status == RunStatus.FAILED }
        val user = vm.aiConversations.state.value.firstMessages.items.single { it.role == MessageRole.USER }
        assertEquals("为什么不是加锁？", user.text)
        // The accepted draft was consumed by the send, in the store and on screen.
        await { vm.aiConversations.state.value.selected?.draft?.text == "" }
        assertEquals(
            "",
            ui.onNodeWithTag("ai-draft").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text,
        )
    }

    @Test
    fun theMascotLeavesTheLastBlockReachableAndStaysOutOfMockExams() {
        start("qa-single", "顺序练习")
        ui.onNodeWithTag("ai-mascot").assertIsDisplayed()
        // With real taps: the last block of the page scrolls clear of the floating mascot.
        ui.onNodeWithTag("question-list").performScrollToNode(hasText("题目信息"))
        ui.onNodeWithTag("question-list").performTouchInput { swipeUp() }
        ui.waitForIdle()
        ui.onNode(hasText("题目信息") and hasClickAction()).performClick()
        await { exists(hasText("原文位置")) }
        assertFalse(exists(hasTestTag("ai-mascot-menu")))

        // A mock exam keeps the old limits: no mascot, no AI entry.
        ui.runOnIdle { vm.startExam(vm.questions.filter { it.id == "qa-single" }, 1, 30) }
        await { vm.page == Page.QUESTION && vm.mode == "模拟考试" && exists(hasTestTag("question-list")) }
        assertFalse(exists(hasTestTag("ai-mascot")))
        assertFalse(exists(hasContentDescription("AI 学习助手")))
    }
}
