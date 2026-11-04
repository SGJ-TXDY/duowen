package cn.embeddedquiz

import android.content.Context
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
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
 * The question AI conversation against a real [AiStateHolder], Room database and a local
 * MockWebServer: no paid request, no stored API Key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiConversationUiTest {
    @get:Rule val ui = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bank =
        """{"schemaVersion":1,"questions":[{"id":"qa","stem":"甲题题干","kind":"SHORT","source":"测试"},{"id":"qb","stem":"乙题题干","kind":"SHORT","source":"测试"}]}"""
    private val reply =
        """{"choices":[{"message":{"content":"**要点**说明"}}],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":10}}"""
    private val dbName = "ai-ui-${System.nanoTime()}.db"
    private lateinit var db: QuizDatabase
    private lateinit var repo: QuizRepository
    private lateinit var holder: AiStateHolder
    private lateinit var scope: CoroutineScope
    private val server = MockWebServer()
    private val responses = LinkedBlockingQueue<() -> MockResponse>()
    private val gates = mutableListOf<CountDownLatch>()
    private val notices = mutableListOf<String>()
    private lateinit var a: Question
    private lateinit var b: Question

    @Before
    fun setup() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    responses.poll(5, TimeUnit.SECONDS)?.invoke() ?: MockResponse().setResponseCode(599)
            }
        server.start()
        db = QuizDatabase.open(context, dbName)
        repo = QuizRepository(db)
        runBlocking {
            repo.importBank(bank)
            a = repo.question("qa")!!
            b = repo.question("qb")!!
        }
        ui.runOnUiThread {
            // The production holder lives in viewModelScope, which runs on Main.immediate.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            holder =
                AiStateHolder(
                    scope,
                    repo,
                    KeyVault(context),
                    context.getSharedPreferences("ai-ui-test", 0),
                    client = DeepSeekClient(endpoint = server.url("/chat/completions").toString()),
                    readKey = { "test-key" },
                    notify = { notices += it },
                )
        }
    }

    @After
    fun cleanup() {
        gates.forEach { it.countDown() }
        val owner = scope.coroutineContext[Job]!!
        ui.runOnUiThread { owner.cancel() }
        // Job completion includes the collectors, request and their finally blocks.
        // Close Room only after this owned-resource completion barrier.
        await { owner.isCompleted }
        db.close()
        context.deleteDatabase(dbName)
        server.shutdown()
    }

    /** Next response waits for the returned gate, so the test controls when it arrives. */
    private fun slow(response: MockResponse): CountDownLatch {
        val gate = CountDownLatch(1)
        gates += gate
        responses += {
            gate.await(10, TimeUnit.SECONDS)
            response
        }
        return gate
    }

    private fun await(condition: () -> Boolean) {
        ui.waitUntil(20000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        ui.waitForIdle()
    }

    private fun prompt(request: RecordedRequest) =
        JSONObject(request.body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")

    private fun show(initial: Question, settings: LearningSettings = LearningSettings()): MutableState<Question> {
        val current = mutableStateOf(initial)
        val theme = mutableStateOf(settings)
        shown = theme
        ui.setContent {
            AppTheme(theme.value) {
                val q = current.value
                LaunchedEffect(q.id, q.contentHash) { holder.observe(q) }
                var prompt by remember(q.id) { mutableStateOf("") }
                AiConversationSection(holder, q, emptySet(), prompt, { prompt = it }, open = true, onToggle = {})
            }
        }
        ui.waitForIdle()
        return current
    }

    private var shown: MutableState<LearningSettings>? = null

    /** The one-tap prompt; a saved or pending bubble may show the same words but is not clickable. */
    private fun chip(text: String) = ui.onNode(hasText(text) and hasClickAction())

    private fun pendingText(text: String) =
        ui.onNode(hasText(text) and hasAnyAncestor(hasTestTag("ai-pending-bubble")))

    private fun assertNoRequestUi() {
        ui.onAllNodesWithTag("ai-pending-bubble").assertCountEquals(0)
        ui.onAllNodesWithTag("ai-retry").assertCountEquals(0)
        ui.onAllNodesWithTag("ai-cancel").assertCountEquals(0)
        ui.onAllNodesWithText("正在分析…").assertCountEquals(0)
    }

    @Test
    fun slowRequestOnAStaysOnAAndItsFailureNeverReachesB() {
        val current = show(a)
        val gate = slow(MockResponse().setResponseCode(500))
        chip("生成详细解析").performClick()
        assertTrue(prompt(server.takeRequest(5, TimeUnit.SECONDS)!!).contains("甲题题干"))
        ui.waitForIdle()
        ui.onNodeWithTag("ai-pending-bubble").assertExists()
        // Both the section summary and the progress row say so.
        ui.onAllNodesWithText("正在分析…").assertCountEquals(2)

        ui.runOnIdle { current.value = b }
        ui.waitForIdle()
        assertNoRequestUi()
        ui.onNodeWithTag("ai-other-busy").assertExists()
        // Sending is blocked for a stated reason, not silently.
        chip("生成详细解析").assertIsNotEnabled()
        ui.onNodeWithContentDescription("发送追问").assertIsNotEnabled()

        gate.countDown()
        await { holder.request?.busy == false }
        assertNoRequestUi()
        ui.onAllNodesWithText("AI 服务暂时不可用").assertCountEquals(0)
        ui.onAllNodesWithTag("ai-other-busy").assertCountEquals(0)
        chip("生成详细解析").assertIsEnabled()

        // Back on A the failure is shown with A's prompt and a retry that resends A.
        ui.runOnIdle { current.value = a }
        ui.waitForIdle()
        ui.onNodeWithTag("ai-pending-bubble").assertExists()
        ui.onNodeWithText("AI 服务暂时不可用").assertExists()
        responses += { MockResponse().setBody(reply) }
        ui.onNodeWithTag("ai-retry").performClick()
        assertTrue(prompt(server.takeRequest(5, TimeUnit.SECONDS)!!).contains("甲题题干"))
        await { holder.request == null && holder.entries.size == 1 }
        assertNoRequestUi()
        ui.onAllNodesWithTag("ai-user-bubble").assertCountEquals(1)
        ui.onNodeWithText("AI 建议，未核实", substring = true).assertExists()
        // Markdown is still rendered: the bold markers are gone.
        ui.onNodeWithText("要点说明", substring = true).assertExists()
        ui.onAllNodesWithText("**", substring = true).assertCountEquals(0)
        assertEquals(listOf("qa"), runBlocking { repo.dao.allAi().map { it.questionId } })

        // B never received A's history either.
        ui.runOnIdle { current.value = b }
        ui.waitForIdle()
        ui.onAllNodesWithTag("ai-user-bubble").assertCountEquals(0)
        assertNoRequestUi()
    }

    @Test
    fun cacheHitAndSuccessLeaveNoPendingOrDuplicateBubble() {
        show(a)
        responses += { MockResponse().setBody(reply) }
        chip("生成详细解析").performClick()
        await { holder.request == null && holder.entries.size == 1 }
        ui.onAllNodesWithTag("ai-user-bubble").assertCountEquals(1)
        assertNoRequestUi()

        chip("生成详细解析").performClick()
        await { !holder.busy }
        assertTrue(notices.any { it.contains("缓存") })
        assertEquals(1, server.requestCount)
        assertNull(holder.request)
        ui.onAllNodesWithTag("ai-user-bubble").assertCountEquals(1)
        assertNoRequestUi()
    }

    @Test
    fun changedQuestionVersionHidesTheOldVersionRequestUntilYouReturn() {
        val current = show(a)
        responses += { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        ui.onNodeWithTag("ai-input").performTextInput("为什么")
        ui.onNodeWithContentDescription("发送追问").performClick()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        ui.waitForIdle()
        pendingText("为什么").assertExists()

        val revised = a.copy(stem = "甲题题干（修订）")
        assertNotEquals(a.contentHash, revised.contentHash)
        ui.runOnIdle { current.value = revised }
        ui.waitForIdle()
        assertNoRequestUi()
        ui.onNodeWithTag("ai-other-busy").assertExists()

        ui.runOnIdle { current.value = a }
        ui.waitForIdle()
        pendingText("为什么").assertExists()
        ui.onNodeWithTag("ai-cancel").performClick()
        await { !holder.busy }
        ui.onNodeWithText("请求已取消，可以重试").assertExists()
        ui.onNodeWithTag("ai-retry").assertIsEnabled()
        ui.onAllNodesWithTag("ai-user-bubble").assertCountEquals(0)
        assertTrue(runBlocking { repo.dao.allAi().isEmpty() })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun costLineUsesReadableTextColorInLightAndDark() {
        runBlocking {
            repo.dao.putAi(
                AiEntry(
                    "seed",
                    a.id,
                    DeepSeekClient.FLASH,
                    "生成详细解析",
                    "AI 建议，未核实\n解析",
                    100,
                    20,
                    10,
                    0.000123,
                    1,
                    a.contentHash,
                    "EXPLANATION",
                    1,
                )
            )
        }
        show(a)
        await { holder.entries.size == 1 }
        for (dark in listOf(false, true)) {
            ui.runOnIdle { shown!!.value = LearningSettings(theme = if (dark) "DARK" else "LIGHT") }
            ui.waitForIdle()
            val node = ui.onNodeWithTag("ai-cost")
            node.assertTextEquals("输入 100 / 输出 20 tokens · 估算 $0.000123")
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val drawn = layouts.single().layoutInput.style.color
            val colors = appColors(dark)
            assertEquals(colors.onSurfaceVariant, drawn)
            assertNotEquals(colors.outline, drawn)
            assertTrue("dark=$dark", contrast(drawn, colors.surfaceContainerLowest) >= 4.5)
        }
    }

    private fun contrast(x: Color, y: Color): Double {
        val l1 = x.luminance().toDouble()
        val l2 = y.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}
