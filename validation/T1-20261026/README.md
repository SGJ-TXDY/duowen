# T1 后端验证记录（待审查）

日期：2026-10-26（Asia/Tokyo）。起始 HEAD：92d1cd70df874680caef78ca95ccc0a97b6e8b6d。
源码与本记录在同一 T1 聚焦提交中交付，最终提交 SHA 见交付回复。source-sha256.txt 对应最终被测的两个业务文件和两个新增测试文件；最终验证后已重新核对指纹。

## 实际结果

| 运行 | 命令组 | 测试数 | 通过 | 失败 | 错误/跳过 | 结果与报告 |
| --- | --- | ---: | ---: | ---: | --- | --- |
| 01 | 定向 T1 | 0 | 0 | 不适用 | 未执行测试 | Kotlin when 分支多余逗号导致编译失败；01-targeted.log 保留 |
| 02 | 定向 T1 | 24 | 23 | 1 | 0/0 | 02-targeted.log、02-targeted-xml/ |
| 03 | 定向 T1 | 24 | 24 | 0 | 0/0 | 修正考试 ownerCleared 边界后通过；03-targeted.log、03-targeted-xml/ |
| 04 | assembleDebug + 全套 | 132 | 130 | 2 | 0/0 | Debug 构建成功，全套失败；04-full.log、04-full-xml/ |
| 05 | assembleDebug + 全套 | 132 | 123 | 9 | 0/0 | Debug 构建成功，全套失败；05-full.log、05-full-xml/ |
| 06 | 最终 T1 + 练习相关后端 + lintDebug | 74 | 74 | 0 | 0/0 | 06-backend-and-lint.log、06-backend-xml/；Lint 0 错误、5 警告 |

最终 74 项包括新增 T1 28 项（ExamStateHolderTest 8、ExamLifecycleContractTest 20）与原有相关后端 46 项。两次全套均包含全部 28 项新增 T1 测试且这 28 项全部通过；全套仍有失败，不能把定向结果解释为全套通过，也不能据本次交付发布。

results.json 为从原始 XML 汇总的机器可读计数。各目录保留独立运行的 XML，没有以复测覆盖失败报告。所有实际 JUnit 运行均 0 跳过；日志里的 Gradle SKIPPED/NO-SOURCE 是任务状态，不是被跳过的 JUnit 测试。

## 真实命令

运行 01/02/03：

```powershell
& ./tools/build.ps1 testDebugUnitTest --tests cn.embeddedquiz.ExamStateHolderTest --tests cn.embeddedquiz.ExamLifecycleContractTest
```

运行 04/05：

```powershell
& ./tools/build.ps1 assembleDebug testDebugUnitTest
```

运行 06：

```powershell
& ./tools/build.ps1 testDebugUnitTest --tests cn.embeddedquiz.ExamStateHolderTest --tests cn.embeddedquiz.ExamLifecycleContractTest --tests cn.embeddedquiz.DomainTest --tests cn.embeddedquiz.StorageAndApiTest --tests cn.embeddedquiz.LearningDataTest --tests cn.embeddedquiz.LearningDatesAndReadingTest --tests cn.embeddedquiz.FrontendQueryContractTest --tests cn.embeddedquiz.PersonalMarksContractTest lintDebug
```

每次标准输出/错误均重定向至上述对应日志。默认 XML/HTML 位于 app/build/test-results/testDebugUnitTest、app/build/reports/tests/testDebugUnitTest；复制 XML 到本目录避免下次运行覆盖。完整 Lint 原始 XML/HTML 复制为 lint-results-debug.xml/html。

## 失败分析与未解决项

运行 02：`lifecycleCancellationAndClosedDatabaseBeforeTransactionReportActualFailure` 原假设 db.close 后评分必失败，实际 Room 自动重开并得到 Submitted，强转 Failed 的断言失败。没有放宽断言；业务增加仅考试使用的 ownerCleared 标记，ViewModel 生命周期已结束时不再启动迟到事务。运行 03 该场景与其余 23 项通过。已提交成绩之后关闭数据库的独立场景仍按真实已提交结果清理，未改变数据库关闭策略。

