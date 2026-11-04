# T3 前端：启动错误页、诊断/导出、重试与备份恢复确认（Claude，REPAIR_DISPATCH §16.3）

日期：2026-11-04（Asia/Tokyo）。起始 HEAD：`7a3724375eb95103cb5d5276323773ef5523299c`（GPT T3 后端：c21b86f、25b3142、7a37243）。
运行源码 = 该 HEAD + 本次未提交改动，三个文件的 SHA-256 见 `source-sha256.txt`，与交付提交一致。
隔离：独立 ASCII 副本 `C:\AndroidProjects\EmbeddedQuiz-claude-iso`，自有 `app/build`，`--no-daemon`。

## 01 定向运行（交付版本，真实执行）

`01-targeted.log`（BUILD SUCCESSFUL，testDebugUnitTest 实际执行），XML `01-targeted-xml/`：11 个类 **82/82 通过**，0 失败/错误/跳过。

| 测试类 | 项数 |
| --- | --- |
| StartupRecoveryUiTest（新增） | 8 |
| StartupTest | 1 |
| NotificationEntryUiTest | 5 |
| ReviewReminderTest | 4 |
| ExamSaveUiTest | 9 |
| ExamTerminationUiTest | 12 |
| PracticeEntriesUiTest | 4 |
| NotesAndScoringUiTest | 8 |
| QuickNotesUiTest | 5 |
| StartupRecoveryTest（GPT，未改） | 12 |
| InitializationNotificationTest（GPT，未改） | 14 |

StartupRecoveryUiTest 使用真实 ViewModel、后端 internal 接缝 `beforeStartupStage`（在 BANK 阶段抛错或挂起）和真实损坏的数据库文件；文件选择通过测试自有的 `ActivityResultRegistry` 返回指定 Uri，生产代码无测试钩子。覆盖：

1. 初始化故障显示错误页（标题为 heading、说明带 liveRegion、错误代码/阶段/次数），不是无限加载；无底部导航、业务页面或考试计时。查看/收起诊断，诊断内容不含异常原文。故障解除后显式重试：Loading 时没有重试按钮、连续调用不增加尝试；Ready 后通知仍打开复习页，考试队列/答案/截止时间保持，学习记录不变。
2. 重试再次失败：显示第 2 次尝试的错误，数据不变。
3. 导出提示“包含题库、学习记录与考试缓存……不能作为学习备份直接导入”；取消选择不调用导出；导出成功显示并生成含 manifest 的 ZIP；目标不可写时显示失败与“目标文件可能不完整”，导出按钮仍可用；只发起用户自己的选择。
4. 恢复：取消选择无对话框；对话框“取消”不调用恢复；确认后替换提交并跟随新一次启动（此处新启动仍失败，显示第 2 次错误，不显示“启动成功”/“备份已写入”），数据为备份内容；之后重试到 Ready。
5. 有考试快照时后端拒绝恢复，页面显示“请先结束当前考试，再恢复备份”，仍是同一次错误，数据与考试快照不变。
6. 恢复读取被挂起期间：显示“正在处理，请稍候…”，重试、导出、选择备份均禁用，连续重试无效；放行后进入下一次启动。
7. 数据库文件损坏（DATABASE_UNAVAILABLE、canRestoreBackup=false）：说明“数据库尚不可安全使用，不能恢复备份……”，无恢复入口，仍可导出（原文件字节不变）和重试；还原文件后重试到 Ready，数据不变。
8. 320×480dp、深色、字体缩放 1.6：标题、说明、重试、诊断内容、导出、选择备份均可滚动到并显示、可点击。

## 00 首次证据运行失败与定位（不计为通过证据，如实保留）

`00-targeted-gated-date-loop-failed.log`、`00-…-xml/`：同一命令，NotificationEntryUiTest 5 项中 4 项、StartupRecoveryUiTest 8 项全部 `AppNotIdleException`（每项约 60s）。StartupRecoveryUiTest 单独运行时为 8/8，说明问题与运行顺序/前序测试有关。

二分对照（临时只在隔离副本中改动，均已被同步覆盖）：

| 对照 | AppNavigation | NotificationEntryUiTest 单独运行 |
| --- | --- | --- |
| e1 | 交付前版本（日期刷新循环改为仅 Ready 后运行） | 1/5，首项通过、后 4 项 60s 超时 |
| e2 | HEAD 原版（无启动错误页） | 5/5 |
| e4 | 交付前版本，仅把日期刷新循环恢复为原来的 `LaunchedEffect(Unit) { while (true) … }` | 5/5 |

（e3 为一次替换未生效的重复运行，结果同 e1，不计。）

结论：唯一致因是把日期刷新循环改为 Ready 门控。`learning.refreshDate` 只设置一个内存中的日期值，Ready 前没有收集者，不访问数据库、不执行业务动作，因此交付版本保留原来的不门控写法。**为什么门控会使同一进程中后续 Activity 测试永远无法空闲，尚未查明**；初始值已是当天日期，查询本身不因此改变。记录为待 GPT/Architect 关注的观察项，不宣称已理解根因。

## 未做 / 未验证

- 按 §16.4 未运行完整套件（由 GPT 集成后统一运行一次）。
- 无真机：真实文件选择器、真实损坏存储、TalkBack 实际朗读、系统大字号（测试用 LocalDensity 覆盖字体缩放，深色用 `-night` 限定符）。
- Robolectric 无中文字体，文字宽度不代表真实排版。
