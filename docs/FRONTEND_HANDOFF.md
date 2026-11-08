# 前端交接文档（FRONTEND_HANDOFF）

## 2026-11-08 · 1.7.3 Leader 退回两项的修复（Claude）

详见 `docs/PLAN-1.7.3.md` 末尾“Claude 修复退回两项”。只改了 `AiChat.kt`、`AiDiscussions.kt` 和测试，另外给 `FakeAiConversationFacade` 加了测试用的 `openGate`。

- **改名冲突**：`AiChatController.renameNow` 遇到 `STALE_SESSION` 时调用 `reloadSessions(keep, include)`，保留已加载的页并确保目标在列表中。全局列表的 `reload` 增加 `include` 参数。
- **晚到的打开**：`openGeneration` 代次，`back()`、`leave()` 和新的 `open()` 都会递增它。`openNow` 在锁内检查代次；晚到的成功结果若仍被选中就 `deselect()`。打开与 `leaveNow` 的释放共用一把文件级锁 `aiSelectionLock`。
- **结果**：Leader 两项探针通过；全量 441/441，Lint 0 错误。已生成试装 ZIP。

## 2026-11-08 · 1.7.3 AI 讨论列表、精确打开与列表改名（Claude，PLAN-1.7.3）

基线 `dab8fc2`（v1.7.2），按 GPT 的 `4b720bf`（`feat/1.7.3-ai-discussions-backend`）接线。工作区里与后端相关的 8 个文件（`AiConversationContract/Data/Runtime`、`AiPageReader`、`AiStore`、`AiLocalStoreTest`、`AiRuntimeTest`、`FakeAiConversationFacade`）**原样取自 `4b720bf`**，Claude 没有改动。界面改动单独无法编译，需要与该提交一起合入。

### 改动

- **入口**：「更多」第二组，「AI 费用估算」正上方新增「AI 讨论」（`Page.AI_DISCUSSIONS`，子页面、无底栏，返回到「更多」）。图标为 `AppIcons.Chat`，颜色是新增的 `MoreColors.ai`。
- **列表**（`AiDiscussions.kt`）：读取 `allSessions`，每行显示讨论名、来源类型和 `sourceLabel`（「题目 · 题干首行」或「速记 · 文档名」）。`readOnly` 的行标「题目/文档已更新或删除 · 只读」。
  - 有读取中、空列表、读取失败加重试、加载更多几种状态。
  - 加载更多时遇到 `STALE_CURSOR`，从头重读到原有条数。
- **精确打开**：点一行在本页打开那条讨论，复用 `AiChatScreen` 和 `AiChatController`，按 `sessionId` 调 `open`。适配器沿用原入口：
  - 题目：`QuestionAiSource`，有当前题目时用当前版本，编程题有代码审查；代码审查只带该题已保存的代码，不猜选项。
  - 速记：`DocumentAiSource`，章节为空。
  - 来源已删除时，用列表项的 owner 和 `sourceLabel` 只读查看。
  - 打开时不发送，也不进入刷题流程。返回顺序为「讨论 → AI 讨论列表 → 更多」。离开讨论前先保存草稿、释放编辑权。回到列表时保留滚动位置，并按已加载条数刷新名称。
  - 为此 `AiChatScreen` 新增参数 `onLeaveSession`：只显示单条讨论，不显示来源列表，离开时回调。`AiChatController.leave(then)` 在草稿保存并取消选中后再回调。
- **改名**：全局列表、题目讨论列表、速记讨论列表的「更多操作」里都有「重命名」，共用 `aiRename` 和同一个编辑框 `AiListRenameEditor`。
  - 编辑框预填现有名称。去首尾空白后限 1～80 个码点；名称未改时不能保存。保存中按钮显示「正在保存…」，防止重复提交。
  - 失败时编辑框和输入都保留。遇到 `STALE_SESSION` 会重读列表，编辑框标题显示当前名称，提示「讨论名称已在别处修改……确认后再保存」，用户再点保存时按新版本提交。
  - 讨论内标题栏的改名不变。
  - 与任务单的差异：编辑框是列表上方的内联卡片，不是弹窗。理由同标题栏注释：弹窗里的输入框会抢走页面键盘。Robolectric 下，弹窗内的输入框也一直无法空闲（实测 4 项测试卡死 60 秒）。

### 测试

- `AiChatScreenTest` 新增 5 项：
  - 原列表改名（预填，未改或超长不可保存）；
  - 过期版本时保留输入、显示当前名称、再次确认后保存；
  - 全局列表混合两种来源并按顺序排列、只读标记、精确打开（不发送，速记讨论没有题目快捷操作）、讨论内改名后返回列表即显示新名称、旧版讨论只读；
  - 空列表；
  - 分页、改名、保存失败保留编辑框。
- `QuickNoteAiWiringUiTest` 新增 1 项（真实后端）：
  - 「AI 讨论」入口位于「AI 费用估算」上方，子页无底栏；
  - 两种讨论都在列表里；全局改名后原入口的 `sessions` 也是新名称；
  - 点题目讨论在本页打开，不建刷题队列，「讨论 → 列表 → 更多」逐级返回；
  - 点速记讨论不打开速记阅读器。
- `PageChromeUiTest`：「更多」子页遍历加入「AI 讨论」，并补了返回目标断言。打开该页会启动 AI 运行时，因此 cleanup 改为等资源关闭后再删库（与 `QuestionAiEntryUiTest` 相同）。
- 定向 7 类 81 项全部通过：上述 3 类，加上 `QuestionAiEntryUiTest`、`QuickNoteReaderAiTest`，以及 GPT 的 `AiRuntimeTest`、`AiLocalStoreTest`。
- 已知环境问题：全量运行偶发 1 项「uncaught exceptions before the test started」（Room 失效跟踪器访问已删库的连接）。每次出现在不同的类，单独运行均通过。未改动的 v1.7.2 基线在本机全量运行也出现 1 项同类失败（`PageChromeUiTest.stickyNotes…`）。
- 未做：真机效果，以及任务单验收中的重启后核对和备份恢复。

## 2026-11-08 · 1.7.2 速记问答提示、引用范围与删除讨论（Claude，PLAN-1.7.2）

基线为 `3d52f20`，按任务单中“GPT → Claude 接口交接”的接口接线。只改了界面文件：`AiChat.kt`、`QuickNoteAi.kt` 及测试。**依赖 GPT 尚未提交的 1.7.2 后端**（`deleteSession`、`documentCoverage`、Room 8 等），本提交单独无法编译，需要与 GPT 那批一起提交或合并。

### 改动

- **速记入口说明**：上下文卡片第一行改为“可以追问文档里的内容，也可以继续了解相关概念。文档内容只在你发送时提交。”有阅读位置时，加一行“当前阅读「节名」：问“这一节”时以它为准”，表明阅读位置只用来理解“这一节”这类指代，不限定回答范围。预览有段落时写“预计提供：…（发送时按你的问题重新选取）”；没有相关段落时写“这里没有找到直接相关的段落，也可以照常提问”，仍然可以发送。
- **引用与通用知识分开**：AI 回复下方另起一块“速记依据”，只列出实际提供给模型的段落（`run.documentReferences`）；没列出的内容即来自通用知识。引用为空时整块隐藏，回答照常显示。块内附覆盖说明（`run.documentCoverage`）：
  - 整文且不完整：“整份文档较长：本次使用目录和代表性摘录，覆盖 x/y 节，可能有遗漏。”
  - 整文且完整：“本次提供了整份文档（共 y 节）。”
  - 本节或检索只提供了部分段落时，也会说明。
- **删除讨论**：题目和速记的讨论列表，每行都有“更多操作”菜单，菜单里是“删除讨论”；旧版只读讨论也能删。
  - 确认框显示标题，并说明会清除消息和草稿、无法恢复；已保存的便签仍可查看；已产生的费用仍计入统计。
  - 这条讨论正在生成时，确认框会提示“会先停止，收尾完成后再删除”，按钮改为“停止并删除”：先用后端给出的 owner 调 `cancel`，再 `awaitIdle`，然后删除；如果仍返回 `BUSY`，等空闲后再试一次。
  - 删除成功后刷新列表，并提示“已删除讨论「标题」”。失败时讨论和草稿保留，显示“讨论未删除：原因”。
  - 调用的是 `deleteSession(sessionId, session.question)`，保留列表项里的旧版本 hash 和来源种类。

### 测试（证据：`validation/UI-172-20261108/`；输入为 `3d52f20` 加上工作区 41 个文件，含 GPT 后端，指纹见 `input-fingerprints.csv`）

- `AiChatScreenTest` 增至 27 项：
  - 零引用时回答照常显示，没有依据块；
  - 整文不完整时依据块和覆盖说明的文案；
  - 覆盖说明的各种情况；
  - 取消确认不会删除、旧版只读讨论可删、删除后列表刷新并给出提示；
  - 生成中先停止、等收尾再删除；
  - 删除失败时保留讨论并说明原因。
- `QuickNoteReaderAiTest` 共 5 项，按新文案更新，并加了“无相关段落仍可提问”的断言。
- 真实后端：`QuickNoteAiWiringUiTest` 在发送失败收尾后删除速记讨论，并用 `documentSessions` 确认列表已空；`QuestionAiEntryUiTest` 删除题目讨论，确认 `sessions` 已空、刷题进度不变。
- 定向测试 8 个类 62 项，一次全部通过。全量 `assembleDebug testDebugUnitTest lintDebug --rerun`：BUILD SUCCESSFUL，53 类 428 项全部通过。Lint 0 错误、7 条警告。
- 未做：真实模型试问（如只有 UDP 笔记时问 TCP 三次握手），以及真机效果。按任务单留到联合验收。

## 2026-11-08 · 1.7.1 新界面测试 AppNotIdle 补修（Claude，PLAN-1.7.1 联合收尾）

只改了 `QuickNoteReaderAiTest.kt` 和 `StickyNoteSourceUiTest.kt`，生产代码、断言、超时和共享 helper 都没有改。详见 `validation/UI-171-IDLE-20261108/README.md`。

- **复现**：在原输入（`20c463c4` 加当时 GPT 的 47 个文件，指纹核对一致）上，全量再次得到同样的 9 项失败。只读探针显示，Compose 主 dispatcher 在测试开始时已经处于“原回调丢失”状态：自认为已安排回调，待执行队列里也有任务，但主线程 Handler 中没有这个回调，主线程队列是空的。
- **最小触发顺序**（二分探针确认）：
  - 任一 Compose 界面测试 → 会写快照状态的非界面测试 `EnhancedAiTest`；
  - `StartupTest` → 便签类，卡在 Compose 规则启动时、`@Before` 之前的空闲等待。
- **补修**：
  - 两个类加了外层规则 `@get:Rule(order = 0)`，在 Compose 规则启动前用 `ComposeMainQueue.deliverStrandedCallbacks()` 补送原回调。
  - 清理顺序改为：停 scope → 撤下阅读页并确认 WebView 已离开 → `store.clear()` → 等 `examResourcesClosed`（上限 20 秒）→ 确认数据库已关闭 → 删除测试数据。
  - 起初把补送放在 `setup()` 里，只修好第一种顺序，所以改成外层规则。
- **验证**：两种最小顺序在补修后都通过。以 GPT 的 `0160b153` 加这两个文件做全量构建、测试和 Lint：BUILD SUCCESSFUL，52 类 418 项全部通过，Lint 0 错误、7 条警告。
- **未闭环**：原回调丢失是测试环境问题（Robolectric 在测试间重置队列，而 Compose 沿用同一个 dispatcher），生产代码不受影响。这次只在这两个新类里处理。以后在便签类之后新增界面测试时，需要加同样的外层规则。UI-04A 和 1.7.0 中那些间歇失败的特征与此一致，但没有单独复现，不作为结论。

## 2026-11-08 · 1.7.1 接线（Claude，按 PLAN-1.7.1 “GPT 后端接口”）

按 GPT 写在任务单里的四个接口完成接线，入口在应用中正式启用。只改界面文件，没有改 GPT 的 VM、数据层和运行时。**依赖 GPT 尚未提交的 1.7.1 后端**（Room 7、`documentSessions` 等），本提交单独无法编译，需要与 GPT 那批一起提交或合并。

### 改动清单

| 文件 | 改动 |
| --- | --- |
| `QuickNoteAi.kt` | 新增 `DocumentAiSource`：用 `documentSessions` / `createDocumentSession` 列出和新建讨论，按 `QuestionOwner(id, contentHash, QUICK_NOTE)` 判断是否当前版本；发送只用 FOLLOW_UP，带 `documentSectionId`，不带选项和代码。不提供题目类快捷问法，不提供保存到便签（后端只支持题目回复）。回复下方的“引用：…”读 `run.documentReferences`，同一节相邻段落会合并显示。新增 `quickNoteAiEntry(vm)`：引用预览改用 `vm.quickNoteAiContext`，显示“预计引用：「节名」第 a～b 段”；`STALE_OWNER` 显示“文档内容已更新，请返回列表重新打开后再提问”，`NOT_FOUND` 显示“文档已不可用”。 |
| `QuickNoteReader.kt` | 打开讨论页后先异步取预览，取回后补进上下文卡片；讨论页本身照常可用。 |
| `AppNavigation.kt` | 速记页接上 `quickNoteAiEntry(vm)`，便签页接上 `stickyNoteSources(vm)`。 |
| `QuestionScreen.kt` | 题目讨论的 `noteSaver` 接到 `vm.saveAiReplyAsStickyNote`。 |
| `StickyNoteSourceUi.kt` | `stickyNoteSources(vm)`：把 `StickyNoteOriginView` / `StickyNoteOriginOpen` 映射为界面类型，只读取题库。 |
| 测试 | `FakeAiConversationFacade` 加上文档会话。`AiChatScreenTest` 增至 22 项（真实 `DocumentAiSource` 的发送参数与引用合并显示）。`QuickNoteReaderAiTest` 增至 5 项（预计引用文案，以及文档已更新时的提示）。新增 `QuickNoteAiWiringUiTest`，2 项，使用真实 ViewModel 和后端：经速记页打开文档 → AI 学习助手 → 后端给出的预计引用 → 新建文档讨论（owner 为 QUICK_NOTE）→ 无 Key 时发送失败且可重试 → 不提供保存到便签 → 两次返回后仍在同一文档；另验证便签来源三种状态的映射和只读打开原题，不产生作答记录。 |

### 测试结果（证据：`validation/UI-171-WIRING-20261108/`；输入为 `62ed880b` 加上当时工作区的 47 个文件，含 GPT 的后端，指纹见 `input-fingerprints.csv`）

