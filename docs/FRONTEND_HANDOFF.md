# 前端交接文档（FRONTEND_HANDOFF）

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
