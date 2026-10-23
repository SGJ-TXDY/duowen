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

@OptIn(ExperimentalCoroutinesApi::class)
class AiStateHolder(
    private val scope: CoroutineScope,
    private val repo: QuizRepository,
    private val vault: KeyVault,
    private val prefs: SharedPreferences,
    private val client: DeepSeekClient = DeepSeekClient(),
    private val readKey: () -> String = vault::read,
    private val notify: (String) -> Unit,
) {
    var entries by mutableStateOf<List<AiEntry>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf("")
        private set

    var model by
        mutableStateOf(
            prefs.getString("model", DeepSeekClient.FLASH).takeIf { it in DeepSeekClient.MODELS }
                ?: DeepSeekClient.FLASH
        )
        private set

    var hasKey by mutableStateOf(vault.exists())
        private set

    private val current = MutableStateFlow<Pair<String, String>?>(null)
    private var job: Job? = null
    private var serial = 0
    private var lastRequest: AiRequest? = null

    init {
        scope.launch {
            current
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
        require(value in DeepSeekClient.MODELS)
        model = value
        prefs.writeDurably { putString("model", value) }
    }

    fun cancel() {
        serial++
        job?.cancel()
        busy = false
        error = "请求已取消，可以重试"
    }

    fun retry() {
        lastRequest?.let { ask(it.question, it.text, it.choices, it.type, it.code) }
    }

    fun ask(
        q: Question,
        text: String,
        choices: Set<String>,
        type: AiRequestType,
        code: String = "",
    ) {
        if (busy) return
        observe(q)
        busy = true
        error = ""
        val request = AiRequest(q, text, choices, type, code)
        lastRequest = request
        val requestedModel = model
        val activeSerial = ++serial
        job =
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
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
                        val reply = client.completeCancellable(readKey(), requestedModel, prompt)
                        ensureActive()
                        val label =
                            "AI 建议，未核实\n" +
                                (if (type == AiRequestType.CODE_REVIEW) "未经编译验证\n" else "")
                        repo.dao.putAi(
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
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (activeSerial == serial)
                        error = if (e is AiFailure) e.message!! else "AI 凭据或服务不可用，请重试"
                    withContext(Dispatchers.IO) {
                        LocalErrorLog(vault.context).record(LogEvent.AI, e)
                    }
                } finally {
                    if (activeSerial == serial) busy = false
                }
            }
    }
}