- 定向：8 个类、57 项，一次全部通过（`01-targeted-57.log`）。包括 `PageChromeUiTest`、`QuickNotesUiTest`、`NotesAndScoringUiTest`、`QuestionAiEntryUiTest` 等受影响的已有测试。
- `assembleDebug lintDebug`：BUILD SUCCESSFUL。Lint 0 错误，7 条警告与 v1.7.0 相同。
- **全量（未闭环）**：418 项中 9 项失败（`02-full-418-9-failed.log`），全部是 AppNotIdle（60 秒超时）。失败的只有本次新增的 `QuickNoteReaderAiTest` 5 项和 `StickyNoteSourceUiTest` 4 项；夹在它们中间的 `QuickNotesUiTest`、`StartupRecoveryUiTest` 都通过。按同样顺序只跑尾部 11 个类时全部通过（`03-tail-rerun-pass.log`）。这说明问题取决于全量里更早运行的某个类，根因尚未定位。这两个类与其他界面测试的一个差别是：它们不经过 `QuizApp`，直接渲染页面组件。我没有为让测试通过而改测试或放宽超时。在定位之前，请 Leader 联合验收时注意这一点。全量那轮的原始 XML 已被尾部复跑覆盖，只保留了日志。
- 真机阅读位置、试装包：未做，留待联合验收。

## 2026-11-07 · 1.7.1 阅读器、AI 会话和便签界面（Claude，PLAN-1.7.1）

基线为 v1.7.0（`aca99d8e`）。只改界面文件，没有改共享 `QuizViewModel`、数据层、备份或 AI 运行时。GPT 尚未在任务单里补接口，因此界面先按 1.7.0 契约和四个可选挂点实现。挂点说明写在 `docs/PLAN-1.7.1.md` 的“Claude 侧接线点”一节。挂点没接上时，对应入口不显示，现有行为不变。

### 改动清单

| 文件 | 改动 |
| --- | --- |
| `AiChat.kt` | 新增会话来源接口 `AiChatSource`：题目由 `QuestionAiSource` 包装 1.7.0 门面，行为不变；文档来源由 GPT 提供。新增上下文卡片（当前文档和将引用的范围）、回复下方的“引用：…”、旧版本标签（“旧版题目”/“旧版文档”）、快捷问法由来源决定。新增“保存到便签”（仅对完成且完整的回复提供；连点和重试复用同一 commandId；保存成功后显示“已保存到便签「标题」”和“打开便签”；失败时说明原因并可重试）。 |
| `QuickNoteAi.kt`（新） | `QuickNoteAiEntry` 速记入口、上下文文案、`ContextualAiSource`，以及“阅读位置 → 当前章节”的换算。 |
| `QuickNoteReader.kt` / `QuickNotesScreen.kt` | 顶栏新增“AI 学习助手”（接上入口后才显示）。点按时先读取并保存阅读位置，再在阅读页上层打开讨论；WebView 保持加载，返回后仍在原文档和原位置。两个函数改为 `internal`，因为参数用到 internal 类型。 |
| `StickyNoteSourceUi.kt`（新） | 便签来源的显示与状态（当前 / 题目已更新 / 来源不可用），以及只读原题页：显示题干、选项与参考答案、解析、思路和参考代码；不作答、不判分、不写复习记录。 |
| `StickyNotesScreen.kt` | 有来源的便签在列表和编辑页都显示来源行和“回到原题”。题目已更新时给出提示；题目不存在时说明来源不可用，按钮不可点，正文照常可读。返回顺序：原题 → 便签（编辑中的便签保持打开）。 |
| `QuestionScreen.kt` | AI 讨论页接上“打开便签”：经题目页原有离页流程进入该便签，从便签返回后回到这道题。`noteSaver` 等 GPT 接口就绪后再接。 |
| 测试 | `AiChatScreenTest`（17 → 20 项）、新增 `QuickNoteReaderAiTest`（3 项）、新增 `StickyNoteSourceUiTest`（4 项）。 |

### 定向测试结果（证据：`validation/UI-171-20261107/`）

在干净 worktree（`aca99d8e` 加上表 10 个文件，指纹见 `input-fingerprints.csv`）中运行：

- 最终定向运行 6 个类共 42 项，0 失败（`05-targeted-final-42.log`，XML 在 `test-xml/`）：
  - 新增或修改：`AiChatScreenTest` 20、`QuickNoteReaderAiTest` 3、`StickyNoteSourceUiTest` 4。
  - 受影响的已有测试（未改动）：`QuestionAiEntryUiTest` 2、`QuickNotesUiTest` 5、`NotesAndScoringUiTest` 8。
- 过程记录，全部保留：
  1. 测试编译失败：已有 `showWith` 的尾随 lambda 改绑到了新增的 `noteSaver` 参数，已改为命名参数。
  2. 42 项中 3 项失败：便签卡片可点击，会合并子节点的语义，测试按 tag 找来源行找不到；改为在未合并树中查找，产品代码未改。
  3. 修复后上述类 4/4 通过。
- `assembleDebug lintDebug`：BUILD SUCCESSFUL。Lint 0 错误，7 条警告与 v1.7.0 基线相同，没有新增。
- 共享文件之外的全量回归按任务单在合并后统一运行，本次没有跑全量。

### 覆盖与未覆盖

- 已覆盖：
  - 文档会话的上下文卡片、旧版本只读、一键请求不复用旧版本、引用范围显示；
  - 阅读器打开 AI 页、WebView 仍在、返回到同一文档；
  - 保存便签：仅完成回复可存、连点只发一次、失败后同一命令重试、打开便签；
  - 便签来源三种状态和只读原题，以及从编辑页进入并返回。
- 未覆盖：
  - 换文档和内容版本变化的真实后端行为、长文截取、发送失败的文档路径：要等 GPT 的文档入口接上。
  - 便签备份与恢复、作答和复习记录口径：后端负责，合并后联合验收。
  - Robolectric 的 WebView 不执行脚本，因此“按阅读位置得出当前章节”只做了换算函数的单元断言；在真机上的表现未验证。

## 2026-11-07 · 题目页挂载新会话，迁移旧 AI 入口与费用（Claude）

### 来源与边界

- 用户指示：“从题目页的 AI 助手入口挂载新会话，接通发送、停止、重试、历史和费用。打开新会话会切换发送权，因此要同时迁移旧入口，尤其是编程题的 AI 代码审查，避免按钮失效。”
- 依据 GPT 的 `docs/AI_02B_CALLABLE_20261107.md`：`vm.aiConversations` 第一次被取用时，就把本数据库的发送权切换到新会话，此后旧的 ask/retry 不再发出请求。因此所有旧发送入口都在同一次改动里迁走，没有哪个按钮会停留在“点了没反应”的状态。
- 只改了界面文件：`AiChat.kt`、`QuestionScreen.kt`、`StatisticsScreen.kt`、`MoreScreen.kt` 及对应测试。没有改 QuizViewModel、Repository、AiStateHolder、AI 后端文件，也没有改 GPT 的测试。
- **依赖 GPT 未提交的 AI-02b 后端**（VM 上的 `aiConversations`/`aiCostsState`、运行时、费用存储等）。本提交单独无法编译，必须与那批文件一起，所以 GPT 那批需要先提交或一起提交。验证时用的 60 个未提交文件，指纹见 `validation/AI-MOUNT-20261107/input-fingerprints.csv`。

### 旧入口迁移对照

| 旧入口（AiStateHolder） | 现在 |
| --- | --- |
| 题目页“AI 学习助手”里的生成详细解析 / AI 建议答案 / 变式题快捷按钮 | 同一位置仍有这三个快捷按钮。点按后打开讨论页，在本题当前版本最近一个可编辑的讨论里发送（没有就新建），`input` 为空，用户消息显示请求类型名称。 |
| 追问输入框和发送 | 讨论页底部的草稿框和发送按钮。发送前先保存草稿，并按 `DraftToken` 和 `savedDraftRevision` 声明要消费的那一版；只有回执的 token 与当前编辑版本一致时才清空输入框，所以发送途中继续输入的文字会保留，并照常保存。 |
| 编程题“AI 代码审查”（原来 `vm.aiState.ask(..., CODE_REVIEW, code)`） | 按钮仍在代码编辑器下方，有代码时可点。点按后打开讨论页，以 CODE_REVIEW 发送当前编辑器里的代码，规则同快捷按钮。讨论页的快捷按钮中也有“AI 代码审查”（仅编程题）。只有代码审查携带 `code`，与旧行为一致。 |
| 取消 AI 请求 | “停止”，使用 `state.globalBusy` 里的完整 `RunOwner`，即使是在其他讨论发起的请求也能停。处于 `CLEANING` 时显示“正在停止…”，不提供按钮，也不能发送。 |
| 重试 AI 请求 | 回复下方的“重试”，只在后端给出 `RunView.canRetry` 时显示，用原来的 `RunOwner`。 |
| 旧历史（`ai.entries`） | 讨论列表下方的“早期记录（只读）”，通过 `legacy(AllVersions(questionId))` 分页读取。显示日期、模型、版本标记和“旧估算”金额，点开可看全文并复制；不会带入新讨论的上下文。 |
| 费用页 `learning.costs` | 读 `vm.aiCostsState`：按月（已知估算、网络请求次数、缓存命中、早期记录条数、用量或费率未知的次数）和按日 × 模型 × 币种列出。旧估算单独一行写“旧估算”。未知的不按 0 计入金额。 |
| 更多页“本月 $x”提示 | 读同一份 `aiCostsState` 的本月 USD 数据。全部未知时显示“本月费用未知”，部分未知时显示“本月 $x（另 n 次未知）”。 |

- 题目页的“AI 学习助手”区块本身不读取后端，展开它也不会切换发送权；只有打开讨论或点快捷按钮时才会切换。考试中依然不显示 AI。
- 讨论页占满题目页，返回顺序：讨论 → 讨论列表 → 题目。控制器使用 `vm.viewModelScope`，离开题目时在 ViewModel 作用域里完成“保存 → flush → deselect”。如果另一题已经选中了自己的讨论，不会去 deselect 别人的。
- 旧组件 `AiConversationSection` 已不再挂载，但代码保留：GPT 修改过的 `AiConversationUiTest` 仍直接渲染它。建议由 Architect 决定，连同这个测试一起删除。

### 验证中发现并修好的问题

1. **第一次点“AI 代码审查”会被拒绝**（真实缺陷）：这一下正是第一次取用门面，后端还在 INITIALIZING，于是返回 NOT_READY。现在所有动作都先等初始化完成，等待期间页面显示“AI 存储准备中…”。新增了对应测试。
2. **新的端到端测试拖垮了后续测试**（我的测试清理有误）：AI 运行时随 ViewModel 异步关闭，而测试清理时立刻删除数据库；Windows 上删不掉仍打开的文件，此后所有 Robolectric 界面类测试都因“界面不空闲”超时。现在像 `AiProductionIntegrationTest` 一样，先等 `vm.examResourcesClosed` 完成再删库，最多等 20 秒，超时就判失败，这也顺带验证了关闭确实能完成。
3. 一次全量运行中出现过 `ExamTerminationUiTest` 的单项失败：该类单独运行 12/12 通过，不带本次改动的基线全量也全部通过。它出现在第 2 点修复之前，但没有单独证实两者有关。

### 验证（证据：`validation/AI-MOUNT-20261107/README.md`，9 次运行全部保留）

- 新增或修改的测试：
  - `AiChatScreenTest` 共 17 项，比上次多 7 项：发送并消费草稿、停止和重试使用后端给的 owner、发送途中继续输入的文字保留、快捷按钮选择最近可编辑讨论、代码审查携带代码、没有可编辑讨论时新建、其他讨论占用发送槽、早期记录只读可复制、初始化期间首次点按会等待。
  - `QuestionAiEntryUiTest` 共 2 项，使用真实 ViewModel 和运行时，没有网络也没有 Key：编程题“AI 代码审查”被受理，最后显示可重试的 `CREDENTIALS_UNAVAILABLE` 失败；从助手入口打开讨论并发送追问，被受理后草稿被消费。
  - `MoreUiStateTest` 共 5 项：本月金额只算 USD；未知请求不显示为 0。
- 最后一次全量（构建、测试、lint）：BUILD SUCCESSFUL，49 类、398 项，0 失败 / 0 错误 / 0 跳过。Lint 0 错误；新增的 2 个 `UsableSpace` 警告位于 GPT 的 `AiStorageResources.kt`。
- **未闭环**：修复后的第一次全量出现 7 项 AppNotIdle，都发生在 `setContent`。其中包括未改动的旧 `AiConversationUiTest` 4 项，特征与 UI-04A 那次相同；另有 `AiChatScreenTest` 3 项，分散出现。下一次全量没有复现，根因未证实，交 Architect 裁定，不宣称与本次无关。
- 没有生成 APK：输入含 GPT 未提交的 Room 6 / 备份 4 升级，装上就不能退回 Room 5，试装包需要另行确认后再准备。

## 2026-11-07 · AI 会话界面（按冻结契约实现，尚未挂载，Claude）

### 来源与边界

- 用户指示：“按现有契约实现会话列表、标题、聊天消息、输入草稿、复制及定位最新回复；后端就绪后接入”。
- Architect 原先的顺序是 AI-02b 后端回归通过后，Claude 再接会话 UI（`AI_02A2C_DISPATCH_20261106.md` §末）。本次按用户指示提前开工，但做法保持在边界内：
  - 只新增文件，**没有挂进任何页面或导航**，也没有接 ViewModel。
  - 不碰 GPT 正在修改的文件（QuizViewModel、Repository、AiStateHolder、QuestionScreen、AI 后端文件及其测试）。
  - 不自造临时状态代替契约：选择、首屏消息、run 状态、用量、busy、故障都直接读 `facade.state`。控制器只保存契约留给编辑器的值：正在输入的文字、`localEditVersion`、最近一次确认的 `draftRevision`、用户主动加载的更早消息，以及最近一次拒绝的提示。
- 依赖 GPT 的 `AiConversationContract.kt`。它目前仍是 GPT 工作区里的未提交文件，本次验证使用的版本 SHA256 为 `7C69FA0D088710187FF5C988B456C7FB9AF724E1CAFD9904B4232A81153594BD`。本提交不包含该文件，必须与它一起才能编译。

### 新增文件

| 文件 | 内容 |
| --- | --- |
| `AiChat.kt` | `AiChatController`（界面状态与命令）、`AiChatScreen`（会话列表 / 打开的会话）、文案映射函数 |
| `src/test/.../FakeAiConversationFacade.kt` | 内存版假门面，只按契约实现 UI 依赖的规则（revision、editorEpoch、过期拒绝、游标分页、只读），不发送 |
| `src/test/.../AiChatScreenTest.kt` | 界面测试，见“验证” |

### 功能与契约对应

