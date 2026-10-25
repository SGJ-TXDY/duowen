# 后端交接文档（BACKEND_HANDOFF）

## 2026-10-25（第二轮）· 修复参考解法的过期快照覆盖风险（Codex）

### 本轮范围与现状核查

依照 `gpt.docx` 与用户本轮限制，只修改后端、相关业务测试和本交接 / 接口文档；不提交、不推送 GitHub，不修改 Compose 页面、UI 测试、版本号、签名、构建配置，不覆盖已有 APK。

用户描述的工作台 `PersonalMarksEditor` 已在当前工作区被 Claude 移除：`AnswerWorkbench.kt` 现在调用 `PerceivedDifficultyChips`，`PersonalEditors.kt` 只保留难度控件和参考解法编辑器。`FRONTEND_HANDOFF.md` 最新节也记录了这一变化。**本轮保留这些未提交前端工作，没有重复修改。**

仍存在的跨层问题：`VerifiedSolutionEditor` 的保存回调继续调用 `vm.saveMetadata(meta.copy(verifiedCode=code, codeVerified=verified))`。复现：编辑器读取旧 meta → 另一处更新难度 → 点击保存解法 → 整行替换将旧难度写回。分类卡片仍将旧 `meta.tags` 显示为“标签：…”，与全局独立标签口径容易混淆。

### 后端已完成

- 新增 `vm.saveVerifiedSolution(id, code, verified)` 及 Repository 同名 suspend 接口。Room 事务内读取最新个人资料，只更新解法和核对状态，保留难度与旧标签；不存在资料时按默认值建立，题目不存在或校验失败时不写入。
- 难度继续使用已有 `savePerceivedDifficulty`；两个 Repository 增量接口事务并发互不覆盖。ViewModel 依旧受全局 busy 控制，忙时新命令会被忽略，不提供写入队列承诺。
- 旧 `saveMetadata` 的签名、校验、通知和整行替换语义保留；源码补充遗留接口提示，**未单方面改为忽略 tags 或强制合并**。
- 新接口详细输入、Unit / busy / notice、异常、状态变化和兼容约定均已写入 `API_CONTRACT.md` 的“第二轮”增补。

### 旧题目 tags 决策与依赖审计

保留为**只读历史兼容数据**，新产品入口停止编辑；不删列、不清空、不迁移成全局标签。旧标签与全局便签原本是不同数据，保留能避免旧库和旧备份的信息丢失。只读属于新入口政策：旧 saveMetadata 与备份恢复仍可写入合法历史值，保证调用与数据兼容。

- 搜索：继续查旧 `QuestionPersonal.tags`，保留匹配片段及既有搜索结果；契约把“个人标签”改称“旧版题目标记”。全局标签 / 便签仍独立使用 `searchNotes`，不按题目过滤。
- 分类卡片：当前 `BrowseScreen.kt` 生成 `"标签：$tags"` 的历史字段仍显示，交由 Claude 删除这一分支；保留难度感受。不要为隐藏展示去清空数据库，也不要从摘要中伪造全局标签关联。旧标记仍可在搜索片段中出现，以保留历史检索能力。
- 全量备份：备份 3 原样保留 `questionPersonal[].tags` 与解法 / 难度，独立便签在 `stickyNotes`；旧备份 1/2 的既有恢复规则不变。独立个人答案文件仍只导入 / 导出核对答案。
- LearningCodec：保持 tags 字符串原样读写和原有有效性校验，不增加“tags 必须为空”的限制。已核对却空白的解法仍拒绝，无破坏性放宽。
- 数据库版本 4、备份格式 3、Room 导出 schema 保持不变。

### Claude 需要接入（本轮没有修改这些文件）

