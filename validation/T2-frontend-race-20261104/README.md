# T2 前端补修：离页回调与通知竞态（Claude）

日期：2026-11-04（Asia/Tokyo）。起始 HEAD：`922122013bee5387fa7ff01bd9c60142d844cbe0`（GPT 集成证据，含后端关库修复 `ebcffc3`）。
待修来源：[T2 集成证据](../T2-integrated-20261104/README.md)“Claude 待修”及其诊断 `probe-source/ExamLeaveNotificationProbeTest.kt`。
运行源码 = 该 HEAD + 本次改动，两个文件 SHA-256 见 `source-sha256.txt`，与交付提交一致。隔离副本 `C:\AndroidProjects\EmbeddedQuiz-claude-iso`（自有 app/build，`--no-daemon`）。

## 根因与修复

`ExamLeave.leave` 在 flush 返回后直接执行 `target()`（或设置失败弹窗）。通知把页面设为 REVIEW 后，答题页作用域要到下一帧组合才被取消；若 flush 在此之前返回，旧回调仍会把页面改成 HOME。上一版交接中“页面移出组合会取消等待，不会迟到导航”的说法因此不成立。

修复（仅 `QuestionScreen.kt` 的 `ExamLeave.leave`）：发起离页时记录来源 `page` 与 `practiceSession`；flush 返回后两者有任一变化即视为外部导航已接管，成功回调和失败弹窗都丢弃。持久化队列不受影响；通知路径不等待保存，G1 的 REVIEW 优先级不变。

## 01 前后对照（只运行两个新用例）

| 运行 | QuestionScreen.kt | 成功返回用例 | 失败返回用例 |
| --- | --- | --- | --- |
| 01a | HEAD 版本（未修） | **失败**：`expected:<REVIEW> but was:<HOME>` | 通过 |
| 01b | 交付版本 | 通过 | 通过 |

顺序固定：答题页改答 → 写入屏障 → 点返回（等待 flush）→ 暂停帧时钟 → 主线程 `requestNotificationReview()` 设为 REVIEW 并放行写入 → 不推进帧，空转主 Looper 让 flush 恢复 → 断言仍为 REVIEW；随后恢复帧，复习页可见、没有“保存失败，暂未离开”。

失败返回用例在未修版本上也通过：旧代码会设置弹窗状态，但答题页在下一帧被销毁，弹窗不会显示，也不会导航。所以它是对失败路径的守护，不是复现；修复同样覆盖该路径（不再设置弹窗状态）。该用例还确认失败没有丢失：考试标签页仍显示“保存失败，最新修改尚未保存”，重试后已保存。

日志 `01a-*.log`、`01b-*.log` 记录所用 QuestionScreen.kt 的 SHA-256；XML 在对应 `-xml/`。

## 02 定向运行（交付版本，真实执行）

`02-targeted.log`、`02-targeted-xml/`：10 个类 **95/95 通过**，0 失败/错误/跳过。

| 测试类 | 项数 |
| --- | --- |
| ExamSaveUiTest（7 + 新增 2） | 9 |
| ExamTerminationUiTest | 12 |
| NotificationEntryUiTest | 5 |
| ReviewReminderTest | 4 |
| PracticeEntriesUiTest | 4 |
| NotesAndScoringUiTest | 8 |
| QuickNotesUiTest | 5 |
| ExamPersistenceTest（GPT） | 14 |
| ExamLifecycleContractTest（GPT） | 20 |
| InitializationNotificationTest（GPT，含关库回归） | 14 |

## 未做

未运行完整套件，按 §14.4 由 GPT 在集成版本统一复测。无真机验证。