- **会话列表**：调用 `sessions(questionId, includeArchived = true, cursor, 20)`，带“加载更多”，遇到 `STALE_CURSOR` 从头重载。每行显示标题，以及“只读”“旧版题目”“草稿：首行”这些标记。“新建讨论”调用 `createSession` 后立刻 `open`；`phase` 不是 READY 时不能新建。
- **标题**：点“重命名讨论”在标题下方就地编辑（不用弹窗，键盘留在页面里）。标题去掉首尾空白后需为 1～80 个 Unicode 码点，超出时“保存”不可点。调用时带当前 `sessionRevision`；`STALE_SESSION` 等拒绝会显示提示，不改本地标题。
- **聊天消息**：首屏取 `facade.state.firstMessages`，更早的消息用 `messages(sessionId, cursor)` 分页加载。两者按 `messageId` 合并（实时页优先）、按 `seq` 升序显示，最早的在上面。
  - 用户消息显示在右侧气泡里；AI 回复用 Markdown 渲染。
  - run 状态：准备中 / 正在生成 / 已取消 / 失败（errorCode）。`PARTIAL` 时提示“回复不完整”。
  - 用量如实显示：已知时显示 tokens 和“估算”金额（6 位小数）；不知道就写“用量未知 · 费用未知”，用量不全时注明“用量不完整”，缓存命中写“本次不计费”。不会把估算显示成账单。
- **输入草稿**：
  - 每次 open 用回执里的 `editorEpoch`，`localEditVersion` 从 0 开始，每次输入加 1。
  - 停止输入 300ms 后保存；一直在打字时最多每 2 秒保存一次。保存串行进行，每次用新的 `commandId`，带上 `expectedDraftRevision`。
  - 状态只在确认的版本等于当前版本时才显示“已保存”。迟到的确认、属于别的 editor 或会话的确认，都不会把较新的文字标成已保存，也不会清掉它。保存被拒绝时显示“草稿未保存：原因”，文字保留。
  - 离开（顶部返回或系统返回）的顺序是：先保存当前文字，再 `flushDraft`（仅当本次编辑保存过），然后 `deselect`。任何一步失败都留在当前会话，文字不丢。
  - 只读会话显示原草稿、可以复制，但不能编辑，也不发保存。
- **复制**：每条消息都有“复制”按钮，无障碍标签为“复制提问”或“复制回复”。复制的是存储的原文（保留 Markdown），之后显示“已复制”2 秒。
- **定位最新回复**：
  - 打开会话时自动停在最新一条消息。
  - 用户在底部时，新消息到达会自动跟随；用户往上翻过以后，不会被强行拉回底部。
  - 最新一条 AI 回复不在屏幕内时，右下角显示“最新回复”按钮，点击滚到这条回复的开头。按钮按稳定的 `messageId` 定位。
- 状态提示：`phase` 不是 READY、`storageFault`、`globalBusy`（本会话正在生成，或其他讨论占用中）都有文字说明。`CAPACITY_EXCEEDED` 的文案写明“导出备份不会腾出空间”（契约 §3.3）。

### 请 GPT / Architect 确认的假设

1. **首屏方向**：假设 `firstMessages` 是最新的一页，它的 `next` 指向更早的消息。契约没有写明方向。如果反过来，只需改 `AiChatController.messages/hasOlder/loadOlder` 三处。
2. **草稿合并**：界面自己按 300ms / 2s 去抖，只对去抖后的版本调用 `saveDraft`，每次一个新的 `commandId`。如果后端协调器还会再合并，需要说明被合并的旧命令收到什么回执。
3. **列表范围**：`includeArchived = true`，这样旧版题目的只读讨论也会出现在列表里。
4. **协程作用域**：控制器使用调用方传入的 scope。接入时宿主应提供比页面活得更久的 scope（例如 ViewModel），并在离页时调用 `leaveNow()`。否则页面被销毁时，最后 300ms 内的输入可能来不及保存。

### 未做（后续接入时处理）

- 发送、重试、取消、旧历史 `legacy()`、费用页改读新门面：不在本次要求内，契约上属于 AI-02b/AI-03。
- 挂载位置（题目页入口或独立页面）、返回策略与 `PagePolicy` 的关系、`imePadding`：等真实门面就绪后，与 Architect 确认后再接。
- 真机观感未验。

### 验证（证据：`validation/AI-UI-CHAT-20261107/`）

- 输入：干净 worktree，HEAD `10f4e6d7` 加 4 个文件（`AiChat.kt`、两个测试文件、GPT 的 `AiConversationContract.kt`），指纹见 `input-fingerprints.csv`。不含 GPT 其他未提交的文件。
- 新增 `AiChatScreenTest` 10 项（360×640dp），覆盖：
  - 列表标记与新建会话；
  - 1～80 码点标题和过期拒绝；
  - 消息顺序、状态与用量文案；
  - 300ms / 2s 保存节奏与离页先保存、再 flush、再 deselect；
  - 迟到确认不把新文字标成已保存，保存失败时留在当前会话；
  - 复制内容与“已复制”提示；
  - “最新回复”按钮，以及上翻后不会被强行拉回；
  - 游标分页合并；
  - 只读会话；
  - 未就绪状态。
- 第一次定向运行：10 项中 3 项失败，日志保留在 `01-targeted-first-run-3-failed.log`。
  - 一个真实缺陷：打开长会话时可能停在顶部，因为“跟随”标志可能在初始滚动前，按首次布局被算成 false。已改为打开时必定先落到最新一条，之后只按用户滚动结束时的位置决定是否跟随。
  - Robolectric 中在弹窗里输入文字时一直不空闲，已把重命名改为就地编辑。
  - 测试的打字节奏正好踩在 2 秒边界上，已调整时间，留出约 70ms 余量；没有放宽断言。
- 修复后定向：10/10 通过，确认测试实际执行，没有被 Gradle 判为 UP-TO-DATE。
- 集成：`assembleDebug testDebugUnitTest lintDebug --rerun` 结果 BUILD SUCCESSFUL。测试 40 类、293 项，0 失败 / 0 错误 / 0 跳过，即上一轮的 283 项加本次 10 项。Lint 0 错误，5 个原有警告，没有新增。
- 未生成 APK：没有挂载，试装包里看不到这些改动。

## 2026-11-06 · UI-01 方向更正：吉祥物（用户本意，Claude）

### 用户本意（lead / Architect 请以此为准）

- 用户原话：“我的指令是把软件图标换成吉祥物”；“我这阶段目标就是尽可能多的引入吉祥物到应用中”。
- `docs/ARCHITECT_UI_AI_PLAN_20261106.md` §4 的 UI-01 写成了“应用图标替代吉祥物：将更多页的像素吉祥物换成当前正式软件图标”，**方向与用户本意正好相反**。`docs/UI_0123_ARCHITECT_REVIEW_20261106.md` 也按这个反方向验收。Claude 当时照任务单执行，在 `d5d95501` 里删掉了吉祥物。现已按用户本意改正（见下）。
- 今后派发 UI 任务时请遵守：
  1. **吉祥物是本阶段的主角。** 用户希望吉祥物尽可能多地出现在应用里。能放吉祥物的地方优先放吉祥物，不要用应用图标、动漫头像或通用图标去替换它。
  2. **不要删除或弱化吉祥物**（`Mascot.kt` / `MascotSprite`、`Theme.kt` 中的 `MascotColors`、动画与点击反应、桌面图标）。要改吉祥物本身，先问用户。
  3. 还要在哪些地方引入吉祥物、怎么设计，由 lead 规划，经用户确认后派发。本次 Claude 只做了用户明确要求的两处，**没有设计新的吉祥物位置**。
  4. 派发涉及吉祥物或图标的任务时，在任务单里直接引用上面的用户原话，避免再次理解反。
- 吉祥物现在出现的地方：更多页头像（会动，可点）、桌面启动图标（静态，含主题图标剪影）。其他地方都还没有；例如复习提醒通知的小图标仍是旧的“E”字矢量图 `drawable/ic_launcher.xml`，本次没动。

### 改动（提交 `b4ecd75e`，未推送）

- **更多页恢复吉祥物**：从 `0ecc6366` 原样恢复 `Mascot.kt`、`MascotTest.kt` 和 `Theme.kt` 的 `MascotColors`。`MoreScreen` 头像槽改回 `Mascot(Modifier.size(56.dp))`，删除 `AppIconAvatar`。`PracticeEntriesUiTest` 恢复原来的吉祥物断言（可点击、点后仍停留在更多页）。
- **桌面图标改为吉祥物**：
  - `mipmap-anydpi/ic_launcher.xml` 的前景改为 `drawable/ic_launcher_mascot.xml`，即吉祥物静止正面姿态的像素矢量图，使用浅色配色，背景仍是薄荷绿 `launcher_background`。
  - 新增 `monochrome` 层 `ic_launcher_mascot_mono.xml`，是吉祥物剪影，眼睛和嘴镂空，供 Android 13+ 主题图标使用。
  - 两个矢量图由 `tools/mascot_icon.py` 从 `Mascot.kt` 的 `MascotSprite` 生成，请勿手改。生成时自动取最大格子尺寸（2.45），保证整只吉祥物落在自适应图标 66dp 的安全圆内，圆形和圆角蒙版都不会裁到。
  - 新增 `MascotLauncherIconTest`（4 项）：断言矢量图与 `MascotSprite.cells(MascotPose())` 及浅色配色逐格一致、剪影正确、全部格子在安全圆内、启动图标引用正确。
- 动漫头像 PNG `drawable-nodpi/ic_launcher_anime.png` 不再被引用，已移出 `res`；原图仍在 `assets/branding/`，README 已注明“未经用户同意不要换回”。
- UI-02 导航、UI-03 Lucide 图标都没有改动。没有触及 QuizViewModel、数据层或 AI 文件。

### 验证（证据：`validation/UI-01R-20261106/`）

- 在只含 `b4ecd75e` 的干净 worktree（不含 GPT 未提交文件）中执行 `tools\build.ps1 assembleDebug testDebugUnitTest lintDebug`，结果 BUILD SUCCESSFUL。
  - 测试：39 个类，283 项，0 失败、0 错误、0 跳过。比上一轮多出的 8 项是 `MascotTest`（4 项）和 `MascotLauncherIconTest`（4 项）；`PracticeEntriesUiTest` 5/5 通过。
  - Lint：0 错误，5 个警告。原有的 `IconLauncherShape` 和 `MonochromeLauncherIcon` 警告因换图标而消失。新增 2 个 `VectorPath` 警告（像素图路径较长），只影响启动图标的绘制速度，影响可以忽略。其余 3 个是原有警告：GradleDependency ×2、`CommonComponents.kt:35`。
- `launcher-icon-preview.png` 是按矢量数据在圆形、圆角蒙版下的离线渲染，以及主题剪影。它不能代替真机截图。
- 试装包：`EmbeddedQuiz-1.6.1-ui-mascot-20261106-debug.apk`，放在项目上一级目录，SHA256 `0902931645EAAD68DB3BD982B0BE4FC5D22C1E3167229607A1A4AB1ED4525F92`。由 `b4ecd75e` 构建，沿用 debug.keystore；未改版本号和签名，未覆盖已有 APK，未发布。

### 未做

- 设备观感未验：更多页吉祥物、桌面图标在各蒙版下的效果、主题图标，见 `validation/UI-04B-20261106/DEVICE_CHECKLIST.md` 第 13～15 项。

## 2026-11-06 · UI-04A/B 补交（Claude，UI_0123_ARCHITECT_REVIEW_20261106）

没有修改生产代码、AI 文件、测试或测试 helper；GPT 未提交的文件未触碰。

**UI-04A**（证据：`validation/UI-04A-20261106/`）
- 措辞已更正：本节下方“UI-01～03 · 测试”和 `validation/UI-0123-20261106/README.md` 现在写为“早期全量出现 4 项 AI 不空闲失败；最终集成通过，根因未证实”。原日志保留，未覆盖。
- 原失败 XML 和类顺序已经丢失：之后同一构建目录的定向测试重写了 `test-results`，只剩控制台日志。守护进程日志显示，run 01 期间没有其他构建写入同一项目目录。
- 固定输入复验：提交 `59755191` 加 3 个测试还原为 run 01 当时的版本，在全新 worktree 中执行，137 个文件有指纹。
  - R1 全量：277 项中 3 项失败，正是预期的旧导航路径测试，证明输入还原正确；`AiConversationUiTest` 4/4 通过，排第 2 位（Accent 之后）。
  - 最小前缀 Accent → AI：3 次有效运行（R2a，以及加 `--rerun` 的 R2b2、R2c2）均为 6/6。另有两次 Gradle 判为 UP-TO-DATE、没有执行测试，已标注为无效，不计入结果。
- 结论：没有复现，没有最小复现，根因未定位；不建议为此修改代码或 helper，交 Architect 裁定。

**UI-04B**：本机仍然没有设备和模拟器，保持**待验**。已准备好可以直接照做并记录的清单：`validation/UI-04B-20261106/DEVICE_CHECKLIST.md`（17 项，含设备信息栏和截图栏），需要在现有手机上安装试装包后填写。

## 2026-11-06 · UI-01～03 导航遮挡、应用图标头像、Lucide 图标（Claude，ARCHITECT_UI_AI_PLAN_20261106 §4）

基线 `0ecc6366`（ui-visual-refresh）。两次提交，均未推送：A `59755191`（UI-02），B `d5d95501`（UI-01 + UI-03）。不含 AI 后端改动。没有改 QuizViewModel、Data、Repository、Room、判分、复习排程、考试、启动恢复或 QuestionScreen 的返回语义。证据见 `validation/UI-0123-20261106/`。

### A · UI-02 统一返回、底栏与遮挡

- 新增 `PageChrome.kt`：
  - `PagePolicy` 集中定义一级页/子页、`showsTabBar`（先看速记 `visible`/`reader`，再看 `vm.page`）、顶栏标题与 `parent` 返回目标。
  - `PageTrail` 只在 UI 层记住“费用”是从更多还是设置打开的，用 `rememberSaveable` 保存，不进 ViewModel。
  - `SubPageTopBar` 的返回箭头调用 `OnBackPressedDispatcher`，与系统返回走同一条路径。这样已打开的便签、核对编辑器会先关闭，不会出现顶栏和系统返回各走一套目标的情况。
- `AppNavigation.kt`：全局 `BackHandler` 改用 `PagePolicy.parent`；底栏按策略显示。隐藏时不输出任何节点，Scaffold 只保留系统栏 inset，不叠加底栏高度；`imePadding` 仍在 `consumeWindowInsets` 之后，不会重复计入。子页在内容上方绘制顶栏。
- 行为变化：
  - 智能复习返回到“更多”（原来回首页，通知进入也一样）。
  - 错误记录返回到“设置”。
  - 费用返回到打开它的页面。
  - 便签：先关编辑器 → 回列表 → 回来源（`leaveStickyNotes` 未改，从题目进入仍回原题、保留队列和草稿）。
  - 待核对编辑器先回列表（新增内层 `BackHandler`）。
  - 速记列表保留底栏，阅读器隐藏底栏。`QuickNoteReaderHandle.finish`、`notes.leave`、列表的退出出口均未改。
