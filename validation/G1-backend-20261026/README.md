# G1 后端：初始化完成与通知优先级（待审查）

日期：2026-10-26（Asia/Tokyo）。起始 HEAD：d240f437eb4ad32531e655ad5c184c8257ca1dc5。

本轮按用户明确指令实施后端修复并补业务测试；没有修改 Architect 维护的 REPAIR_DISPATCH.md。正式仓库修改仅限 QuizViewModel.kt、新增 InitializationNotificationTest.kt、API_CONTRACT.md 和 BACKEND_HANDOFF.md，以及本目录证据。

## 行为与接口

- requestNotificationReview(): Unit，Main 调用。初始化期间合并复习请求；先恢复考试队列/位置/答案/截止时间，再决定初始页面，再发布 ready。通知优先于普通考试恢复的 QUESTION；初始化后收到请求立即 REVIEW。不受普通写任务 busy 限制，不写考试快照或学习数据。
- awaitInitialization(): InitializationOutcome，可在调用方协程等待。Ready(examRestored:Boolean) 表示恢复与页面选择均已完成；Failed(message:String) 表示准备/恢复失败，ready 保持 false。结果每个 ViewModel 一次且稳定，不承诺所有 Flow 首次发射已经完成。
- 多个等待者共享同一完成信号，单个等待者取消不取消初始化；所属 ViewModel 清理时取消未完成信号。初始化失败在诊断日志写入前即可发布 Failed，日志写入故障不阻断完成信号。
- 兼容旧 MainActivity 初始化期间直接写入的 REVIEW：恢复不再覆盖这个明确目标。现有通知入口无需前端同步切换即可修复原失败场景；后续 Claude 应调用新门面并移除重复导航效应。
- 初始化失败不由通知命令自动重试，不增加 T3 恢复界面；不增加偏好键、迁移、持久化通知队列或 T2 协议。构造 ViewModel 本身失败、原有坏快照清理策略及之后 Flow 异常不在一次完成信号的保证范围。

## 实际验证

| 运行 | 范围 | 总数 | 通过 | 失败/错误/跳过 |
| --- | --- | ---: | ---: | --- |
| 01 | 新业务测试、原 G1、T1 后端及查询回归 | 54 | 54 | 0/0/0 |
| 02 | 一次完整 Android 测试 | 156 | 156 | 0/0/0 |

Debug assembleDebug 成功；lintDebug 完成，0 错误、5 项既有警告。原 ReviewReminderTest 4/4，包括此前失败的 notificationReviewEntryWinsAfterUnfinishedExamIsRestored。新增 InitializationNotificationTest 12/12；T1 后端 28/28、ExamTerminationUiTest 12/12 均在完整运行通过。

01-business-regression.log 首次启动 Kotlin 编译服务曾报告一次启动异常，Gradle 自动恢复后继续编译并 BUILD SUCCESSFUL；没有为此修改编译配置/依赖或丢弃日志。该命令最终为 54/54，是一次完整有效测试执行。

原始证据：01-business-xml/、02-full-xml/、两份完整命令日志、results.json、lint-results-debug.xml/html。保留 Gradle 原始日志行尾空格；没有为了差异格式检查改写日志。JUnit 0 跳过，日志的 Gradle SKIPPED/NO-SOURCE 不是跳过测试。

### 精确命令

在独立副本执行，EQ_ASCII_LINK=C:\AndroidProjects\EmbeddedQuiz-gpt-g1-backend：

```powershell
& ./tools/build.ps1 -Tasks @('testDebugUnitTest','--tests','cn.embeddedquiz.InitializationNotificationTest','--tests','cn.embeddedquiz.ReviewReminderTest','--tests','cn.embeddedquiz.ExamStateHolderTest','--tests','cn.embeddedquiz.ExamLifecycleContractTest','--tests','cn.embeddedquiz.FrontendQueryContractTest','--rerun','--no-daemon')
& ./tools/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','--rerun','lintDebug','--continue','--no-daemon')
```

两个 testDebugUnitTest 均真实执行，不以 UP-TO-DATE 充当新证据。完整测试只执行一次。

### 12 项新业务测试覆盖

1. 初始化完成前请求复习，Ready 发布时已经 REVIEW，且整份考试和学习数据保留。
2. 无通知普通启动，原考试恢复到 QUESTION。
3. 没有考试时通知进入 REVIEW，不创建学习记录。
4. 没有考试、没有通知时保留 HOME。
5. 没有考试、没有通知时保留明确选择的其他页面。
6. 旧 MainActivity 的 page=REVIEW 路径不被考试恢复覆盖。
7. 初始化后请求立即进入 REVIEW，考试不变。
8. 初始化前后连续重复请求不重复恢复或写入。
9. 普通写任务 busy 时通知不被丢弃。
10. 取消一个等待者不取消其他等待者或初始化。
11. 实际缺失数据库题目表导致初始化失败，明确返回 Failed，保留考试键、原学习记录、SRS 及每日统计；通知不会伪报成功或自动重试。
12. 所属 ViewModel 清理后，尚未完成的等待收到取消，不永久挂起。

考试比较包括精确队列顺序、index、已答/未答集合、原 deadline、原偏好 JSON；学习比较包括原 Attempt 列表、Progress 全字段和 DailyCount，未答题不建 Progress。业务测试不创建 Activity/Compose 测试规则，不掺入 G1 UI 收尾策略。

## 可追溯性与边界

测试源码为 git archive d240f43 加 source-sha256.txt 中三份覆盖文件；独立 app/build，不占用共享构建输出。覆盖文件中的两个 Kotlin 文件与正式工作区逐一核对指纹一致；API_CONTRACT 同步记录本次门面。未提交的 Architect 文档和原方案未纳入副本或提交。正式交付报告及源码随同一个聚焦提交，最终 SHA 见交付回复。

环境沿用离线 JDK 17、Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21、compileSdk 35；新增业务测试 Robolectric SDK 28。没有改依赖或构建脚本。仅副本标准 build 目录生成 Debug 产物，没有复制/覆盖稳定 APK、安装、推送、打标签或发布。

本次完整运行通过不表示旧 G1 Activity/Recomposer 收尾问题已修复；该正式测试清理仍需按第 12 节由对应执行者落实，本轮没有改 ReviewReminderTest。MainActivity 新接口接入、实际 onNewIntent UI 测试、配置变更及真机验收待 Claude/Architect；旧入口已通过原测试与业务兼容测试。Python 工具未改未重跑；T2 未开展、跨进程提交窗口仍保留。

状态：G1 后端待审查，不自行标记整个 G1 或稳定发布验收通过。