1. `PersonalEditors.kt` 的 `VerifiedSolutionEditor`：将唯一保存回调替换为 `vm.saveVerifiedSolution(q.id, code, verified)`。该组件同时用于编程区和待核对工作台，统一替换后两处都受益；保持忙时禁用、人工核对含义和本地编辑状态。不要再从 meta 快照整行写回。
2. `BrowseScreen.kt` 的卡片 `personal = listOfNotNull(...)`：删除读取 `it.tags` 并拼接“标签：…”的分支，保留 `it.perceivedDifficulty`。题目级标签不再展示为当前产品字段。搜索匹配片段仍可能含历史标签，这一兼容口径已在契约说明。
3. 工作台现在已是 `PerceivedDifficultyChips`，保持现状；不要恢复 PersonalMarksEditor 或题目标签输入。全局标签继续走 `openStickyNotes`，不增加题号绑定。
4. 完成接入后，针对“保存解法不回退刚修改的难度”、卡片不展示题目标签补充前端验收，并更新前端交接记录。**在该回调尚未替换前，旧页面仍有快照覆盖风险，本轮后端接口不会自动替换旧调用。**

### 测试与保护检查

只执行一次相关业务测试：`testDebugUnitTest --tests cn.embeddedquiz.PersonalMarksContractTest --tests cn.embeddedquiz.FrontendQueryContractTest --tests cn.embeddedquiz.LearningDataTest --offline`，**27 项通过、0 失败、0 跳过**（10 + 10 + 7），新增 8 项。不运行 UI 测试或完整套件，不执行 assemble / APK 打包或签名任务。

覆盖：仅改解法保留最新难度、旧标签和每题笔记；并发解法 / 难度写入；未核对草稿与清空；已核对空白解法、缺题和空题号失败回滚；数据库 4 与备份 3 的旧数据往返、备份 1/2 兼容；旧标签与全局便签搜索独立；ViewModel 状态 / notice、busy 忽略规则和旧 saveMetadata 的整行替换兼容。

验证实际测试源码与当前工作区一致，Room schema 4 导出文件未变化；以开发前指纹核对，只修改本轮预期的 6 个既有文件（Repository、ViewModel、两个业务测试类及两个文档）。Compose 页面、已有及未提交 UI 测试、前端交接、版本 / 构建配置、既有 APK / 源码 ZIP / gpt.docx 均未变；Git HEAD 与 index 指纹未变，没有提交、暂存或推送。原始日志、JUnit XML 和检查汇总见 `validation/backend-2026-10-25-verified-solution/summary.json`。

署名：Codex（OpenAI）。

---

## 2026-10-25 · 新图标、练习入口调整与答题页精简（Codex）

### 用户要求与责任边界

本轮依据用户提供的 `gpt.docx` 分工：Codex 完成业务接口、数据可靠性、图标素材与启动资源；Compose 页面、布局和展示交互由 Claude 实现。用户本轮已授权测试后提交 GitHub。**以下页面改动尚待 Claude 接入，不能把本轮 APK 当作全部界面需求已完成的版本。**

用户补充确认：**标签是全局独立内容，不属于某一道题；笔记属于每一道题。** 截图顶部纸张图标在代码中实际调用 `vm.openStickyNotes()`，打开全局独立便签；这是用户所指的全局标签入口。本轮沿用该全局独立能力，不新增题目与标签的绑定。旧版 `QuestionPersonal.tags` 只是已有的题目级字段，保留旧数据和兼容能力，不能误当成此次全局标签的实现。

### Codex 已完成

- 生成原创日式动漫女性头像并接入 Android 启动图标，包含 432 px 前景、适配所有支持版本（最低 Android 26）的自适应图标与圆形图标引用。原图：`assets/branding/launcher-anime-source.png`；生成方式、提示词和再生成资源命令见同目录 `README.md`。
- 增量新增 `vm.savePerceivedDifficulty(id, difficulty)`，按数据库最新记录只保存当前题的难度感受，保留旧题目标签、已核对解法和核对标记。对应 Repository 操作使用 Room 事务；无表结构变更。
- 验证首页分类 / 编程专区 / 考试目标页面已有业务支持，无需创建第二套题库、考试状态或全局标签存储。
- 补充业务回归：难度设置 / 清空、无效输入、缺失题号、并发题目笔记与难度写入、旧数据 / 备份格式兼容、ViewModel 发布状态，以及移动入口后练习草稿与考试截止时间保留。

### Claude 实施清单（需要修改 Compose，Codex 本轮未改）

#### 1. 首页“练习方式”增加两个入口

位置：`HomeScreen.kt` 的练习方式区。保留学习新题、随机练习、顺序练习、薄弱知识点，新增：

