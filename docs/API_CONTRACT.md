# Android 业务接口契约

题目页已接入 AI 会话门面，费用页和更多页共用费用状态。Room 6 和备份格式 4 的接入契约见文末。

## 2026-11-04 · VC-01 AI 请求归属展示（Claude，按 REPAIR_DISPATCH §21 授权的小范围后端例外，待审查）

只追加 `AiStateHolder` 的只读请求快照与带请求号的重试/取消；不改 DAO/schema、缓存键、上下文 4 轮、费用计算、网络协议、凭据、评分/考试/保存协议。仍只允许一个在途请求，无排队、无持久化会话。Main 调用、Compose 可观察：

```kotlin
data class AiRequestUiState(
    val requestId: Int,       // = 受理时的内部 serial
    val questionId: String,
    val contentHash: String,
    val prompt: String,       // 展示文字：FOLLOW_UP 为用户原文，其余为 AiRequestType.label（与成功后 AiEntry.prompt 一致）
    val busy: Boolean,
    val error: String,
) { fun belongsTo(q: Question): Boolean } // questionId 与 contentHash 都相同
val aiState.request: AiRequestUiState?
fun aiState.requestFor(q: Question): AiRequestUiState? // 仅当最近请求属于 q 当前版本
fun aiState.retry(requestId: Int): Boolean
fun aiState.cancel(requestId: Int): Boolean
```

- `ask` 受理时立即固定 requestId、题目、contentHash、展示文字，`busy=true`。`busy`/`error`/`request` 的后续更新都检查同一 serial，过期回调不覆盖新请求。
- 成功写入缓存或命中缓存：`request=null`（历史记录行代表该请求，不留待回复快照）。失败：`request` 保留，`busy=false`、`error`=原失败文字。取消：`busy=false`、`error="请求已取消，可以重试"`，不写缓存。
- `observe(q)` 只切换记录订阅（并按原行为清旧版 `error`），**不修改 `request`**；离开 A 再返回 A 可恢复 A 的在途/失败展示。在另一题新 `ask` 会替换本实例的最后请求，不跨多次失败归档。
- `retry(id)`：只有 `id` 仍是 `request.requestId`、该请求已失败或取消、且没有任何在途请求时才重发原请求（新 requestId），返回 true；否则无操作返回 false。`cancel(id)`：只有 `id` 是在途请求时取消对应网络任务并返回 true。
- 原 `busy`（全局一个在途请求）、`error`、`ask`、`retry()`、`cancel()` 语义保持兼容，供旧调用方使用。UI 只在 `requestFor(q)` 非空时显示待回复气泡、分析中、错误、重试、取消；`busy && requestFor(q)?.busy != true` 时禁发送并说明“AI 正在处理其他题目”。

---

## 2026-11-04 · T4 代码草稿（按 §18 固定接口实现，待审查）

T1/T2/T3 已按共享文档验收；本节说明 T4 实际后端实现，前端按 §18.4 接入，整体验收另行记录。Room 仍 5、备份仍 3，不改通用 OrderedWriter、便签/速记协议、评分、历史迁移、依赖或版本。

Main 调用、Compose 可观察：

```kotlin
sealed interface CodeDraftSaveState {
    data object Inactive : CodeDraftSaveState
    data class Saving(val revision: Long) : CodeDraftSaveState
    data class Saved(val revision: Long) : CodeDraftSaveState
    data class Error(val revision: Long, val message: String) : CodeDraftSaveState
}
fun codeDraftSaveState(id: String): CodeDraftSaveState
val codeDraftEditingBlocked: Boolean
fun updateCodeDraft(id: String, code: String, notes: String): Unit
fun retryCodeDrafts(id: String? = null): Unit
suspend fun flushCodeDrafts(id: String? = null): Boolean
fun saveNotes(id: String, code: String, notes: String): Unit // 保留签名
```

同题尾沿 300ms、最早未保存改动最长 2000ms，单调时钟，不因每次输入重置上限。窗口和在途都为 Saving；一次实际串行 IO 写入，每题最多一个最新待写版本，替换而非每字符排队。数据库繁忙时不能承诺墙钟硬截止，但不并发写、不无限增长队列。同文本不增 revision、不新增写入；首次已加载文本仅 seed，状态仍 Inactive。选区/输入法 composition 是前端本地态，后端接口只接 text。

Saved(revision) 仅在 Room 的该次 code/notes 事务成功返回后确认最新受理版本。保存采用原 repo.update 事务，在事务内读最新 Progress 再仅 copy code/notes，保留收藏、flagged、评分/计数/SRS 与其他字段。IO/数据库工作均不在 Main；保存不占全局 busy，不因普通 Saving 禁输入。updateCodeDraft 同步维护 codeDrafts.drafts 及已缓存 QuestionDraft 的 code/notes；不重建 CodeEditorState，不因 Progress Flow 重发回滚已受理文字。Error 保留最新内存文本；新文字继续按窗口调度，旧版本失败/成功不降级当前状态。无新文字时失败不自动反复重试；重复同文本不会隐式重试。

retryCodeDrafts(id) 只立即重试该题最新 Error 版本；null 处理所有当前 Error。已经新版本 Saved 时无操作，不回放旧失败，不导航、不触碰考试或便签。

flushCodeDrafts(id) 捕获调用时该题最新版本；null 捕获所有当时受理版本，立即强制取出对应合并窗口。等待所捕获版本或同代际更新版本提交才 true；最新失败返回 false，保留 Error，不偷偷重试。无任务 true。调用者取消向上传播，仅取消等待，不取消已受理写入。数据代际改变后尚未得出结果的旧等待返回 false，不把旧任务当新数据已保存。Saved 只说明文字，不表示考试答案或普通笔记未点击保存的本地编辑已持久化。

saveNotes 更新后显式 flush 当前题；可靠成功才提示“代码与笔记已保存”，失败提示且保持 Error。普通题本地笔记仍须原保存按钮触发此入口；仅修改 QuestionDraft.notes 不自动调用后端。草稿状态不可据此把未点击保存的本地文字标成已保存。

备份/普通恢复/复习重置/T3 restoreStartupBackup 共用草稿 exclusive 边界：先 codeDraftEditingBlocked=true，拒绝新草稿更新，再 flush 所有当前窗口，之后持有与草稿写入相同的互斥锁执行读取/导出或替换。flush 失败中止整个操作、无成功提示、不清缓存；导出结束/异常/取消释放边界。编辑在边界前已经受理的文字必被包括；边界中的新更新被拒绝，UI 必须禁对应输入。

恢复成功提交后推进代际并清理旧 drafts/QuestionDraft、题目缓存和练习会话状态，旧任务/旧等待/旧重试不写回新数据；恢复校验或事务失败保持原缓存与数据。重置先保存最新文字，再按原 repo.reset 清复习/历史，保留收藏、flagged、代码、笔记、便签；成功推进代际但保留已保存文字镜像，新代际状态 Inactive。替换入口必须使用 VM 边界，不直接调用裸 repo.restore/reset 绕过活动草稿；Room 自身事务仍负责与同期非草稿字段写入串行。T2 考试守卫和 T3 确认/数据库可用性限制继续生效。

owner clear 同步停止接受输入、强制提交窗口最新版本。草稿 worker 生命周期独立于 viewModelScope，先启动 drain 再取消 owner；在独立 IO 收尾中等待草稿、考试 writer/operation 和原 owner 读取全部退出后才关 DB。草稿 drain 预算 2500ms，超时发协作式取消，join 仍等实际 IO/事务退出，不提前关闭活动连接；不阻塞 Main、不无限重试。已失败且无新输入的版本不会因 clear 无限重试；取消/失败保持 Error，不谎报 Saved。无法承诺不可取消的系统 IO 卡死时硬限时关库，也不保证立即强杀前未落盘文字保全。

兼容 codeDrafts.writer 的 pending/error/retry/flush/close 仅委托这一套策略，没有第二写入通道；pending 包含窗口、在途及失败未落盘题，flush 包含窗口并在失败时抛异常。Claude 应使用上面的正式 VM 接口。独立使用 CodeDraftStateHolder 的调用方需 shutdown().join() 后再关 repo DB（独立生命周期）；VM 已统一处理。

内部测试接缝为 codeDrafts.persistence：CodeDraftClock 控制时间，beforeWrite/afterWrite/beforeBoundary 控制 IO/边界，closeBudgetMillis 仅供缩短故障测试等待；UI 生产代码不读取这些字段。实际写入计数用测试数据库触发器审计已提交 progress 文字变更，不以队列长度替代。

---

## 2026-11-04 · T3 启动恢复与历史存储（GPT 实现契约，待审查）

用户授权启动 T3；本节是实际后端接口与保数据选择，供 Architect 审查后 Claude 接入。共享派发尚未补齐 T3 固定接口，本节不冒称已有新的 Architect 裁定。本节覆盖下文 G1 的“每实例仅一次、无重试”和 T2 的旧启动错误限制；G1 通知优先级、T2 会话和台账语义保留。Room 仍 5、备份仍 3，无结构迁移或依赖升级。

只读可观察状态与 Main 入口：

```kotlin
enum class StartupStage { DATABASE, VALIDATION, BANK, INDEX, EXAM }
sealed interface StartupState {
    data class Loading(val attempt: Int, val stage: StartupStage, val message: String) : StartupState
    data class Ready(val attempt: Int, val examRestored: Boolean) : StartupState
    data class Error(val attempt: Int, val stage: StartupStage, val message: String,
        val code: String, val canRestoreBackup: Boolean) : StartupState
}
val startupState: StartupState
val startupRecoveryBusy: Boolean
fun retryInitialization(): Unit
suspend fun awaitInitialization(): InitializationOutcome // 原签名保留
suspend fun readStartupDiagnostics(): String
suspend fun exportStartupDiagnostics(uri: Uri): StartupRecoveryResult
suspend fun restoreStartupBackup(uri: Uri, confirmReplace: Boolean = false): StartupRecoveryResult
sealed interface StartupRecoveryResult {
    data object Completed : StartupRecoveryResult
    data class Rejected(val message: String) : StartupRecoveryResult
    data class Failed(val message: String) : StartupRecoveryResult
}
```

DATABASE 阶段先对 schema 5 的九张依赖表及其读取列做只读 LIMIT 0 预检，缺表/缺列先进入 Error，不在发布 Ready 后才由订阅报错；不写入或修复数据，也不代替 Room 迁移和后续运行时错误处理。Loading.attempt 从 1 递增，stage/message 提供加载阶段；Ready 仅在考试恢复和通知选页完成后发布。Error 的 code 为 DATABASE_UNAVAILABLE / STORED_DATA_INVALID / EXAM_UNAVAILABLE / PREPARATION_FAILED；message 为可展示的固定说明，不含 SQL、题干、路径或异常原文。canRestoreBackup 仅表示本次数据库基本访问已成功，可以尝试恢复；仍须确认和会话守卫，不能把 true 当成保证恢复必成功。

