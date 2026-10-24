# 前端交接文档（FRONTEND_HANDOFF）

前端（Claude）负责 Jetpack Compose 界面；后端（GPT/Codex）负责数据库、复习算法、后台任务、网络和 ViewModel 业务逻辑。本文件记录每次前端改动和需要后端处理的事项，最新一次放在最上面。

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
