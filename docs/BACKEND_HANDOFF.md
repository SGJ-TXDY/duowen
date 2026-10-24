# 后端交接文档（BACKEND_HANDOFF）

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
