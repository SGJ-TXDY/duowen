# T1 前端接入验证证据（Claude）

日期：2026-10-26（Asia/Tokyo）。起始 HEAD：`3628e8816bbfe07445c3c78e7161942b4df81973`（T1 后端基线）。
运行时源码为该 HEAD + T1 前端未提交改动；四个改动文件的 SHA-256 见 `source-sha256.txt`，与交付提交中的文件一致。

环境：`tools/build.ps1`（JDK 17.0.16、Gradle 8.9 离线、ASCII 联接 `C:\AndroidProjects\EmbeddedQuiz` → 本仓库）。
每次运行前确认没有 Gradle 测试 worker 在运行（只有空闲的 Gradle daemon 与 Kotlin daemon），运行前删除旧 XML，运行后立即复制到本目录，未与 GPT 的测试同时执行。

## 01 定向运行（交付要求的测试）

命令：`tools\build.ps1 testDebugUnitTest --tests cn.embeddedquiz.ExamTerminationUiTest --tests cn.embeddedquiz.NotesAndScoringUiTest --tests cn.embeddedquiz.PracticeEntriesUiTest --tests cn.embeddedquiz.QuickNotesUiTest --tests cn.embeddedquiz.ExamStateHolderTest --tests cn.embeddedquiz.ExamLifecycleContractTest --rerun`

日志 `01-targeted.log`（`testDebugUnitTest` 真实执行，非 UP-TO-DATE），XML `01-targeted-xml/`：

| 测试类 | 项数 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| ExamTerminationUiTest（新增） | 11 | 0 | 0 | 0 |
| NotesAndScoringUiTest | 8 | 0 | 0 | 0 |
| PracticeEntriesUiTest | 4 | 0 | 0 | 0 |
| QuickNotesUiTest | 5 | 0 | 0 | 0 |
| ExamLifecycleContractTest（后端，未改） | 20 | 0 | 0 | 0 |
| ExamStateHolderTest（后端，未改） | 8 | 0 | 0 | 0 |
| 合计 | 56 | 0 | 0 | 0 |

## 02 完整单元测试 + Lint（一次运行，未拼接）

命令：`tools\build.ps1 testDebugUnitTest --rerun lintDebug --continue`；日志 `02-full-and-lint.log`（12:53:43–12:56:02），XML `02-full-xml/`（20 个类），Lint `02-lint-results-debug.xml`。

- 测试：**143 项，142 通过，1 失败**，0 错误，0 跳过。失败项为 `ReviewReminderTest.notificationReviewEntryWinsAfterUnfinishedExamIsRestored`（expected REVIEW, was QUESTION），与基线已记录的 G1 失败相同。**因此这不是全绿运行。**
- 本次未复现 GPT 运行 05 中的 9 项 Compose 超时；这只是一次观测，不能作为 9.3 调查的结论或闭环证据。
- Lint：0 错误，5 警告（GradleDependency ×2、ModifierParameter `CommonComponents.kt`、IconLauncherShape、MonochromeLauncherIcon），与基线相同，不在本次改动文件中。

## 开发过程中的失败运行（不计为证据）

新测试编写期间的前两次定向运行（仅 ExamTerminationUiTest）分别 4/11、2/11 失败，原因均为测试本身：暂停时钟下第二个选项在小窗口外；失败通知 Snackbar 遮住恢复按钮；考试入口失败卡片在 470dp 测试窗口内被裁切（此项同时是真实的小屏 / 大字号风险，已在 `ExamSetup` 中让卡片可滚动）。未削弱断言、未加 sleep。

## 未验证

未构建 APK，未做真机验证（浅深色、大字号、TalkBack 实际朗读、小屏布局）；Robolectric 只检查了语义与可用性。T2 跨进程重复提交窗口不在本任务范围。