retryInitialization 只在 Error、无恢复工作、owner 未结束时受理，立即切 Loading；Loading/Ready 时无操作，不自动无限重试。每次初始化串行，失败前不启动 Room 订阅；成功后一次性开始题目、学习、便签等观察。不是在失败之后不断创建新 collector。ready / loadingMessage / importProgress 保留兼容。

awaitInitialization 捕获调用时的尝试。该次 Ready/Failed 结果稳定；旧等待者的 Failed 不被重试改写，新调用等待新尝试。取消一个等待者不取消初始化；owner clear 取消未完成信号。无显式重试时，失败后的通知请求不自动重试，仍返回同次 Failed。InitializationOutcome.Failed 保留原通用 message“题库加载失败，请查看本地错误记录”，详细阶段使用 startupState。通知请求跨 Error/重试保留，恢复 queue/index/answers/deadline 后先决定 REVIEW，再发布 Ready。

readStartupDiagnostics 在 IO 生成脱敏 JSON：状态、阶段、code、attempt、异常类名、文件存在与大小、当前格式版本。无需 ready 或成功打开 Room；不读 API Key、完整偏好、题干/回答或异常 message。构造 ViewModel 之前的资源/工厂创建异常不在此状态协议内；Room 的实际打开/迁移在初始化中，失败可由本协议表达。

exportStartupDiagnostics 只在 Error 受理；IO 流式写 ZIP，含原数据库和存在的 WAL/journal、诊断清单及 exam-cache.json（如存在）。不调用 Room 打开、checkpoint 或 SQL；源文件前后哈希/集合有变化就 Failed，不对不稳定副本报成功。禁止 file URI 指向应用私有数据，避免覆盖源。ZIP 含用户数据库内容，但不导出密钥/完整设置，须由用户主动选择目的地；不自动发送。它是保留现场的诊断包，不是可直接导入的 v3 备份，不承诺多进程写入中的恢复一致性。exam-cache 明确只是缓存诊断，不能证明 commit 成功。取消/失败可能留下不完整目的文件，源数据不改。

restoreStartupBackup 必须 confirmReplace=true；UI 需先说明将替换题库/学习历史等并确认。数据库基本访问未成功时 Rejected，提示先导出诊断，不能自动替换损坏数据库。数据库可访问时先核实考试；活动/待清理/无法确认考试状态均 Rejected，不清场。允许恢复时沿用 repo.restore 对 v1～v3 备份的严格校验与原子替换，本机 exam_terminations 保留；无新学习记账。备份 v3 不含 Attempt 的自增内部 ID，成功替换可重建该内部 ID，所有导出字段/学习状态保持原格式语义。

Completed 对导出表示 ZIP 写完；对恢复只表示替换已提交并受理了新初始化，不等于 Ready。调用方应继续观察 startupState / 等待新 awaitInitialization。重复恢复/导出被串行互斥拒绝；恢复工作使用 startupRecoveryBusy，不占普通 busy。准备/导出取消向上传递并取消工作；已确认且受理的替换事务、已知结果和新启动调度抵抗取消，owner 关库等待该工作退出。若 owner 此时已清理，事务仍得出结果，新实例负责初始化，不给已销毁 owner 启动新流程。

历史读取：HistoricalQuestionV3 冻结既有字段解码、校验、内容哈希/索引与分类映射，LearningMigration 3→4 不再依赖 BankCodec/QuestionIndex；合法旧数据原 JSON 和所有学习记录保留。坏 JSON、未知题型、无效选项/答案、存储 ID 不一致明确失败、整个迁移回滚，不伪造成可判分题，不跳过删除。已为 v4/v5 的数据库也在随包升级/索引更新前验证，避免坏条目被静默覆盖。Repository 读取历史用同一冻结规则；外部导入/备份验证仍严格 BankCodec，不降低外部要求。没有声称 v4 新增校验已被证明拒绝正常旧数据。

Room 打开使用 PreservingOpenHelperFactory：损坏回调报告失败保留文件，显式 allowDataLossOnRecovery(false)，不调用默认损坏删库。SQL schema 仍为 5，旧数据诊断/修复前不作破坏性清理。回退本轮代码不会降低 DB 版本，不可回到会对损坏库自动删数据的旧路径而声称仍有保数据保证。

Claude 接入：AppNavigation 启动分支以 startupState 区分 Loading / Error / Ready；Error 页不依赖 ready=true，提供诊断、用户选文件导出、显式重试和恢复确认。恢复按钮的可用性同时考虑 startupRecoveryBusy/canRestoreBackup，但实际 Rejected 仍应展示；数据库打不开时明确说明恢复限制。UI 不访问 SQL/偏好、不清库、不以 Completed 显示“已经启动成功”，不改变通知优先级。GPT 本轮不改 UI；未接入之前，旧 UI 的 ready=false 分支仍只显示加载，不代表 T3 错误页已完成。

---

## 2026-11-04 · T2 异步考试持久化（GPT 后端已实现，待审查）

本节以 REPAIR_DISPATCH §14 为准，覆盖下文 T1 的同步保存、仅实例内防重复和 Room 4 限制；其他普通练习、通知优先级和备份契约保持。Room 现在为 **5**，备份仍 **3**。Claude 的 T2 UI 接入与双方集成验收另行记录，不将后端实现视为整个 T2 验收完成。

Main 入口（既有结束/成绩接口签名不变）：

```kotlin
sealed interface ExamSaveState {
    data object Inactive : ExamSaveState
    data class Saving(val revision: Long) : ExamSaveState
    data class Saved(val revision: Long) : ExamSaveState
    data class Error(val revision: Long, val message: String) : ExamSaveState
}
val examSaveState: ExamSaveState
fun retryExamSave(): Unit
suspend fun flushExamSave(): Boolean
```

`startExam/answerExam/saveExam` 只复制内存数据、递增本场 revision、排队后返回；JSON 编码和 commit 在独立 IO 写入器中执行。不占 busy，不使用 runBlocking。每份不可变快照含随机 UUID sessionId、revision、完整题序、index、deadline、答案副本。一个在途加一个可替换的待写版本，版本不重排、任务不无界累积。旧版本完成不得覆盖新版本保存状态。Saved 仅为最新版本得到 commit=true；commit=false 即使改变偏好内存也不能证明落盘。

Error 不丢输入、普通答题仍可进行；每次新改动触发一次新尝试，无改动不自动循环。retryExamSave 仅在可作答的活动考试重排最新快照；结束中、Failed 和终态无副作用。Inactive 表示无待保存考试；已终止但待清理考试由 examOperationState 表达，不能把 Inactive 当成“可以继续作答”。

flushExamSave 在调用方 Main 协程挂起，捕获调用时的 sessionId/revision，直到该版本或同会话更高版本可靠保存，返回 true；无活动/待保存快照 true。保存失败返回 false，保留最新 Error；有较新尝试在途则等待较新尝试结果。调用方取消向上传播，不取消已经受理的后台写入。接口不会做隐式重试；Error 后应显式 retryExamSave，再等待 flush。对超时/立即强杀前未落盘输入不作保全承诺。

finishExam 同步冻结完整快照并进入 Submitting；IO 成绩事务前等待保存屏障。失败为 Failed(SUBMIT, RECORDS, false)，message 精确为“考试保存失败，未提交成绩”，保留 Error，零学习写入。retryExamOperation 重试原冻结快照及操作；该失败可确认 abandonExam。未答/空选择计零分，仅非空选择调用原 record，普通练习与 SRS 未改。

同一持久化互斥协议串行保存和结束。放弃隔离未开始快照，等待在途写入，直接写 ABANDONED 终止记录再清理，不要求答案先保存成功。已提交不改成放弃。事务/清理失败继续使用 T1 Failed；ABANDON 故障为 SESSION_CLEAR。终止标记一旦已知，保存状态 Inactive、答案冻结，仅允许显式清理重试，不能重新评分或改变终止结果。提交和放弃都成功清理后，迟到保存无副作用。

Room 外层事务先查询终止记录，再加载题目、评分、写学习记录；学习副作用与 SUBMITTED 标记、成绩五字段共同提交/回滚。已有 SUBMITTED 直接返回原成绩，不依赖题目当前内容；已有 ABANDONED 不写学习记录。唯一冲突导致整个事务回滚，不在事务外吞冲突后继续记账。终止记录不自动裁剪。

恢复在 IO 解析偏好并先查台账：已终止恢复原结果与 Failed(SESSION_CLEAR)，只清理；未终止恢复输入并重新确认保存。初始化恢复完成后仍由同一 G1 顺序决定页面：通知优先 REVIEW，无通知继续 QUESTION；awaitInitialization 不等同于快照已保存，请观察 examSaveState/flush。损坏快照不静默删除，初始化返回 Failed，原内容保留。

旧无 ID 快照分配 UUID，并将身份与提交输入可靠落盘后才允许记账。额外 legacyHash 是原匿名快照原文的 SHA-256 迁移别名，存入同一新表的可空唯一索引：身份写失败且放弃清理也失败时，旧匿名磁盘快照仍能命中 ABANDONED，不能重生为可答考试。它不是新场次 ID；新考试始终随机 UUID、别名为空。无法追溯去重升级前已经提交的历史考试。

ViewModel clear 后拒绝新考试输入，并取消本实例所属任务；IO 收尾等待这些已取消任务中正在退出的 Room 读取/写入，以及本次考试操作、独立快照写入器全部结束后才关闭数据库。取消不等于 SQLite 读取已立即退出，不能提前关闭连接池；不阻塞 Main、不保持活动收集任务，也不无限重试或保活整个应用。clear 前尚未进入事务的操作可按原取消语义退出/失败，不承诺必须提交。

备份/重置共享数据库所属考试守卫与数据替换互斥锁。活动、结束失败或待清理考试明确拒绝导入/重置，提示“请先结束当前考试，再恢复备份或重置学习记录”；不依赖 prefs.contains，不静默清理。备份 v3 不含考试快照和台账；成功导入/重置保留本机台账。外部直接替换整份数据库、回退到不遵守台账的 APK 不在去重保证内。

迁移仅新增 exam_terminations 表和 legacyHash 唯一索引；1→4 历史迁移与 schema 不变，不使用 destructiveMigration。升级前结束考试并导出可恢复 v3 备份，保留旧 APK 和原备份。v4 APK 不能直接读取 v5 数据库，源码 revert 不是数据库降级。优先采用继续识别 v5/台账的前向修复；必须回旧版时，在明确确认数据替换之后，把升级前 v3 备份导入独立的干净旧版数据环境并核验，再切换。禁止自动删库、卸载清数据或破坏性降级；当前 v3 导出不含台账，不能拿它宣称保留跨版本幂等保证。

