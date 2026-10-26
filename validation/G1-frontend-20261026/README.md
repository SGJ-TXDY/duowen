# G1 前端：通知入口接入、Activity 测试 Compose 收尾、通知与恢复交互测试（Claude）

日期：2026-10-26/27（Asia/Tokyo）。起始 HEAD：`d21c381cf4b62cbdfc7c2ebf20700cd5de03cc61`（GPT G1 后端）。
运行源码 = 该 HEAD + 本次未提交改动，四个文件的 SHA-256 见 `source-sha256.txt`，与交付提交一致。

隔离：按 REPAIR_DISPATCH 11.2，在独立 ASCII 副本 `C:\AndroidProjects\EmbeddedQuiz-claude-iso`（robocopy 自本仓库，排除 build/.gradle/.git/validation）中构建和测试，使用该副本自己的 `app/build`；`--no-daemon`。

## 01 定向运行（真实执行）

命令见 `01-targeted.log`（BUILD SUCCESSFUL，testDebugUnitTest 实际执行）；XML `01-targeted-xml/`：

| 测试类 | 项数 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| NotificationEntryUiTest（新增） | 5 | 0 | 0 | 0 |
| ReviewReminderTest（含 G1 用例） | 4 | 0 | 0 | 0 |
| InitializationNotificationTest（GPT，未改） | 12 | 0 | 0 | 0 |
| ExamTerminationUiTest | 12 | 0 | 0 | 0 |
| PracticeEntriesUiTest | 4 | 0 | 0 | 0 |
| NotesAndScoringUiTest | 8 | 0 | 0 | 0 |
| QuickNotesUiTest | 5 | 0 | 0 | 0 |
| 合计 | 50 | 0 | 0 | 0 |

## 02 固定顺序对照：G1 Activity 测试 → 下一个 Compose UI 测试

用仅存在于隔离副本的有序 Suite（源码 `G1ThenUiOrderProbeTest.kt.txt`，运行后已删除，不进入正式源码）固定执行
`ReviewReminderTest.notificationReviewEntryWinsAfterUnfinishedExamIsRestored` → `PracticeEntriesUiTest.homeEntriesOpenBanksAndTabBarOffersExam`。其他源码均为交付版本，只替换 ReviewReminderTest：

| 运行 | ReviewReminderTest | G1 用例 | 随后的 UI 用例 |
| --- | --- | --- | --- |
| 02a | HEAD 版本（原收尾） | 通过 | **失败**：`AppNotIdleException: Compose did not get idle … in 60 SECONDS`（60.08s） |
| 02b | 交付版本 | 通过 | 通过（1.44s） |

日志 `02a-*.log`、`02b-*.log` 记录所用 ReviewReminderTest 的 SHA-256；XML 在对应 `-xml/` 目录。02a 重现了 GPT 隔离调查中的失败路径，02b 在同一顺序下消除该失败。

## 定位过程（开发中的失败运行，不计为证据）

1. 首次定向运行：新类 4/5 失败、G1 收尾断言“仍有 1 个 Recomposer”。探针（只在隔离副本临时加打印，已被同步覆盖）显示：
   - 从同一 JVM 的第二个 Activity 测试起，`vm.getApplication() !== ApplicationProvider` 的 Application，ViewModel 读取的 SharedPreferences 不是测试写入的那份；`AndroidViewModelFactory.getInstance()` 的静态 `_instance` 缓存了第一个测试的 Application（javap 确认 lifecycle-viewmodel 2.8.7 字段名）。生产环境一个进程只有一个 Application，不受影响。
   - G1 单独运行时 Activity 的 Recomposer 在第一轮即退出；排在其他 Compose 类之后时，它保持 `hasPendingWork=true` 而主 Looper 完全空闲，等待无法使其结束。说明问题是窗口 Recomposer 绑定了早前测试遗留的 UI 调度器，单纯等待不足以修复。
2. 修复（仅测试代码）：Activity 测试前重置该静态工厂；G1 所在类加 `createEmptyComposeRule()`，使 Activity 窗口使用规则的重组器，由规则负责关闭；销毁后仍检查本 Activity 新增的 Recomposer 为 0（不取消、不等待其他实例，不要求全局集合为空）。
3. 另有两处新测试自身问题：`recreate()` 后需 `visible()` 才有 Compose 根；首页“继续未交卷考试”卡片需滚动到可见。

## 未做 / 未验证

- 按 11.3 未自行运行完整套件，由 GPT 在集成版本统一运行。
- 无真机：真实通知点击、冷启动/热启动、进程被杀后恢复、TalkBack。
- 初始化失败时（`Failed`）仍停留在加载页，没有错误页（属于 T3，未开展）。
