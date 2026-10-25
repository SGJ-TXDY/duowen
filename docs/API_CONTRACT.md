# Android 业务接口契约

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