Claude 接入：保存文案按 Saving/Saved/Error/Inactive；选项权限仍优先受 examOperationState 控制。主动离页在调用方协程等待 flush=true 后执行原导航，false 留页并提供 retryExamSave；后台仅尽力异步 flush，不阻塞 onStop/onPause，不阻挡通知复习入口。清理失败重启使用既有成绩和 retryExamOperation，禁止生产 UI 访问偏好/DAO。internal 故障接缝：examState.persistence.beforeWrite、examState.hooks 与 examState.sessionPreferences，仅测试使用。

---

## 2026-10-26 · G1 初始化完成与通知复习入口（GPT 后端，待审查）

新增主线程门面 `requestNotificationReview(): Unit`：初始化前只记住请求；先完整恢复考试 queue/index/answers/deadline，再决定初始页面，有复习请求时 REVIEW 优先，否则普通启动继续 QUESTION。初始化成功后调用立即转 REVIEW，不受普通写任务 busy 限制。重复请求合并为同一目标，不写学习记录、不重置或保存考试快照。返回 Unit 只表示请求已受理，初始化失败不伪报导航完成。

新增 `suspend awaitInitialization(): InitializationOutcome`：在调用方协程等待本 ViewModel 的一次初始化结果，不切换到 IO 做查询。`Ready(examRestored: Boolean)` 只在恢复状态和通知优先级均已应用、ready=true 后发布；`Failed(message: String)` 在初始化准备/恢复失败时发布友好提示，ready 仍 false。多个等待者共享同一稳定结果，取消一个等待者不取消初始化；ViewModel 清理取消尚未完成的信号，等待者收到 CancellationException。Ready 的恢复标记描述本次启动历史，不随之后结束考试而重算。信号不承诺全部 Flow 首次发射已完成，也不代表未来查询永不失败。

兼容：保留原 ready/page 等状态。旧 MainActivity 在初始化期间直接写入的 Page.REVIEW 同样被视作明确的复习目标，不会被考试恢复覆盖；因此旧入口在前端切换前也保持正确。Claude 后续应将 openNotification/onNewIntent 改为调用 requestNotificationReview 并消费 Intent extra，移除重复直接导航/LaunchedEffect；后端不读取 Android Intent，也不修改 MainActivity 或 Compose 页面。

失败/边界：初始化失败不会由通知命令自动重试数据库或清除考试，后续请求只保留待处理意图，awaitInitialization 仍返回同一 Failed；本接口没有新增启动重试/错误恢复页面，不开展 T3。初始化准备出错时会保留既有考试偏好；构造 ViewModel 本身失败、或原有损坏快照解析清理策略，不在该完成信号保证范围内。请求与结果仅本实例内存态，不增加偏好键、迁移或 T2 协议。

---


## 2026-10-26 · T1 考试结束接口（按 REPAIR_DISPATCH 第 8 节裁定）

本节优先于下文旧考试接口描述。GPT 后端实现待审查，Claude UI 尚待接入；不表示 T1 已验收或允许发布。

主线程门面：`finishExam(): Unit`、`abandonExam(): Unit`、`retryExamOperation(): Unit`。返回不代表完成；执行时 `busy=true`，数据库与会话清理在 IO，状态在 Main 发布。`finishExam` 兼容手动/超时调用，Failed、进行中及终止状态的重复调用无副作用，也不刷新 notice。`retryExamOperation` 仅显式重试当前 Failed；没有失败时无操作。`abandonExam` 只在用户二次确认后调用，取消确认无需后端命令。全局 busy 时不受理新结束操作。

只读可观察入口：`examOperationState: ExamOperationState`、`examSummary: ExamResult?`。

`ExamResult(total:Int, answered:Int, unanswered:Int, correct:Int, score:Int)`：total 为冻结的整卷题数，answered 仅计非空选择，unanswered=total-answered，score=correct*100/total（整数百分制）。缺键/空集合未答，零分且不创建 Attempt/Progress，不影响复习安排、错题或每日完成；多选漏选但非空继续按已答错误学习规则处理。缺题、重复/空队列或不可判分题整次校验失败，不缩小分母。普通练习/手动自评规则不变。

状态类型为 `Idle`、`Submitting`、`Submitted(result)`、`Abandoning`、`Abandoned`、`Failed(operation, stage, recordsCommitted, result, message)`。operation 为 `SUBMIT/ABANDON`；stage 为 `VALIDATION/RECORDS/SESSION_CLEAR`；message 为固定友好文本，不暴露数据库异常。

| 状态 | examFinished / examSummary / examResult | 队列、答案、截止时间与允许操作 |
| --- | --- | --- |
| Idle（活动考试） | false / null / 空字符串 | 保留当前输入；可选择、保存、交卷或确认放弃 |
| Submitting | false / 未提交时 null，Room 已提交后为结果 / 空字符串 | 冻结受理时的队列和深复制答案；阻止选择、保存、新练习/考试及冲突结束操作 |
| Submitted | true / 已提交结果 / 由该结果生成的兼容成绩文字 | 保留交卷队列、答案、截止时间用于成绩展示；持久化考试键已可靠清理，迟到 answer/save 无操作，可开始新会话 |
| Abandoning | false / null / 空字符串 | 保留本次会话至可靠清理完成，阻止所有冲突考试命令 |
| Abandoned | false / null / 空字符串 | 清空本次队列、答案、截止时间及题目草稿，mode 回到普通练习，撤销考试上下文；不生成成绩、不跳路由，Claude 决定离页 |
| Failed(SUBMIT, VALIDATION/RECORDS, false) | false / null / 空字符串 | 保留会话但冻结输入；可 retryExamOperation 重试原冻结输入，也可确认 abandonExam；不能继续改答案 |
| Failed(SUBMIT, SESSION_CLEAR, true) | false / 已提交结果 / 空字符串 | 成绩已落库，仅可 retryExamOperation 清理会话；不能放弃、重新评分、改答案或开始新会话 |
| Failed(ABANDON, SESSION_CLEAR, false) | false / null / 空字符串 | 会话内存保留，仅可 retryExamOperation 重试放弃清理；不能转成交卷 |

新会话重置操作状态/结构化成绩/兼容成绩。会话锁定独立于 `prefs.contains("exam")`；即使 commit=false 已删内存键，结束中和失败状态仍锁定。重试不读取后来变化的页面 queue/index 或答案。未开始新会话前，终止后迟到 answer/save 不复活考试。

学习提交：同一 Room 外层事务内加载整卷、校验判分资格、计算结果，并仅对非空作答调用原有 record；失败整卷回滚。Room 成功后固定本实例的已提交结果，再可靠删除 exam；只有两步都成功才 Submitted。不依赖额外 historyPage 查询发布结果。放弃只删除考试快照，不写学习表。清理失败保留真实阶段；显式重试已提交成绩只清理，不再 grade。

取消：临界段前取消释放本次 busy/进行中状态，初次操作回 Idle，重试回原 Failed；不生成成功。进入临界段后仅保护本次事务结果确定、内存提交标记与一次清理尝试；ViewModel 清理时标记考试所属生命周期已结束，尚未开始的评分事务不再启动；数据库同时关闭时按真实成功/失败发布，不延长数据库生命周期，不保证进程结束后工作完成。

T1 只提供本实例防重复。Room 与偏好不是原子提交，成绩已写入、快照未可靠清理时崩溃仍有跨进程重复窗口；此为 T2 发布阻断。每次选择的同步可靠保存保持原样，本节不承诺 T2 的异步保存/完成协议。

Claude 后续接入：使用结构化成绩；交卷说明未答计零但不改变复习安排；二次确认放弃；错误页重试调用 retryExamOperation，按上表显示是否仍可放弃；结束中禁用冲突操作，清理失败不显示交卷成功。后端不更改 UI 或路由。

---


## 2026-10-25 故障勘误：速记正文请求不能按未知图片请求拒绝

用户导入示例后出现 `data:text/html;charset=utf-8;base64,` / `ERR_HTTP_RESPONSE_CODE_FAILURE`。当前 ReaderClient 只服务 assets 路径，其他请求统一 404；这个规则没有区分正文主请求，属于阅读容器层面的集成缺陷，不需要更改 Markdown、导入存储或任何数据库。

建议最小修复：将当前文档可信 HTML 放在内存，`loadUrl` 打开固定虚拟地址 `https://appassets.androidplatform.net/quicknotes/{id}/document.html`；该精确主框架请求返回 `text/html; utf-8` / 200，其余图片仍走既有 readAsset。未知地址、其他文档、跨域、data/file/content、非主框架索取正文继续拒绝。不要简单放行所有 https 或所有主请求。

公开后端接口、数据 / 备份格式与资源 URL 不变。本轮补丁已在用户明确授权后接入，只调整阅读容器的加载 / 路由，不改 Compose 布局；验证及独立试装包状态见 BACKEND_HANDOFF 最新节。

---

## 2026-10-25（第三轮）· 独立速记模块

### 独立性与导航接口

`vm.quickNotes: QuickNotesStateHolder` 为新模块门面。`vm.openQuickNotes(): Unit` 仅设置 `quickNotes.state.visible=true`；`vm.quickNotes.leave(onLeft: () -> Unit = {}): Unit` 异步等待位置写入后隐藏模块并在主线程回调。两者不改变 `vm.page/queue/index/mode/practiceSession/examAnswers/examDeadline`，不清空题目草稿。模块与 Room / QuizRepository / SRS 无关系，使用独立内部文件存储。

本轮不增加 `Page` 枚举项，避免单方面破坏 Compose 的穷尽 when；Claude 用独立 visible 状态选择内容和导航高亮。底部“复习”换成“速记”，原 `Page.REVIEW`、首页复习入口与到期提醒不变。换其他底部项先 `quickNotes.leave { vm.navigate(target) }`，不要直接隐藏未保存位置。

### 状态与返回模型

`vm.quickNotes.state: QuickNotesState` 为只读 Compose 可观察快照：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| visible | Boolean | 是否显示速记模块，初始 false，非持久化导航状态 |
| loading | Boolean | 初次 / 重试读取文档索引中，初始 true |
| working | Boolean | 文档打开、导入、删除、备份 / 恢复、离页等异步操作中 |
| documents | List<QuickNoteSummary> | 成功读取的文档列表，按导入时间降序；空集合可以是成功空库，不应在 loading 时宣称空库 |
| reader | QuickNoteDocument? | 当前打开的文档；null 表示列表，载入失败保留旧成功内容 |
| conflict | List<QuickNoteSummary> | 重复内容或同源文件名的已有文档，非空时让用户选择取消 / 保留两份 |
| error | String | 可展示错误；新操作开始清空，失败保留已有数据，不自动重置索引 |

