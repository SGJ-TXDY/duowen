package cn.embeddedquiz

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

/** 设置 › AI 模型 (PLAN-1.7.5) over GPT's real AiProviderStore, with Keys kept in memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
class AiModelSettingsUiTest {
    @get:Rule(order = 0)
    val strandedMainQueue = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                ComposeMainQueue.deliverStrandedCallbacks()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1) val ui = createComposeRule()

    private val vault = mutableMapOf<String, String>()
    private val keys =
        object : AiProviderKeys {
            override fun read(id: String) = vault[id].orEmpty()
            override fun save(id: String, value: String) {
                if (value.isEmpty()) vault.remove(id) else vault[id] = value
            }
            override fun exists(id: String) = vault[id]?.isNotEmpty() == true
        }
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val owner = ViewModelStore()
    private lateinit var store: AiProviderStore
    private lateinit var vm: QuizViewModel

    @Before
    fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        val prefs = app.getSharedPreferences("ai-model-ui-test", 0)
        prefs.edit().clear().commit()
        store = AiProviderStore(prefs, keys)
        ui.runOnUiThread {
            vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    requireNotNull(modelClass.cast(QuizViewModel(app, store)))
            })[QuizViewModel::class.java]
            vm.navigate(Page.SETTINGS_AI)
        }
        ui.setContent {
            MaterialTheme {
                if (vm.page == Page.SETTINGS_AI) AiModelSettings(vm) else AiDiscussionsScreen(vm)
            }
        }
        ui.waitForIdle()
    }

    @After
    fun cleanup() {
        ui.runOnUiThread { owner.clear() }
        if (::vm.isInitialized) {
            ui.waitUntil(20_000) {
                shadowOf(Looper.getMainLooper()).idle()
                vm.examResourcesClosed?.isCompleted == true
            }
        }
        app.deleteDatabase("embeddedquiz.db")
    }

    private fun scrollTo(matcher: SemanticsMatcher) = ui.onNodeWithTag("settings-ai").performScrollToNode(matcher)

    private fun fill(name: String, url: String, model: String, key: String) {
        scrollTo(hasTestTag("provider-name"))
        ui.onNodeWithTag("provider-name").performTextReplacement(name)
        ui.onNodeWithTag("provider-url").performTextReplacement(url)
        ui.onNodeWithTag("provider-model").performTextReplacement(model)
        ui.onNodeWithTag("provider-key").performTextReplacement(key)
    }

    private fun save() {
        scrollTo(hasTestTag("provider-save"))
        ui.onNodeWithTag("provider-save").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("正在保存…").fetchSemanticsNodes().isEmpty() }
        ui.waitForIdle()
    }

    private fun current() = ui.onNodeWithTag("ai-current-model").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }

    @Test
    fun aServiceIsAddedEditedSwitchedAndDeleted() {
        assertEquals("DeepSeek · ${DeepSeekClient.FLASH}", current())
        scrollTo(hasTestTag("provider-add"))
        ui.onNodeWithTag("provider-add").performClick()
        fill("实验室网关", "https://gw.example.com/v1", "lab-chat", "sk-lab-1")
        ui.onNodeWithTag("provider-tools").performClick()
        save()

        // Added, stored with its own encrypted Key slot, and in use.
        val saved = store.profiles().single()
        assertEquals("https://gw.example.com/v1/chat/completions", saved.url)
        assertTrue(saved.supportsTools)
        assertEquals("lab-chat", store.selectedModel())
        assertEquals("实验室网关 · lab-chat", current())
        ui.onAllNodesWithTag("provider-editor").assertCountEquals(0)
        ui.onNode(hasTestTag("provider-row") and hasText("lab-chat · gw.example.com · Key 已保存 · 支持工具")).assertExists()

        // Same host, Key left empty: the saved Key stays.
        ui.onNodeWithContentDescription("编辑「实验室网关」").performClick()
        fill("实验室网关 2", "https://gw.example.com/v2", "lab-chat", "")
        save()
        assertEquals("实验室网关 2", store.profiles().single().name)
        assertTrue(store.hasKey(saved.id))

        // Another host needs a new Key; the form stays with what was typed.
        ui.onNodeWithContentDescription("编辑「实验室网关 2」").performClick()
        fill("实验室网关 2", "https://other.example.net/v1", "lab-chat", "")
        ui.onNodeWithText("地址换到了其他域名，需要重新填写 Key。").assertExists()
        save()
        ui.onNodeWithTag("provider-error").assertExists()
        assertEquals("https://gw.example.com/v2/chat/completions", store.profiles().single().url)
        ui.onNodeWithTag("provider-key").performTextReplacement("sk-other")
        save()
        assertEquals("https://other.example.net/v1/chat/completions", store.profiles().single().url)

        // Back to the built-in model, then to the service again.
        scrollTo(hasText(DeepSeekClient.PRO))
        ui.onNode(hasText(DeepSeekClient.PRO) and hasClickAction()).performClick()
        ui.waitForIdle()
        assertEquals(DeepSeekClient.PRO, vm.model)
        assertEquals(DeepSeekClient.PRO, store.selectedModel())
        assertEquals("DeepSeek · ${DeepSeekClient.PRO}", current())
        ui.onNodeWithTag("provider-row").performClick()
        ui.waitForIdle()
        assertEquals("lab-chat", store.selectedModel())

        // Deleting the service in use falls back to the built-in model and drops its Key.
        ui.onNodeWithContentDescription("编辑「实验室网关 2」").performClick()
        scrollTo(hasTestTag("provider-delete"))
        ui.onNodeWithTag("provider-delete").performClick()
        ui.onNodeWithTag("provider-delete-confirm").performClick()
        ui.waitForIdle()
        assertTrue(store.profiles().isEmpty())
        assertTrue(vault.isEmpty())
        assertTrue(store.selectedModel() in DeepSeekClient.MODELS)
        assertTrue(current().startsWith("DeepSeek · "))
    }

    @Test
    fun anAddressThatIsNotHttpsIsRefusedWithItsReason() {
        scrollTo(hasTestTag("provider-add"))
        ui.onNodeWithTag("provider-add").performClick()
        fill("明文", "http://plain.example.com/v1", "plain-chat", "sk-1")
        save()
        ui.onNodeWithTag("provider-error").assertExists()
        ui.onNodeWithText("请输入不含凭据、查询参数或片段的 HTTPS 地址", substring = true).assertExists()
        assertTrue(store.profiles().isEmpty())
        assertTrue(vault.isEmpty())
    }

    @Test
    fun aServiceSelectedInSettingsReceivesTheDiscussionOverHttps() = runBlocking {
        withProviderTestNetwork {
            vm.db.aiServices.configure(AiRuntimeConfig({ "" }, AiChatTransport(client = client), providers = store))
            ui.waitUntil(20_000) {
                shadowOf(Looper.getMainLooper()).idle()
                vm.ready || vm.startupState is StartupState.Error
            }
            assertTrue(vm.startupState.toString(), vm.ready)
            scrollTo(hasTestTag("provider-add"))
            ui.onNodeWithTag("provider-add").performClick()
            fill("Local", a.providerEndpoint("/v1"), "vendor/original", "fixture-ui-key")
            save()
            assertEquals("vendor/original", vm.model)

            // Editing the selected service keeps the selection and updates the model used to send.
            ui.onNodeWithContentDescription("编辑「Local」").performClick()
            fill("Local", a.providerEndpoint("/v2"), "vendor/selected", "")
            save()
            assertEquals("vendor/selected", vm.model)

            scrollTo(hasText(DeepSeekClient.PRO))
            ui.onNode(hasText(DeepSeekClient.PRO) and hasClickAction()).performClick()
            ui.runOnIdle { assertEquals(DeepSeekClient.PRO, vm.model) }
            scrollTo(hasTestTag("provider-row"))
            ui.onNodeWithTag("provider-row").performClick()
            ui.runOnIdle { assertEquals("vendor/selected", vm.model) }

            a.enqueue(providerTestReply())
            ui.runOnIdle { vm.navigate(Page.AI_DISCUSSIONS) }
            ui.onNodeWithTag("ai-new-free").performClick()
            ui.onNodeWithTag("ai-free-field").performTextInput("Send from the selected service")
            ui.onNodeWithTag("ai-free-send").performClick()
            ui.waitUntil(20_000) {
                shadowOf(Looper.getMainLooper()).idle()
                ui.onAllNodesWithText("verified answer", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            val request = a.providerRequest()
            assertEquals("/v2/chat/completions", request.path)
            assertEquals("Bearer fixture-ui-key", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("vendor/selected", body.getString("model"))
            assertTrue(body.getJSONArray("messages").toString().contains("Send from the selected service"))
            assertEquals(1, a.requestCount)
            assertEquals(0, b.requestCount)
        }
    }
}
