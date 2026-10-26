# T1 补充回归：失败后只读翻题 + 显式重试（Claude，REPAIR_DISPATCH 10.2 / 11.4）

日期：2026-10-26（Asia/Tokyo）。起始 HEAD：`8b8a0cd1e9b4c6c93682e9293e042017870e973a`。
运行源码 = 该 HEAD + `ExamTerminationUiTest.kt` 未提交改动（SHA-256 见 `source-sha256.txt`，与交付提交一致）。未改生产代码、后端或契约。

## 隔离

按第 11.2 节，在独立 ASCII 副本 `C:\AndroidProjects\EmbeddedQuiz-claude-iso`（robocopy 自本仓库，排除 build/.gradle/.git/validation）中构建与测试，使用该副本自己的 `app/build`，不占用共享 `C:\AndroidProjects\EmbeddedQuiz\app\build`。

## 01 有效运行

命令：`C:\AndroidProjects\EmbeddedQuiz-claude-iso\tools\build.ps1 testDebugUnitTest --tests cn.embeddedquiz.ExamTerminationUiTest --rerun --no-daemon`
日志 `01-ExamTerminationUiTest.log`（17:36 结束，BUILD SUCCESSFUL，testDebugUnitTest 真实执行），XML `01-xml/`：

**ExamTerminationUiTest 12/12 通过**，0 失败、0 错误、0 跳过；含新增 `failedSubmitAllowsReadOnlyBrowsingAndRetryGradesFrozenAnswersOnce`。

新增用例链路：三题（a 选对、b 选错、c 未答）→ 数据库触发器使 b 的 Attempt 插入失败，得到 `Failed(SUBMIT, RECORDS, recordsCommitted=false)`，无学习记录 → 实际点击“下一题 / 下一题 / 上一题”浏览 → 每题原选择仍显示、选项禁用、点击不改变选择；`examAnswers` 与偏好快照不变 → 去掉故障后在第 2 题点“重试交卷” → `Submitted`，`ExamResult(3, 2, 1, 1, 33)`，横幅“答对 1 / 3，得分 33”；Attempt 恰为 a×1（正确）、b×1（错误），c 无 Attempt；Progress 只有 a、b。

## 00 首次运行（不计为证据，如实保留）

`00-first-run-test-helper-failure.log`：同一命令（当时未加 --no-daemon），12 项中新增用例 1 项失败。原因是测试辅助函数：`questionNode(hasText("乙二") and isSelected())` 会先滚动寻找该节点，用它断言“不存在”时必然报 “No node found … in scrollable container”。改为定位选项后 `assertIsNotSelected()`，断言强度不变，未加等待。该次 XML 已在后续清理中删除，失败文本记录在日志中。

该次运行 Gradle 已结束，但包装脚本因 Gradle 守护进程继承输出句柄而未退出；有效运行改用 `--no-daemon`。

## 未做

按第 11.4 节未自行运行完整套件（由 GPT 集成后统一运行）。无真机验证。