`QuickNoteSummary`：`id/title/sourceName/contentHash: String`，分别为 UUID、首个 H1 / 文件名推导标题（重名加序号）、原 .md 文件名、正文与图片组成的内容指纹；`importedAt: Long` 为毫秒时间戳；`position: QuickNotePosition` 为该文档位置。

`QuickNotePosition(anchor: String="", offset: Double=0.0, fraction: Double=0.0)`：标题 ID、标题内 CSS 像素偏移、滚动比例。非空 anchor 必须是此文档目录中的 ID；offset 有限且在 0..10000000，fraction 有限且在 0..1。精确采集与恢复流程见 `QUICK_NOTES_RENDERING.md`，不能直接把设备像素当 CSS 像素。

`QuickNoteDocument(summary: QuickNoteSummary, rendered: QuickNoteRendered)`；`rendered` 提供 `htmlBody: String`、`headings: List<QuickNoteHeading(id:String,level:Int,title:String)>`、`codeBlocks: Map<String,String>` 和 `warnings: List<String>`。HTML 是 CommonMark + 表格 / 删除线的语义正文，不是整张阅读页面；颜色 / 字号 / 横向滚动容器由 Claude 接入。警告包含缺图、无效锚点、网络 / SVG 图片、已识别的数学公式和嵌入内容占位限制，不得静默隐藏；不保证自动识别所有第三方扩展。

### 前端命令

以下非 suspend 命令在主线程调用，返回 Unit；由 ViewModel 的生命周期协程执行 IO。working / loading 时新普通命令不入队，并设置“速记正在处理，请稍后重试”；UI 应禁用相应操作。成功后更新模块 state，不使用刷题 `busy/notice`；异常显示在 `state.error`。取消继续传播，不伪装成成功。错误不删除旧文档或读取失败的索引。

| 接口 | 输入与行为 |
| --- | --- |
| `quickNotes.reload()` | 无参数；重新加载列表，loading / working → 成功列表或 error；可用于错误重试，working 时不重复加载 |
| `quickNotes.importUri(uri: Uri)` | 系统 SAF 选到的 .md / Markdown 配套 .zip；按 DISPLAY_NAME 判断扩展，在 IO 有界读取并立刻复制正文与资源到内部；流会关闭，无长期 URI 权限依赖；选择器取消不调用 |
| `quickNotes.importPackage(pack: QuickNotePackage)` | 非 UI 适配 / 测试入口；pack 含 sourceName/markdownPath:String、markdown:ByteArray、images:Map<String,ByteArray>，输入不可并发修改；同样执行大小、编码与图片校验 |
| `quickNotes.confirmKeepBoth()` | 用户明确选择保留两份后调用，使用已复制到内存的待定导入包，生成新 UUID / 可区分标题，不覆盖旧文档或位置；无待定冲突时报错 |
| `quickNotes.cancelImport()` | 非 working 时清除冲突与待定包，不改库 |
| `quickNotes.openDocument(id: String)` | 等此前位置写入完成，验证正文并渲染，成功更新 reader；文档不存在 / 文件损坏时 error，保留旧成功内容 |
| `quickNotes.closeDocument()` | 等此前位置写入后 reader=null，返回文档列表；失败保留阅读页并提示 |
| `quickNotes.deleteDocument(id: String)` | 删除此文档、资源和阅读位置；缺文档时失败。先提交索引再清理资源，失败 / 崩溃残留的未引用文件夹在下次成功读取列表时清理，不影响其他模块 |
| `quickNotes.savePosition(id: String, position: QuickNotePosition)` | 入独立有序队列，不占 working；working 时或文档已不在列表时忽略迟到回调。前端离页前先入队最后位置；非法值 / IO 失败记录 positionSaveError，不伪称保存成功 |
| `quickNotes.retryPositionWrites()` | 重试位置保存失败项 |
| `quickNotes.exportBackupUri(uri: Uri)` | 系统 CreateDocument 目的 URI；先 flush 位置，写独立速记 ZIP，流关闭才算完成；写目标失败不改本地文档，部分外部文件应让用户重试导出 |
| `quickNotes.restoreBackupUri(uri: Uri, replace: Boolean=false)` | 独立备份 ZIP；默认合并、内容相同跳过，保留现有较新的阅读位置；不同内容复制为新文档，保留备份位置。只有用户明确确认后才传 replace=true 替换速记库；不触碰刷题库 |

`quickNotes.pendingPositionWrites: Int`、`quickNotes.positionSaveError: String` 独立反映写入队列。`suspend quickNotes.flushPositions(): Unit` 等待此前队列完成；失败抛异常，调用者显示错误 / 重试，不能忽略。`leave/closeDocument/删除/备份` 已内部等待，不要在主线程 runBlocking。队列未完成就杀进程可能丢失最后一小段滚动，已成功原子落盘的位置可重启恢复。

`quickNotes.copyCode(blockId: String): Boolean` 同步复制当前 reader 的 codeBlocks 原文到系统剪贴板（保留缩进与换行），找到返回 true，未知 ID / 无 reader 返回 false，不执行代码，不改变文档或刷题状态。HTML 的 `quicknote-copy:code-N` 需前端拦截调用它。

`suspend quickNotes.readAsset(id: String, encodedPath: String): QuickNoteAsset?` 在 IO 从该文档资源索引取 PNG/JPEG/GIF/WebP。返回 `bytes: ByteArray` 与 `mimeType: String`；未知文档 / 图片返回 null，非法路径、超限或校验失败抛异常。编码路径来自 HTML 的单段 URL 编码逻辑路径；资源回调用 `request.url.encodedPath.substringAfter("/assets/")`，不要预先解码后再传入。前端后台资源拦截回调把 null / 错误转成明确的缺图响应，不允许资源请求回退到网络或通用文件访问。

### Repository 与持久化

`QuickNotesRepository(File(context.filesDir,"quicknotes-v1"))` 的所有读写均为 suspend 并内部切 IO，通过自身 Mutex 串行操作。一个应用使用同一实例。核心入口：`listDocuments():List<QuickNoteSummary>`、`importDocument(pack,keepBoth=false):QuickNoteImportResult`（Imported / Conflict）、`openDocument(id):QuickNoteDocument`、`savePosition(id,position):Unit`、`deleteDocument(id):Unit`、`readAsset(id,encodedPath):QuickNoteAsset?`、`exportBackup():ByteArray`、`restoreBackup(bytes,replace=false):Int`（新恢复文档数）。静态 `parsePackage(name,bytes):QuickNotePackage` 支持 .md / 配套 ZIP，不读题库，不执行文档内容。直接 Repository 调用不产生 UI 状态，异常向上传递。

存储由独立 `index.json`（quickNotesVersion=1）、UUID 文档文件夹和按哈希映射的图片组成；AtomicFile 提交索引，导入 / 恢复先准备完整文件，再一次性更新索引，失败回滚新文件；损坏索引不会被当空库覆盖。有效备份加明确 replace=true 可恢复损坏索引。正文及资源读取有哈希核对。

正文 ≤4 MiB，有效 UTF-8（可带 BOM）；正文与图片 ≤32 MiB，最多 512 个 ZIP 条目；库最多 200 份文档；独立备份 ≤64 MiB / 512 条目。相对图片通过保留目录的 ZIP 导入，包内路径可规范化但不允许越出包；文件头与扩展名匹配。SVG / 网络图不自动载入，缺失时返回正文占位和警告。

题库数据库仍为 **4**，刷题备份仍为 **3**，没有结构迁移或字段复用。刷题备份不包含速记，旧版备份恢复不删除速记；速记使用上面的**独立备份**保存正文、图片、标题和位置，不把版本数字当作 Room 迁移。更完整的渲染范围、限制和前端容器要求见 `QUICK_NOTES_RENDERING.md`。

---

## 2026-10-25（第二轮）· 参考解法增量保存与旧题目标签兼容口径

### `vm.saveVerifiedSolution(id: String, code: String, verified: Boolean): Unit`

用途：工作台及编程区的个人参考解法编辑器保存**当前题的解法与人工核对状态**，避免从旧 `QuestionPersonal` 快照整体写回，盖掉刚更新的难度或旧标签。调用方在主线程调用；前端只替换保存命令，不自行读写 DAO。

| 输入 | 约束与含义 |
| --- | --- |
| `id: String` | 已存在题目 ID，不可为空白；缺题时失败，不创建悬空个人资料 |
| `code: String` | 原样保存（包括缩进、换行与首尾空白），不执行代码、不调用 AI；`""` 配合 `verified=false` 可清除解法 |
| `verified: Boolean` | 用户明确人工核对的标记；为 true 时要求 `code.isNotBlank()`；非空解法可保存为 false，空白解法不可标记为已核对 |

返回 `Unit`，**不表示已经保存成功**。遵循现有 `launchTask`：调用时若 `busy=true` 则忽略，正常开始后立即 `busy=true`，IO 写入完成后恢复 false。成功通知 `notice="个人参考解法已保存"`；校验 / 缺题 / 数据库异常由既有任务处理设置 `notice="操作未完成：…"`，并记录脱敏错误。UI 需禁用忙时保存；不要在同一次点击中连续调用解法和难度两个命令并假定两次都执行。

内部 `suspend QuizRepository.saveVerifiedSolution(id: String, code: String, verified: Boolean): Unit` 复用难度保存的 Room 事务：读取数据库**最新** `QuestionPersonal`（不存在则构造默认值），仅 `copy(verifiedCode=code, codeVerified=verified)`，经 `LearningCodec` 校验后写入。校验失败抛 `IllegalArgumentException`，数据库异常向调用方传播；事务回滚，连默认行也不落盘。Repository 自身不维护 `busy/notice`。

成功后的状态：`learning.metadata` 现有 Flow 发布新值；保留 `tags/perceivedDifficulty`，不写 `Progress.notes/code`、全局便签、原题答案或原始 JSON，不影响页面、练习队列、成绩、SRS、考试状态。并发使用 Repository 的解法 / 难度增量接口时，事务串行读取最新行，两者互不覆盖。ViewModel 的并发命令仍受上述全局 busy 约束，不能据 Repository 并发能力推断 UI 命令都会入队。

前端替换 `VerifiedSolutionEditor` 中的：

```kotlin
vm.saveVerifiedSolution(q.id, code, verified)
```

难度继续调用 `vm.savePerceivedDifficulty(q.id, value)`（未设置传 `""`）。两者只负责各自字段。