- 子页去掉与顶栏重复的页内标题；便签页去掉多余的“返回”按钮，“继续答题”保留。复习列表和错误记录补了底部留白。
- “更多”的滚动位置靠现有的 `SaveableStateProvider` 保留，已用测试证实。

### B · UI-01 应用图标头像（**已作废**：方向与用户本意相反，已由本文顶部“UI-01 方向更正”取代）

- `MoreScreen.AppIconAvatar`：使用 `drawable-nodpi/ic_launcher_anime` 加 `launcher_background` 底色，按自适应图标的可见区放大 108/72，裁圆角，56dp，不着色，不可点击，语义“应用图标”。没有使用旧的 `drawable/ic_launcher` 矢量图，也没有改启动图标。
- 吉祥物不再有其他引用，因此删除 `Mascot.kt`、`MascotTest.kt` 以及 `Theme.kt` 中只供吉祥物使用的颜色；更多页不再运行动画。

### B · UI-03 Lucide 图标

- `AppIcons.kt` 由 `tools/lucide_icons.py` 从官方 `lucide-static@1.69.0` SVG 生成：circle、rect、line 都转换为路径，arc flag 补上分隔符；统一 24 视口、2 描边、圆端点/圆连接；填充元素（tag 的圆点）和实心变体保留填充。
- 保留原有 `AppIcons` 名称，只把 `TagThin` 换成 `NotebookPen`（便签行）。映射与来源见 `docs/third_party/LUCIDE.md`；ISC/MIT 许可在 `docs/third_party/` 和 APK 的 `assets/licenses/` 中各有一份。
- 排查了所有纯图标按钮：都有语义标签，点击区 ≥48dp，无需修改。

### 测试（真实数量）

- 新增 `PageChromeUiTest`（9 项，360×640dp）。在边界之外，还断言了点击结果：
  - 策略矩阵；
  - 更多 → 复习，系统返回和顶栏返回都回到更多，滚动位置不变；
  - 5 个更多子页都隐藏底栏，并能返回；
  - 设置 → 费用/错误记录返回设置；
  - 便签逐层返回，从题目进入时回到原题；
  - 待核对最后一张卡片完整可见并可点，编辑器先关闭；
  - 统计和复习的末项在可视区内，复习末项点击后进入答题；
  - 速记在统计页之上仍显示底栏，阅读器隐藏底栏，目录最后一项可点击，返回后回到列表、再返回到首页；
  - 空速记列表末项位于底栏之上。
- 新增 `AppIconsTest`（2 项）。更新了 `PracticeEntriesUiTest`、`NotificationEntryUiTest`、`NotesAndScoringUiTest`、`ExamSaveUiTest`、`StartupRecoveryUiTest`：这些测试原先在子页上点底栏，现在先经顶栏返回，原有断言全部保留。
- 集成：干净 worktree 只含 d5d95501，执行 `assembleDebug testDebugUnitTest lintDebug`，结果 BUILD SUCCESSFUL；37 个类 275 项，0 失败、0 错误、0 跳过；lint 0 错误、5 警告（都不在本轮改动中）。
- A 提交前的一次全量运行中，`AiConversationUiTest` 有 4 项不空闲失败（`AppNotIdleException`）。最终集成全量通过，**根因未证实**。（更正：原先“判断为依赖执行顺序的不稳定测试”的结论没有证据支持，已撤回；处理见下方 UI-04A。）
- APK：`../EmbeddedQuiz-1.6.1-ui-20261106-debug.apk`，SHA256 `329277787390A115BAB20C18EAD73C2C41553FDBE2AA13A0C6483B907D1F6A95`。

### 限制与开放项

- **未做设备验收**：本机没有设备和模拟器。WebView 文末、手势/三键导航、放大字号、键盘、浅/深色截图与录屏都需要在真机上补做。
- Robolectric 下系统 inset 为 0，只能证明“底栏/父容器边界内完整可见且可点击”，不能证明真机系统栏的避让。
- 同一工作区里有其他执行者未提交的 AI 文件（`AiConversation*.kt` 等），本轮没有触碰，也没有提交。集成验证在不含这些文件的独立 worktree 中进行。

## 2026-11-04 · VC-01 AI 请求按题目版本归属与费用文字对比（Claude，REPAIR_DISPATCH §21）

```text
任务 ID：VC-01
执行者及职责：Claude；本次按 §21 授权含 AiStateHolder 小范围后端例外
起始 HEAD：6becc3f（加当时未提交的视觉/对话工作区）
交付 commit：2ff0141 视觉规范与八色主题；21e4268 VC-01 补修（另有本交接与证据的归档提交）
修改文件与对应函数：
  AiStateHolder.kt：新增 AiRequestUiState、request、requestFor(q)、retry(requestId)、cancel(requestId)；ask 受理时固定快照；
    失败/成功/缓存命中在 finally 与 busy 同时落定；局部变量更名避免遮蔽。原 busy/error/ask/retry()/cancel() 行为不变。
  QuestionScreen.kt：AI 区抽为 AiConversationSection（同文件），只按 requestFor(q) 显示待回复气泡、分析中、错误、重试、取消；
    其他题目在途时禁发送并显示“AI 正在处理其他题目，完成后可在本题提问。”；删除本地 remember(aiPending)；
    费用小字改 aiCostTextColor = onSurfaceVariant（原 outline）。代码审查按钮仍走 vm.aiState.ask。
  测试：EnhancedAiTest 新增快照归属与旧 requestId 用例；新增 AiConversationUiTest 4 项；LearningDatesAndReadingTest 补费用对比。
  API_CONTRACT.md：顶部追加 VC-01 节。
接口/行为变化：见 API_CONTRACT 顶部 VC-01 节。DAO/schema、缓存键、4 轮上下文、费用计算、网络协议、凭据、评分/考试/保存均未改。
是否超出白名单：否（QuizViewModel 未改；QuestionScreen 新增 internal 组件仅在 AI 区内）
修复前失败场景与证据：审查报告的 A→B 路径；定向第 1 轮发现快照与 busy 分两步落定的短暂不一致，已修（validation/VC-01-20261104/focused-run1-failed）
修复后测试命令、数量、结果、报告位置：
  定向 4 类 18/18；完整 21e4268：assembleDebug 成功，lint 0 错误 5 警告，测试 256 中 4 失败（CodeDraftSaveUiTest，已知、未解决）。
  证据 validation/VC-01-20261104/README.md
故障注入/生命周期/并发验证：MockWebServer 门控慢请求、500、NO_RESPONSE 取消、缓存命中、contentHash 改变、离开返回
尚未验证的内容：完整套件的 4 个已知失败根因（已证实由新 AiConversationUiTest 跨类干扰引起，去掉该测试全套 252/252；按用户指示停止排查）；真机与视觉验收
数据兼容与回退方法：无存储格式变化；回退 21e4268 即恢复原全局 busy/error 展示
需要另一执行者接入的事项：Architect 裁定已知失败的处理方式（README“可选处理”）
状态：待审查
```

## 2026-11-04 · T4 前端补修：缺题页与加载页的草稿离页等待（Claude）

起始 HEAD `bdf72bd`（GPT T4 集成证据）。处理 [T4 集成证据](../validation/T4-integrated-20261104/README.md) 中“普通缺题页绕过草稿等待”。

- **原因**：`DraftLeave` 只挂在已加载题目的 `QuestionContent`。加载中和缺题分支没有草稿协调器：系统返回由外壳直接回首页，缺题页顶部返回走 ExamLeave（非考试时直接放行）。草稿按队列题号受理，与题目能否加载无关。
- **修复**（只改 `QuestionScreen.kt`）：新增 `rememberPracticeDraftLeave(vm, id)`，非考试时按当前队列题号建立草稿离页协调器（系统返回 + 失败对话框）；加载中分支和缺题页都使用它。缺题页顶部返回在练习中走草稿等待、等待时禁用，考试中仍走 ExamLeave；缺题页显示该题真实草稿状态与重试。通知接管核查沿用 page/practiceSession/index。后端、契约未改。
- **测试**：`CodeDraftSaveUiTest` 新增 3 项缺题用例（系统返回等待在途草稿；失败时顶部/系统返回留页、显式重试后离开；等待时通知接管）。GPT 的诊断类原样在修复后运行 2/2 通过（db90bcc 上为 2/2 失败）。定向 13 个类 **98/98 通过**。证据：`validation/T4-frontend-missing-20261104/`。
- **说明**：我自己的 HEAD 对照在 HEAD 上 3/3 失败，但失败在缺题页新增状态行的夹具断言，早于离页断言，不当作导航缺陷的复现；导航缺陷复现以 GPT 诊断为准。加载中分支与缺题页共用同一协调器，但没有可控接缝把加载状态停住，没有单独正式用例，只经代码审查。
- 未运行完整套件（由 GPT 统一复测）；无真机验证。状态：待审查。

## 2026-11-04 · T4 前端第二部分：草稿保存状态、重试、切题/离页先保存与排他边界（Claude，REPAIR_DISPATCH §18.4）

起始 HEAD `42be3e4`（GPT T4 后端 38cb7c3；前端第一部分 ac387ac 的编辑器改动已在其中）。接口按 API_CONTRACT 顶部 T4 节，界面不再使用 `codeDrafts.writer`。后端、DAO、契约、派发文档未改；QuestionDraft.kt 未改；考试仍只走 ExamLeave。Architect 的未跟踪文件未暂存。

### 修改的文件与函数

| 文件 | 改动 |
| --- | --- |
| `QuestionScreen.kt` | 新增 `DraftSaveStatus`：Saving“草稿保存中…”、Saved“草稿已保存到本机”、Error 显示后端 message（liveRegion Assertive）和“重试保存”（调用 `retryCodeDrafts(id)`）、Inactive 不显示。编程题的状态放在代码编辑器**上方**，输入时可见。新增 `DraftLeave`/`rememberDraftLeave`/`DraftLeaveDialog`：普通练习的上一题/下一题、“完成，回到首页”、顶部返回、系统返回（本页 `BackHandler` 优先于外壳）和“打开全局标签”先 `flushCodeDrafts(id)`；Inactive/Saved 直接通过；true 才执行原动作，false 留页并弹“草稿保存失败，暂未离开”（重试保存 → 成功后继续 / 留在本页）；发起时记录 page/practiceSession/index，返回后有任一变化（通知等外部导航接管）即丢弃成功导航和失败弹窗。等待期间代码与笔记输入、翻题冻结。编辑器 `onChange` 与自动保存笔记改调 `updateCodeDraft`。`codeDraftEditingBlocked` 时代码编辑器只读、笔记只读、“保存笔记/保存代码与笔记”禁用，并显示“正在备份、恢复或重置学习数据，代码和笔记暂时不能修改。”普通客观题手动笔记：本地文字与已受理文字不同时显示“笔记有未保存的修改，点“保存笔记”后才会写入”，一致时才显示草稿状态，不把未点保存的编辑称为已保存。 |
| `MainActivity.kt` | `onStop` 以 `CoroutineStart.UNDISPATCHED` 发起一次 `flushCodeDrafts()`，不等待结果、不阻塞生命周期。契约中 flush 在首次挂起前强制提交合并窗口，因此转后台时最后文字立即进入写入，而不是最多再等 2 秒；不承诺立即强杀时仍保存。（与 T2 不同：T2 的 flush 只等待、不写入，故当时未加。） |
| `CodeDraftSaveUiTest.kt` | 在第一部分 3 项基础上新增 9 项真实 ViewModel 用例（规则改为 Activity 宿主以测系统返回），见证据 README。 |

### 设计说明（供 Architect 审查）

- **只有普通练习走草稿离页等待**；考试题没有代码/笔记编辑区（笔记区在考试中隐藏），仍只用 ExamLeave，二者不会同时出现在一题上。
- **离页失败只给“重试保存 / 留在本页”**，与 T2 一致；不会把用户永久卡住：外部导航（通知）不受拦截，失败状态回到题目仍可见、可重试。
- **AppNavigation 未改**：答题时底部导航隐藏，外壳返回被本页处理器覆盖，离开入口都在答题页内；缺题页没有草稿编辑区。
- 读取 `codeDrafts.drafts` 仅用于判断手动笔记是否已受理（VM 的 questionDraft 也用它），不经过 writer、不写入。

### 测试结果（证据：`validation/T4-frontend-20261104/`）

- 定向 13 个类 **95/95 通过**（独立副本自有 build 目录，`--rerun` 真实执行），含 GPT 的 CodeDraftPersistenceTest 11、CodeDraftBoundaryTest 12 及考试/通知/启动/便签/速记 UI 回归。
- 首轮 12 项中 5 项失败，均为测试问题（高列表项只滚入一部分导致点击落空；滚动辅助误用于“不存在”断言）；其中状态在编辑器下方不易看到也是真实的小屏问题，已把状态移到编辑器上方。未削弱断言、未加等待。
- 证据 README 附 7 步真机检查步骤（长代码输入延迟、输入法、切题/离页、转后台、备份期间、手动笔记、大字号/TalkBack）。
- 未运行完整套件（由 GPT 统一集成运行一次）；无真机验证。
- 状态：T4 前端待审查。

## 2026-11-04 · T4 前端第一部分：编辑器只在文字变化时通知（Claude，REPAIR_DISPATCH §18.4）

起始 HEAD `77c06bd`。**§18.2 草稿保存门面尚未实现**（`CodeDraftSaveState`、`codeDraftSaveState`、`codeDraftEditingBlocked`、`updateCodeDraft`、`retryCodeDrafts`、`flushCodeDrafts` 在代码中不存在），GPT 本地也没有可对齐的实现。前端不能改 QuizViewModel、不直接访问 writer，也不自建同名类型（会与 GPT 的定义冲突），因此本提交只完成不依赖该接口的部分。

- `CodeEditor.kt`：`onValueChange` 只在文字实际变化时调用 `onChange`；光标、选区和输入法组合态留在本地 `TextFieldValue`，不再产生草稿更新。新增 `enabled`（默认 true）：false 时只读、可选可复制、Tab 缩进禁用，供 `codeDraftEditingBlocked` 接入。QuestionScreen 和 PersonalEditors 的现有调用不变。
- `CodeDraftSaveUiTest.kt`（新增，3 项）：编辑器本身的改动报告、光标保持、Tab 缩进与禁用态。旧编辑器上第一项失败（三次纯光标移动被报告为三次改动），交付版本 3/3。
- 定向回归 7 个类 **45/45 通过**（证据：`validation/T4-frontend-editor-20261104/`）。输入法组合无法在 Robolectric 注入，需真机验证。

