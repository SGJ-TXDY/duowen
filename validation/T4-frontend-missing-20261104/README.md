# T4 前端补修：缺题页与加载页的草稿离页等待（Claude）

日期：2026-11-04（Asia/Tokyo）。起始 HEAD：`bdf72bd3f850efc471b783a361b04392cd928acd`（GPT T4 统一集成证据；受测前端 db90bcc）。
待修来源：[T4 集成证据](../T4-integrated-20261104/README.md)“独立验收诊断：普通缺题页绕过草稿等待”及其诊断 `probe-source/T4MissingDraftProbeTest.kt`。
运行源码 = 该 HEAD + 本次改动，两个文件的 SHA-256 见 `source-sha256.txt`，与交付提交一致。隔离副本 `C:\AndroidProjects\EmbeddedQuiz-claude-iso`（自有 app/build，`--no-daemon`）。

## 根因与修复

`DraftLeave` 只挂在已加载题目的 `QuestionContent` 中。`QuestionScreen` 的 q==null 分支（加载中、缺题）没有草稿协调器：系统返回落到外壳的直接回首页，缺题页顶部返回走 ExamLeave，在非考试时直接放行。草稿按队列题号受理，与题目能否加载无关，因此可能在途或失败。

修复（仅 `QuestionScreen.kt`）：新增 `rememberPracticeDraftLeave(vm, id)`，非考试模式下按**当前队列题号**建立 DraftLeave，挂草稿离页对话框与系统返回处理；加载中分支和 `MissingQuestion` 都使用它。缺题页顶部返回在练习模式走草稿等待、在考试模式仍走 ExamLeave；等待时返回按钮禁用；缺题页显示该题的真实草稿状态（保存中/已保存/失败及重试）。异步结果沿用 page/practiceSession/index 核查，通知接管时丢弃旧导航和弹窗。考试协议、后端接口未改。

## 01 前后对照（新增三项缺题用例）

| 运行 | QuestionScreen.kt | 结果 |
| --- | --- | --- |
| 01a | HEAD 版本 | 3/3 失败，但失败点是夹具中“缺题页显示草稿保存中…”的新界面断言，早于离页断言，**不作为导航缺陷的复现** |
| 01b | 交付版本 | 3/3 通过 |

导航缺陷的复现以 GPT 的诊断为准：其 02 运行在 db90bcc 上 2/2 失败（期望 QUESTION，实得 HOME）。

## 02 GPT 诊断类原样在修复后运行

`02-gpt-probe-on-fixed-ui.log`、`-xml/`：把 `probe-source/T4MissingDraftProbeTest.kt`（SHA-256 见 `02-gpt-probe-on-fixed-ui.sha.txt`）原样复制到隔离副本，只运行其两项缺题用例：**2/2 通过**（系统返回等待在途草稿；保存失败时顶部返回留页并弹草稿错误对话框）。运行后已从隔离副本删除，不进入正式测试输入。

## 03 定向运行（交付版本，真实执行）

`03-targeted.log`（BUILD SUCCESSFUL，testDebugUnitTest 实际执行），XML `03-targeted-xml/`：13 个类 **98/98 通过**，0 失败/错误/跳过。CodeDraftSaveUiTest 15（原 12 + 新增 3），NotesAndScoringUiTest 8、QuickNotesIsolationTest 4、ExamSaveUiTest 9、ExamTerminationUiTest 12、PracticeEntriesUiTest 4、QuickNotesUiTest 5、NotificationEntryUiTest 5、ReviewReminderTest 4、StartupRecoveryUiTest 8、StartupTest 1，GPT 的 CodeDraftPersistenceTest 11、CodeDraftBoundaryTest 12。

新增正式用例（CodeDraftSaveUiTest）：

1. `missingQuestionSystemBackWaitsForTheAcceptedDraft`：缺题页显示“草稿保存中…”；系统返回留在题目页、顶部返回禁用；放行写入后回首页，状态已保存，数据库为最新文字。
2. `missingQuestionTopBackStaysOnFailureAndLeavesAfterAnExplicitRetry`：失败提示可见；顶部返回弹“草稿保存失败，暂未离开”，留在本页且草稿保留；系统返回同样被拦下，对话框“重试保存”成功后回首页、数据库为最新文字。
3. `missingQuestionLeaveYieldsToANotificationThatArrivesWhileWaiting`：等待离开时通知设为复习页，写入在下一帧前返回，仍为复习页、无旧对话框。

## 未覆盖 / 未验证

- **加载中分支**与缺题页使用同一个 `rememberPracticeDraftLeave`，但加载过程没有可控接缝（题目读取在 IO 中立即完成），没有单独的正式用例；只经代码审查。
- 未运行完整套件（由 GPT 在共同版本统一运行）；无真机验证。
