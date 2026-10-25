package cn.embeddedquiz

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Images are only served from this virtual origin; everything else the page asks for gets a 404. */
private const val ASSET_HOST = "appassets.androidplatform.net"

/** Lets navigation outside the reader store the final reading position before the module hides. */
class QuickNoteReaderHandle {
    internal var capture: ((() -> Unit) -> Unit)? = null

    fun finish(then: () -> Unit) = capture?.invoke(then) ?: then()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickNoteReader(vm: QuizViewModel, document: QuickNoteDocument, handle: QuickNoteReaderHandle) {
    val notes = vm.quickNotes
    val id = document.summary.id
    val rendered = document.rendered
    val context = LocalContext.current
    val colors = quickNoteColors()
    val zoom = (LocalDensity.current.fontScale * 100).roundToInt()
    val web = remember(id) { ReaderWeb(id, rendered.headings.map { it.id }.toSet(), document.summary.position) }
    val saves = remember(id) { Channel<Unit>(Channel.CONFLATED) }
    SideEffect { web.onScrolled = { saves.trySend(Unit) } }
    var tocOpen by rememberSaveable { mutableStateOf(false) }
    var warningsOpen by rememberSaveable { mutableStateOf(false) }

    fun capture(then: () -> Unit = {}) =
        web.capture { position ->
            if (position != null) notes.savePosition(id, position)
            then()
        }
    DisposableEffect(handle, id) {
        handle.capture = { then -> capture(then) }
        onDispose { handle.capture = null }
    }
    // At most two position writes a second, with a trailing write once scrolling stops.
    LaunchedEffect(id) {
        for (tick in saves) {
            capture()
            delay(500)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, id) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) capture() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { capture { notes.closeDocument() } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { capture { notes.closeDocument() } },
                enabled = !notes.state.working,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(AppIcons.Back, contentDescription = "返回速记列表")
            }
            Text(
                document.summary.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(
                onClick = { tocOpen = true },
                enabled = rendered.headings.isNotEmpty(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(AppIcons.ListOrder, contentDescription = "目录")
            }
        }
        if (notes.state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (rendered.warnings.isNotEmpty())
                ReaderWarnings(rendered.warnings, warningsOpen) { warningsOpen = !warningsOpen }
            if (notes.state.error.isNotBlank()) ErrorNotice(notes.state.error)
            if (notes.positionSaveError.isNotBlank()) {
                ErrorNotice("阅读位置未保存：${notes.positionSaveError}")
                SecondaryAction("重试保存阅读位置") { notes.retryPositionWrites() }
            }
        }
        val html = remember(rendered.htmlBody, colors) { quickNoteHtml(rendered.htmlBody, colors, newNonce()) }
        AndroidView(
            factory = { ctx ->
                createReaderWebView(ctx, web, notes, colors.background) {
                    toast(ctx, "阅读页意外关闭，请重新打开")
                    notes.closeDocument()
                }
            },
            update = { view ->
                if (view.settings.textZoom != zoom) {
                    view.settings.textZoom = zoom
                    // Text reflows at a new size: return to the same heading instead of saving the jump.
                    web.reapply()
                }
                web.load(view, html, colors.background)
            },
            onRelease = { view ->
                web.view = null
                view.destroy()
            },
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("quick-note-reader"),
        )
    }

    if (tocOpen)
        ModalBottomSheet(onDismissRequest = { tocOpen = false }) {
            Text(
                "目录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(
                Modifier.testTag("quick-note-toc"),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(rendered.headings, key = { it.id }) { heading ->
                    Surface(
                        onClick = {
                            tocOpen = false
                            web.jump(heading.id)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            heading.title,
                            style =
                                if (heading.level <= 2) MaterialTheme.typography.bodyLarge
                                else MaterialTheme.typography.bodyMedium,
                            color =
                                if (heading.level <= 2) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier.padding(
                                    start = (24 + (heading.level - 1).coerceAtMost(3) * 16).dp,
                                    end = 24.dp,
                                    top = 12.dp,
                                    bottom = 12.dp,
                                ),
                        )
                    }
                }
            }
        }
}

@Composable
private fun ReaderWarnings(warnings: List<String>, open: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        color = MaterialTheme.status.warningContainer,
        contentColor = MaterialTheme.status.onWarningContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    "${warnings.size} 条显示提示",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Icon(
                    if (open) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = if (open) "收起显示提示" else "展开显示提示",
                    modifier = Modifier.size(18.dp),
                )
            }
            if (open) warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Page-side state for one open document; the WebView itself is owned by the AndroidView. */
private class ReaderWeb(val documentId: String, private val anchors: Set<String>, start: QuickNotePosition) {
    var view: WebView? = null
    private var loaded: String? = null
    val documentUrl = "https://$ASSET_HOST/quicknotes/$documentId/document.html"
    @Volatile var pageContent: ByteArray? = null
        private set
    /** Scroll events only count as reading once the saved position has been applied. */
    var restored = false
    /** Last touch, key or table-of-contents jump: scrolls without one are layout changes, not reading. */
    var lastInteraction = 0L
    var position = start
    var onScrolled: () -> Unit = {}