### `QuestionPersonal.tags`：保留为只读兼容数据

决策：不删除字段、不清空历史值、不转换成全局便签；当前产品入口停止新增或编辑题目级标签。原因是数据库 4 和备份 3 已有该字段，清空 / 拒绝旧值会造成用户数据丢失或旧备份恢复失败，而把题目标记转成全局标签会混淆两种不同数据。

**“只读”指新产品入口的使用政策，不是修改数据库列权限。** 为保持向后兼容，旧 `vm.saveMetadata(value: QuestionPersonal)` 继续执行校验后的整行替换，仍接受含 tags 的合法旧对象；备份恢复也仍能写入历史 tags。新功能没有题目标签写接口，不能继续使用 `saveMetadata(meta.copy(...))`。本轮没有改变其签名、busy/notice、错误或整行替换语义；遗留调用方若不迁移，仍存在旧快照覆盖风险。

| 依赖 | 调整后的口径 |
| --- | --- |
| `searchQuestions` / Repository 搜索 | 保留旧 tags 的关键词检索和命中片段以兼容历史数据；“个人标签”明确指**旧版题目标记**，不是顶部全局标签。SQL、排序、AND 关键词与筛选行为不变。全局便签标题 / 正文仍仅由 `searchNotes` 查询，不按题号关联 |
| 分类题目卡片 | 不再显示题目级“标签：…”这项；只保留难度感受。当前 `BrowseScreen.kt` 仍有历史标签展示，须 Claude 删除生成 `"标签：$tags"` 的分支。搜索命中片段可能仍包含旧标记文字，这是历史搜索兼容行为，不代表全局标签绑定题目 |
| 全量备份 3 | `questionPersonal[].tags` 原样导出 / 恢复，包括非空旧值；保存新解法或难度后再备份也不丢旧值。全局独立内容继续在 `stickyNotes`，不与 tags 相互迁移 |
| 旧备份 1 / 2 | 按已有规则读取；它们没有 v3 个人资料块，恢复时继续保留本地已有个人资料。v1 没有独立便签时保留本地便签，v2 仍按原有规则恢复便签 |
| 独立个人答案导入 / 导出 | 原本只覆盖个人核对答案，不包含 tags / 参考解法；保持原样，不与全量备份混淆 |
| `LearningCodec` | 保持原格式与校验：tags 字符串原样读写；题号非空、难度为 `""/易/中/难`、已核对解法非空。不得新增 `tags.isEmpty()` 限制或自动清空；v3 按原要求包含 tags，v1/2 不走该个人资料块解析 |

数据库仍为 **4**，备份仍为 **3**；没有迁移、字段变化或破坏性接口修改。Compose 展示与调用替换由 Claude 实施，见本轮交接。

---

## 2026-10-25 增补：仅更新每题难度感受

### `vm.savePerceivedDifficulty(id: String, difficulty: String): Unit`

用途：供答题页“笔记与难度感受”合并区保存**当前题的个人难度**；与原题难度、判分、复习调度独立。全局标签 / 便签继续使用既有 `openStickyNotes/leaveStickyNotes`，不传题号，不在本接口中保存。

| 输入 | 约束 |
| --- | --- |
| `id: String` | 已存在题目的 ID，不可为空白；题目不存在时不创建悬空个人资料 |
| `difficulty: String` | 仅 `""`、`"易"`、`"中"`、`"难"`；UI“未设置”映射为 `""`，不能直接传“未设置” |

返回 `Unit`，按既有 `launchTask` 异步写命令执行：调用方在主线程调用，写入切 IO；执行期间 `busy=true`，忙时调用可能忽略。成功 `notice="难度感受已保存"`；失败沿用 `notice="操作未完成：…"`，不宣称落盘成功。前端应禁用忙时保存，观察 `busy/notice/learning.metadata`，不要把函数返回当作完成回调。

内部 `QuizRepository.savePerceivedDifficulty(id, difficulty): Unit`（suspend）在 Room 事务中通过新增 DAO `personalMetadata(id: String): QuestionPersonal?` 读取最新值，只更新 `perceivedDifficulty`，首次写入使用默认个人资料。按现有 `LearningCodec` 校验，缺题 / 非法值抛 `IllegalArgumentException`，数据库异常传播到调用方；事务失败回滚。Repository 入口不设置 UI 状态，前端使用 ViewModel 门面。

成功后 `learning.metadata` 的现有 Flow 会发布更新；不主动乐观更新、不导航、不改变练习队列 / 作答记录 / SRS / 今日目标 / 考试状态。保留同一记录的旧版 `tags/verifiedCode/codeVerified`，不写 `Progress.notes/code`，不修改全局独立便签。该更新与 `saveNotes` **不是同一个事务**；前端不得在一次点击中连续调用两个受 `busy` 控制的命令。

兼容：仅增加入口和 DAO 查询，旧 `saveMetadata(QuestionPersonal)` 整体替换接口保留；数据库仍为 4、备份仍为 3，无迁移。现有旧版题目级 `tags` 保留为兼容数据，与本轮用户要求的全局标签不是同一概念，不新增全局标签与题目的关联。

### 本轮页面调整可复用的入口（无变更）

| 用户入口 | 调用 | 边界 |
| --- | --- | --- |
| 首页分类练习 | `vm.navigate(Page.BROWSE)` | 只切页，保留会话和独立筛选 |
| 首页编程专区 | `vm.navigate(Page.PROGRAMMING)` | 只切页；编程不进入日常 SRS |
| 底部模拟考试 | `vm.navigate(Page.EXAM)` | 打开现有考试页；考试开始 / 继续沿用 `startExam`，未交卷不能覆盖 |
| 顶部全局标签 / 便签 | `vm.openStickyNotes()` / `vm.leaveStickyNotes()` | 无题号，全局列表，恢复来源页和原练习草稿 |
| 每题笔记 | `vm.questionDraft(q).notes` / `vm.saveNotes(id, code, notes)` | 每题存储；保存语义保持原样 |

---

更新日期：2026-10-25。适用于当前 1.5.0 工作区。本文件约定前端可调用的业务入口；后端负责实现与兼容，Claude 负责页面和展示交互。本次接口均为增量新增，不删除或改变已使用的成员。

## 通用约定

- 未特别说明的入口均位于 `QuizViewModel`（下文简称 `vm`）。非 suspend 命令和 Compose 状态读取在主线程调用。
- `var ... private set` 及只读 getter 都是可观察状态，不应由前端复制后自行维护第二份业务状态。旧版可写字段暂时保留兼容，后续接入优先使用命令函数。
- suspend 查询在调用方协程中执行，内部切到 `Dispatchers.IO`。成功返回完整结果；数据库异常向上传递，取消抛出 `CancellationException`，前端不能把取消当作错误显示。
- `launchTask` 包装的既有写命令异步执行，执行期间 `busy=true`；忙碌时新写命令可能被忽略。失败通过 `notice: String` 提示，结束后 `busy=false`，返回 `Unit` 不代表已经落盘。需要明确等待的入口有单独的 suspend 契约。
- 当前本地数据库版本仍是 4，备份格式仍是 3，继续兼容备份 1、2；本次没有结构变更或数据迁移，不涉及签名、版本号、权限、依赖及网络策略变更。

## 本次新增：ViewModel 搜索入口

### `suspend searchQuestions(filter: BrowseFilter, page: Page): List<QuestionSummary>`

用途：替代分类页面直接调用 Repository。合法页面及对应旧查询如下：

| page | 查询范围 | 旧接口对应参数 |
| --- | --- | --- |
| `Page.BROWSE` | 日常题（排除编程题） | `repo.search(filter, false, false)` |
| `Page.PROGRAMMING` | 仅编程题 | `repo.search(filter, true, false)` |
| `Page.MISTAKES` | 日常题，附加错题分组筛选 | `repo.search(filter, false, true)` |
| `Page.EXAM` | 日常题候选全集 | `repo.search(filter, false, false)` |

`Page.EXAM` 的结果仍可能含无标准答案的题；`startExam` 负责最终筛选可判分题，保持当前界面口径。其他 Page 抛出 `IllegalArgumentException("该页面不支持题库搜索")`，不执行查询。

`BrowseFilter` 的输入字段均为 `String`：

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `search` | 空字符串 | 空白分隔关键词，最多 16 个不同关键词，AND 匹配；SQL 参数绑定，`%`、`_`、反斜杠按字面量处理 |
| `source` / `company` | `全部` | 原来源 / 公司精确筛选 |
| `category` | `全部` | **规范分类**精确筛选，不使用原始分类编号 |
| `kind` | `全部` | `Kind.name`，不是中文 label |
| `difficulty` | `全部` | 原题难度，不使用个人感受代替 |
| `group` | `错题` | 仅错题页使用：`错题`、`不会`、`收藏`、`待核对` |

搜索覆盖题干、选项、答案、解析、参考代码、题目笔记、代码草稿和旧版题目标记 `QuestionPersonal.tags`（只读历史兼容）。顶部全局标签 / 独立便签另查 `searchNotes`，不属于题目标签。`不会` 要求当前仍错且最近一次评估为 `UNKNOWN`；待核对沿用题库标记或用户标记的现有规则。

返回结果按题目 ID 升序；空集合是成功结果。`QuestionSummary` 是轻量摘要，不含原始 JSON、完整题干、图片和选项正文：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `id` | `String` | 题库原始 ID |
| `stem` | `String` | 题干摘要（最多 180 个 Kotlin 字符），完整原文用 `loadQuestion` |
| `kind` | `Kind` | `SINGLE` / `MULTIPLE` / `TRUE_FALSE` / `CODE_READING` / `SHORT` / `PROGRAMMING` |
| `source`, `category`, `normalizedCategory`, `company`, `difficulty` | `String` | 原来源、原分类、规范分类、公司、原难度 |
| `reviewRequired`, `hasAnswer` | `Boolean` | 原题核对标记、是否存在原题答案；存在答案不等同于可自动判分 |
| `correctKeys`, `optionKeys` | `String` | 逗号分隔的原答案键、选项键 |
| `contentHash` | `String` | 原题内容哈希，用于核对答案和 AI 上下文有效性 |
| `matchSnippet` | `String` | 搜索命中片段，无关键词时为空，用于前端高亮 |
| `daily`, `objective`, `autoGradable` | `Boolean`（计算属性） | 是否日常题、客观题、原题是否可自动判分；含个人答案的判定使用 `learning.canGrade` |

不会改变 `page`、`busy`、`notice`、练习队列或已保存的筛选条件；不写数据库。方法本身不做防抖、不缓存、不生成界面状态，便于前端沿用已有 `LaunchedEffect` 的 160ms 防抖和取消机制。

