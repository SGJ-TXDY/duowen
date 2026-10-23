package cn.embeddedquiz

import android.content.SharedPreferences
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class AiStateHolder(
    private val scope: CoroutineScope,
    private val repo: QuizRepository,
    private val vault: KeyVault,
    private val prefs: SharedPreferences,
    private val notify: (String) -> Unit,
) {
    var entries by mutableStateOf<List<AiEntry>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var model by mutableStateOf(prefs.getString("model", "deepseek-flash")!!)
        private set

    var hasKey by mutableStateOf(vault.exists())
        private set

    init {
        scope.launch { repo.dao.ai().collect { entries = it } }
    }

    fun refreshKey() {
        hasKey = vault.exists()
    }

    fun chooseModel(value: String) {
        model = value
        prefs.writeDurably { putString("model", value) }
    }

    fun ask(q: Question, text: String, choices: Set<String>, type: AiRequestType) {
        if (busy) return
        busy = true
        val requestedModel = model
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val history =
                        entries
                            .filter { it.questionId == q.id }
                            .takeLast(4)
                            .joinToString("\n") { "用户：${it.prompt}\n助手：${it.response}" }
                    val prompt =
                        "题目：${q.stem}\n选项：${q.options}\n我的选择：$choices\n原始参考答案：${q.answer.ifBlank { "原文缺失" }}\n代码：${q.referenceCode}\n历史讨论：$history\n本次问题：$text"
                    val actual =
                        if (type == AiRequestType.EXPLANATION) {
                            prompt.substringBefore("\n历史讨论：") + "\n本次问题：$text"
                        } else prompt
                    val key = DeepSeekClient.hash(requestedModel + "\n" + actual)
                    if (repo.dao.cached(key) != null) {
                        notify("已使用缓存解析，没有产生新费用")
                        return@withContext
                    }
                    val reply = DeepSeekClient().complete(vault.read(), requestedModel, actual)
                    repo.dao.putAi(
                        AiEntry(
                            key,
                            q.id,
                            requestedModel,
                            text,
                            reply.text,
                            reply.input,
                            reply.output,
                            reply.hit,
                            DeepSeekClient.estimate(requestedModel, reply),
                            System.currentTimeMillis(),
                        )
                    )
                }
            } catch (e: Exception) {
                notify(if (e is AiFailure) e.message!! else "AI 凭据或服务不可用，请重新设置 Key 后重试")
            } finally {
                busy = false
            }
        }
    }
}
