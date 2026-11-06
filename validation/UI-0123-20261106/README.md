# UI-01～03 证据（Claude，2026-11-06）

任务单：`docs/ARCHITECT_UI_AI_PLAN_20261106.md` §4。基线 `0ecc6366`。

| 提交 | 范围 |
| --- | --- |
| `59755191` | A：UI-02 导航/底栏/遮挡 |
| `d5d95501` | B：UI-01 应用图标头像 + UI-03 Lucide 图标 |

## 文件

| 文件 | 内容 | 结果 |
| --- | --- | --- |
| `00-baseline-0ecc636-ai-isolated.log` | 未改动基线的独立 worktree，单跑 `AiConversationUiTest` | BUILD SUCCESSFUL |
| `01-full-suite-after-A-5975519.log` | A 的代码（提交前）全量 `testDebugUnitTest` | 277 项，7 项失败（见下） |
| `02-updated-classes-and-ai-isolated.log` | 更新后的 4 个类 + 单跑 `AiConversationUiTest` | 34 项全通过 |
| `02b-icons-more-pagechrome.log` | B 的定向回归：`AppIconsTest`、`PracticeEntriesUiTest`、`MoreUiStateTest`、`PageChromeUiTest` | 20 项全通过 |
| `03-integration-assemble-test-lint-d5d95501.log` | 干净 worktree（只含 d5d95501），`assembleDebug testDebugUnitTest lintDebug` | BUILD SUCCESSFUL |
| `03-class-results.csv`、`03-test-xml/` | 集成测试按类结果与原始 XML | 37 个类，275 项，0 失败，0 错误，0 跳过 |
| `lint-results-debug.xml` | 集成 lint | 0 错误，5 警告 |

## 01 中 7 项失败的处理

- 3 项由新行为引起（测试在更多子页上点底栏，而子页已不显示底栏）：`NotesAndScoringUiTest.bottomNavigationAndDarkModeAndGoalPersist`、`ExamSaveUiTest.notificationDuringLeaveWinsAndNoStaleDialogWhenTheFlushFails`、`StartupRecoveryUiTest.failureShowsTheErrorPageAndAnExplicitRetryKeepsNotificationAndExam`；另 `NotificationEntryUiTest` 的 2 项在此前定向运行中同因失败。已改为“先经顶栏返回到更多，再点底栏”，原有考试/通知/草稿断言全部保留。
- 4 项为 `AiConversationUiTest`（均为 `AppNotIdleException`）。**更正（UI-04A）**：早期全量出现了 4 项 AI 不空闲失败，最终集成通过，根因未证实。原先写的“与执行顺序有关、不是本次造成、与历史 4 个已知失败一致”没有证据支持，已撤回。单跑通过（00、02）和最终全量通过（03）只能说明这几次运行通过。固定输入复验见 `../UI-04A-20261106/README.md`。本轮没有修改 AI 代码。

## Lint 警告（都不在本轮改动中）

GradleDependency ×2（lifecycle 2.8.7，本轮不升级依赖）、`CommonComponents.kt:35` ModifierParameter（原有）、IconLauncherShape 与 MonochromeLauncherIcon（桌面启动图标原有资源，按任务单未改动）。

## 产物

`../EmbeddedQuiz-1.6.1-ui-20261106-debug.apk`（项目上一级目录），SHA256 `329277787390A115BAB20C18EAD73C2C41553FDBE2AA13A0C6483B907D1F6A95`，由 d5d95501 构建，沿用 debug.keystore；未改版本号/签名，未覆盖任何既有 APK，未发布。

## 限制

本机没有设备，也没有模拟器（`adb devices` 为空）。WebView 阅读器文末、手势/三键导航、放大字号、键盘真机表现，以及截图/录屏，都还**未在设备上验证**。Robolectric 测试（360×640dp）断言了边界与点击结果，但不能替代 WebView 真机验收。