### `suspend searchNotes(text: String): List<StickyNote>`

用途：替代 `vm.repo.searchNotes(text)`，按上述关键词 AND 规则检索独立便签标题与正文。结果按 `updated` 降序；`StickyNote` 字段为 `id/title/content: String`、`created/updated: Long`（毫秒时间戳）。空关键词沿用旧接口返回全部便签；分类页应保持空白搜索时跳过便签查询的行为。

只查询已落盘数据；刚输入且尚在写入队列中的便签可能尚未出现。如需导出前的严格快照，先 `flushStickyNotes()`。取消、错误和状态变化规则同 `searchQuestions`。

## 本次新增：首页汇总

### `vm.todaySummaryState: TodaySummaryState`（只读、Compose 可观察）

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `value` | `TodaySummary?` | 成功快照；加载或失败时为 null |
| `loading` | `Boolean` | 初始 / 切换日期 / 重试查询中为 true |
| `error` | `String` | 成功和加载时为空；失败为简体中文友好提示，不含底层异常消息 |

`vm.todaySummary: TodaySummary?` 是 `todaySummaryState.value` 的便捷只读别名。不要把 null 当成真正的零题库；空库成功时所有计数为 0。

`TodaySummary` 字段：

| 字段 | 类型 | 计算口径 |
| --- | --- | --- |
| `day` | `Long` | 设备本地日期的 `LocalDate.toEpochDay()` |
| `dueCount` | `Int` | 日常题且 `seen>0` 且 `due<=day`；含逾期，不含编程和已删除题目的悬空进度 |
| `unseenCount` | `Int` | 日常题且无进度或 `seen=0` |
| `dailyCount` | `Int` | 全部非编程题数量（含简答和待核对题） |
| `programmingCount` | `Int` | 全部编程题数量 |

四项计数在同一条 SQL 中聚合，观察 `questions` 和 `progress`；导入、恢复、答题、重置导致数据库变化后自动刷新。不依赖页面遍历题库，也不改变今日目标和连续学习天数原有算法。

### `learning.refreshDate(date: LocalDate): Unit`（已有）

应用外壳目前每秒把设备日期传入此方法，日期相同时不重复查询；日期变化后取消旧日期的观察，发布加载状态并获取新快照，兼容闰日、跨月和跨年。**本轮没有改应用外壳**。新结果携带 `day`，前端可与正在显示的日期比对，避免切日瞬间显示旧日期计数。日历日期始终由设备时区决定，不使用 UTC 截断毫秒。

### `retryTodaySummary(): Unit`

用途：查询失败后的用户重试；重新订阅当前日期汇总，即使日期没变也会查询。经加载状态后发布成功或错误，不占用全局 `busy`，不修改练习 / 考试状态。错误仅在本地脱敏记录，不上传。正常情况下无需调用。

Repository 内部新增 `observeTodaySummary(today: Long): Flow<TodaySummary>`，DAO 新增 `todaySummary(today: Long): Flow<TodaySummary>`。前端请使用 ViewModel 状态，不直接收集这两个底层入口。

## 本次新增：当前练习上下文

### `vm.currentQuestionSummary: QuestionSummary?`（只读、Compose 可观察）

从 `queue.getOrNull(index)` 对应 ID 取摘要；空队列、索引越界、题库里无此 ID 或摘要未加载时为 null。跟随 `queue/index/questions` 更新，无查询、无写入、无自动跳页。切到便签时仍表示刚才练习的题，返回后保持一致；交卷后若队列仍保留也继续返回该位置的摘要。用于“继续练习”卡片显示分类，前端应优先读 `normalizedCategory`。

## 既有公开状态（保持兼容）

| 状态 | 类型 | 读取 / 写入规则与含义 |
| --- | --- | --- |
| `ready`, `busy` | `Boolean` | 只读，离线题库启动完成 / 全局写任务进行中 |
| `importProgress`, `loadingMessage` | `Float`, `String` | 只读，首次题库准备进度和中文提示 |
| `notice` | `String` | 操作反馈，现有应用外壳显示后清空 |
| `questions` | `List<QuestionSummary>` | 只读，全库摘要，不含完整 JSON |
| `progress` | `Map<String, Progress>` | 只读，进度快照；未学题用 `p(id)` 返回默认值 |
| `attempts` | `List<Attempt>` | 只读，最近 100 条；统计不能用它代替全库 SQL 聚合 |
| `page` | `Page` | 既有可写导航状态，优先调用 `navigate`；切便签必须走专用入口 |
| `queue`, `index`, `mode` | `List<String>`, `Int`, `String` | 当前会话题号、位置和模式；现有页面翻题后仍需保存考试进度 |
| `practiceSession`, `practiceRecords` | `Int`, `Map<String,String>` | 只读，会话重置标识与本会话已判分记录，防止重复入账 |
| `examDeadline`, `examAnswers`, `examFinished`, `examResult` | `Long`, `Map<String,Set<String>>`, `Boolean`, `String` | 既有考试状态；截止时间为毫秒，不因跳页暂停 |
| `stickyNotes`, `noteDraft`, `pendingNoteWrites`, `noteSaveError` | `List<StickyNote>`, `StickyNote?`, `Int`, `String` | 便签已落盘列表、编辑草稿、排队数与有序写入错误 |
| `aiEntries`, `aiBusy`, `model`, `hasKey` | `List<AiEntry>`, `Boolean`, `String`, `Boolean` | AI 当前题记录、请求中、模型 ID、凭据是否已保存；不提供 Key 回显 |
| `errorLogText` | `String` | 只读，本地错误记录展示内容 |

### `vm.learning` 的既有状态与函数

| 成员 | 类型 / 签名 | 约定 |
| --- | --- | --- |
| `settings` | `LearningSettings` | 只读，`dailyGoal: Int`、`theme/fontSize: String`、`reminderEnabled: Boolean`、`reminderMinute: Int`（0..1439）、通知授权记录等 |
| `daily` | `List<DailyCount>` | 近 30 天有记录的日期；`answered` 按题 ID 去重，其余自动 / 自评作答数分开；无记录日期自行补零 |
| `learnedDays` | `List<Long>` | 所有有作答记录的本地日期，连续天数用 `LearningDates.streak(days, today)` |
| `categories` | `List<CategoryCount>` | 规范分类聚合，按正确率由低到高；掌握度按每题最后一次作答 |
| `upcoming` | `List<DueCount>` | 未来 7 天，逾期合并进今天；缺失日期补零 |
| `costs`, `totals` | `List<CostCount>`, `Totals` | 按日按模型估算费用，全历史 SQL 汇总 |
| `answers`, `metadata` | `Map<String,PersonalAnswer>`, `Map<String,QuestionPersonal>` | 只读快照，不能直接作为最终可判分依据；需校验题目哈希和选项 |
| `filters` | `SnapshotStateMap<Page,BrowseFilter>` | 既有可写筛选状态，按页面独立保存，返回时保留 |
| `personal(q)` | `(Question) -> PersonalAnswer?` | 只返回与当前题目内容及选项一致的个人核对答案 |
| `canGrade(q)` | `(Question或QuestionSummary) -> Boolean` | 自动判分能力，含有效个人核对答案 |
| `answerSource(q)` | `(Question) -> String` | `个人核对` / `原题库` / `无可自动核对答案` |

### 既有查询与练习命令

| 接口 | 输入 → 返回 | 状态与错误 |
| --- | --- | --- |
| `p(id)` | `String → Progress` | 缺失时默认未学；无写入 |
| `suspend loadQuestion(id)` | `String → Question?` | IO 读取完整 JSON，小型缓存；缺题 null，异常 / 取消传播；可能补充该题进度 |
| `due(today)` | `Long`（默认本地今天）`→ List<QuestionSummary>` | 同汇总到期口径，按错题优先再到期日期排序；暂时保留给开始练习取队列 |
| `start(list, selectedMode)` | `List<QuestionSummary>, String → Unit` | 成功重建会话并进入刷题；空列表给 notice；未交卷考试优先返回考试 |
| `startWeakPractice()` | `() → Unit` | 至少 3 次记录、正确率低于 65% 的分类，最多 20 题；不足则 notice |
| `questionDraft(q)` | `Question → QuestionDraft` | 当前会话的回答草稿；现有页面继续使用，跨便签保持，开始新会话清空 |
| `submit(q,text,choices,done)` | `Question,String,Set<String>,()->Unit → Unit` | 写命令，自动判分成功后调用 done；本会话相同题防重复；无可判分答案则失败 |
| `assess(q,text,choices,result,done)` | 上述参数加 `Assessment → Unit` | 仅不可自动判分题，结果为 `CORRECT/WRONG/UNKNOWN`；成功后 done |
| `toggleFavorite(id)` / `toggleFlag(id)` | `String → Unit` | 写命令，切换个人标记 |
| `navigate(page)` | `Page → Unit` | 便签走 `openStickyNotes`，其他页直接切换 |

### 既有考试与便签命令

| 接口 | 输入 → 返回 | 状态与错误 |
| --- | --- | --- |
| `startExam(list,count,minutes)` | `List<QuestionSummary>,Int,Int → Unit` | 筛选可判分日常题、随机抽题，保存截止时间与队列；已存在考试则 notice |
| `answerExam(id,answer)` | `String,Set<String> → Unit` | 更新并可靠写入考试选择；已到时则触发交卷 |
| `saveExam()` | `() → Unit` | 将当前考试位置与答案可靠提交到偏好存储，不降低原落盘保证 |
| `finishExam()` | `() → Unit` | 异步事务判分，提交历史后更新完成状态和成绩；busy / 已交卷时忽略 |
| `openStickyNotes()` / `leaveStickyNotes()` | `() → Unit` | 记住并恢复来源页，不改练习队列或考试截止时间 |
| `editStickyNote(note)` / `closeStickyNote()` / `newStickyNote()` | `StickyNote` 或无参数 `→ Unit` | 编辑、关闭、新建草稿；新建即排队保存 |
| `updateStickyNote(title,content)` | `String,String → Unit` | 更新草稿并有序自动写入，不占全局 busy |
| `deleteStickyNote(note)` / `retryStickyNotes()` | `StickyNote` 或无参数 `→ Unit` | 有序删除 / 重试失败写入，错误观察 noteSaveError |
| `suspend flushStickyNotes()` | `() → Unit` | 等待此前入队便签操作完成；保存失败抛异常，取消可传播 |

### 既有设置、文件与个人核对命令

以下写命令默认遵循全局 `busy/notice` 约定，返回 `Unit`。Android 文件选择器应由前端管理 URI 授权，不直接传文件路径。

