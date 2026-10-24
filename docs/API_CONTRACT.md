# Android 业务接口契约

更新日期：2026-10-24。适用于当前 1.5.0 工作区。本文件约定前端可调用的业务入口；后端负责实现与兼容，Claude 负责页面和展示交互。本次接口均为增量新增，不删除或改变已使用的成员。

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

搜索覆盖题干、选项、答案、解析、参考代码、题目笔记、代码草稿和个人标签。独立便签另查 `searchNotes`。`不会` 要求当前仍错且最近一次评估为 `UNKNOWN`；待核对沿用题库标记或用户标记的现有规则。

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