**待 GPT 实现 §18.2 后继续（同一测试文件扩充）**：QuestionScreen 草稿保存中/已保存/失败显示与 `retryCodeDrafts`；普通练习上一/下一题、返回/系统返回、便签入口、缺题页的“先 flush、成功才离开、失败留页（重试保存/留在本页）”协调器，沿用 T2 的来源页面/会话核查以防通知接管后迟到导航；`codeDraftEditingBlocked` 时禁用代码与笔记输入；普通客观题笔记保存状态不把未点保存的编辑称为已保存；必要时 MainActivity.onStop 的非阻塞尽力 flush。考试模式继续只走 ExamLeave。

状态：第一部分待审查；T4 前端整体未完成。

## 2026-11-04 · T3 前端：启动错误页、诊断/导出、重试与备份恢复确认（Claude，REPAIR_DISPATCH §16.3）

起始 HEAD `7a37243`（GPT T3 后端 c21b86f、25b3142）。接口按 API_CONTRACT 顶部 T3 节与 §16.2。后端、迁移、SQL、恢复协议、契约、派发文档未改；MainActivity 未改（文件选择用 Compose 的 `rememberLauncherForActivityResult`）；T4 未开始。Architect 的未跟踪文件未暂存。

### 修改的文件与函数

| 文件 | 改动 |
| --- | --- |
| `StartupScreen.kt`（新增） | `StartupLoading`：标题、阶段说明、确定进度条。`StartupErrorScreen`（按 attempt 重建，旧次结果不带到新次）：“应用未能启动”（heading）、后端 message（liveRegion）、错误代码/阶段/第几次启动；“重试启动”调用 `retryInitialization()`；“查看诊断”显示 `readStartupDiagnostics()` 的原文（仅缩进），读取失败有提示；“诊断包”说明包含题库、学习记录与考试缓存、不是学习备份、不会上传，用户选择 ZIP 目的地后才调用导出，取消不调用；成功/失败（含“目标文件可能不完整”）均显示。`canRestoreBackup` 时“选择学习备份”→ 确认对话框（说明替换范围、考试会被拒绝、诊断包不能恢复）→ 确认后才 `restoreStartupBackup(uri, true)`；Rejected/Failed 原样显示并留在页面；Completed 后跟随新启动状态，不显示“启动成功”。不可恢复时说明“数据库尚不可安全使用，不能恢复备份。请先导出诊断包保留原库，再重试启动”，仍可导出和重试。处理中显示“正在处理，请稍候…”，点击即置本地忙标志并结合 `startupRecoveryBusy` 禁用全部操作。整页可滚动。 |
| `AppNavigation.kt` | 内容区按 `startupState`：Loading → StartupLoading；Error → StartupErrorScreen（不依赖 ready）；Ready → 原应用内容。未 Ready 时不显示底部导航、不启用系统返回处理；启动失败的通用 notice 不弹 Snackbar（它指向尚无法打开的页面），Ready 后照常。考试超时提交原本就以 `vm.ready` 门控。日期刷新循环保持原样不门控（见下）。G1 通知优先级、考试恢复不变。 |
| `StartupRecoveryUiTest.kt`（新增） | 8 项，见证据 README。 |

### 需要注意 / 开放项

- **日期刷新循环未做 Ready 门控。** 首版把它改为仅 Ready 后运行，结果同一进程中后续 Activity 测试全部 `AppNotIdleException`。二分对照确认这是唯一致因：恢复原写法后全部通过。`refreshDate` 只写内存日期值，Ready 前无人收集，不访问数据库，所以保留原写法是安全的。**门控为何导致测试永不空闲尚未查明**，作为观察项记录在证据 README，请 GPT/Architect 判断是否需要跟进。
- 诊断与恢复的保数据说明全部使用后端 message，前端没有追加“任何错误都能修好”的承诺。
- Robolectric 下用 `LocalDensity` 覆盖字体缩放、`-night` 限定符模拟深色；真机大字号/TalkBack/真实文件选择器尚未验证。

### 测试结果（证据：`validation/T3-frontend-20261104/`）

- 定向 11 个类 **82/82 通过**（独立副本自有 build 目录，`--rerun` 真实执行）：新 StartupRecoveryUiTest 8，StartupTest、NotificationEntryUiTest、ReviewReminderTest、ExamSaveUiTest、ExamTerminationUiTest、PracticeEntriesUiTest、NotesAndScoringUiTest、QuickNotesUiTest，以及 GPT 的 StartupRecoveryTest、InitializationNotificationTest。
- 首次证据运行失败（上述门控问题）和二分对照原样保留在证据目录，不计为通过。开发中另有一处修正：“诊断包”小节标题曾与按钮同名“导出诊断包”，测试和读屏都会遇到两个同名节点，已把标题改为“诊断包”。
- 未运行完整套件（按 §16.4 由 GPT 集成后统一运行）；无真机验证。
- 状态：待审查。

## 2026-11-04 · T2 前端补修：离页回调被通知覆盖（Claude）

起始 HEAD `9221220`（GPT 集成证据；后端关库修复 `ebcffc3`）。处理 [T2 集成证据](../validation/T2-integrated-20261104/README.md) 中的 Claude P1。

- **更正上一节的说法**：“等待协程属于答题页作用域，通知导航时会被取消，不会迟到导航”不成立。页面改为 REVIEW 后，答题页作用域要到下一帧才取消；flush 在此之前返回时，旧回调会把页面改回 HOME。GPT 的诊断已实证复现。
- **修复**：`QuestionScreen.kt` 的 `ExamLeave.leave` 在发起时记录来源页面与 `practiceSession`，flush 返回后若任一变化（页面已被通知或其他外部导航接管）就丢弃成功导航和失败弹窗。持久化队列照常进行；通知路径不等待保存，G1 的 REVIEW 优先级不变。没有改 AppNavigation、MainActivity 或后端。
- **测试**：`ExamSaveUiTest` 新增两项，把 GPT 的诊断改为正式回归，保留“通知先设 REVIEW，保存返回在下一帧之前”的顺序：
  - 成功返回：未修版本失败（期望 REVIEW，实得 HOME），修复后通过。
  - 失败返回：确认不弹旧对话框、不导航，失败仍在考试标签页可见并可重试。该项在未修版本上也通过（旧弹窗状态会被下一帧的销毁掩盖），作为失败路径的守护而非复现。
- **结果**（证据：`validation/T2-frontend-race-20261104/`）：前后对照见上；交付版本 10 个相关类 **95/95 通过**（独立副本自有 build 目录，`--rerun`）。未运行完整套件（由 GPT 集成复测）；无真机验证。
- 状态：待审查。

## 2026-11-04 · T2 前端：考试保存提示、显式重试、离页等待（Claude，REPAIR_DISPATCH §14.3）

起始 HEAD `831bcbe`（GPT T2 后端：`5923f4f`、`8b88842`、`831bcbe`）。接口按 API_CONTRACT 顶部 T2 节：`examSaveState`、`retryExamSave()`、`suspend flushExamSave()`。QuizViewModel、ExamStateHolder、ExamPersistence、DAO、迁移、契约、派发文档未改；Architect 未跟踪文件未暂存。

### 修改的文件与函数

| 文件 | 改动 |
| --- | --- |
| `QuestionScreen.kt` | 新增 `ExamSaveStatus`：Saving“保存中…”、Saved“已保存到本机”、Error 显示后端 message（“保存失败，最新修改尚未保存”，liveRegion Assertive）及“重试保存”（仅答案可编辑时提供）、Inactive 不显示。新增 `ExamLeave`/`rememberExamLeave`/`ExamLeaveDialog`：可作答的考试离页先 `flushExamSave()`，true 才执行原导航；false 留页并弹“保存失败，暂未离开”（重试保存 → 成功后继续离开 / 留在本页）。`QuestionContent`：保存提示放在进度条下（不随列表滚动）；顶部返回、系统返回（本页 `BackHandler` 优先于外壳）与“打开全局标签”走离页等待；等待期间选项、简答、翻题、交卷冻结。`MissingQuestion` 同样显示保存状态并走离页等待。T1 结束/失败的冻结权限不变。 |
| `BrowseScreen.kt` `ExamSetup` | Idle 的未交卷卡片显示同一保存状态与重试，离开试卷（例如被通知切走）后失败仍可见。 |
| `ExamSaveUiTest.kt`（新增） | 7 项，见证据 README。 |

### 设计说明（供 Architect 审查）

- **只在 Idle 可作答时拦截离页。** 结束中、T1 Failed（包括“考试保存失败，未提交成绩”）和清理失败时答案已冻结，`retryExamSave` 按契约无效，若再拦截会把用户困在页面；这些状态的恢复入口在结束面板和考试标签页，离页不拦。
- **离页失败只给“重试保存 / 留在本页”，没有“仍然离开”。** 按 §14.3“false 留在原页并提供重试”。持续写入故障时用户仍可交卷（屏障失败后进入 Failed，可自由离页）或确认放弃，不会被永久卡住。
- **AppNavigation、MainActivity 未改。** 答题时底部导航隐藏，离页入口都在答题页内，由本页处理；外壳返回处理器被本页 `BackHandler` 覆盖。等待协程属于答题页作用域：通知等外部导航使页面离开组合时等待被取消，不会迟到导航，也不拦截 G1 通知优先级。
- **后台/通知路径没有另加 flush。** 契约中 flush 只等待、不写入、不隐式重试；写入器独立于界面生命周期持续落盘，onStop/通知时调用它不会增加任何保存保证，Error 也不会因此恢复。失败状态在答题页和考试入口持续可见。若 Architect 仍要求“后台尽力 flush”，可在 MainActivity.onStop 加一次不等待结果的 launch，改动很小。

### 测试结果（证据：`validation/T2-frontend-20261104/`）

- 定向 10 个类 **92/92 通过**（`--rerun` 真实执行，独立副本自己的 build 目录）：新 ExamSaveUiTest 7、既有考试/通知/练习 UI 38、GPT 的 ExamPersistenceTest 14 / ExamLifecycleContractTest 20 / InitializationNotificationTest 13。
- 编写中失败与定位见证据 README：首轮 4 项失败均为测试问题（未等题目加载；重启测试在同一组合内换 ViewModel，旧处理器仍绑定旧实例）。探针确认后端重启后的清理重试工作正常。
- 未运行完整套件（按 §14.4 由 GPT 合入双方提交后统一运行一次）；无真机验证。

### 需要 GPT 集成时注意

- 本提交基于 `831bcbe`，只改上述三个 UI/测试文件和本文档、证据目录。
- 状态：待审查。

## 2026-10-27 · G1 前端：通知入口接入、Activity 测试 Compose 收尾、通知与恢复交互测试（Claude）

起始 HEAD `d21c381`（GPT G1 后端）。接口来自 `API_CONTRACT.md` 顶部 G1 段：`requestNotificationReview()`、`awaitInitialization(): InitializationOutcome`。REPAIR_DISPATCH 尚无 G1 正式派发节，本次按用户指示和 GPT 分析中划给 Claude 的范围（MainActivity 通知接入、Activity 测试收尾、通知 UI 测试）执行；QuizViewModel、API_CONTRACT、REPAIR_DISPATCH 未改。

### 修改的文件与函数

| 文件 | 改动 |
| --- | --- |
| `MainActivity.kt` | `openNotification`：先移除 `OPEN_REVIEW` extra，再调用 `vm.requestNotificationReview()`。删除 `reviewAfterLoad` 状态、直接写 `vm.page = REVIEW` 和 `LaunchedEffect(vm.ready, reviewAfterLoad)` 竞态。`onCreate` 只在 `savedInstanceState == null` 时处理启动 Intent（进程重建会带回原启动 Intent，不应再次打开复习）；`onNewIntent` 不变地转发。 |
| `ActivityTestSupport.kt`（新增，仅测试） | `resetDefaultViewModelFactory()`：清掉 `AndroidViewModelFactory` 静态缓存的旧 Application；`runningRecomposers()` + `destroyAndAwaitRecomposers(before)`：销毁后只检查本 Activity 新增的 Recomposer 已退出。 |
| `ReviewReminderTest.kt` | 类加 `createEmptyComposeRule()`（Activity 窗口使用规则的重组器，由规则关闭）；G1 用例构建 Activity 前重置工厂，`visible()` 后的单次 `Looper.idle()` 改为 `compose.waitForIdle()`（组合现在由规则时钟驱动），`finally` 改为 `destroyAndAwaitRecomposers` 后再清理偏好与数据库。业务断言（REVIEW、队列、模式、深色状态栏）不变。 |
| `NotificationEntryUiTest.kt`（新增） | 5 项真实 Activity + UI 测试，见下。 |

### 测试覆盖（NotificationEntryUiTest）

- 冷启动带通知 + 未交卷考试：`Ready(examRestored=true)` 后为 REVIEW，extra 已消费；队列、index、答案、截止时间、偏好快照不变、无学习记录；从首页“继续未交卷考试”进入，回到第 2 题，计时显示，翻回第 1 题原选择仍在。
- 普通启动：恢复考试直接进入答题页。
- 运行中 `onNewIntent` 通知：立即 REVIEW；重复通知不改变考试；无 extra 的 Intent 与配置变更（recreate，ViewModel 同一实例）不会重新打开复习。
- 进程重建（带 savedInstanceState）时原启动 Intent 不再触发复习，正常恢复考试。
- 无考试：通知打开复习；普通启动留在首页。
- 等待点一律使用 `awaitInitialization()`，不以 ready 或单次 Looper.idle 作为完成。

### 测试结果（证据：`validation/G1-frontend-20261026/`）

- 定向 7 个类 **50/50 通过**（`--rerun`，真实执行，独立副本自己的 build 目录）。
- 固定顺序对照“G1 → 下一个 Compose UI 测试”：用 HEAD 的旧收尾时后者 `AppNotIdleException`（60s）失败，交付版本两项都通过。有序 Suite 只在隔离副本中，源码文本已归档。
- 定位中发现两个测试环境根因（详见证据 README）：`AndroidViewModelFactory` 静态缓存首个测试的 Application，使后续 Activity 测试的 ViewModel 读到旧偏好；窗口 Recomposer 排在其他 Compose 类之后时绑定遗留调度器，单纯等待无法结束。两者都只在测试中修正，不影响生产。
- 未自行运行完整套件（按 11.3 由 GPT 集成后统一运行）；无真机验证。

### 需要其他执行者 / Architect 注意

- 其他直接用 Robolectric 构建 Activity、且读取 ViewModel 状态的测试也会受静态工厂缓存影响；目前仓库里只有 ReviewReminderTest 与新类，已处理。
- 初始化 `Failed` 时界面仍停在加载页，没有错误/重试页，属于 T3。
- 状态：待审查。