| 展示 | 调用 | 预期 |
| --- | --- | --- |
| 编程专区 | `vm.navigate(Page.PROGRAMMING)` | 打开现有编程题筛选页，不混入日常复习 |
| 分类练习 | `vm.navigate(Page.BROWSE)` | 打开现有分类筛选页，保留 `learning.filters[Page.BROWSE]` |

布局由 Claude 按现有视觉系统处理，建议沿用双列卡片。统计区的编程题数量继续读取首页汇总，不用新建计数逻辑。

#### 2. 底部“刷题”改为“模拟考试”

位置：`AppNavigation.kt` 的 `bottom` 列表。最终五项为：**首页、模拟考试、复习、错题、更多**。

- 第二项目标改为 `Page.EXAM`，标签“模拟考试”，选择适当现有图标；不要把 `Page.QUESTION` 枚举改名或改为考试页。
- `Page.QUESTION` 继续作为所有练习和考试的内部答题页面，保留已有聚焦答题行为和首页“继续上次练习”。
- `MoreScreen` 中删除 `Page.BROWSE`、`Page.PROGRAMMING`、`Page.EXAM` 三个重复入口；保留全局便签 / 标签、工作台、统计、费用与设置。
- 修改选中项映射：分类和编程页归入“首页”；考试设置页选中“模拟考试”。从分类、编程、考试设置页系统返回时回首页，不能继续回已移除相关入口的“更多”。
- 复习角标继续使用当天 `todaySummary`；不修改考试计时、判分和 SRS。
- 用户有未交卷考试时，“模拟考试”进入现有考试设置 / 继续机制；不得重新开始而覆盖队列、答案、截止时间。

#### 3. 答题页下方只保留一个“笔记与难度感受”区

位置：`QuestionScreen.kt` 当前两个 `ExpandableSection`（“我的笔记”“标签与难度感受”），以及 `PersonalEditors.kt` 中的 `PersonalMarksEditor`。

- 合并为一个展开区，标题“笔记与难度感受”，包含当前题笔记输入和难度感受（未设置 / 易 / 中 / 难）。删除下方独立“标签与难度感受”区及其题目标签输入。
- 笔记继续读 `vm.questionDraft(q).notes`，沿用 `vm.saveNotes(q.id, code, notes)` 和现有代码草稿自动保存语义；切题时不能把上一题笔记带入下一题。
- 难度从 `vm.learning.metadata[q.id]?.perceivedDifficulty.orEmpty()` 读取，保存使用新增 `vm.savePerceivedDifficulty(q.id, value)`；“未设置”传空字符串。不要从过期 `meta.copy(...)` 写回整个对象。
- 笔记和难度仍是不同持久化操作，合并展示不表示原子保存。若用两个保存动作，应各自受 `vm.busy` 控制；不得在一个点击回调中连续调用两个 `launchTask` 命令（第二个可能因 busy 被忽略）。如设计成单个“保存全部”，需先向后端请求统一事务接口，不得仅连续调用假装一起保存。
- 考试作答中保持已有个人编辑隐藏规则；完成后的查看行为保持原有判分与查看逻辑。

#### 4. 顶部保留全局独立标签入口

位置：`QuestionScreen.kt` 的 `QuestionTopBar`，现有纸张图标 `onNotes = vm::openStickyNotes`，语义描述当前是“打开便签”。

- 保留顶部入口并继续调用现有全局独立便签能力；根据用户“全局标签”的叫法统一可见文案 / 无障碍描述，避免把它做成当前题的标签编辑框。
- 下方合并区不再重复显示全局标签入口，也不提供题目级标签输入。
- `openStickyNotes/leaveStickyNotes` 已能恢复来源页，且不修改练习队列、未保存题目草稿、考试答案和截止时间。全局列表不应按当前 `q.id` 过滤。
- `PersonalMarksEditor` 可拆成仅难度控件供合并区复用；核对 `AnswerWorkbench.kt` 的旧编辑器调用点，避免误删其他已存在能力。旧题目级标签的删除 / 迁移不在本轮范围，历史备份保持可读。

### 前端验收步骤

