package cn.embeddedquiz

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** WebView request routing only; no Compose layout or CSS changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
class QuickNoteWebTransportTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val directory = File(app.filesDir, "web-transport-${UUID.randomUUID()}")
    private lateinit var notes: QuickNotesStateHolder
    private lateinit var reader: Any
    private lateinit var client: WebViewClient
    private lateinit var view: WebView
    private lateinit var documentId: String
    private lateinit var html: String
    private val readerClass = Class.forName("cn.embeddedquiz.ReaderWeb")
    private val image = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=")
    private val documentUrl get() = "https://appassets.androidplatform.net/quicknotes/$documentId/document.html"

    @Before
    fun setup() {
        val repository = QuickNotesRepository(directory)
        val imported = runBlocking {
            repository.importDocument(QuickNotePackage("test.md", "test.md", "# 中文\n![图](a.png)".toByteArray(), mapOf("a.png" to image)))
        } as QuickNoteImportResult.Imported
        documentId = imported.document.id
        notes = QuickNotesStateHolder(scope, repository, app)
        val constructor = readerClass.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }
        reader = constructor.newInstance(documentId, setOf("中文"), QuickNotePosition())
        val clientClass = Class.forName("cn.embeddedquiz.ReaderClient")
        val clientConstructor = clientClass.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }
        client = clientConstructor.newInstance(app, reader, notes, {}) as WebViewClient
        view = WebView(app)
        val body = QuickNoteMarkdown().render(javaClass.getResource("/quicknotes/linux.md")!!.readText(Charsets.UTF_8)).htmlBody
        html = quickNoteHtml(body, QuickNoteColors(Color.WHITE, Color.BLACK, Color.GRAY, Color.BLUE, Color.GRAY, Color.LTGRAY, Color.YELLOW, Color.BLACK, false), "regression-nonce")
    }
    @After
    fun cleanup() { notes.shutdown(); scope.cancel(); view.destroy(); directory.deleteRecursively() }
    private fun load(value: String = html) {
        readerClass.getDeclaredMethod("load", WebView::class.java, String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(reader, view, value, Color.WHITE)
    }
    private fun request(url: String, main: Boolean = true): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun hasGesture() = false
        override fun isRedirect() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Test
    fun originalTransportReproduces404ButPatchedTransportServesUtf8MainDocument() {
        load()
        assertEquals(documentUrl, shadowOf(view).lastLoadedUrl)
        val response = client.shouldInterceptRequest(view, request(documentUrl))!!
        assertEquals(200, response.statusCode)
        assertEquals("text/html", response.mimeType)
        assertEquals("utf-8", response.encoding)
        assertEquals(html, response.data.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    @Test
    fun themeReloadUpdatesOnlyTrustedDocumentBytesAtStableLocalUrl() {
        load()
        val changed = html.replace("regression-nonce", "new-theme-nonce")
        load(changed)
        val response = client.shouldInterceptRequest(view, request(documentUrl))!!
        assertEquals(200, response.statusCode)
        val body = response.data.bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertEquals(changed, body)
        assertTrue(body.contains("Content-Security-Policy"))
        assertTrue(body.contains("new-theme-nonce"))
    }

    @Test
    fun arbitraryDataFilesOtherDocumentsAndSubframesCannotReadMainHtml() {
        load()
        val denied = listOf("data:text/html;charset=utf-8;base64,", "file:///private.md", "content://private/doc", documentUrl + "?unexpected=1", documentUrl.replace(documentId, UUID.randomUUID().toString()), documentUrl.replace("https:", "http:"))
        denied.forEach { assertEquals(it, 404, client.shouldInterceptRequest(view, request(it))!!.statusCode) }
        assertEquals(404, client.shouldInterceptRequest(view, request(documentUrl, false))!!.statusCode)
    }

    @Test
    fun documentImagesStillComeFromTheIndependentLocalAssetStore() {
        load()
        val url = "https://appassets.androidplatform.net/quicknotes/$documentId/assets/a.png"
        val response = client.shouldInterceptRequest(view, request(url, false))!!
        // Existing image response uses the framework's optional/unset status (0) constructor.
        assertTrue(response.statusCode == 0 || response.statusCode == 200)
        assertEquals("image/png", response.mimeType)
        assertArrayEquals(image, response.data.use { it.readBytes() })
    }

    @Test
    fun missingAndExternalResourcesStayBlocked() {
        load()
        listOf("https://appassets.androidplatform.net/quicknotes/$documentId/assets/missing.png", "https://example.com/a.png", "https://appassets.androidplatform.net.evil/quicknotes/$documentId/assets/a.png", "https://appassets.androidplatform.net/unknown.js").forEach {
            assertEquals(it, 404, client.shouldInterceptRequest(view, request(it, false))!!.statusCode)
        }
    }

    @Test
    fun fragmentRequestsForThisMainDocumentKeepTheSameUtf8Content() {
        load()
        val response = client.shouldInterceptRequest(view, request(documentUrl + "#%E4%B8%AD%E6%96%87"))!!
        assertEquals(200, response.statusCode)
        assertEquals(html, response.data.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}