## 2026-10-26 · T1 补充回归：失败后只读翻题与显式重试（Claude，REPAIR_DISPATCH 10.2 / 11.4）

起始 HEAD `8b8a0cd`。只改 `ExamTerminationUiTest.kt`，新增 `failedSubmitAllowsReadOnlyBrowsingAndRetryGradesFrozenAnswersOnce`；生产代码、后端、契约未动。Architect 的未跟踪文档与证据未暂存。

- **链路**：三题 a 对 / b 错 / c 未答 → 用既有 DB 触发器接缝让提交在未提交记录阶段失败（`Failed(SUBMIT, RECORDS, false)`）→ 实际点上一题 / 下一题浏览，原选择仍显示、选项禁用、点击无效，答案与快照不变 → 去掉故障后在其他题位置点“重试交卷” → 成绩 `ExamResult(3,2,1,1,33)`；a、b 各一条 Attempt（对 / 错），c 无 Attempt；Progress 只有 a、b。说明重试使用原冻结试卷与答案，不受翻题后的 index 影响。
- **结果**：整个 `ExamTerminationUiTest` **12/12 通过**（`--rerun`，真实执行）。按 11.2 在独立副本 `C:\AndroidProjects\EmbeddedQuiz-claude-iso` 自己的 build 目录运行，不覆盖共享 app/build。证据：`validation/T1-frontend-frozen-retry-20261026/`。
- **首次运行**新用例失败 1 项，原因是测试辅助函数用“滚动查找”断言“节点不存在”；改用 `assertIsNotSelected()`，断言强度不变、未加等待。首次日志保留在证据目录，不计为证据。
- 未发现生产代码问题。按 11.4 未自行跑完整套件；无真机验证。状态：待审查。

## 2026-10-26 · T1 前端接入：交卷说明、结构化成绩、放弃考试与失败恢复（Claude）

依据 `REPAIR_DISPATCH.md` 第 9.2 节与 `API_CONTRACT.md` 顶部 T1 契约。起始 HEAD `3628e8816bbfe07445c3c78e7161942b4df81973`（后端基线）。只改白名单内的 Compose 页面和新增 UI 测试；`ExamStateHolder.kt`、`QuizViewModel.kt`、`Repository.kt`、API_CONTRACT、REPAIR_DISPATCH、方案稿和 Architect 证据都没有动。

### 本次修改的文件与函数

| 文件 | 改动 |
| --- | --- |
| `QuestionScreen.kt` | 新增 `ExamControls`（只按契约状态表推导：`editable` = 打开中的考试且 `Idle` 且 `!busy`；`abandonOffered/canAbandon` = `Idle` 或 `Failed(SUBMIT, VALIDATION/RECORDS, false)`；`canRetry` = 任一 `Failed` 且 `!busy`），`ExamConfirmDialog`（交卷 / 放弃二次确认），`ExamEndPanel`（结束中 / 失败阶段 + 重试 + 放弃），`MissingQuestion`（缺题视图）、`ExamMenu`（顶部“考试操作”→“放弃本次考试”）。`QuestionScreen` 区分“加载中”和“题目不存在”。`QuestionContent`：成绩横幅改读 `examSummary` 五个字段；选项、简答输入、交卷按钮只在 `editable` 时可用，点击时再检查一次；`Submitting/Abandoning`（及 busy）时上一题 / 下一题禁用；失败时动作栏主按钮变为对应重试，列表顶部显示阶段说明。 |
| `BrowseScreen.kt` `ExamSetup` | 未交卷考试卡片：Idle 时“继续考试”+“放弃本次考试”；非 Idle 时显示同一 `ExamEndPanel`（含重试按钮）和“查看试卷”。卡片可纵向滚动，失败说明较长时按钮不会被裁掉。搜索 / 筛选 / 其他页面未改。 |
| `AppNavigation.kt` | 一处 `LaunchedEffect(vm.examOperationState)`：变为 `Abandoned` 且当前在 `Page.QUESTION` 时切到 `Page.EXAM`。原因：放弃成功会清空队列，答题页在同一帧被移出组合，页面自身无法处理这次导航。启动、通知优先级（G1）和导航模型未改。 |
| `ExamTerminationUiTest.kt`（新增） | 11 项，见下。 |

### 交互

- **交卷确认**：“已答 X 题，未答 Y 题，共 N 题。未答题计 0 分，但不会写入学习记录，也不改变复习安排和错题本。”按钮“确认交卷 / 继续作答”。已答数只是对当前答案的展示计数，成绩结论只来自 `examSummary`。
- **成绩**：“答对 c / t，得分 s”，副标题“已答 a 题 · 未答 u 题（计 0 分，不改变复习安排）”。只在 `examFinished`（Submitted）时显示。
- **放弃**：答题页顶部“考试操作”菜单、失败面板、考试入口卡片三处入口，全部先弹“放弃本次考试？”，说明不计成绩、不写学习记录、答案丢弃；“取消”不调用后端。成功后回到“模拟考试”标签页（考试设置），不显示成绩。
- **失败阶段**：
  - `Failed(SUBMIT, VALIDATION/RECORDS, false)`：“交卷未完成”+ 后端 message +“答案已冻结”；可“重试交卷”或确认放弃。
  - `Failed(SUBMIT, SESSION_CLEAR, true)`：“成绩已保存，会话清理未完成”；只有“重试清理”，不显示成绩横幅、交卷或放弃。
  - `Failed(ABANDON, SESSION_CLEAR, false)`：“放弃未完成”；只有“重试放弃清理”，不能改为交卷。
- 重试只由按钮调用 `retryExamOperation()`；没有在 LaunchedEffect、倒计时或重组中自动重试。
- **确认框失效**：`examOperationState / examFinished / mode / busy` 任一变化都关闭确认框（例如确认框打开时超时自动交卷）；点确认时重新读取当前许可，不允许的不调用。
- **缺题**：题目加载返回 null 时显示“本题无法加载，题目可能已从题库移除。”和结束面板（交卷 / 放弃 / 重试），不再一直停在“正在加载题目…”。考试标签页卡片同样可以处理。
- **离开再回来**：失败状态不只靠 Snackbar；返回首页再进“模拟考试”标签，卡片仍显示真实阶段和恢复按钮。

### 已对接的后端接口

`vm.examOperationState`、`vm.examSummary`、`vm.finishExam()`、`vm.abandonExam()`、`vm.retryExamOperation()`，以及既有 `examFinished / examAnswers / examDeadline / queue / index / mode / busy`。UI 测试使用后端 internal 接缝 `examState.hooks.beforeCritical` 和 `examState.sessionPreferences`，未修改后端实现。

### 需要 GPT/Codex 新增或调整的接口

暂无阻塞项。记录一点供 Architect 判断：`Failed` 状态下我允许上一题 / 下一题浏览已冻结的答案（不能修改；契约写明重试不读取后来变化的 index），只在 `Submitting/Abandoning` 时禁用翻题。如要求失败时也禁止翻题，改一处条件即可。

### 测试结果（证据：`validation/T1-frontend-20261026/`）

- **定向运行（真实执行，--rerun）**：`ExamTerminationUiTest` 11、`NotesAndScoringUiTest` 8、`PracticeEntriesUiTest` 4、`QuickNotesUiTest` 5，以及未改动的后端 `ExamLifecycleContractTest` 20、`ExamStateHolderTest` 8，**56/56 通过**，0 跳过。
- **完整运行 + Lint（一次）**：143 项中 142 通过，失败 1 项为基线 G1 `ReviewReminderTest.notificationReviewEntryWinsAfterUnfinishedExamIsRestored`（REVIEW / QUESTION），**不是全绿**。本次没有复现 9 项 Compose 超时，但只是一次观测，不能闭环 9.3。Lint 0 错误、5 个既有警告。
- `ExamTerminationUiTest` 覆盖：部分作答确认框数量与说明、取消不调用后端、结构化成绩、仅已答题写记录；全未答零分且无 Attempt/Progress；放弃确认 / 取消（队列、答案、截止时间、快照不变）/ 成功回到考试标签且无成绩；结束中选项、翻题、交卷按钮禁用，连点与返回键只产生一次操作；RECORDS 失败冻结答案、超时入口不重试、显式重试成功；提交后清理失败只显示“重试清理”、无成绩横幅 / 交卷 / 放弃，离开后从考试标签恢复；放弃清理失败只能重试放弃；缺题不卡在加载、交卷失败后可放弃；放弃 / 交卷确认框打开时超时交卷会关闭确认框；深色 + 大字号下失败标题为 heading、重试按钮可用；普通练习没有考试控件。
- 测试编写中前两次运行有失败，均为测试问题（小窗口内节点不可见、Snackbar 遮挡）；其中考试入口失败卡片被裁切也是真实的小屏风险，已让卡片可滚动。没有削弱断言或加 sleep。
- 测试运行前都确认没有其他 Gradle 测试 worker，在独立证据目录保存 XML，没有与 GPT 的测试同时运行。

### 尚未验证

没有构建 APK，没有真机检查（浅深色、大字号换行、TalkBack 朗读、小屏）。T2 跨进程重复提交窗口仍是发布阻断。

### 本次未做

没有修改后端、API_CONTRACT、REPAIR_DISPATCH；没有开始 T2；没有推送、发布或覆盖 APK。状态：待审查。

## 2026-10-26 · 新配色与 1.6.0 版本整合（Claude）

用户要求只改颜色美化界面，并授权提交 GitHub、整合为 1.6 版本。

### 本次修改的文件

| 文件 | 改动 |
| --- | --- |
| `Theme.kt` | `appColors` 浅色 / 深色两套全部换新：主色靛蓝（浅 `#4355B9` / 深 `#BAC3FF`），强调色琥珀（星标、便签、搜索高亮、统计图自评线），中性色偏冷灰；页面浅灰底 + 白卡片的结构不变。`statusColors`（答对绿 / 待核对琥珀）没改。Android 9 及以下的导航栏底色同步新背景。 |
| `values/themes.xml`、`values-night/themes.xml` | 启动窗口背景改为新背景色，启动不闪旧颜色。 |
| `app/build.gradle.kts` | 经用户授权：`versionCode` 6 → 7，`versionName` 1.5.0 → 1.6.0。签名配置没动。 |
| `CHANGELOG-1.6.0.md`（新增）、`README.md` | 1.6.0 更新说明与署名。 |

没有改布局、交互、组件结构、启动图标和任何后端文件。速记阅读页的颜色取自主题，自动跟随。所有文字与背景的对比度 ≥ 5.1:1，边框 ≥ 4:1。

### 需要 GPT/Codex 新增或调整的接口

暂无。

### 测试结果

在干净副本中：`assembleDebug`、`lintDebug` 通过（0 错误、5 个既有警告）；完整测试 104 项中 103 项通过，失败的是已知时序不稳定的 `ReviewReminderTest.notificationReviewEntryWinsAfterUnfinishedExamIsRestored`，单独重跑该类 4/4 通过。APK `复习软件\EmbeddedQuiz-1.6.0-debug.apk`（新文件），1.6.0 / 7，签名证书与之前相同。新配色未在真机上看过。

## 2026-10-25（第五轮）· Opus 阅读加载缺陷与 Codex 修复同步

本节由 Codex 按用户要求回填，记录已获用户授权并获认可的最小修复；原前端设计与样式不变。完整补丁、复盘和最终 APK 信息见 BACKEND_HANDOFF 第五轮。

- **原问题位置**：QuickNoteReader.kt 的 ReaderWeb.load 与 ReaderClient.shouldInterceptRequest。原实现 loadDataWithBaseURL 的正文引导请求也经过“仅图片放行、其他全 404”的拦截器；正文没有主请求例外，导致用户打开已导入的 Markdown 后出现 data: / ERR_HTTP_RESPONSE_CODE_FAILURE。
- **原测试遗漏**：阅读容器出现、目录弹层和 HTML / CSS 文本断言不代表 WebView 正文成功加载。Robolectric 不真正加载 Chromium 页面；必须补充请求响应测试和真实 WebView 验收。
- **已改动**：固定本地 document.html URL + 内存 UTF-8 HTML，仅精确当前文档主请求返回显式 200；图片、拒绝策略、CSP、nonce、Compose / CSS / 主题 / 字号和存储均保持。旧第四轮“其余一律 404”的描述只适用于未知请求，不能再用于正文。
- **责任说明**：Opus 的容器实现没有区分正文与图片；Codex 先前接口交接也没有把正文例外写清，已经共同补齐契约。不是要求用户修改 / 重导入示例文档。
- **验证**：新增路由测试 API 28 / 33 共 12 项，原 QuickNotesUiTest 5 项，全过；assembleDebug / lintDebug 通过。用户已认可此次修复，不将此反馈扩展为所有设备、主题和辅助功能的全面验收。
- **后续不得回退**：保留正文 / 资源分类，禁止改成无条件 404 或无条件网络放行；新增 loading 方式时验证主请求 200、图像 / 缺图、跨文档拒绝和中文。真实 WebView 继续验收目录、脚本、复制、横向滚动和位置恢复。

未处理的操作结果、working 时 leave、TalkBack 和通知测试时序问题仍是第四轮列出的后续事项。

署名：Codex（OpenAI）；原速记 Compose 前端贡献：Claude Opus。

---

前端（Claude）负责 Jetpack Compose 界面；后端（GPT/Codex）负责数据库、复习算法、后台任务、网络和 ViewModel 业务逻辑。本文件记录每次前端改动和需要后端处理的事项，最新一次放在最上面。

---

## 2026-10-25（第四轮）· 速记模块前端（Claude）

依据 `BACKEND_HANDOFF.md`（2026-10-25 第三轮）“Claude 接入清单” 8 项、`API_CONTRACT.md` 速记一节和 `QUICK_NOTES_RENDERING.md` 实现。只写 Compose 页面、阅读容器和 UI 测试；Codex 的 `QuickNote*.kt`、`QuickNotes*.kt`、`QuizViewModel.kt`、`build.gradle.kts`、`app/libs` 和后端测试都没有动，工作区里 Codex 未提交的改动原样保留。

### 本次修改的文件