1. 首页练习方式出现分类练习和编程专区，分别进入正确题库，返回首页；“更多”不再有分类、编程专区、模拟考试。
2. 底部只出现要求的五项；模拟考试能配置、开始、继续和交卷；切页、回首页再进入不重置倒计时。
3. 一道题设置笔记和难度，切另一道题显示其自己的内容；返回后保存值正确，重启后仍在。
4. 答题页只有一个“笔记与难度感受”区；无下方重复标签区。顶部打开同一份全局标签 / 便签，换题后列表相同；从中返回恢复原题和草稿。
5. 检查浅色 / 深色、小屏、大字号、导航返回和 TalkBack；检查手机桌面的圆形 / 圆角新图标。这些视觉与真机检查本轮未完成。
6. Claude 完成后补充相关 UI 测试与 `FRONTEND_HANDOFF.md`，再构建包含全部页面变更的试装包。

### 接口、兼容及测试

完整契约见 `API_CONTRACT.md` 的 2026-10-25 增补。旧 `saveMetadata/saveNotes` 和所有页面枚举继续存在；数据库版本仍为 4，备份格式仍为 3。未改签名、版本号、权限、题库、Compose 页面和已有 UI 测试；不覆盖历史稳定 APK。

- `assembleDebug` 通过；`lintDebug` 通过（0 个错误、5 个警告，详情见 `validation/backend-2026-10-25/lint-results-debug.xml`）。
- 相关业务测试共 **24 项通过、0 失败、0 跳过**：DomainTest 5、LearningDataTest 7、FrontendQueryContractTest 8、PersonalMarksContractTest 4。其中新增 6 项。初次新增测试数据遗漏必填 `source` 导致 4 项失败，修正后仅重跑这 4 项并通过；先前已通过的 20 项结果保留，未重复运行完整套件。
- SHA-256 指纹核对 40 个受保护文件与稳定产物均未改变；实际构建的源码与项目最终源码一致。
- 独立试装包：项目上级目录 `EmbeddedQuiz-1.5.0-icon-backend-20261025-debug.apk`，版本仍为 1.5.0 / 6。签名 SHA-256 为 `2c73c905e6d127dd92fbfaf5351058572cb8e78e98beb477eb1a0f37ee43b151`，与现有 home-browse 版本一致。
- 该试装包包含新图标和后端难度接口，**首页、导航和答题页仍等待 Claude 按上文清单修改**；没有运行 UI 测试或真机安装验收。
- 原始构建日志、最终成功的 JUnit XML、静态检查、签名与汇总见 `validation/backend-2026-10-25/summary.json`。图标缩放和圆形裁切经过本地图片预览检查；桌面真实效果仍需设备确认。Android 13+ 的单色主题图标暂未提供，系统仍可显示彩色自适应图标；剩余图标 Lint 提示包含单色图标缺失和前景位图铺满；自适应蒙版已本地预览，仍须真机确认。

署名：**Codex（OpenAI）**，2026-10-25。

---

## 2026-10-24 · 前端查询边界与首页业务汇总

依据 `FRONTEND_HANDOFF.md` 中 2026-10-24 的四项后端请求开发。本轮只修改业务、DAO、Repository、ViewModel、单元测试和文档；不改 Claude 的页面、配色、组件、交互或现有 UI 测试。

### 已完成

1. 建立 `API_CONTRACT.md`，梳理既有状态和命令，补齐输入、返回、异步、异常、状态变化及兼容规则。
2. 新增 ViewModel suspend 搜索入口，内部切 IO，保留 SQL 筛选、AND 搜索、命中片段和独立便签搜索口径。原 Repository 接口保留。
3. 新增首页只读汇总，四项数量由同一条 SQL 计算，并随题库和进度更新；包含加载、失败和重试状态，沿用本地日期刷新入口。
4. 新增当前练习题摘要，便签往返时仍可读取刚才题目的规范分类。

### 新增接口和调用方式

完整类型及边界以 `API_CONTRACT.md` 为准：

| 入口 | 前端用法 |
| --- | --- |
| `vm.searchQuestions(filter, page)` | 分类页的协程中替换 `vm.repo.search(...)`，传当前 `Page`，保留现有 160ms 防抖 |
| `vm.searchNotes(text)` | 替换 `vm.repo.searchNotes(text)`，搜索为空时仍跳过便签查询 |
| `vm.todaySummaryState` / `vm.todaySummary` | 读取加载 / 错误 / 成功状态；成功时读 `dueCount/unseenCount/dailyCount/programmingCount` |
| `vm.retryTodaySummary()` | 首页汇总失败后的重试按钮；正常情况下不需要调用 |
| `vm.currentQuestionSummary` | “继续练习”卡读取 `?.normalizedCategory`，null 时省略分类 |