| 接口 | 输入 | 行为 |
| --- | --- | --- |
| `importUri(uri)` | `Uri` | JSON 题库导入，已有 ID 跳过，不覆盖旧题和进度；最多 32 MiB |
| `backupUri(uri)` / `restoreUri(uri)` | `Uri` | 备份不含 Key；等待便签 / 代码写入；恢复成功清理会话缓存并回首页，旧备份兼容 |
| `resetProgress()` | 无 | 清理学习历史与 SRS，保留收藏、笔记、便签；不应误作清空用户数据 |
| `updateSettings(transform)` | `(LearningSettings)->LearningSettings` | 读取最新设置后事务更新、校验并重排提醒；不要用过期快照覆盖其他字段 |
| `markNotificationRequested(done)` | `()->Unit` | 单独异步持久化“已请求”再回调；失败 notice，不回调，不反复请求权限 |
| `savePersonalAnswer(q,selected,notes)` | `Question,Set<String>,String` | 校验哈希和有效选项，单独保存；不改原始 JSON；个人答案优先于原题库 |
| `deletePersonalAnswer(id)` | `String` | 删除个人核对答案 |
| `saveMetadata(value)` | `QuestionPersonal` | 个人标签 / 难度 / 已核对参考代码；不得由 AI 自动确认 |
| `importPersonalUri(uri)` / `exportPersonalUri(uri)` | `Uri` | 独立个人答案文件导入导出，导入验证当前题目哈希和选项 |
| `loadErrorLog()` / `clearErrorLog()` | 无 | 读取 / 清除脱敏的本地环形记录，更新 errorLogText，无上传 |
| `saveNotes(id,code,notes)` | `String,String,String` | 有序保存代码 / 题目笔记，等待队列完成后提示 |

编程草稿现有入口 `vm.codeDrafts.update(id: String, code: String, notes: String): Unit` 自动排队保存；观察 `codeDrafts.writer.pending: Int / error: String`，失败用 `writer.retry()`，导出前 `suspend writer.flush(): Unit`。前端不要调用 `clear/close`，不自行执行代码。

### 既有 AI 入口

| 接口 | 输入 → 返回 | 行为 |
| --- | --- | --- |
| `saveKey(value)` | `String → Unit` | 写命令，加密保存 / 空值删除，只有 hasKey 可读；不得回显、备份或记录 Key |
| `chooseModel(value)` | `String → Unit` | 仅允许 `DeepSeekClient.MODELS` 中模型 ID；无效输入抛参数异常，可靠持久化 |
| `ask(q,text,choices,type)` | `Question,String,Set<String>,AiRequestType=FOLLOW_UP → Unit` | 用户主动请求；busy 时忽略；状态见 aiState；此门面不传编程代码 |
| `aiState.ask(q,text,choices,type,code)` | 上述参数加 `code:String="" → Unit` | 编程审查可使用的既有入口，仅用户主动点击时调用 |
| `aiState.observe(q)` | `Question → Unit` | 切换当前题目及哈希的记录订阅，无网络 |
| `aiState.cancel()` / `aiState.retry()` | `() → Unit` | 取消当前请求 / 重试最后一次请求，观察 busy、error |

`AiRequestType` 为 `EXPLANATION/FOLLOW_UP/SUGGESTED_ANSWER/VARIANT/CODE_REVIEW`。结果只在成功时缓存；失败观察 `aiState.error: String`，不写失败缓存。追问仅取当前题目相同内容哈希最近 4 轮。所有生成内容标注“AI 建议，未核实”，代码审查注明“未经编译验证”，都不参与判分、不自动写原题或个人核对答案。费用为估算。

## 兼容与边界

- 原有 `vm.repo/db/vault` 等成员保留以免破坏已有代码，**不作为新增前端功能的推荐接口**；本轮不收窄访问权限，也不改 Claude 现有直接 Repository 调用。
- 前端下一轮只需替换搜索调用、订阅汇总和显示当前题分类；具体位置见 `BACKEND_HANDOFF.md`。所有页面布局、样式、导航高亮方案与 UI 测试由前端负责人处理。
- 既有命令的局限已如实写明，没有借本轮契约整理改变签名或偷偷改变行为。将来需要破坏性接口变更，先提出方案并经确认。

## AI-02a-1：独立 AI 数据基础（2026-11-06）

冻结依据 `AI_02_CONTRACT_FREEZE_20261106.md`。本批新增代码但**未接生产**：正式 QuizDatabase 仍 v5、学习备份仍 v3，旧 AiStateHolder/DeepSeek 发送不变。下列契约不能作为已挂载的 VM/Compose 功能调用。

- `AiConversationContract.kt`：冻结门面/owner/revision/状态/结果类型。分页用 `AiPage<T>`，避开现有导航 `Page`；请求类型复用 `AiRequestType`，枚举持久化为 name 稳定字符串，未使用 ordinal。`AiConversationFacade` 仅接口，本批没有运行时实现。
- `AiCostsContract.kt`：可空用量/已知估算/未知调用数、来源和币种契约；本批没有替换学习费用订阅或实现聚合服务。
- `AiConversationData.kt`、`AiConversationMigration.kt`：五表独立实体/DAO及未注册的5→6增量迁移。无 questions FK，无旧 ai 双写。仅测试宿主注册；测试宿主 `AiFoundationTestDb` 在 src/test 且 exportSchema=false，不覆盖正式schema。
- `AiSnapshotCodec.kt`：完整 Question 构造字段无损快照，保留 raw 与 options 顺序，复用现有 contentHash；固化 system/template/parameters/history/payload/hash，不接受凭据字段。实际入模字节上限64 KiB是本地限制，不宣称线上模型token兼容。
- `AiConversationCodec.kt` / `AiRowCodec.kt`：新增AI五段的确定编码、严格字段/枚举/Long/十进制金额、引用/角色/顺序/收据校验及容量计量。它们**不是完整备份4实现**；旧学习数据、完整文件streaming/staging在下一批。
- 内部 `AiStore`：由调用方传同一个测试Room宿主和当前题目读取函数，在Room事务内冻结/接受；create/rename/saveDraft/accept/retry/cancel成功收据同事务保存，相同commandId返回原收据，不同参数抛`AiStoreFailure(IDEMPOTENCY_CONFLICT)`。不创建HTTP、无凭据访问、未实现发送协调器或Compose门面。存储/校验异常继续向调用方传播，不显示假成功；正式门面错误映射在后续接入批。

容量默认32 MiB新增AI段、1 MiB单文本字段、100000消息。`reservedBytes`是剩余预约；`consume`以实际规范字节增量扣减，含预约字段自身数字长度，用二分求满足守恒约束的最大剩余额，终态释放余量。重复eventSeq不再次写库/扣减；事务失败回滚正文、预约、草稿与命令。eventSeq为离线注入的持久化事件序号，不构成网络事件/cleanup仲裁完成的声明。

`AiDraftCoalescer`是可控时钟纯调度器：尾沿300ms、连续输入最长2秒，force用于离页flush。它仅合并待保存文本，不在每字符创建收据；实际受理保存才通过AiStore写一条命令。纯调度器不拥有后台线程/自动定时器，不是现有代码草稿writer的替代；后续运行时协调器负责定时唤醒、串行提交及失败保留/重试。`AiStore.flushDraft`只确认已落盘revision，不能保存尚未提交的编辑文本。

本批 checkpoint/终态/中断恢复仅为独立数据安全路径，`recoverInterrupted`不重发请求。完整HTTP取消、CLEANING槽、旧新sender恢复租约、费用消费者、关库/启动屏障、归档分页与完整备份4均待对应接入批，不可由本批测试外推为已完成。

## AI-02a-1-F1 数据契约补修（2026-11-06）

仍不接生产Room/旧发送/Compose；F1任务依据`AI_02A1_REVIEW_AND_FIX_20261106.md`。

- 账期：AiStore注入`now:()->Long`与`businessZone:()->ZoneId`。每个接受命令只读一次时钟；accept/retry各只读一次业务时区，统一确定本attempt的createdAt/billingDay/billingZone。retry复用原payload/模型/参数，重新归本次账期。codec拒绝createdAt与billingZone换算不等于billingDay的数据，无新run的隐式修正例外；旧AiEntry不进入此新协议校验。
- 收尾空间：`requiredClosingReserve(run)`以同一规范codec计量最长中断/取消终态差额，加一条必要取消收据的最坏编码上界。收据原始UTF-8上限16KiB，按每字节最多6字节JSON转义计量，并包含command字段及数组分隔符；终态时间/cutoff按Long最大宽度计量。这个仅计量、不落盘的上界标本不充作有效收据。非终态必须满足剩余预约≥此下限，终态预约为0；decode/import/普通persist统一预检，recoverInterrupted也在写前复核。
- checkpoint的`consume`二分区间从收尾下限起算，不能将下限花在新正文；超出可消费预算整笔回滚，不删部分文本、不从全局空闲额度借钱。保留actualEncodedBytes+outstandingReservedBytes≤原上界/容量，含预约字段自身数字变化。没有把已消费预约恢复成完整6.25MiB。
- 估价：新增AiEstimateCodec以保存的三项费率按“本币种/百万tokens”计算：`((input-hit)*missRate + hit*hitRate + output*outputRate)/1000000`。使用BigDecimal精确中间运算，仅最终金额以12位小数、HALF_UP舍入；落盘去多余尾零，用数值compareTo校验恢复值，因此等值尾零合法。当前DeepSeek币种仍USD，本轮无线上价格查询或调整。
- AiUsageEvidence.estimated保留为内部调用兼容输入，**不作为账本值**；Store由tokens/费率重新计算。usage不完整或priceVersion/费率未知时估价null，已知完整费率和usage时必须有相等的估价；缓存仅NOT_APPLICABLE/本次0，不复制源用量。字段长度/整数范围/decimal语法独立于显示格式校验。

维护门槛：未来变更快照参数默认值必须保留旧snapshotVersion解码规则；不能把存量payload按新默认判无效。全量dataset校验在接近32MiB容量时的CPU/分配/事务延迟仍须在AI-02a-2/运行时接入前实测；F1不扩大为内部存储重构，完整备份流式路径不能调用全量dataset后声称低内存。

## AI-02a-2 A/B：局部存储与完整备份基础（2026-11-06，未挂生产）

依据AI_02A2_DISPATCH_20261106连续实现A→B；正式Data/Room版本、Repository、VM、启动/关闭、旧sender与Compose均保持原状。以下是独立基础协议，不是现有UI可调用的新发送入口。

### A：受影响行与精确计量