| 文件 | 改动 |
| --- | --- |
| `AppNavigation.kt` | 底部五项改为 **首页、模拟考试、速记、错题、更多**。速记槽调用 `vm.openQuickNotes()`，以 `quickNotes.state.visible` 高亮；在速记里点其他项时，先取阅读页的最后位置，再 `quickNotes.leave { vm.navigate(target) }`。内容区在 visible 时换成速记页，**日期刷新、通知和考试计时的 effects 仍在 QuizLayout 中挂着**，考试倒计时不受影响。`vm.page` 被外部改变（例如点了复习提醒通知）时自动 `leave()`，跟随到新页面。“更多”列表加入“智能复习”（`Page.REVIEW`），因为它不再在底部栏；复习角标随底部“复习”项一起去掉，首页主按钮仍显示到期数。 |
| `QuickNotesScreen.kt`（新增） | 文档列表：标题、来源文件名、导入日期、“读到 N%” / “尚未阅读”，点卡片打开，垃圾桶图标删除。主按钮“导入 Markdown 或配套 ZIP”（SAF `OpenDocument`，取消不调用后端）。右上角菜单“导出速记备份”（`CreateDocument`）、“恢复速记备份”。读取中显示“正在读取速记…”，处理中显示进度条，错误显示 `state.error` 和“重新读取列表”，位置保存失败显示“重试保存阅读位置”。读取中或读取失败时不显示空库提示。底部说明刷题备份不含速记。 |
| `QuickNoteReader.kt`（新增） | 阅读页：顶部返回 / 标题 / 目录按钮；`warnings` 折叠为“N 条显示提示”；WebView 显示 `htmlBody`。详见下方“阅读容器”。 |
| `AppIcons.kt` | 新增 `QuickNotes`、`Trash`、`Import` 图标。 |
| `QuickNotesUiTest.kt`（新增） | 5 项，见“测试结果”。 |

### 交互与对话框

- **重复导入**：`state.conflict` 非空时弹出“已有相同的文档”，列出冲突文档，按钮“取消导入”（`cancelImport`）/“保留两份”（`confirmKeepBoth`），点外面等于取消。不提供覆盖。
- **删除**：确认框写明“将删除这份文档的正文、图片和阅读位置，无法撤销。刷题数据不受影响。”
- **恢复备份**：先选文件，再选方式。对话框说明这是速记备份 ZIP，不是要阅读的配套 ZIP；“合并恢复”（推荐，`replace=false`）；“替换…”再弹二次确认，写明会替换全部 N 份文档及阅读位置、不影响刷题，确认后才传 `replace=true`。
- **成功提示**：后端命令只报告失败，前端在 `working` 结束、`error` 和 `conflict` 都为空时弹 Toast（导入完成 / 已删除 / 已导出 / 已恢复）。
- **返回键**：阅读页 → 存位置后回列表；列表 → 离开速记回首页。

### 阅读容器（按 `QUICK_NOTES_RENDERING.md`）

- 外壳 HTML：UTF-8、移动 viewport；颜色取当前 Material 主题（浅色 / 深色），`color-scheme` 同步，WebView 背景同色，不会白闪。字号用 `textZoom = fontScale × 100`，跟随系统和应用内字号设置；字号变化后按锚点重新定位，不把这次跳动存成新位置。
- `.qn-table-scroll`、`.qn-code-scroll` 各自 `overflow-x:auto; max-width:100%`；`pre` 为 `white-space:pre`，不截断、不去缩进；单元格最小宽度 5.5em，宽表格横向滑动。
- **安全**：CSP `default-src 'none'`，只允许带随机 nonce 的应用脚本、内联样式和 `https://appassets.androidplatform.net` 的图片；`allowFileAccess / allowContentAccess / domStorage / 多窗口` 全关，没有 `addJavascriptInterface`。`shouldInterceptRequest` 只把 `/quicknotes/{id}/assets/` 交给 `readAsset`（后台线程里等待），其余一律 404，不走网络或 file://。`quicknote-copy:` → `copyCode` + Toast；页内 `#锚点` 留在本页；http / https / mailto 交给系统应用；其他 scheme 拦截并提示。渲染进程崩溃时关闭阅读页并提示，不让整个应用崩溃。
- 图片 `onerror` / 已损坏的图片替换成“图片无法显示：…”提示块，不留破图标。
- **目录**：底部弹出，按级别缩进，点击滚到对应 `id`。
- **阅读位置**：页面脚本算出 `anchor + offset(CSS px) + fraction`，Kotlin 端校验（未知锚点回退到空锚点和比例，数值夹到契约范围）。滚动后最多每秒 2 次入队，停止滚动后补一次；离开阅读页、切换底部项、应用进入后台（ON_PAUSE）都先取最后位置。只有在恢复完成后、且最近有触摸 / 按键 / 目录跳转时的滚动才保存，避免把布局变化存成新位置。恢复在 `onPageFinished` 后执行，若图片仍在加载则等 `load` 事件；锚点优先，找不到时按比例。

### 已对接的后端接口

`vm.openQuickNotes`、`quickNotes.state`（visible / loading / working / documents / reader / conflict / error）、`leave`、`reload`、`importUri`、`confirmKeepBoth`、`cancelImport`、`openDocument`、`closeDocument`、`deleteDocument`、`savePosition`、`positionSaveError`、`retryPositionWrites`、`copyCode`、`readAsset`、`exportBackupUri`、`restoreBackupUri`。

### 需要 GPT/Codex 新增或调整的接口（都不阻塞当前功能）

1. **操作结果。** 命令只在失败时写 `error`，前端靠“working 结束且无错误”推断成功。若能提供明确的完成结果（例如最近一次操作类型 + 成功 / 失败），或把 `restoreBackup` 已返回的“新恢复文档数”放进状态，提示可以更准确（如“恢复了 3 份”）。
2. **`leave()` 在 working 时被拒绝。** 如果正在导入 / 打开文档时用户点了别的底部项，或者通知把页面切走，`leave()` 只设置“速记正在处理”，速记仍然盖在页面上。建议 `leave()` 排在当前操作之后执行，而不是直接拒绝。
3. **`ReviewReminderTest.notificationReviewEntryWinsAfterUnfinishedExamIsRestored` 时序不稳定**（本轮单独跑两次都通过，和其他类一起跑时偶尔失败，上午已记录）。属于通知入口测试，建议给断言加等待。

### 尚未完成 / 已知限制

- **真实 WebView 没有在真机上验证。** Robolectric 不渲染 WebView、不执行脚本，所以表格 / 代码横向滑动、深色配色、图片加载、复制代码、目录跳转、位置恢复都只经过编译和代码审查，需要真机按 Codex 的验收清单检查（实际 Linux 文档 5 张表格、C 代码复制、中文目录、缺图提示、配套 ZIP、重启后位置、浅深色、大字号）。
- 只用读屏手势（TalkBack）滚动时，阅读位置不会保存（没有触摸事件），离开时取的最后位置也不更新。
- 进程被立即杀死时，最后一小段滚动可能丢失（后端已说明）。
- 底部栏的复习到期角标没有了，是否要在“更多 → 智能复习”或首页别处补一个角标，需要你决定。

### 测试结果

- `assembleDebug`、`lintDebug` 通过；lint 0 错误，5 个警告与之前相同，新文件没有警告。
- 受影响测试类：`QuickNotesUiTest` 5、`NotesAndScoringUiTest` 8、`PracticeEntriesUiTest` 4、Codex 的 `QuickNotesIsolationTest` 4 **全部通过**；`ReviewReminderTest` 4 项中 `notificationReviewEntryWinsAfterUnfinishedExamIsRestored` 在一起跑时失败一次，单独在含 / 不含本轮前端改动的两份源码里各跑两次都通过，判断为已知的时序问题，和速记无关。
  - `QuickNotesUiTest` 覆盖：底部没有“复习”；未交卷考试时进入速记再离开，`examDeadline`、队列和答案都不变，`page` 没变；“更多”能进智能复习；导入实际 Linux 文档后打开，阅读容器和“N 条显示提示”出现，目录能打开并点击跳转后关闭，返回键先回列表、再回首页；重复导入弹窗，取消后仍 1 份、保留两份后 2 份；删除确认文字；HTML 外壳的 CSP / nonce / 横向滚动 CSS，以及位置解析的校验和夹值。
- **测试环境问题**：第一次在共享构建目录里把 assemble、lint 和 5 个测试类一起跑，所有 Compose UI 测试（包括之前稳定的旧测试）都报 `AppNotIdleException`，每项等 60 秒。逐步排查：去掉本轮前端改动后单测通过；加回改动后单测也通过；在干净的源码副本里只跑测试（不同时跑 lint），全部通过。结论是共享构建目录 / 与 lint 并行时的环境问题，不是代码 Bug。最终结果都来自干净副本，构建源码与工作区一致。
- 试装包：`复习软件\EmbeddedQuiz-1.5.0-quicknotes-20261025-debug.apk`（新文件，没覆盖已有 APK），包含 Codex 的速记后端和本轮前端。版本 1.5.0 / 6，签名证书 SHA-256 `2c73c905…b151`，与之前一致，可覆盖安装；文件 SHA-256 `8290de6f…0b9d`。

### 本次未做

没有提交或推送 GitHub，没有修改版本号、签名、构建配置或依赖，没有覆盖已有 APK。

---

## 2026-10-25（第三轮）· 接入参考解法增量保存，分类卡片不再显示旧标签（Claude）

依据 `BACKEND_HANDOFF.md`（2026-10-25 第二轮）“Claude 需要接入”的 1、2 项。

### 本次修改的文件

| 文件 | 改动 |
| --- | --- |
| `PersonalEditors.kt` | `VerifiedSolutionEditor` 的保存改为 `vm.saveVerifiedSolution(q.id, code, verified)`，不再用 `saveMetadata(meta.copy(...))` 整体写回。编程区和待核对工作台两处都用这个组件。前端已没有任何 `saveMetadata` 调用。 |
| `BrowseScreen.kt` | 题目卡片删除“标签：…”，只保留“我感觉：X”。旧标签仍可被搜索命中（后端兼容口径）。 |
| `PracticeEntriesUiTest.kt` | 新增 `savingVerifiedSolutionKeepsLatestDifficulty`：编辑器打开后再改难度，保存解法后难度仍是新值。 |

### 已对接的后端接口

`vm.saveVerifiedSolution`。

### 需要 GPT/Codex 新增或调整的接口

暂无。

### 测试结果

- `assembleDebug`、`lintDebug` 通过（0 错误，5 个警告与之前相同）。
- 一次运行 7 个相关测试类共 48 项：UI 测试 `PracticeEntriesUiTest` 4、`NotesAndScoringUiTest` 8，后端 `PersonalMarksContractTest` 10、`FrontendQueryContractTest` 10、`LearningDataTest` 7、`DomainTest` 5 全部通过；`ReviewReminderTest` 4 项中 `notificationReviewEntryWinsAfterUnfinishedExamIsRestored` 失败（期望 REVIEW，实际 QUESTION）。
- 这项是**测试时序不稳定**，不是功能问题：恢复考试先设 `page=QUESTION` 再设 `ready=true`，`MainActivity` 的 `LaunchedEffect` 在 ready 后才切到 REVIEW；测试在 ready 后只让主线程空转一次就断言，机器负载高（lint 和 7 个类一起跑）时这一帧还没执行。只跑 2 个类时 4/4 通过，此前两轮也都通过。本轮和 GPT 都没有改 `MainActivity` 或启动流程。建议后续给这个测试加等待（`MainActivity` / 通知入口属于后端范围，这里没改）。
- 新测试调试中修正了两处测试问题：取到的代码编辑器下标不对（上方工作区编辑器已滚出列表）；编辑器保持焦点会把列表滚回去，导致按坐标点不到保存按钮，改为直接触发按钮的点击动作。功能代码没有为测试改动。
- 试装包：`复习软件\EmbeddedQuiz-1.5.0-20261025-debug.apk`（新文件），包含本轮全部前后端改动。版本 1.5.0 / 6，签名证书 SHA-256 `2c73c905…b151`，与之前一致，可覆盖安装；文件 SHA-256 `7a07c9f2…d4f3`。

### 已知风险

与上一节相同：没有真机检查小屏、大字号、深色模式和 TalkBack。

---

## 2026-10-25 · 练习入口调整、模拟考试标签与答题页精简（Claude）

依据 `BACKEND_HANDOFF.md`（2026-10-25）中的“Claude 实施清单”4 项完成前端改动。只改 Compose 页面、图标和 UI 测试；没有修改 `QuizViewModel`、`Repository`、`Data.kt`、`Navigation.kt`（`Page` 枚举）、后端测试和构建配置。

### 本次修改的文件

| 文件 | 改动 |
| --- | --- |
| `HomeScreen.kt` | 练习方式增加第三行双列卡片：“分类练习”→ `vm.navigate(Page.BROWSE)`，“编程专区”→ `vm.navigate(Page.PROGRAMMING)`。原 4 个入口不变。列表加 `testTag("home-list")` 供 UI 测试滚动。 |
| `AppNavigation.kt` | 底部第二项由“刷题”改为“模拟考试”（`Page.EXAM`，新图标）。高亮映射：分类、编程专区、`Page.QUESTION` 的选题页归“首页”，考试设置页归“模拟考试”，其余归“更多”。系统返回：分类、编程专区、模拟考试页回首页。“更多”删除分类、编程专区、模拟考试三项；便签项显示为“全局标签 / 便签”。复习角标逻辑不变。 |
| `BrowseScreen.kt` | 考试设置页：有未交卷考试时，显示“有未交卷的考试”卡片（已答数 / 总题数）和“继续考试”按钮（`vm.page = Page.QUESTION`），不显示“开始考试”，因此不会覆盖队列、答案和截止时间。 |
| `QuestionScreen.kt` | 下方“我的笔记”“标签与难度感受”两个展开区合并为一个“笔记与难度感受”：上面是难度感受，下面是笔记输入和保存按钮。删除题目级标签输入。摘要显示笔记首行和“感觉X”。日常题的保存按钮改名为“保存笔记”（编程题仍为“保存代码与笔记”）。顶部纸张图标的无障碍描述改为“打开全局标签”，调用不变（`vm::openStickyNotes`）。 |
| `PersonalEditors.kt` | 新增 `PerceivedDifficultyChips`：未设置 / 易 / 中 / 难四个单选 Chip，**点选即保存**（`vm.savePerceivedDifficulty(q.id, value)`，“未设置”传 `""`），忙时禁用；选中状态直接读 `learning.metadata[q.id]?.perceivedDifficulty`，不保留本地副本。**删除 `PersonalMarksEditor`**（题目级标签输入 + `saveMetadata(meta.copy(...))` 整体写回），已无调用方。 |
| `AnswerWorkbench.kt` | 待核对工作台底部的“我的题目标记”（自定义标签 + 难度 + “保存个人标记”）改为 `PerceivedDifficultyChips`。经用户授权，与答题页一致去掉题目级标签输入。 |
| `StickyNotesScreen.kt` | 标题改为“全局标签”，下加一行说明“独立于题目，所有题目共用同一份”。列表和编辑逻辑不变。 |
| `AppIcons.kt` | 新增 `Exam`（剪贴板加勾）、`Folder` 图标。 |
| `NotesAndScoringUiTest.kt` | 顶部按钮查找文字由“打开便签”改为“打开全局标签”；工作台测试增加检查：没有“自定义标签”输入框，点“中”后难度保存成功。 |
| `PracticeEntriesUiTest.kt`（新增） | 3 项 UI 测试，见下。 |

