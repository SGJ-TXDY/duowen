package cn.embeddedquiz

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import java.net.Proxy
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiProviderRoutingTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private fun id() = UUID.randomUUID().toString()
    private class Keys : AiProviderKeys {
        val values = mutableMapOf("" to "built-in-fixture")
        override fun read(id: String) = values[id].orEmpty()
        override fun save(id: String, value: String) { if (value.isEmpty()) values.remove(id) else values[id] = value }
        override fun exists(id: String) = id in values
    }

    private fun store(keys: Keys = Keys()) = AiProviderStore(app.getSharedPreferences("providers-${id()}", 0), keys)
    private fun <T> ok(result: ResultOf<T>): T = when (result) {
        is ResultOf.Ok -> result.value
        is ResultOf.Rejected -> error("Unexpected rejection: ${result.reason.code}")
    }

    @Test fun selectedProviderFromViewModelReachesHttpsThroughTheWorkbenchAndSurvivesReopen() = runBlocking {
        network {
            val providers = store()
            val application = app as Application
            var profileId = ""
            fun waitOnMain(condition: () -> Boolean) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (!condition() && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    LockSupport.parkNanos(1_000_000)
                }
                assertTrue("ViewModel operation did not complete", condition())
            }
            fun model(owners: ViewModelStore): QuizViewModel = ViewModelProvider(owners, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    requireNotNull(modelClass.cast(QuizViewModel(application, providers)))
            })[QuizViewModel::class.java]
            app.deleteDatabase("embeddedquiz.db")
            val firstOwner = ViewModelStore()
            val vm = model(firstOwner)
            try {
                vm.db.aiServices.configure(AiRuntimeConfig({ "built-in-fixture" }, AiChatTransport(client = client),
                    providers = providers))
                waitOnMain { vm.ready || vm.startupState is StartupState.Error }
                assertTrue(vm.ready)
                val profile = vm.saveProvider(null, "Local", a.endpoint("/v1"), "vendor/original", "fixture-ui-key")
                profileId = profile.id
                assertEquals(listOf(profile), vm.providerProfiles)
                assertTrue(vm.hasProviderKey(profile.id))
                assertEquals(DeepSeekClient.FLASH, vm.model)
                vm.chooseModel(profile.model)
                val edited = vm.saveProvider(profile.id, "Local edited", profile.url, "vendor/selected", "",
                    supportsTools = true)
                assertEquals(edited.model, vm.model)
                assertTrue(vm.providerProfiles.single().supportsTools)
                assertFalse(vm.hasKey)

                a.enqueue(reply())
                val workbench = vm.aiWorkbench
                workbench.newConversation()
                workbench.freeText = "send from selected model"
                val sending = workbench.startFree()
                waitOnMain { sending.isCompleted }
                assertNotNull(workbench.opened)
                val request = a.request()
                assertEquals("/v1/chat/completions", request.path)
                assertEquals("Bearer fixture-ui-key", request.getHeader("Authorization"))
                assertEquals("vendor/selected", JSONObject(request.body.readUtf8()).getString("model"))
                withTimeout(15000) { ok(vm.aiConversations.awaitIdle()) }
                assertEquals(0, b.requestCount)
            } finally {
                firstOwner.clear()
                waitOnMain { vm.examResourcesClosed?.isCompleted == true }
            }
            val nextOwner = ViewModelStore()
            val reopened = model(nextOwner)
            try {
                assertEquals("vendor/selected", reopened.model)
                assertEquals(profileId, reopened.providerProfiles.single().id)
                assertTrue(reopened.hasProviderKey(profileId))
                reopened.deleteProvider(profileId)
                assertTrue(reopened.providerProfiles.isEmpty())
                assertFalse(reopened.hasProviderKey(profileId))
                assertEquals(DeepSeekClient.FLASH, reopened.model)
                assertEquals(DeepSeekClient.FLASH, providers.selectedModel())
            } finally {
                nextOwner.clear()
                waitOnMain { reopened.examResourcesClosed?.isCompleted == true }
                app.deleteDatabase("embeddedquiz.db")
            }
        }
    }

    @Test fun crossingOriginRequiresFreshKeyAndSameOriginPathStillInvalidatesRetry() {
        val keys = Keys()
        val store = store(keys)
        val profile = store.save(null, "A", "https://example.com/v1/", "vendor/model", "key-fixture-a")
        assertEquals("https://example.com/v1/chat/completions", profile.url)
        assertEquals("key-fixture-a", store.resolve(profile.model, profile.route).key)
        store.select(profile.model)
        for (url in listOf("https://other.example/v1", "https://example.com:4433/v1")) {
            assertTrue(runCatching { store.save(profile.id, "B", url, profile.model, "") }.isFailure)
            assertEquals(profile, store.profiles().single())
        }
        val nameAndKey = store.save(profile.id, "Renamed", profile.url, profile.model, "key-fixture-b")
        assertEquals(profile.route, nameAndKey.route)
        assertFalse(keys.exists(profile.slot))
        assertEquals("key-fixture-b", store.resolve(profile.model, profile.route).key)
        val path = store.save(profile.id, "Path", "https://example.com/v2", profile.model, "")
        assertEquals(profile.id, path.id)
        assertNotEquals(profile.route, path.route)
        assertTrue(runCatching { store.resolve(profile.model, profile.route) }.exceptionOrNull() is AiProviderUnavailable)
        val fresh = store.save(path.id, "Fresh", "https://other.example/v1", path.model, "key-fixture-c")
        assertEquals("key-fixture-c", store.resolve(fresh.model, fresh.route).key)
        assertFalse(keys.exists(path.slot))
        assertEquals("built-in-fixture", keys.read(""))
        store.delete(fresh.id)
        assertEquals(DeepSeekClient.FLASH, store.selectedModel())
        assertFalse(keys.exists(fresh.slot))
        assertTrue(runCatching { store.resolve(fresh.model, fresh.route) }.exceptionOrNull() is AiProviderUnavailable)
    }

    @Test fun unsafeAddressesAndDuplicateModelIdsAreRejected() {
        val store = store()
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com?key=x",
            "https://example.com/#private", "https://exa\nmple.com").forEach {
            assertTrue(runCatching { store.save(null, "test", it, "custom-model", "fixture") }.isFailure)
        }
        val profile = store.save(null, "test", "https://example.com", "custom-model", "fixture")
        assertTrue(runCatching { store.save(null, "other", "https://elsewhere.example", profile.model, "fixture") }.isFailure)
    }

    @Test fun clearingBuiltInKeyLeavesCustomVaultSlotsIntact() {
        val credentials = app.getSharedPreferences("credentials", 0)
        val slot = id()
        try {
            credentials.edit().putString("value", "built-in-ciphertext").putString("iv", "built-in-iv")
                .putString("custom.$slot.value", "fixture-ciphertext").putString("custom.$slot.iv", "fixture-iv").commit()
            val vault = KeyVault(app)
            vault.save("")
            assertFalse(vault.exists())
            assertTrue(vault.existsSlot(slot))
            vault.saveSlot(slot, "")
            assertFalse(vault.existsSlot(slot))
        } finally { credentials.edit().clear().commit() }
    }

    private class Network(val a: MockWebServer, val b: MockWebServer, val client: OkHttpClient)
    private suspend fun network(test: suspend Network.() -> Unit) {
        // Self-signed loopback fixture. Only this test client trusts the certificate.
        val password = "fixture-only".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            requireNotNull(AiProviderRoutingTest::class.java.getResourceAsStream("/ai-provider-localhost.p12")).use { load(it, password) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }.keyManagers
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("localhost", keys.getCertificate("localhost"))
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        fun server() = MockWebServer().apply {
            protocols = listOf(Protocol.HTTP_1_1)
            protocolNegotiationEnabled = false
            useHttps(serverContext.socketFactory, false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val a = server()
        val b = server()
        val client = OkHttpClient.Builder().sslSocketFactory(clientContext.socketFactory, manager)
            .protocols(listOf(Protocol.HTTP_1_1)).proxy(Proxy.NO_PROXY).build()
        try { Network(a, b, client).test() }
        finally { a.shutdown(); b.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    private fun MockWebServer.endpoint(path: String) = url(path).newBuilder().host("127.0.0.1").build().toString()
    private suspend fun MockWebServer.request(): RecordedRequest = withContext(Dispatchers.IO) {
        requireNotNull(takeRequest(10, TimeUnit.SECONDS)) { "No HTTPS request reached the fixture" }
    }

    private fun reply() = MockResponse().setBody("""{"choices":[{"index":0,"finish_reason":"stop",
        "message":{"role":"assistant","content":"verified answer"}}],
        "usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":10}}""")

    private suspend fun withRuntime(config: AiRuntimeConfig, test: suspend (QuizRepository, AiConversationFacade) -> Unit) {
        val name = "provider-${id().take(8)}.db"
        val db = QuizDatabase.open(app, name)
        try {
            db.aiServices.configure(config)
            val repo = QuizRepository(db)
            val service = repo.aiConversations
            withTimeout(10000) { service.state.first { it.phase == ServicePhase.READY } }
            test(repo, service)
        } finally {
            withContext(NonCancellable) { withTimeout(15000) { db.aiLifecycle.closing().joinAll() } }
            db.close()
            app.deleteDatabase(name)
        }
    }

    @Test fun tlsRetriesStayFrozenAndRejectEndpointChangesOrRedirects() = runBlocking {
        network {
            val store = store()
            val profile = store.save(null, "A", a.endpoint("/v1"), "vendor/model", "fixture-a-key")
            val config = AiRuntimeConfig({ error("Custom requests cannot use the built-in Key") },
                transport = AiChatTransport(client = client), providers = store)
            withRuntime(config) { repo, service ->
                val original = AiFreeDiscussionCommand(id(), "private-origin-text", profile.model)
                a.enqueue(MockResponse().setResponseCode(307).addHeader("Location", b.endpoint("/capture")))
                val first = ok(service.startFreeDiscussion(original))
                val sent = a.request()
                val originalBody = sent.body.readUtf8()
                assertEquals("/v1/chat/completions", sent.path)
                assertEquals("Bearer fixture-a-key", sent.getHeader("Authorization"))
                assertEquals(profile.model, JSONObject(originalBody).getString("model"))
                assertFalse(JSONObject(originalBody).has("thinking"))
                withTimeout(15000) { ok(service.awaitIdle()) }
                assertEquals(0, b.requestCount)
                a.enqueue(MockResponse().setResponseCode(500))
                val retry = ok(service.retry(RetryCommand(id(), first.owner)))
                assertEquals(originalBody, a.request().body.readUtf8())
                withTimeout(15000) { ok(service.awaitIdle()) }
                val originalRow = requireNotNull(repo.db.ai().run(retry.owner.requestId))
                assertEquals("FAILED", originalRow.status)
                assertEquals(profile.route, AiFrozenRoute.read(JSONObject(originalRow.contextSnapshot)))
                val changed = store.save(profile.id, "B", b.endpoint("/v2"), profile.model, "fixture-b-key")
                val stale = service.retry(RetryCommand(id(), retry.owner)) as ResultOf.Rejected
                assertEquals(RejectCode.PROVIDER_CHANGED, stale.reason.code)
                assertEquals(first, ok(service.startFreeDiscussion(original)))
                assertEquals(0, b.requestCount)
                b.enqueue(reply())
                val fresh = ok(service.startFreeDiscussion(AiFreeDiscussionCommand(id(), "new-service-text", changed.model)))
                val request = b.request()
                assertEquals("Bearer fixture-b-key", request.getHeader("Authorization"))
                assertEquals("/v2/chat/completions", request.path)
                assertFalse(request.body.readUtf8().contains("private-origin-text"))
                withTimeout(15000) { ok(service.awaitIdle()) }
                val row = requireNotNull(repo.db.ai().run(fresh.owner.requestId))
                assertEquals("COMPLETED", row.status)
                assertNull(row.estimatedUsd)
                assertNotEquals(originalRow.model, row.model)
                val costs = AiDeletedCostLedger.read(repo.db.openHelper.readableDatabase)
                assertEquals(setOf(originalRow.model, row.model), costs.map { it.model }.toSet())
                val backup = repo.backup()
                assertEquals(7, JSONObject(backup).getInt("backupVersion"))
                assertFalse(backup.contains("fixture-a-key"))
                assertFalse(backup.contains("fixture-b-key"))
                assertFalse(backup.contains(profile.url))
                assertFalse(backup.contains(changed.url))
                repo.restore(backup)
                assertEquals(RejectCode.PROVIDER_CHANGED, (service.retry(RetryCommand(id(), retry.owner)) as ResultOf.Rejected).reason.code)
                assertEquals(1, b.requestCount)
            }
        }
    }

    @Test fun cacheAndCostBucketsAreScopedToTheFrozenRoute() = runBlocking {
        network {
            val store = store()
            val first = store.save(null, "A", a.endpoint("/v1"), "same-model", "fixture-a")
            withRuntime(AiRuntimeConfig({ "" }, AiChatTransport(client = client), providers = store)) { repo, service ->
                repo.importBank("""{"schemaVersion":1,"questions":[{"id":"q","stem":"fixture","kind":"SHORT","source":"fixture"}]}""")
                val q = requireNotNull(repo.question("q"))
                val owner = QuestionOwner(q.id, q.contentHash)
                val s = ok(service.createSession(id(), owner)).session
                suspend fun send(): AcceptedReceipt {
                    val current = ok(service.open(s.sessionId)).session
                    return ok(service.send(SendCommand(id(), s.sessionId, owner, current.contextRevision, null, null,
                        "same request", emptySet(), "", AiRequestType.EXPLANATION, first.model)))
                }
                a.enqueue(reply())
                val original = send()
                a.request()
                withTimeout(15000) { ok(service.awaitIdle()) }
                val cached = send()
                withTimeout(15000) { ok(service.awaitIdle()) }
                assertEquals("CACHE", repo.db.ai().run(cached.owner.requestId)?.source)
                assertEquals(1, a.requestCount)

                val second = store.save(first.id, "B", b.endpoint("/v2"), first.model, "fixture-b")
                b.enqueue(reply())
                val switched = send()
                assertEquals("Bearer fixture-b", b.request().getHeader("Authorization"))
                withTimeout(15000) { ok(service.awaitIdle()) }
                val routeA = requireNotNull(repo.db.ai().run(original.owner.requestId))
                val routeB = requireNotNull(repo.db.ai().run(switched.owner.requestId))
                assertEquals(second.route, AiFrozenRoute.read(JSONObject(routeB.contextSnapshot)))
                assertNotEquals(routeA.cacheKey, routeB.cacheKey)
                assertEquals("NETWORK", routeB.source)
                val costs = AiDeletedCostLedger.read(repo.db.openHelper.readableDatabase).associateBy { it.model }
                assertEquals(1L, costs.getValue(routeA.model).cacheHits)
                assertEquals(1L, costs.getValue(routeB.model).networkCalls)
                assertEquals(1L, costs.getValue(routeB.model).unknownEstimateCalls)
            }
        }
    }

    @Test fun customToolsRequireOptInAndStopAtAChangedRouteBetweenHops() = runBlocking {
        network {
            val store = store()
            val profile = store.save(null, "A", a.endpoint("/v1"), "note-model", "fixture-a")
            val thirdDispatch = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var hops = 0
            val config = AiRuntimeConfig({ "" }, AiChatTransport(client = client),
                beforeDispatch = { if (++hops == 3) { thirdDispatch.complete(Unit); resume.await() } },
                providers = store, documentToolsEnabled = true)
            withRuntime(config) { repo, service ->
                val doc = requireNotNull(repo.db.quickNotesRepository.createDocument(
                    QuickNoteCreate(id(), "原文", "# 私有章节\n\n只在本机的原文。")))
                val session = ok(service.createDocumentSession(id(), doc)).session
                suspend fun send(input: String): AcceptedReceipt {
                    val s = ok(service.open(session.sessionId)).session
                    return ok(service.send(SendCommand(id(), s.sessionId, s.question, s.contextRevision, null, null,
                        input, emptySet(), "", AiRequestType.FOLLOW_UP, profile.model)))
                }
                a.enqueue(reply())
                val plain = send("请查原文")
                assertFalse(JSONObject(a.request().body.readUtf8()).has("tools"))
                withTimeout(15000) { ok(service.awaitIdle()) }
                assertEquals(AiSnapshotCodec.DOCUMENT_REQUEST_VERSION, repo.db.ai().run(plain.owner.requestId)?.contextSnapshotVersion)

                val enabled = store.save(profile.id, profile.name, profile.url, profile.model, "", supportsTools = true)
                a.enqueue(MockResponse().setBody("""{"choices":[{"index":0,"finish_reason":"stop",
                    "message":{"role":"assistant","content":null,"tool_calls":[
                    {"id":"list","type":"function","function":{"name":"list_sections","arguments":"{}"}}]}}],
                    "usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":10}}"""))
                val tool = send("请查全文")
                assertTrue(JSONObject(a.request().body.readUtf8()).has("tools"))
                withTimeout(8000) { thirdDispatch.await() }
                store.save(profile.id, "B", b.endpoint("/v2"), profile.model, "fixture-b", supportsTools = true)
                resume.complete(Unit)
                withTimeout(15000) { ok(service.awaitIdle()) }
                val row = requireNotNull(repo.db.ai().run(tool.owner.requestId))
                assertEquals("PROVIDER_CHANGED", row.errorCode)
                assertEquals(AiSnapshotCodec.DOCUMENT_AGENT_VERSION, row.contextSnapshotVersion)
                assertEquals(enabled.route, AiFrozenRoute.read(JSONObject(row.contextSnapshot)))
                assertEquals(100L, row.inputTokens)
                assertEquals(2, a.requestCount)
                assertEquals(0, b.requestCount)
            }
        }
    }

    @Test fun editingAddressBetweenAcceptanceAndDispatchDoesNotSendFrozenText() = runBlocking {
        network {
            val store = store()
            val profile = store.save(null, "A", a.endpoint("/v1"), "same-model", "fixture-a")
            val entering = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            withRuntime(AiRuntimeConfig({ "" }, AiChatTransport(client = client),
                beforeDispatch = { entering.complete(Unit); resume.await() }, providers = store)) { repo, service ->
                val accepted = ok(service.startFreeDiscussion(AiFreeDiscussionCommand(id(), "frozen", profile.model)))
                withTimeout(8000) { entering.await() }
                store.save(profile.id, "B", b.endpoint("/v2"), profile.model, "fixture-b")
                resume.complete(Unit)
                withTimeout(15000) { ok(service.awaitIdle()) }
                val row = requireNotNull(repo.db.ai().run(accepted.owner.requestId))
                assertEquals("PROVIDER_CHANGED", row.errorCode)
                assertFalse(row.networkDispatched)
                assertEquals(0, a.requestCount + b.requestCount)
            }
        }
    }
}
