# AI-02b 可调用入口

题目页使用这里的会话门面进行非流式发送，费用页和更多页共用费用状态。存储使用 Room 6、备份格式 4 和会话数据版本 1。

| 入口 | 用途 |
| --- | --- |
| `vm.aiConversations` / `repo.aiConversations` | 同库唯一 `AiConversationFacade`；读取 `state`，调用创建、打开、分页、草稿、发送、取消、重试 |
| `vm.aiCostsState` / `vm.learning.aiCostsState` | 同一个 `StateFlow<AiCostsUiState>`，供费用页和更多页消费 |
| `vm.aiCosts.observe()` / `repo.aiCosts.observe()` | 上述共享状态的 `Flow` 接口 |

第一次取得会话门面会初始化并切换本数据库的发送权。已有旧请求先取消并等待写入、连接和回调退出，随后进入 `READY`。此后旧入口只读历史，旧 ask/retry 不再发请求；单读费用不触发发送切换。题目页的快捷请求和代码审查均使用新入口。早期记录通过独立的只读历史入口查看。

示例：在用户明确点击后，新建一个会话并提交一条追问。现有会话直接使用 `open(sessionId)` 或选中会话的当前 revision，无须每次新建。

```kotlin
import java.util.UUID
import kotlinx.coroutines.flow.first

suspend fun startAiDiscussion(
    vm: QuizViewModel,
    question: Question,
    text: String,
): ResultOf<AcceptedReceipt> {
    val ai = vm.aiConversations
    ai.state.first { it.phase != ServicePhase.INITIALIZING }
    val owner = QuestionOwner(question.id, question.contentHash)
    val created = when (val result = ai.createSession(UUID.randomUUID().toString(), owner)) {
        is ResultOf.Ok -> result.value.session
        is ResultOf.Rejected -> return result
    }
    val opened = when (val result = ai.open(created.sessionId)) {
        is ResultOf.Ok -> result.value.session
        is ResultOf.Rejected -> return result
    }
    return ai.send(SendCommand(
        commandId = UUID.randomUUID().toString(),
        sessionId = opened.sessionId,
        question = owner,
        expectedContextRevision = opened.contextRevision,
        draftToken = null,
        savedDraftRevision = null,
        input = text,
        choices = emptySet(),
        code = "",
        requestType = AiRequestType.FOLLOW_UP,
        model = vm.model,
    ))
}
```

- **发送回执**表示消息、请求快照和命令收据已落盘；从 `state.selectedRun`、`state.firstMessages` 或 `messages()` 看结果。首次页是最新窗口，页内按 `seq` 升序；`next` 继续读更早消息。游标绑定过滤、排序和数据代际；收到 `STALE_CURSOR` 需重载。旧历史使用独立 `legacy()` 过滤器，空 hash 只读。
- **草稿**使用 `open` 返回的 `editorEpoch`。保存回执只对应实际落盘版本；频繁提交合并，较早未受理版本返回 `STALE_DRAFT`。显式 `flushDraft` 推动待写版本落盘；关闭/替换也结清已接管的待写草稿。发送前按当前编辑 token 与已保存 revision 明确声明要消费的草稿；只在回执仍匹配当前编辑器及版本时清空 UI。换会话/退出会撤销旧编辑权。
- **停止与重试**必须使用原回执/RunView 的完整 `RunOwner`。`cancel(newCommandId, owner)` 可先返回已持久化取消，`globalBusy.phase=CLEANING` 仍禁止新发送与重试；`awaitIdle()` 等待连接/回调与写入收尾。重复成功的命令返回原接受事实；`cleanupPending` 是取消回执当时的事实，实际槽状态看 `state`。`retry(RetryCommand(newCommandId, owner))` 只对当前题版本、最新失败/取消且该用户消息没有成功尝试的轮次开放，复用原用户消息和冻结 HTTP body。
- **缓存**由同会话、同快照及实际请求键匹配。只有正常 `stop` 的非空完整答复写缓存；长度截断、过滤、异常、停止不写完整缓存。命中仍提交自己的消息、run 与收据，`source=CACHE`、`sourceRequestId` 指向原网络请求，费用0、不复制原用量。
- **费用**按日/模型/币种分组，月份按保存的账期归并。旧估算单列 `LEGACY_ESTIMATE`，不推断旧零用量；新尝试从 `RUN` 取数，未派发的不计网络调用，已标记派发但结果不明则保留未知计数。缓存只加命中数。费用页/更多页共同订阅上述新状态；原有 `learning.costs` 是未迁移旧页面的兼容列表，不是新调用的总账。

生产地址为 DeepSeek `/chat/completions`。发送按[官方 Chat Completions 协议](https://api-docs.deepseek.com/api/create-chat-completion/)使用 `stream=false` 和冻结正文。原始输入预算64 KiB，输出参数4096；这是本地限制，不提供精确 token 预测。请求体不可重发，关闭自动重试和重定向。Key 仅在派发时由 KeyVault 读取并放入认证头；不把请求头、服务端错误正文或 Key 写入记录。

估算对完整有效用量使用 2026-11-07 核对的[官方峰值 USD 费率](https://api-docs.deepseek.com/quick_start/pricing/?model_version=DeepSeek-V4-Pro-0813)快照，按百万 tokens 计算并最终舍入到12位。没有推算折扣或账单；部分、缺失、非法用量及未知费率不显示为已知零费用。各币种独立累计。

真实 HTTP 路径用本地 MockWebServer 验证，不使用用户 API Key 调用外部模型。Room 写入失败时会停止网络并报告 `STORAGE_FAILED`，可用备份与启动冷恢复规则保留现场；不会自动补发。应用清理时先封口并等待 `awaitClosed` 对应的所有任务退出，再关库。页面接入细节见 [前端交接](FRONTEND_HANDOFF.md)。

[前后端集成验证记录](../validation/AI-MOUNT-20261107/README.md)
