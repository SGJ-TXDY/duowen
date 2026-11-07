# 题目页挂载新会话与旧入口迁移，验证记录（Claude，2026-11-07）

说明见 `docs/FRONTEND_HANDOFF.md` 顶部。

## 输入

在干净 worktree `C:\AndroidProjects\eq-aimount` 上，以 HEAD `c560a9e5` 为基础，加入当时主工作区全部 60 个未提交的 `app/`、`tools/` 文件：其中 52 个是 GPT 的 AI-02b 后端及其测试，8 个是本次 Claude 的改动。最终输入的指纹见 `input-fingerprints.csv`。提交前核对过：GPT 的文件在验证期间没有变化，本次提交的 8 个文件与快照完全一致。

## 各次运行（全部保留，没有挑选）

| # | 日志 | 内容 | 结果 |
| --- | --- | --- | --- |
| 1 | `01-aimount-1.log` | 定向：AiChatScreenTest、QuestionAiEntryUiTest、MoreUiStateTest | 23 项中 2 项失败。1 项是测试问题（快捷按钮行占了位置，消息不在屏幕内）。1 项是**真实缺陷**：首次点“AI 代码审查”时后端还在 INITIALIZING，返回 NOT_READY，已修复。 |
| 2 | `02-aimount-2.log` | 同上定向 | 24 项中 1 项失败：失败的 run 在 CLEANING 期间仍占着发送槽，测试却断言为空闲；已改为等待槽释放。 |
| 3 | `03-aimount-3.log` | QuestionAiEntryUiTest | 2/2 通过 |
| 4 | `04-aimount-full.log` | 全量 + 构建 + lint | 398 项中 1 项失败：`ExamTerminationUiTest.missingQuestionDoesNotTrapUserAndCanStillBeAbandoned`（找不到“确认放弃”） |
| 5 | `05-aimount-exam-alone.log` | ExamTerminationUiTest 单独运行 | 12/12 通过 |
| 6 | `06-aibase-full.log` | **基线**：同一快照，但不含本次 8 个文件；全量 | BUILD SUCCESSFUL，全部通过 |
| 7 | `07-aimount-full-2.log` | 全量（事先定好的一次复跑） | 398 项中 16 项失败。从 QuestionAiEntryUiTest 之后，所有 Robolectric 界面类测试都报 AppNotIdle（每项 60 秒）。原因：**本次新测试的清理有误**，没等 AI 运行时异步关闭就删除了数据库。已改为像 AiProductionIntegrationTest 那样先等 `vm.examResourcesClosed`，最多 20 秒，超时判失败。 |
| 8 | `08-aimount-full-3.log` | 修复后全量 + 构建 + lint | 398 项中 7 项失败，都是在 `setContent` 时出现的 AppNotIdle：AiChatScreenTest 17 项中失败 3 项，且分散在不同位置；**未改动的**旧 AiConversationUiTest 4 项全部失败，特征与 UI-04A 那次未定位的现象相同。Gradle 把上一轮失败的类排到前面执行。这段时间守护进程日志里只有这一次构建。 |
| 9 | `09-aimount-full-4.log` | 事先定好的最后一次全量 + 构建 + lint | **BUILD SUCCESSFUL。49 类、398 项，0 失败 / 0 错误 / 0 跳过。** Lint 0 错误、7 个警告：其中 5 个是已有的，2 个 `UsableSpace` 位于 GPT 的 `AiStorageResources.kt`，不在本次文件里。 |

第 9 次的原始 XML 在 `test-xml/`，lint 在 `lint-results-debug.xml`。第 1～8 次的 XML 已被后续运行覆盖，只保留了日志；上表的失败细节来自当时读取的 XML 输出。

## 结论与未闭环

- 本次新增或修改的测试：AiChatScreenTest 17 项、QuestionAiEntryUiTest 2 项（真实 ViewModel 和运行时，无网络、无 Key）、MoreUiStateTest 5 项，在第 9 次全量中全部通过。
- 第 8 次的 7 项 AppNotIdle **根因未证实**。它们是间歇出现的，其中包括本次没有改动的旧测试，并且在第 9 次全量中没有复现。这不能证明它们与本次改动无关，只能如实记录，交 Architect 裁定。
- 第 4 次的 ExamTermination 单项失败也没有单独定位。它出现在测试清理缺陷修复之前；修复后的两次全量中它都通过了。
- 没有生成 APK：这个输入包含 GPT 未提交的 Room 6 / 备份 4 升级，安装后不能退回 Room 5，需要另行确认后再准备试装包。
