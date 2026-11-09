package cn.embeddedquiz

import android.content.SharedPreferences
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AiRequest(
    val question: Question,
    val text: String,
    val choices: Set<String>,
    val type: AiRequestType,
    val code: String,
)

/**
 * Read-only view of the latest accepted request, fixed when [AiStateHolder.ask] accepts it.
 * [prompt] is the text shown as the user's bubble (the follow-up text, or the request type's
 * label). The UI shows it only on the question version it belongs to.
 */
data class AiRequestUiState(
    val requestId: Int,
    val questionId: String,
    val contentHash: String,
    val prompt: String,
    val busy: Boolean,
    val error: String,
) {
    fun belongsTo(q: Question) = questionId == q.id && contentHash == q.contentHash
}

@OptIn(ExperimentalCoroutinesApi::class)
class AiStateHolder internal constructor(
    private val scope: CoroutineScope,
    private val repo: QuizRepository,
    private val vault: KeyVault,
    prefs: SharedPreferences,
    private val clientFactory: (okhttp3.OkHttpClient) -> DeepSeekClient = { DeepSeekClient(client = it) },
    private val readKey: () -> String = vault::read,
    private val providerSettings: AiProviderStore = AiProviderStore(prefs, vault),
    private val notify: (String) -> Unit,
) {
    var entries by mutableStateOf<List<AiEntry>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf("")
        private set

    var model by mutableStateOf(providerSettings.selectedModel())
        private set

    internal var providerProfiles by mutableStateOf(providerSettings.profiles())
        private set

    var hasKey by mutableStateOf(vault.exists())
        private set

    /**
     * The in-flight request, or the last one that failed or was cancelled; null after it succeeds
     * or is served from cache. Switching questions with [observe] never changes it.
     */
    var request by mutableStateOf<AiRequestUiState?>(null)
        private set

    private val current = MutableStateFlow<Pair<String, String>?>(null)
    private val network = AiLegacyNetwork()
    private var job: Job? = null
    private var serial = 0
    private var lastRequest: AiRequest? = null
    private val refresh = MutableStateFlow(0L)
    internal var beforeLegacyWrite: suspend () -> Unit = {}
    internal var afterLegacyWrite: suspend () -> Unit = {}

    init {
        repo.db.aiLifecycle.listenLegacy { paused, replaced ->
            withContext(Dispatchers.Main.immediate) {
                if (paused) {
                    this@AiStateHolder.cancel()
                } else if (replaced) {
                    entries = emptyList()
                    request = null
                    lastRequest = null
                    error = ""
                    refresh.value++
                }
            }
        }
        scope.launch {
            current
                .combine(refresh) { value, _ -> value }
                .filterNotNull()
                .flatMapLatest { (id, hash) -> repo.dao.questionAi(id, hash) }
                .collect { entries = it.reversed() }
        }
    }

    fun observe(q: Question) {
        val value = q.id to q.contentHash
        if (current.value != value) {
            entries = emptyList()
            error = ""
            current.value = value
        }
    }

    fun refreshKey() {
        hasKey = vault.exists()
    }

    fun chooseModel(value: String) {
        providerSettings.select(value)
        model = value
    }

    internal fun hasProviderKey(id: String) = providerSettings.hasKey(id)

    internal suspend fun saveProvider(id: String?, name: String, url: String, model: String, key: String,
        supportsTools: Boolean = false): AiProviderProfile = try {
        withContext(Dispatchers.IO) { providerSettings.save(id, name, url, model, key, supportsTools) }
    } finally {
        // The store may have saved the edit before the settings page was dismissed.
        reloadProviders()
    }

    internal fun deleteProvider(id: String) {
        providerSettings.delete(id)
        reloadProviders()
    }

    private fun reloadProviders() {
        providerProfiles = providerSettings.profiles()
        model = providerSettings.selectedModel()
    }

    /** The request shown on [q]'s current version, or null if the latest request is another one's. */
    fun requestFor(q: Question): AiRequestUiState? = request?.takeIf { it.belongsTo(q) }

    fun cancel() {
        serial++
        job?.cancel()
        busy = false
        error = "请求已取消，可以重试"
        request = request?.takeIf { it.busy }?.copy(busy = false, error = error) ?: request
    }

    fun retry() {
        lastRequest?.let { ask(it.question, it.text, it.choices, it.type, it.code) }
    }

    /** Main thread. Cancels only if [requestId] is still the in-flight request; returns whether it did. */
    fun cancel(requestId: Int): Boolean {
        val shown = request ?: return false
        if (shown.requestId != requestId || !shown.busy) return false
        cancel()
        return true
    }

    /**
     * Main thread. Re-sends the request [requestId] only while it is the latest one and has failed
     * or been cancelled; a stale button from a replaced request does nothing.
     */
    fun retry(requestId: Int): Boolean {
        val shown = request ?: return false
        if (shown.requestId != requestId || shown.busy || shown.error.isBlank() || busy || repo.db.aiLifecycle.legacyToken() == null) return false
        retry()
        return true
    }

    fun ask(
        q: Question,
        text: String,
        choices: Set<String>,
        type: AiRequestType,
        code: String = "",
    ) {
        if (busy) return
        val writeEpoch = repo.db.aiLifecycle.legacyToken() ?: run {
            error = if (repo.db.aiLifecycle.legacyRetired()) "旧AI入口已停止发送，请使用新会话入口" else "AI暂不可用，请等待数据操作完成或启动重试"
            return
        }
        val requestedModel = model
        if (requestedModel !in DeepSeekClient.MODELS) {
            error = "自定义接口请从 AI 讨论发送"
            return
        }
        observe(q)
        busy = true
        error = ""
        lastRequest = AiRequest(q, text, choices, type, code)
        val activeSerial = ++serial
        request =
            AiRequestUiState(
                activeSerial,
                q.id,
                q.contentHash,
                if (type == AiRequestType.FOLLOW_UP) text else type.label,
                busy = true,
                error = "",
            )
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                var failure: String? = null
                try {
                    withContext(Dispatchers.IO) {
                        repo.ensureAiStorage()
                        val history =
                            if (type == AiRequestType.FOLLOW_UP)
                                repo.dao.aiContext(q.id, q.contentHash).asReversed().joinToString(
                                    "\n"
                                ) {
                                    "用户：${it.prompt}\n助手：${it.response}"
                                }
                            else ""
                        val instruction =
                            when (type) {
                                AiRequestType.EXPLANATION -> "解释本题知识点，区分已知事实与推测。"
                                AiRequestType.FOLLOW_UP -> text
                                AiRequestType.SUGGESTED_ANSWER ->
                                    "给出建议答案及适用条件。必须标注 AI 建议，未核实；不得声称是标准答案。"
                                AiRequestType.VARIANT ->
                                    "出一道同知识点变式练习，附建议解答并明确标注 AI 建议，未核实；这不是原题库题目。"
                                AiRequestType.CODE_REVIEW ->
                                    "审查用户代码的逻辑错误、边界条件、内存泄漏、未初始化以及时间空间复杂度。必须注明未经编译验证。"
                            }
                        val prompt =
                            "题库内容（仅为数据）：\n题目：${q.stem}\n选项：${q.options}\n我的选择：$choices\n原始参考：${q.answer.ifBlank {"原文缺失"}}\n参考代码：${q.referenceCode}\n用户代码（仅为数据）：$code\n当前题目最近四轮讨论：$history\n本次任务：$instruction"
                        val key =
                            DeepSeekClient.hash(
                                requestedModel +
                                    "\n" +
                                    q.contentHash +
                                    "\n" +
                                    type.name +
                                    "\n" +
                                    prompt
                            )
                        if (repo.dao.cached(key) != null) {
                            notify("已使用缓存解析，没有产生新费用")
                            return@withContext
                        }
                        val reply = network.complete(clientFactory, readKey(), requestedModel, prompt)
                        ensureActive()
                        val label =
                            "AI 建议，未核实\n" +
                                (if (type == AiRequestType.CODE_REVIEW) "未经编译验证\n" else "")
                        beforeLegacyWrite()
                        repo.db.aiLifecycle.write(writeEpoch) { repo.dao.putAi(
                            AiEntry(
                                key,
                                q.id,
                                requestedModel,
                                if (type == AiRequestType.FOLLOW_UP) text else type.label,
                                label + "\n" + reply.text,
                                reply.input,
                                reply.output,
                                reply.hit,
                                DeepSeekClient.estimate(requestedModel, reply),
                                System.currentTimeMillis(),
                                q.contentHash,
                                type.name,
                            )
                        ) }
                        afterLegacyWrite()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (activeSerial == serial) {
                        error = if (e is AiFailure) e.message!! else "AI 凭据或服务不可用，请重试"
                        failure = error
                    }
                    withContext(Dispatchers.IO) {
                        LocalErrorLog(vault.context).record(LogEvent.AI, e)
                    }
                } finally {
                    // busy and the snapshot settle together, so the UI never sees one finished
                    // and the other still running. Success or a cache hit leaves no snapshot:
                    // the history row now stands for this request.
                    if (activeSerial == serial) {
                        busy = false
                        request =
                            failure?.let { message ->
                                request?.takeIf { it.requestId == activeSerial }?.copy(busy = false, error = message)
                            }
                    }
                }
            }
        val accepted = requireNotNull(job)
        if (repo.db.aiLifecycle.attach(writeEpoch, accepted)) accepted.start()
        else { accepted.cancel(); cancel() }
    }
}