五张未注册AI表各增加内部`encodedBytes:Long`及覆盖索引，无第六张正式表。值表示`AiRowCodec`规范外部行JSON的精确UTF-8字节数，**不包括SQL内部计量列本身**；该列不进入备份/冻结业务接口。SQL写入与正文在同一事务更新；导入重算（显式非零内部计量必须相等），备份wire若伪造该字段直接拒绝。外部AiDataset视图不带内部计量值；`audit()`以全量oracle核对存量计量和完整引用。

全局字节 = 空AI信封规范字节 + 各表SUM(encodedBytes) + 各数组max(COUNT-1,0)分隔符；更新事务核算行差额和全局分隔符差额，叠加剩余预约及消息数限制。`AiMeter`不是进程内唯一计数缓存：第二Store、重建后同样读数据库聚合。预约消费另纳入局部/全局数组差异，不从其他未用额度借收尾预算。

草稿/标题仅读目标session与本commandId；发送/重试/checkpoint读取根run、当前完整历史及直接必要引用。`AiWorkingSet`不会递归加载无关会话或所有旧payload。局部校验仍检查变化行/引用/快照/payload/费用/账期/收据，已提交且未改变的引用不反复解析；不可信导入、staging和冷边界仍逐项完整验证。正常持久化依赖受控DAO同事务维护计量，正式接入前必须调用边界audit，不能把任意SQL篡改后的计量当可靠事实。

快照v1的4096输出参数、支持模型及解析规则固定在AiSnapshotCodec v1，不跟随未来DeepSeekClient默认值变化。冷中断恢复遇系统时钟回拨时，逻辑finishedAt不早于原createdAt，以免合法账本因墙钟变化无法收尾。

### B：单文件导出与磁盘预检

`AiFullBackupCodec(context,workDir).export(testHost,openNewDestination,resource)`：在一个Room事务快照内以游标逐行写临时UTF-8文件，随后用同一恢复预检器检查，通过后才打开**新目的文件**并复制；复制/flush/close均完成才返回bytes+SHA256收据。factory是受信应用适配器，未来UI必须使用新建文档；不能承诺任意提供者覆盖写失败可回滚旧文件，旧有效备份从不用作临时文件。

`prepare(input,resource)`：8192字节缓冲复制输入到独立spool，再JsonReader逐行解析至临时SQLite索引；重复字段/ID、未知版本/枚举、坏引用/循环/重复成功轮次、伪造收据/用量/估价/账期/预约均在目标写入前拒绝。新AI段在读取时提前计量，最终以磁盘聚合及逐根验证复核；不会加载整个文件JSONObject或全库AiDataset。`AiBackupStage`只暴露只读遍历，预检完成连接置query_only，close删除本次私有临时文件。

备份4含旧业务段和五AI段/aiConversationVersion1；问题对象附`_storedJson`保留原问题JSON的空白/顺序（既有同名扩展仍由原JSON保留），仅版本4解释此元数据。旧1～3沿用原默认字段/缺段保留语义；新聊天/账本保留，v1便签缺失保留，v1～2学习设置/个人答案/资料保留。设置只导出原getSettings语义的id=1，并保留目标其他本地设置行；通知已询问标记保持OR。旧ai空hash/旧hash及原Double估算照旧，不伪造聊天或用量。

整个文件可超过32MiB，旧单行数据不套新AI的1MiB上限；新AI规范段仍受32MiB/100000消息/单字段1MiB限制。旧超大单行或实际磁盘/内存不足可失败，目标不部分替换；8192只是I/O缓冲，不是RAM峰值声明。`resource`可由适配器施加本机资源预检，实际I/O失败同样保原库并清理本次临时文件。

`AiBackupTestRestorer`只在src/test：目标单事务替换，先按RESTRICT依赖逆序有界删除，再按消息seq依赖插入；中途异常全部回滚。传入非终态run先在staging转中断，保留部分文本/已知或未知用量并释放预约，不恢复网络。重复恢复不重复账本。生产旧/新sender停止租约、考试占用、草稿互斥及关库屏障仍属2C，不能从测试适配器外推为已挂生产。

A/B收尾补充：旧业务Int字段在JSONObject窄化前检查Long→Int范围，拒绝2^32等回绕；合法旧应用生成的整数编码不变。输入流所有权从prepare调用开始接管，工作目录不可用/初始化失败也关闭输入；输入close失败会清理已准备的staging并抛错，不返回可恢复/导出成功。目标仍只在完整预检后由测试恢复器单事务操作。

草稿编辑权补充：session私有SQL列`activeEditorEpoch`与`openEditor`在同一事务更新，保存同时核对本Store token、数据库当前epoch和草稿revision。第二Store打开新编辑器后，旧owner即使重新读取最新revision仍被拒绝；数据库重开后必须重新取得编辑权。该列不计入规范wire、不进入外部DTO或备份；恢复不会授予先前进程的编辑权。

## AI-02a-2C：正式数据接入（2026-11-06，未发布）

本节接替前述A/B“未挂生产”的实现状态：QuizDatabase正式version6，注册原1→5链及AiConversationMigration(5,6)，五表与schema6一致；应用版本/签名/依赖不改。现有Compose/旧AiEntry发送及费用显示保持；未实现AI-02b门面、流式发送或费用消费者切换。

- `QuizRepository.backupTo(openNewDestination)`直接输出完整格式4；`restoreFrom(InputStream)`从调用开始接管输入，先spool/staging校验，再使用AiBackupRestorer生产事务替换。普通与启动URI恢复均调用它，不再readText整份文件或套32MiB整文件限制。`backup():String`/`restore(String)`为兼容入口，委托同一codec/恢复器；字符串入口仍有整文件内存分配，不作为生产文件路径。
- `AiStorageLifecycle`附着同一QuizDatabase，所有Repository共享替换门闩、AI写入epoch/任务、冷验证状态和owner关闭登记。恢复在原草稿排他内先flush当前接受文本及便签；即使无效备份被拒绝，也保留可靠保存的当前文字，不推进generation。预检不暂停sender；替换前禁止ask/retry，Main撤销请求状态，取消并join所有旧job（包括早先取消仍在退出的job），在同一草稿排他内再获得写锁及ExamAccess，单事务替换。暂停失败/事务失败也释放admission；成功清除旧请求归属并刷新旧AI观察，不重发。生产AiStore的独立操作也登记owner与写入门槛。
- 旧sender保持原prompt/模型/解析/入库格式和completeCancellable，AiLegacyNetwork对每次请求配置同样超时/重定向策略的受控OkHttp client；取消仍call.cancel，外层job在NonCancellable finally等待callEnd/callFailed确认连接和响应资源释放，再允许恢复。客户端回调后续只可能解析已读入的不可变文本/尝试恢复失效continuation，不拥有Room/UI写权限；外层job/finally和实际IO都已退出。Room写入前另核对epoch和协程取消。测试注入改为接收受控HTTP client的factory，原UI断言及超时不改。
- owner安装现有codeDrafts.exclusive作为共享替换边界，直接repo.restore也需要flush草稿与便签，提交后推进草稿generation。预检期间响应取消；进入目标写入阶段后以NonCancellable完成原子事务及owner刷新，再检查调用方取消，不在提交后留下旧writer代际。活动考试在目标写入前由ExamAccess再次拒绝。关闭先封admission并取消所有存储任务，等待旧AI/备份/草稿/考试/owner退出后db.close。
- Ready前检查原九表及五新表的依赖列，再以AiStorageValidation逐行/逐根验证规范字节、引用、快照、账期、估价和预约，非终态保文本收尾，不加载整个AiDataset。元数据不符明确失败，不静默修复/删库。冷规则与staging复用；源备份也逐行核对物理encodedBytes，不能伪造大小绕限。
- 生产资源适配器检查工作目录可写、cache与数据分区至少16MiB可用余量；spool/导出/索引读取及目标逐行写入持续发出进度；首次、1MiB增长、进度回绕、128行或500ms时查询实际余量，避免每8KiB反复stat。此为本机余量保护，不保证另一进程或文档提供者未来容量；实际IO/WAL/close异常同样失败。当前以usableSpace实际余量作保守预算，不调用allocateBytes自动回收缓存；可回收缓存空间不计入，可能提前拒绝。只清理本次独立临时文件。诊断编译目标6/4/1，不推断坏库现场版本，不打开坏库升级。

正式升级后的库不能由Room5 APK直接读取；格式4不伪装格式3，旧版不支持恢复它。迁移前保留旧版格式3备份，回退源码不会降级数据库。本轮不发布、不覆盖稳定APK/试装ZIP。

## AI-02b：会话与费用门面（2026-11-07）

正式入口：`QuizViewModel.aiConversations` / `QuizRepository.aiConversations`，共享同库唯一服务；`vm.aiCostsState`、`vm.learning.aiCostsState`、`vm.aiCosts.observe()` 共用一次费用上游。完整调用示例与前端语义见 [AI_02B_CALLABLE_20261107.md](AI_02B_CALLABLE_20261107.md)。冻结的 `AiConversationContract.kt` / `AiCostsContract.kt` 签名不变。

- 第一次读取会话门面执行 opt-in 切换，等已有旧请求退出后 READY；旧 ask/retry 封口，旧历史仍可读，旧 UI 未挂载新入口时继续原行为。费用只读入口不会触发切换。
- 独立 actor 串行接收命令、结果截止及短 Room 写入，不持锁等待网络退出。非流式 HTTP 逐字节使用已提交 payload；标记派发/费率快照先落盘，再启动 one-shot 请求。取消/完成有独立 CLEANING，连接/回调/持久化收尾结束前不得创建新网络请求；恢复/关闭通过同库生命周期 hook 停止、等待、换代。已接受的草稿/请求不依赖 ViewModel scope 完成收尾。
- 首屏最新、向前分页、页内 seq 升序。会话和旧历史按时间+稳定 ID keyset。HMAC 游标绑定过滤/排序/代际；成功恢复失效。创建/标题/草稿/send/retry/cancel 返回持久事实；读取已有收据不再发送。缓存成功事务直接产生完整 CACHE run，不再预约网络正文余量；旧接受信封仍使用 PREPARING 标记保存历史收据，不改变格式4字段。
- 首次或空闲后的草稿可以立即持久化，频繁调用300ms尾沿/最长2秒合并；显式flush、发送和边界优先清理当前待写项。不把 pending 标成 Saved。编辑权随切换撤销；完整 owner/epoch/序号拦迟到结果。
- 新费用窄投影在单事务读取旧估算与已派发新账本；按币种分组，保留已知与未知次数，不用源用量填缓存。旧 `learning.costs` 留给尚未迁移的页面；新消费者统一共享新状态。

2C 文档及原交付证据保留为历史。未改 Room/备份版本、旧AI客户端、Compose、导航、依赖或发布产物。SSE 与实际页面接入另行处理。