运行 04 两项失败：

- `NotesAndScoringUiTest.textDraftAndManualAssessmentSurviveNotesAndQuestionNavigation`：UncaughtExceptionsBeforeTest，附带 LearningStateHolder.totals 查询在连接池关闭后的异常。新增生命周期测试改为先取消并等待无关 Room 订阅完成，再专门验证考试操作与关闭数据库竞争；不吞异常、不改 UI 测试。运行 05 未再出现此异常。
- `ReviewReminderTest.notificationReviewEntryWinsAfterUnfinishedExamIsRestored`：期望 REVIEW、实际 QUESTION，与派工 G1 场景一致；未越界修改初始化或通知 UI。运行 05 此项通过，这不等于 G1 已修复或已验收。

运行 05 九项失败均为 AppNotIdleException：

- PracticeEntriesUiTest：savingVerifiedSolutionKeepsLatestDifficulty、examTabResumesUnfinishedExamInsteadOfRestarting、notesAndDifficultyShareOneSectionAndStayPerQuestion、homeEntriesOpenBanksAndTabBarOffersExam。
- QuickNotesUiTest：deleteExplainsWhatIsRemoved、duplicateImportAsksBeforeKeepingBoth、tabReplacesReviewAndLeavingKeepsTheExamRunning、readingPageLocksScriptsAndImagesAndParsesPositions、documentOpensWithOutlineAndWarningsAndBackReturnsToList。

其错误发生在 Compose 等待空闲时，单次等待 60 秒后超时。`05-worker-32328.txt` 为运行中的线程栈采样（仅规范化行尾空白）：SDK 28 Main Thread 在 ComposeIdlingResource 推进测试帧；采样中没有显示该主线程等待考试事务锁。这只是定位线索，尚不足以判定根因、排除后端关联或定性为 flaky。没有延长超时、删除/跳过测试或修改既有 UI。交由 Architect 继续审查并决定后续职责分配，本交付不把这些失败列为通过。

最终 06 是明确筛选的后端定向运行，不替代失败的两次全套；它用于确认最终考试实现和普通练习相关业务规则。Python 工具未修改且本次未运行其测试，真机及 Claude 新 UI 未验证。

## 环境与 Lint

Windows；PowerShell；JDK 17；Gradle 8.9；AGP 8.7.3；Kotlin/Compose compiler 2.0.21；compileSdk 35；新增考试测试为 Robolectric 4.14.1 / SDK 28。使用仓库原 tools/build.ps1 和离线缓存，未升级依赖。开跑前核验 ASCII 联接 `C:\AndroidProjects\EmbeddedQuiz` 指向本次 EmbeddedQuiz 仓库，未改联接或构建脚本。

Lint 五项：GradleDependency 两项、ModifierParameter、IconLauncherShape、MonochromeLauncherIcon；数量与派工基线一致，无错误。本次未修改依赖、UI 参数或启动图标。

## 数据、生命周期与发布边界

Room 4、备份 3、原考试快照字段不变；Repository 通用 record、练习 submit/assess、学习算法、原题库、签名与版本均未修改。仅标准 app/build 目录产生 Debug 构建产物，未复制/覆盖稳定 APK，未建立稳定标签、推送或发布。

T1 同实例结束/失败重试有内存防重复，未增加持久化提交标识或 T2 写入协议。Room 已提交而偏好清理尚未可靠完成时崩溃，跨进程重复窗口仍存在。受控生命周期测试不能证明真实进程强杀后仍可靠完成。

代码、状态表和 Claude 接入事项见 docs/API_CONTRACT.md 顶部 T1 段与 docs/BACKEND_HANDOFF.md 本轮交付。状态：待审查。
