# T1 统一集成验证（11.4，待审查）

固定源码 HEAD：9a3987dd6daa5f6bec65c20c2815abd21d9e8de7（2026-10-26，Asia/Tokyo）。包括 GPT 后端 3628e88、Claude 前端 8b8a0cd、Claude 冻结答案翻题补充回归 9a3987d。运行前没有未提交的受跟踪源码改动；start-status.txt 保留未跟踪文档/证据状态。未将 Architect 的文档、方案稿和审查目录混入提交。

## 唯一一次完整集成运行

在共享正式仓库执行，确认没有其他测试任务覆盖其 app/build；隔离调查使用另一副本，构建输出分开。命令：

```powershell
$env:EQ_ASCII_LINK = 'C:\AndroidProjects\EmbeddedQuiz'
& ./tools/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','--rerun','lintDebug','--continue','--no-daemon')
```

- 144 项：143 通过、1 失败、0 错误、0 跳过。完整 testDebugUnitTest 真实执行。
- 唯一失败：ReviewReminderTest.notificationReviewEntryWinsAfterUnfinishedExamIsRestored，expected REVIEW / actual QUESTION。
- ExamTerminationUiTest 12/12，包括实际只读翻题后重试；ExamLifecycleContractTest 20/20、ExamStateHolderTest 8/8。
- 原 9 项超时对应的 PracticeEntriesUiTest 4/4、QuickNotesUiTest 5/5；本次未复现超时，但隔离实验仍证明 G1 收尾存在顺序依赖，不据一次完整运行关闭该问题。
- assembleDebug 成功；Lint 检查完成，0 错误、5 警告（GradleDependency×2、ModifierParameter、IconLauncherShape、MonochromeLauncherIcon）。
- 总命令非零退出，因为上述 G1 失败；--continue 只让其余构建/Lint 检查完成，没有跳过或隐藏失败。日志结尾 BUILD FAILED 是真实整体状态。

证据：01-full-build-lint.log、01-full-xml/ 全部原始 JUnit XML、results.json、lint-results-debug.xml/html、integration-head.txt、source-sha256.txt。源码指纹覆盖当前包中的全部生产和测试 Kotlin 文件，运行结束后复核不变。证据归档/报告提交只改变文档，不改变这次被测代码。

环境沿用原离线工具链：JDK 17、Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21、compileSdk 35；未升级依赖或修改构建配置。

## 边界

本轮不重复完整套件。G1 代码修复尚未派发；只读根因、最小修复范围和接口建议见 ../T1-isolation-investigation-20261026/G1-analysis.md。九项超时的受控复现/取消完成对照见其 README 与原始 XML，不能用本次 9/9 覆盖失败记录。

没有修改通知导航、UI、业务接口、数据库/备份、题库、版本或签名；没有开始 T2。仅标准 build 目录产生 Debug 产物，未覆盖稳定 APK、未打标签、未推送或发布。Python 工具本轮未改也未重跑。真机小屏、大字号、浅深色、TalkBack、配置变更和进程边界仍待验收；T2 跨进程重复提交窗口保留。

状态：待审查；T1 整体尚未验收，不能标为全绿或稳定发布。