    fun load(view: WebView, html: String, background: Int) {
        if (loaded == html) return
        loaded = html
        restored = false
        view.setBackgroundColor(background)
        pageContent = html.toByteArray(Charsets.UTF_8)
        view.loadUrl(documentUrl)
    }

    fun restore() {
        val p = position
        view?.evaluateJavascript("qnRestore(${JSONObject.quote(p.anchor)}, ${p.offset}, ${p.fraction})") {
            restored = true
        }
    }

    fun reapply() {
        if (restored) view?.postDelayed({ restore() }, 300)
    }

    fun jump(anchor: String) {
        interacted()
        view?.evaluateJavascript("qnJump(${JSONObject.quote(anchor)})", null)
    }

    fun interacted() {
        lastInteraction = SystemClock.uptimeMillis()
    }

    fun scrolled() {
        // A fling keeps reporting scrolls after the finger lifts, so each one extends the window.
        if (!restored || SystemClock.uptimeMillis() - lastInteraction > 1500) return
        interacted()
        onScrolled()
    }

    /** Reads the position from the page; answers null if the page is not ready or never replies. */
    fun capture(onResult: (QuickNotePosition?) -> Unit) {
        val web = view
        if (web == null || !restored) return onResult(null)
        var done = false
        val handler = Handler(Looper.getMainLooper())
        val fallback = Runnable {
            if (!done) {
                done = true
                onResult(null)
            }
        }
        handler.postDelayed(fallback, 400)
        web.evaluateJavascript("window.qnPosition ? qnPosition() : null") { raw ->
            if (done) return@evaluateJavascript
            done = true
            handler.removeCallbacks(fallback)
            val parsed = parsePosition(raw, anchors)
            if (parsed != null) position = parsed
            onResult(parsed)
        }
    }
}

internal fun parsePosition(raw: String?, anchors: Set<String>): QuickNotePosition? =
    runCatching {
            val json = JSONObject(raw ?: return null)
            val offset = json.optDouble("offset", 0.0)
            val fraction = json.optDouble("fraction", 0.0)
            if (!offset.isFinite() || !fraction.isFinite()) return null
            val anchor = json.optString("anchor", "").takeIf { it in anchors }.orEmpty()
            QuickNotePosition(
                anchor,
                if (anchor.isEmpty()) 0.0 else offset.coerceIn(0.0, 10_000_000.0),
                fraction.coerceIn(0.0, 1.0),
            )
        }
        .getOrNull()

// The page script is the app's own; imported Markdown is escaped HTML and cannot add scripts.
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createReaderWebView(
    context: Context,
    web: ReaderWeb,
    notes: QuickNotesStateHolder,
    background: Int,
    onCrash: () -> Unit,
): WebView =
    WebView(context).apply {
        setBackgroundColor(background)
        settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = false
        }
        webViewClient = ReaderClient(context, web, notes, onCrash)
        // Touch is only observed, never consumed, so normal scrolling and links keep working.
        setOnTouchListener { _, _ ->
            web.interacted()
            false
        }
        setOnKeyListener { _, _, _ ->
            web.interacted()
            false
        }
        setOnScrollChangeListener { _, _, _, _, _ -> web.scrolled() }
        web.view = this
    }