现有 `learning.refreshDate(today)` 已由应用外壳每秒调用；本轮无需增设计时循环。汇总的 `day` 是本地 epochDay，前端接入时应与正在展示的日期比对。四项计数不替代开始练习所需题目列表：点击到期复习仍可调用 `vm.due(day)`，点击学习新题仍可临时筛选未学题。

### 需要 Claude 接入的前端改动（本轮未修改）

- `BrowseScreen.kt` 的查询 `LaunchedEffect`：换用两个 ViewModel 搜索函数。查询取消应重新抛出 `CancellationException`；真实错误提供重试并恢复 searching，避免无限进度条。当前界面没有专门的错误状态，这是原有代码的边界风险，并非本轮已修复。
- 同一 effect 目前监听 `filter/questions/progress/learning.metadata`，独立便签变化未作为 key。复现：打开含便签命中的搜索 → 进入便签修改标题 / 正文使其不匹配 → 返回。若组合仍保留，结果可能停留旧列表。建议把 `vm.stickyNotes` 加入 effect key，或在返回分类页时重新查询；后端查询每次都返回已落盘的当前数据。
- `HomeScreen.kt`：成功后使用汇总显示四项数量，避免每次重组遍历；加载和失败时不要把 null 直接显示为零。今日目标、连续天数继续读现有 `learning` 状态，不变更口径。
- `AppNavigation.kt`：到期角标可读汇总 `dueCount`，替代 `derivedStateOf { vm.due(...).size }`。加载 / 失败是否隐藏角标由 Claude 决定。
- `HomeScreen.kt` 的继续练习卡可显示 `currentQuestionSummary?.normalizedCategory`。
- 分类页底部导航高亮、其余页面重设计、深色真机视觉检查仍属于前端工作，本轮没有改变。

### 数据与兼容

- 数据库仍为 4，不新增表或列，Room 查询新增不要求迁移；备份格式仍为 3，旧版 1、2 的恢复逻辑未改。
- 所有旧接口继续存在，现有前端可以暂不接入新接口，行为保持原样。
- 汇总排除编程题和悬空进度；未学是无记录或 `seen=0`；到期是已学且到期日不晚于当天。闰日 / 跨月 / 跨年通过日期参数计算，不按固定 24 小时累加。
- 没有修改题库、签名、版本号、权限、依赖；没有提交或推送 GitHub，没有覆盖稳定 APK 或发布源码包。

### 验证记录

开发前相关基线 `DomainTest`（5 项）和 `LearningDataTest`（7 项）共 12 项通过。新增 `FrontendQueryContractTest` 覆盖空题库、计数口径、悬空进度、闰日 / 跨月 / 跨年、数据库变化订阅、搜索门面与旧查询一致性、非法页面、取消传播、当前题上下文和便签往返。

最终离线执行 `testDebugUnitTest`，只选择下列相关业务测试类，Kotlin 与 Room 查询编译通过：

| 测试类 | 通过 | 失败 / 跳过 |
| --- | --- | --- |
| `DomainTest` | 5 | 0 / 0 |
| `LearningDataTest` | 7 | 0 / 0 |
| `FrontendQueryContractTest`（新增） | 6 | 0 / 0 |
| 合计 | **18** | **0 / 0** |

原始基线、最终构建日志、JUnit XML、开发前文件指纹和汇总保存在 `validation/backend-2026-10-24/`，汇总入口为 `summary.json`。本轮基线和最终各执行一次，没有重复完整套件；未运行 UI 测试、Lint、真机安装或排版验收，也未生成新版发布包。

以开发开始时的 SHA-256 为准核对：Claude 的前端文件、已有 UI 测试、`FRONTEND_HANDOFF.md`、构建配置、两个稳定 APK 和发布源码 ZIP 都保持不变。原有未提交的前端改动完整保留；本轮后端及文档也留在工作区供审阅，没有混合提交或推送。