### 交互变化

- 首页“练习方式”变成 3 行 6 个入口；从分类 / 编程专区按返回键回首页。
- 底部五项：首页、模拟考试、复习、错题、更多。答题中底部栏仍隐藏。
- 有未交卷考试时点“模拟考试”，看到的是“继续考试”，不是新考试设置。
- 答题页只有一个“笔记与难度感受”区。难度点一下就保存，并弹出后端的“难度感受已保存”提示；笔记仍需点“保存笔记”。两者是两次独立操作，没有在一次点击里连续调用两个 `launchTask` 命令。
- 考试作答中这个区域仍隐藏（沿用原来的 `!exam` 条件）。

### 已对接的后端接口

`vm.savePerceivedDifficulty`、`vm.navigate(Page.BROWSE / PROGRAMMING / EXAM)`、`vm.openStickyNotes`、`vm.questionDraft(q).notes`、`vm.saveNotes`、`vm.learning.metadata`、`vm.examAnswers`、`vm.examFinished`、`vm.mode`、`vm.queue`。

### 需要 GPT/Codex 新增或调整的接口

1. **个人参考解法的增量保存。** `VerifiedSolutionEditor`（编程题工作区、待核对工作台都会用到）仍是 `vm.saveMetadata(meta.copy(verifiedCode = code, codeVerified = verified))`，`meta` 是界面读取时的快照，会整体覆盖 `QuestionPersonal`。如果期间难度刚被 `savePerceivedDifficulty` 改过，可能被旧值盖回。建议仿照 `savePerceivedDifficulty` 提供例如 `vm.saveVerifiedSolution(id, code, verified)`：事务内读最新记录，只改 `verifiedCode/codeVerified`。接口给出后前端负责换用。
2. **题目级 `tags` 的去向。** 前端已不再提供任何题目级标签输入，只剩旧数据。目前 `searchQuestions` 仍搜索“个人标签”，分类页题目卡片仍会显示旧标签。请决定是保留为只读兼容数据还是停止使用，并在契约里写明；如果分类页不该再显示，告诉前端删哪一处。

### 尚未完成 / 待确认

- `Page.NOTES` 的枚举文案仍是“便签”（`Navigation.kt` 是共享文件，没改），只在“更多”列表和便签页标题换成了“全局标签”。便签页里“新建便签”“便签标题”等按钮文字也没改。
- `AppIcons.Practice` 已不在底部栏使用，保留未删。

### 测试结果

- `assembleDebug`、`lintDebug` 通过；lint 0 个错误、5 个警告，与后端 2026-10-25 记录的相同，都不在本次改动的文件里。
- 只运行受影响的测试类：`PracticeEntriesUiTest` 3 项、`NotesAndScoringUiTest` 8 项、`ReviewReminderTest` 4 项，**共 15 项，全部通过**。工作台改动后又重跑 `NotesAndScoringUiTest` 和 `PracticeEntriesUiTest`（11 项），全部通过；`ReviewReminderTest` 不经过工作台，没有重跑。没有运行完整套件。
  - `PracticeEntriesUiTest` 覆盖：底部有“模拟考试”、没有“刷题”；首页两个入口进入正确页面、返回键回首页；“更多”里没有三个重复入口；未交卷考试点“模拟考试”只能继续，截止时间和队列不变；一题设置难度和笔记，切到下一题显示自己的空内容，再切回来值仍在；答题页没有“标签与难度感受”和标签输入框；顶部按钮打开全局标签，返回后仍是原题。
  - 新测试第一次跑有 2 项失败，都是测试本身的问题，不是功能 Bug：测试环境会同时载入内置题库，测试按题目顺序取题取错了；按返回键前没等页面重组完成；保存提示的 Snackbar 盖住了滚到底部的展开区标题，点击被它挡住。都在测试里修正，没有改功能代码。
- 试装包：`复习软件\EmbeddedQuiz-1.5.0-ui-20261025-debug.apk`（新文件，没覆盖已有 APK）。版本 1.5.0 / 6，签名证书 SHA-256 `2c73c905…b151`，与之前的包一致，可以覆盖安装。工作台改动后重新生成并替换了这一个本轮试装包，文件 SHA-256 `7cf6a885…2ccf`。

### 已知风险

- 没有在真机上看过。小屏或大字号下，首页第三行副标题“编程题，不进日常复习”可能换行；难度 Chip 行在很窄的屏幕上可能放不下 4 个（目前没有横向滚动）。
- 深色模式、TalkBack 读法和新启动图标的圆形 / 圆角裁切需要真机确认。
- 答题时保存提示的 Snackbar 会盖住列表底部约一行内容，约 10 秒后自动消失，也可以点“关闭”。这是原有布局的行为，这次没改。

### 本次未做

没有提交或推送 GitHub，没有修改版本号、签名或构建配置，没有覆盖已有 APK。

---

## 2026-10-24（第二轮）· 接入后端新接口

依据 `API_CONTRACT.md` 和 `BACKEND_HANDOFF.md`（2026-10-24），把上一轮请求的 4 个接口接入前端，并修复后端指出的两个前端风险。

### 本次修改的文件

| 文件 | 改动 |
| --- | --- |
| `HomeScreen.kt` | 3 个统计卡和主按钮的到期数改读 `vm.todaySummaryState`，只采用 `day` 与当前日期一致的快照；加载中显示"—"，不显示为 0；失败时显示错误文字和"重试"按钮（`vm.retryTodaySummary()`）。题目列表只在点击按钮时才构建（`vm.due(day)` 和未学题筛选），重组时不再遍历题库。"继续上次练习"卡显示 `vm.currentQuestionSummary?.normalizedCategory`。 |
| `BrowseScreen.kt` | 查询改用 `vm.searchQuestions(filter, page)` 和 `vm.searchNotes(text)`，不再调用 `vm.repo`。保留 160ms 防抖；`CancellationException` 重新抛出；真实错误时显示"搜索失败，请重试"和重试按钮，并结束加载状态。`vm.stickyNotes` 加入 effect 的 key，修改便签后返回会重新查询。 |
| `AppNavigation.kt` | 底部"复习"角标改读 `vm.todaySummary?.dueCount`（只采用当天的快照），不再调用 `vm.due()`；汇总加载中或失败时不显示角标。仍只在导航栏插槽内读取，避免整个外壳重组。 |
| `docs/FRONTEND_HANDOFF.md` | 增加本节 |

没有修改 `QuizViewModel`、`LearningStateHolder`、`Repository`、`Data.kt`、`FrontendModels.kt`、后端测试和构建配置。

### 已对接的接口

`vm.searchQuestions`、`vm.searchNotes`、`vm.todaySummaryState`、`vm.todaySummary`、`vm.retryTodaySummary`、`vm.currentQuestionSummary`。分类页已不再直接访问 Repository。

### 需要 GPT/Codex 新增或调整的接口

暂无。

### 测试结果

- `assembleDebug` 和 `lintDebug` 通过。
- 运行受影响的测试类：`NotesAndScoringUiTest` 8 项、`ReviewReminderTest` 4 项，以及后端契约测试 `FrontendQueryContractTest` 6 项，**共 18 项，全部通过**。没有重复运行完整套件。
- 试装包：`复习软件\EmbeddedQuiz-1.5.0-home-browse-debug.apk`，包名 `cn.embeddedquiz`，版本 1.5.0 / versionCode 6，签名证书 SHA-256 `2c73c905…b151`，与 1.5.0 一致，可以直接覆盖安装。版本号没有改，因为改版本需要动构建配置，等授权。

### 已知风险

- 分类页的搜索错误和重试界面、首页汇总的加载和错误状态，都没有 UI 测试覆盖，只经过编译检查。
- 首页和分类页还没在真机上看过。
- 构建脚本在任务结束后有时不会退出（Gradle 守护进程占着输出）。构建本身已经完成，不影响产物。这个问题没有处理，只记录在这里。

---

## 2026-10-24 · 首页与分类页重设计（基于 1.5.0，未提交）

### 本次修改的文件

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/cn/embeddedquiz/HomeScreen.kt` | 重写布局：日期与连续天数、今日目标卡片（环形进度和主按钮）、3 个统计卡、继续练习卡、2×2 练习方式入口 |
| `app/src/main/java/cn/embeddedquiz/BrowseScreen.kt` | 重写布局：页面标题、带图标的搜索框、筛选 Chip、结果数与开始按钮、新题目卡片、空状态、模拟考试设置卡片 |
| `app/src/main/java/cn/embeddedquiz/CommonComponents.kt` | 新增 `FilterMenuChip`（点开下拉菜单的筛选 Chip），其他组件不变 |
| `app/src/main/java/cn/embeddedquiz/AppIcons.kt` | 新增图标：Search、Play、Shuffle、ListOrder、Target、Book、Flame |
| `app/src/test/java/cn/embeddedquiz/NotesAndScoringUiTest.kt` | 只改 1 处断言：首页目标改用无障碍描述查找（见下文） |
| `docs/FRONTEND_HANDOFF.md` | 新建本文件 |

没有改动 Room、DAO、Repository、复习算法、WorkManager、DeepSeek、ViewModel、构建脚本和依赖。

### 交互变化

**首页**
- 今日目标改为环形进度，显示"还差 N 题完成"或"今日目标已完成"。环形的无障碍描述为"今日目标 X / Y 题"（UI 测试按它查找）。
- 主按钮：有到期题时显示"开始今日复习 · N 题到期"，行为与原来一致：`vm.start(vm.due(day), "到期复习")`。**没有到期题时改为"今日复习已清空 · 学习新题"，启动新题练习**；原来这时点按钮会提示"当前没有符合条件的题目"。
- 统计卡：未学新题、日常题库、编程专区（数字的算法与原来相同）。
- "继续上次练习"卡片显示模式和进度条；有未交卷考试时标题为"继续未交卷考试"。
- 练习方式：学习新题、随机练习、顺序练习、薄弱知识点，调用的函数与原来的按钮相同。
- 原首页底部的判分说明卡缩成一行灰色小字。

**分类页（同一组件也用于编程专区、错题收藏、模拟考试）**
- 每个分区显示自己的标题和副标题（题库 / 编程专区 / 错题与收藏 / 模拟考试）。
- 搜索框带搜索图标和清空按钮；"空格分隔多个关键词"的提示只在输入后出现。
- 5 个筛选项（分类、来源、公司、题型、原题难度）改为可横向滚动的 Chip，选中后高亮并显示所选值，点开菜单可选"全部"恢复。"规范分类"更名为"分类"并排在第一位，选项按名称排序。
- 错题页的"查看：错题 / 不会 / 收藏 / 待核对"从下拉菜单改为单选 Chip。
- 题目卡片：题型标签、公司、收藏星标，右上角显示"待核对"（琥珀色）或"已学"（绿色）；题干最多 3 行并高亮搜索词；匹配片段放在灰底块里；个人标签和难度感受用主色显示；底部一行是分类、来源、难度和是否已保存个人核对答案。
- 筛选结果为空时显示空状态提示。
- 模拟考试的题量和时长改为单选 Chip，放在"考试设置"卡片里，"开始考试"按钮通栏显示。

### 已对接的接口（沿用现有界面已在用的公开成员，未新增后端调用）

- `vm.questions`、`vm.p(id)`、`vm.due(day)`、`vm.queue`、`vm.index`、`vm.mode`、`vm.examFinished`、`vm.page`、`vm.busy`、`vm.notice`
- `vm.start(list, mode)`、`vm.startWeakPractice()`、`vm.startExam(list, count, minutes)`、`vm.editStickyNote(note)`、`vm.openStickyNotes()`
- `vm.learning.daily`、`vm.learning.settings.dailyGoal`、`vm.learning.learnedDays`、`vm.learning.filters`、`vm.learning.metadata`、`vm.learning.answers`
- `LearningDates.streak(...)`
- `vm.repo.search(...)`、`vm.repo.searchNotes(...)`：分类页原本就直接调用 Repository，本次保持不变，见下方请求 2。

### 需要 GPT/Codex 新增或调整的接口

1. **建立 `docs/API_CONTRACT.md`。** 仓库里还没有接口契约文档，前端目前只能把"现有界面已在调用的 ViewModel 公开成员"当作事实契约。请列出允许前端调用的状态和函数。
2. **ViewModel 层的搜索接口。** `Browse` 目前直接调用 `vm.repo.search(filter, programming, mistakes)` 和 `vm.repo.searchNotes(text)`，界面因此依赖 Repository。建议提供例如 `suspend fun searchQuestions(filter, page): List<QuestionSummary>` 和 `suspend fun searchNotes(text): List<StickyNote>`，或者直接提供带防抖的结果状态。
3. **首页汇总状态。** 首页每次重组都要在界面里重新统计未学新题数、到期列表、日常题数和编程题数（遍历全部题目）。建议 ViewModel 提供一个只读汇总，例如 `TodaySummary(dueCount, unseenCount, dailyCount, programmingCount)`，前端只负责展示。底部导航的到期角标目前在 `AppNavigation.kt` 里用 `derivedStateOf` 调用 `vm.due()`，同样可以改用这个汇总。
4. **（可选）当前练习的上下文。** "继续上次练习"卡片想显示当前题目的分类，但目前只拿得到题号和模式。如需显示，请暴露当前题目的 `QuestionSummary`。

### 尚未完成的前端工作

- 复习、统计、设置、便签、更多、待核对工作台，以及"开始刷题"入口页（刷题标签下、尚未开始练习时）都还是旧布局，只换了配色和组件样式。
- 从分类页进入时，底部导航高亮的是"更多"（沿用旧的映射）。设计稿里高亮的是"刷题"，是否调整待确认。
- 深色模式没有在真机上检查过。

### 测试结果与已知风险

- `assembleDebug` 编译通过。
- 只运行了受影响的测试：`NotesAndScoringUiTest`（8 项）和 `ReviewReminderTest`（4 项，会经过应用外壳和首页），**12 项全部通过**。按约定没有重复运行完整测试套件（45 项）。
- **分类页没有专门的 UI 测试**，筛选 Chip、空状态和考试设置卡片只经过了编译检查。
- **没有在真机或模拟器上看过**。Robolectric 没有中文字体，测试里测得的宽度不代表真实排版。超小屏或大字号下，首页"还差 N 题完成"和分类页右侧的两个按钮可能换行，需要真机确认。
- 首页无到期题时主按钮的行为变了（见上文），如果不希望这样，可以改回原来的"开始今日复习"。

### 本次未做

- 没有提交、推送 GitHub，也没有覆盖任何已有 APK（`复习软件\EmbeddedQuiz-1.5.0-debug.apk`、`EmbeddedQuiz-debug.apk` 都没动）。`app/build/outputs/apk/debug/app-debug.apk` 是这次构建生成的产物，仅供试装。