private class ReaderClient(
    private val context: Context,
    private val web: ReaderWeb,
    private val notes: QuickNotesStateHolder,
    private val onCrash: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        when {
            url.scheme == "quicknote-copy" ->
                toast(context, if (notes.copyCode(url.schemeSpecificPart)) "已复制代码" else "无法复制这段代码")
            // In-page anchors resolve against the virtual origin; keep them in this document.
            url.host == ASSET_HOST -> url.fragment?.let { web.jump(it) }
            url.scheme in setOf("http", "https", "mailto") ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (e: ActivityNotFoundException) {
                    toast(context, "没有可以打开此链接的应用")
                }
            else -> toast(context, "不支持打开此类链接")
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        // Serve the trusted top-level document ourselves; never return 404 for its bootstrap.
        if (request.isForMainFrame && url.toString().substringBefore('#') == web.documentUrl) {
            web.pageContent?.let { body ->
                return WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(body))
            }
        }
        val prefix = "/quicknotes/${web.documentId}/assets/"
        val path = url.encodedPath.orEmpty()
        if (url.scheme == "https" && url.host == ASSET_HOST && path.startsWith(prefix)) {
            // Runs on a WebView background thread, so waiting for the file read is allowed here.
            val asset = runCatching { runBlocking { notes.readAsset(web.documentId, path.removePrefix(prefix)) } }.getOrNull()
            if (asset != null) return WebResourceResponse(asset.mimeType, null, ByteArrayInputStream(asset.bytes))
        }
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    override fun onPageFinished(view: WebView, url: String?) = web.restore()

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        web.view = null
        onCrash()
        return true
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun newNonce() = UUID.randomUUID().toString().replace("-", "")

internal data class QuickNoteColors(
    val background: Int,
    val text: Int,
    val muted: Int,
    val primary: Int,
    val border: Int,
    val codeBackground: Int,
    val warning: Int,
    val onWarning: Int,
    val dark: Boolean,
)

@Composable
private fun quickNoteColors(): QuickNoteColors {
    val scheme = MaterialTheme.colorScheme
    val status = MaterialTheme.status
    return QuickNoteColors(
        background = scheme.background.toArgb(),
        text = scheme.onBackground.toArgb(),
        muted = scheme.onSurfaceVariant.toArgb(),
        primary = scheme.primary.toArgb(),
        border = scheme.outlineVariant.toArgb(),
        codeBackground = scheme.surfaceContainer.toArgb(),
        warning = status.warningContainer.toArgb(),
        onWarning = status.onWarningContainer.toArgb(),
        dark = scheme.background.luminance() < 0.5f,
    )
}

private fun css(color: Int) = "#%06X".format(0xFFFFFF and color)

/**
 * Reading page around the backend's semantic HTML. The CSP only lets the nonce-tagged app script
 * run and only lets images load from the virtual asset origin.
 */
internal fun quickNoteHtml(body: String, c: QuickNoteColors, nonce: String): String =
    """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://$ASSET_HOST; style-src 'unsafe-inline'; script-src 'nonce-$nonce'; base-uri 'none'; form-action 'none'">
<style>
:root{color-scheme:${if (c.dark) "dark" else "light"}}
html{-webkit-text-size-adjust:100%}
body{margin:0;padding:12px 16px 48px;background:${css(c.background)};color:${css(c.text)};font-family:sans-serif;font-size:16px;line-height:1.75;overflow-wrap:break-word}
h1,h2,h3,h4,h5,h6{line-height:1.4;margin:1.4em 0 .6em;scroll-margin-top:12px}
h1{font-size:1.5em}h2{font-size:1.28em;padding-bottom:.3em;border-bottom:1px solid ${css(c.border)}}h3{font-size:1.12em}h4,h5,h6{font-size:1em}
body>:first-child{margin-top:0}
p,ul,ol,blockquote{margin:.7em 0}ul,ol{padding-left:1.4em}li{margin:.3em 0}li>p{margin:.3em 0}
a{color:${css(c.primary)}}
code{font-family:monospace;font-size:.88em;background:${css(c.codeBackground)};padding:.12em .36em;border-radius:4px}
blockquote{margin-left:0;padding:.1em 0 .1em 12px;border-left:3px solid ${css(c.primary)};color:${css(c.muted)}}
hr{border:none;border-top:1px solid ${css(c.border)};margin:1.6em 0}
img{max-width:100%;height:auto;border-radius:8px}
.qn-table-scroll{overflow-x:auto;max-width:100%;margin:1em 0;border:1px solid ${css(c.border)};border-radius:8px}
table{border-collapse:collapse;font-size:.92em;line-height:1.6}
th,td{padding:8px 12px;text-align:left;vertical-align:top;min-width:5.5em;border-bottom:1px solid ${css(c.border)}}
th+th,td+td{border-left:1px solid ${css(c.border)}}
thead th{background:${css(c.codeBackground)};font-weight:600}
tbody tr:last-child td{border-bottom:none}
.qn-code-block{margin:1em 0;border:1px solid ${css(c.border)};border-radius:8px;background:${css(c.codeBackground)};overflow:hidden}
.qn-copy{display:block;text-align:right;padding:8px 12px;font-size:.82em;text-decoration:none;color:${css(c.primary)};border-bottom:1px solid ${css(c.border)}}
.qn-code-scroll{overflow-x:auto;max-width:100%}
pre{margin:0;padding:12px;white-space:pre;font-family:monospace;font-size:.86em;line-height:1.55;tab-size:4}
pre code{background:none;padding:0;border-radius:0;font-size:1em}
.qn-missing-image{display:block;margin:.6em 0;padding:10px 12px;border-radius:8px;background:${css(c.warning)};color:${css(c.onWarning)};font-size:.9em}
</style></head>
<body>
$body
<script nonce="$nonce">$READER_SCRIPT</script>
</body></html>"""

/** Position, jump and broken-image handling. Kept free of '$' so it can live in a Kotlin string. */
private const val READER_SCRIPT =
    """(function(){
function heads(){return Array.prototype.slice.call(document.querySelectorAll('h1[id],h2[id],h3[id],h4[id],h5[id],h6[id]'));}
function top(el){return el.getBoundingClientRect().top+window.scrollY;}
function maxY(){return Math.max(0,document.documentElement.scrollHeight-window.innerHeight);}
window.qnPosition=function(){var y=window.scrollY,a='',o=0,hs=heads();
for(var i=0;i<hs.length;i++){var t=top(hs[i]);if(t<=y+1){a=hs[i].id;o=Math.max(0,y-t);}else break;}
var m=maxY();return {anchor:a,offset:o,fraction:m>0?Math.min(1,Math.max(0,y/m)):0};};
function apply(a,o,f){var el=a?document.getElementById(a):null;var y=el?top(el)+o:f*maxY();window.scrollTo(0,Math.max(0,Math.min(maxY(),y)));}
window.qnRestore=function(a,o,f){if(document.readyState==='complete'){apply(a,o,f);}else{window.addEventListener('load',function(){apply(a,o,f);});}return true;};
window.qnJump=function(id){var el=document.getElementById(id);if(el){el.scrollIntoView();return true;}return false;};
document.addEventListener('click',function(e){var link=e.target.closest?e.target.closest('a[href^="#"]'):null;if(!link)return;
e.preventDefault();var id=link.getAttribute('href').slice(1);try{id=decodeURIComponent(id);}catch(err){}qnJump(id);});
function broken(img){var s=document.createElement('span');s.className='qn-missing-image';s.setAttribute('role','note');
s.textContent='图片无法显示：'+(img.getAttribute('alt')||'未命名图片')+'。文件可能已损坏或格式不受支持。';img.replaceWith(s);}
Array.prototype.forEach.call(document.images,function(img){if(img.complete&&img.naturalWidth===0){broken(img);}else{img.addEventListener('error',function(){broken(img);});}});
})();"""
