# UI-04A · 早期 AiConversationUiTest 全量失败的证据核对（Claude，2026-11-06）

依据：`docs/UI_0123_ARCHITECT_REVIEW_20261106.md` UI-04A。未修改任何生产代码、AI 文件、测试或测试公共 helper；只做隔离复验、归档和措辞更正。GPT 未提交的 AI 基础文件未触碰，也不在本次输入中。

## 结论

**根因未证实。** 早期一次全量（`../UI-0123-20261106/01-full-suite-after-A-5975519.log`，277 项，7 失败）中，`AiConversationUiTest` 的 4 个方法均为 `AppNotIdleException`（`AiConversationUiTest.kt:125` 的 `setContent` 与 `:115` 的 `waitForIdle`）。用同一份源码输入在干净目录按同样的类顺序复跑，这 4 项都通过，最小前缀连续 3 次也通过。现有证据不能说明早期失败与本次改动无关，也不能说明它与历史上的 4 个全量失败同根因；只能说明在下列固定输入下没有复现。

## 原始失败证据的现状

- 原失败 XML 和类顺序**已丢失**：run 01 在主工作区 `app/build` 中执行，之后同一目录又跑了定向测试（约 12:57 起），Gradle 重写了 `test-results`。唯一留存的是 run 01 的控制台日志（含失败方法、异常类型和行号），原样保存在 `../UI-0123-20261106/`。
- 日志中 4 个 AI 方法失败的打印顺序（slow → changedQuestion → costLine → cacheHit）与 R1 XML 中的方法顺序一致；AI 失败先于 ExamSave、NotesAndScoring、StartupRecovery 打印，与 R1 中 AI 排在第 2 位相符。这只是推断，不能代替原 XML。
- 原目录无法按原状复现：原 `app/build` 已被覆盖；当前主工作区含有 GPT 未提交的 AI 文件（12:57 起出现，晚于 run 01 结束的 12:55:11）。
- 并发构建：Gradle 守护进程日志显示，12:44:43～12:55 期间对任何项目目录只有 run 01 这一次构建（daemon 23676），没有其他构建写入同一项目目录。机器总体负载没有记录，无法核对。

## 固定输入（R1/R2）

- 提交 `5975519160020253228de700afef4ed94839a30f`（A，UI-02）。把 `ExamSaveUiTest`、`NotesAndScoringUiTest`、`StartupRecoveryUiTest` 还原为 `0ecc6366` 的版本（它们的底栏路径修正写于 run 01 结束之后），并加上当时工作区中未跟踪的 `app/src/main/assets/licenses/LUCIDE_LICENSE.txt`。
- 全新 worktree `C:\AndroidProjects\eq-ui04a`，没有旧的构建目录。137 个输入文件的 SHA256 见 `r1-input-fingerprints.csv`；运行计划在运行前写入 `PLAN.txt`。
- 已修复的 `ComposeMainQueue` helper（ec123e0a）是该提交的祖先，因此在输入中。但它只被 `CodeDraftSaveUiTest` 和 `ComposeMainQueueTest` 调用，`AiConversationUiTest` 不使用它。
- 命令均为既有 `tools\build.ps1`（离线工具链、单个 test worker），没有改超时、没有 sleep、没有重试。

## 运行结果

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| R1 | `testDebugUnitTest`（全量） | 37 类 277 项，3 失败：正是 3 个旧导航路径测试（证明输入还原正确）；`AiConversationUiTest` 4/4 通过，排第 2 位（Accent 之后），完整类顺序见 `r1-class-order.txt` |
| R2a | `--tests AccentPreferenceTest --tests AiConversationUiTest` | 6/6 |
| R2b、R2c（无效） | 同 R2a | Gradle 判定 UP-TO-DATE，**测试没有执行**。XML 副本已删除，日志保留为 `*-INVALID-up-to-date.log` |
| R2b2 | 同上加 `--rerun` | 6/6，确实执行（`Task :app:testDebugUnitTest`） |
| R2c2 | 同上加 `--rerun` | 6/6，确实执行 |

各轮原始 XML 在 `r1-xml/`、`r2a-xml/`、`r2b2-xml/`、`r2c2-xml/`，汇总在 `run-summary.csv`，起止时间在 `run-times.txt`。

## 仍未闭环 / 交 Architect 裁定

- 固定源码输入下没有复现，找不到最小复现，因此**没有可交的复现**，也没有可证实的根因。可能与原目录的构建状态或当时的机器状态有关，但这只是假设，没有证据，不作为结论。
- 不建议为此修改生产代码或测试 helper。若 Architect 需要更多数据，可以在固定输入下增加相同顺序的全量重复次数，次数应事先确定，不按结果挑选。
