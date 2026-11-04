# VC-01 验证证据（Claude，2026-11-04）

任务：REPAIR_DISPATCH §21。分支 `ui-visual-refresh`，起始 `6becc3f`。

| 提交 | 内容 |
| --- | --- |
| `2ff0141` | 视觉规范与八色主题（Theme / CommonComponents / Settings 选色 / AccentPreference） |
| `21e4268` | VC-01：AI 请求归属快照、对话区按题目版本展示、费用文字颜色、回归测试、API_CONTRACT 追加 |

源码指纹与运行前工作区：`source-state.txt`（HEAD `21e4268`）。

## 结果一览

| 运行 | 版本 | 命令 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 定向第 1 轮 | 未提交（同 21e4268 前身） | 4 个相关类 | 18 中 1 失败：失败时快照先结束、全局 busy 后清除，B 页短暂显示“其他题目处理中”。真实缺陷，已修（busy 与快照在 finally 同时落定） | `focused-run1-failed/` |
| 定向第 2 轮 | 修复后 | 同上 | **18/18** | `focused-run2.log` |
| 完整 | **21e4268** | `assembleDebug lintDebug testDebugUnitTest --rerun --continue` | assembleDebug 成功；lint 0 错误 5 警告（依赖版本提示）；测试 **256 中 4 失败**（均为 `CodeDraftSaveUiTest`） | `full-suite.log`、`lint-results-debug.*`、`full-run-at-21e4268-CodeDraftSaveUiTest.xml` |
| 完整（仅测试） | **21e4268** | `testDebugUnitTest --rerun` | 同样 256 中 4 失败，可稳定复现，与 lint 并行无关 | `diagnosis/3-*` |

lint 报告文件时间早于本次运行：本次 `lintAnalyzeDebug` 实际执行，`lintReportDebug` 因分析结果相同为 UP-TO-DATE，报告内容即 21e4268 的结果。

## 已知失败（未解决，如实保留）

`CodeDraftSaveUiTest` 中只渲染 `MaterialTheme { CodeEditor }` 的 3 个用例各卡 60 秒 `AppNotIdleException`（Compose 不空闲），另 1 个 Activity 用例 `theExclusiveBoundaryFreezesCodeAndNotesUntilItEnds` 找不到“正在备份…”提示；同类其余 11 个通过。

已确定的事实（隔离 worktree，独立 build 目录，均 `--rerun` 完整套件）：

| # | 内容 | 结果 |
| --- | --- | --- |
| 6 | `2ff0141`（无 VC-01） | 251/251 |
| 7 | VC-01 全部生产代码 + EnhancedAiTest/颜色断言，**去掉** `AiConversationUiTest` | 252/252 |
| 8A / 8B | 仅保留 UI 测试的前两个 / 后两个方法 | 8A 失败，8B 通过 |
| 9 | 只保留“慢请求 A→B” / 只保留“缓存命中” | 两者各自都失败 |
| 12 | UI 测试改为自建 OkHttp 并在 teardown 关闭 | 256/256，但 Gradle 把上次失败类提前执行，AI 测试排在其后，不作数 |
| 13 | 同 12，正常顺序（AI 14:44:23 → CodeDraftSave 14:44:32） | 仍 4 失败，OkHttp 推测不成立；该改动已撤回，未提交 |

结论：失败由新测试 `AiConversationUiTest` 中“经网络成功回复”的用例对后续 Compose 测试造成的干扰引起；生产代码去掉该测试后全套通过。按 `--tests` 过滤只运行前置几个类（`diagnosis/1,2,4,5`）时均不复现；紧接 AI 测试后的探针（`diagnosis/10,11`）看到 Recomposer、全局快照、主 Looper 都干净。根因未确定，按用户指示停止排查。

未做：没有跳过/削弱断言或加长等待；没有修改 `CodeDraftSaveUiTest`。

## 可选处理（交 Architect 裁定）

1. 保持现状交审：VC-01 行为由 EnhancedAiTest 门面测试和 UI 测试覆盖，全套已知 4 失败归因于新测试的跨类干扰。
2. 由测试负责人继续定位 UI 测试与 CodeDraftSaveUiTest 的交互（候选：成功回复后的 SelectionContainer/Markdown 渲染、Room 写入后的失效通知、Robolectric 沙箱内的全局状态）。
3. 临时让 `AiConversationUiTest` 在独立 JVM 运行（Gradle `forkEvery` 或单独任务）——属构建配置改动，需裁定，本次未做。

## 未验证

无真机/截图视觉验收；无真实付费 AI 请求；八色在真机深浅色下的观感未看。
