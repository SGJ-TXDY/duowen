# 前端交接文档（FRONTEND_HANDOFF）

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
