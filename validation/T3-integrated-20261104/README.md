# T3 前后端统一集成验证（待审查）

最终固定 `cb4a70a` 完整复测 **210/210 通过，0 失败/错误/跳过**；Debug 构建成功，Lint **0 错误/5 条既有警告**。105 项输入运行前后逐字节一致，28 个测试类真实执行，无临时诊断用例。首次失败、定序诊断和最终全套分别归档。

初始固定共同 HEAD：`f6bcbcbbefad1f0dc3653c8ed732defcea3565a8`。包含 GPT 后端 c21b86f、25b3142、7a37243 和 Claude 前端 f6bcbcb。共享文档第 16 节已批准后端阶段及实际接口，本轮按 §16.4 做统一集成；T3 整体验收交由 Architect。本轮未改生产代码、接口、迁移或 UI。首轮统一验证暴露三个旧测试的完成条件/隔离缺口；经用户明确授权，提交 cb4a70a，只对三个既有测试增加 5 行，原断言保持。另新增验证证据和后端交接。

开工工作区仍只有 Architect 原有五个未跟踪项，未修改或暂存。initial-status.txt 在创建本证据目录后记录，因此另显示本目录本身。没有编辑 REPAIR_DISPATCH 或 FRONTEND_HANDOFF，没有开展 T4、改版本、推送、标签、Release 或覆盖稳定 APK。

## 固定输入与执行

复用 GPT 自有隔离副本 `.gpt-t3-backend-20261104` 及它自己的 app/build，入口 `C:\AndroidProjects\EmbeddedQuiz-gpt-t3-backend-20261104`；不使用或覆盖 Claude 的构建目录。将共同 HEAD 的 105 项 Android 输入复制为当前工作区的精确字节，逐一 SHA-256 对照，无额外 app/src 文件；Claude 三个交付文件的源码指纹全部匹配。见 tested-head.txt、source-fingerprints.csv、frontend-fingerprint-verification.csv、01-full-command.txt。

每个固定版本各统一运行一次：首轮 f6bcbcb，修正后的 cb4a70a 必要复测；两轮分别归档，不将定向结果拼接成全套。命令：

```powershell
./tools/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','--rerun','lintDebug','--continue','--no-daemon')
```

沿用离线 JDK 17 / Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / Room 2.7.2，无工具或依赖升级。现有 schema 1～5、Room 5、备份 3 均保持。

首轮 f6bcbcb 完整结果：**210 项，207 通过、3 失败，0 错误/跳过**；28 个测试类，新 StartupRecoveryUiTest 8/8。Debug 构建成功，Lint **0 错误/5 条既有警告**，警告 ID/说明与后端最终报告一致。命令因测试失败退出 1，BUILD FAILED in 3m 14s（55 个任务：19 执行、36 up-to-date）；不标全绿。01-full-xml/、01-full-summary.csv、01-failures.json、01-final-result.json、01-lint/ 保留全部原始结果；01-after-fingerprints.csv 确认 105 项输入运行前后一致。schema/ 保存未改的 1～5，Debug 产物仅记录指纹，不发布。

## 集成范围与观察项

前端已实际消费 startupState，只有 Ready 展示业务页面；Error 提供诊断查看、用户选文件导出、显式重试及先选取后确认的备份恢复。不可安全打开数据库时不能替换；活动/未知考试被后端拒绝。恢复 Completed 只代表替换提交及新启动受理，页面继续观察下一次状态，不能直接报启动成功。G1 通知优先级和 T2 未结束考试保护继续适用。

StartupRecoveryUiTest 8 项正式用例与全部旧测试共同执行，覆盖可见错误/重试与通知考试、再次失败、诊断预览与文件取消、ZIP 成功/失败、恢复取消/确认/拒绝/再次初始化失败、处理中禁重复、真实损坏库、320×480dp 深色及 1.6 字体模拟。不能用 Claude 的定向 82/82 或 GPT 后端的 202/202 替代本次统一结果；原有首轮失败和对照保留在各自交付目录。

Claude 记录的日期刷新观察项：只读审阅确认 LearningStateHolder.refreshDate 只更新内存日期 StateFlow，启动成功前没有 start() 创建的业务订阅；保留既有循环不会在 Error 中访问数据库或提交考试。考试计时仍以 ready 门控。本轮不改 UI，不重跑未交付的门控变体；“改为门控为何让测试不能空闲”的根因仍未确认，不将该现象说成已经修复。


## 三处旧测试修正与定向对照

用户已明确允许 GPT 将候选 5 行应用于三个测试并统一复测；文件归属的这次例外由用户授权，不扩大生产 UI 或后端范围。测试补修提交 `cb4a70a518514ea5f654eaa2939f8f2b0f5ecdc8`。

- ExamTerminationUiTest：原失败精确为 revision 2/空答案变成 revision 3/刚选择的 A，表明读取基线时先前的合法保存仍在途。只在保存快照基线前等待 examSaveState.Saved；取消后的队列、答案、截止时间、快照、页面和无学习记录断言全部保留。
- QuickNotesIsolationTest：Ready 不保证所有业务 Flow 首次发射，原基线 due=[]、后续 due=[a]。只在隔离操作开始前等待夹具的 progress[a].seen==1；之后的备份、每题草稿、复习列表及首页等原断言全部保留。
- StartupTest：默认 AndroidViewModelFactory 静态缓存可绑定前序 Application。只在创建 Activity 前调用现有 resetDefaultViewModelFactory，不修改生产默认工厂、不降低 906 题断言。

| 轮次 | 诊断/修复条件 | 实际结果 |
| --- | --- | --- |
| 02 | 新 StartupRecoveryUiTest→未修 StartupTest，额外只读工厂诊断 | 9/9，未复现；工厂与当前 Application 相同 |
| 03 | ReviewReminderTest→新 UI 类→未修 StartupTest | 13/13；工厂已指向旧 Application，但旧库已删除，因此这轮仍能加载 906；不能将它称为失败复现 |
| 04 | ReviewReminderTest→未修 StartupTest；在已证实不同的旧 Application 中放一题夹具 | 5 项中 1 失败，明确复现期望 906/实际 1 |
| 05 | 同一旧 Application/一题夹具，创建 Activity 前重置工厂；加两处等待修正，跑相关类 | **21/21**，0 失败/错误/跳过 |
| 06 | 用户批准的正式三文件补修，移除所有临时诊断/夹具源码，固定 cb4a70a 统一复测 | **210/210**，构建成功，Lint 0 错误/5 既有警告 |

02～05 是独立副本中的诊断，不计入正式套件通过数；原始 XML/日志/逐类 CSV 及 probe-source/ 保留。04 人工夹具证明串用上下文的失效机制；首轮 full 没有工厂上下文探针，不声称已从首轮直接记录到相同路径。候选补丁见 candidate-test-fixes.patch，candidate-tests/ 是最终正式修正文件全文。06-source-fingerprints.csv / 06-tested-head.txt / 06-full-command.txt 固定必要复测输入，105 项逐字节匹配且确认 app/src 不含额外诊断文件。原失败不删除、不拼接通过数。

最终 06-full.log 为 BUILD SUCCESSFUL in 1m 46s（55 个任务：6 执行、49 up-to-date），退出 0；06-full-xml/、06-full-summary.csv、06-final-result.json、06-lint/ 为本次完整原始结果，06-after-fingerprints.csv 为运行后核对，06-debug-apk-fingerprint.json 为最终独立 Debug 产物摘要。测试任务真实重跑；Lint 原始警告 ID/说明与首轮相同。

## Git 只读检查

用户报告前端提交后的自动后台整理 File exists。当前提交可读取，git fsck --full --no-reflogs 返回 0，只有 12 个 dangling blob，无缺失或损坏对象报告；原始输出见 git-fsck.log。这不能判定后台整理报错的具体根因；本轮未运行 gc/prune、删除 lock、重写历史或做仓库修复。交付提交按单次命令 gc.auto=0 禁止触发自动整理，不修改全局或仓库配置。

## 尚待验证与交接

无新增真机结果：实际文件选择器、真实存储故障/进程被杀、TalkBack 朗读、系统字号与配置变化仍需设备验收。Robolectric 的字体/深色模拟不等同真机排版。构造 ViewModel 之前的资源错误、不稳定多进程原库现场一致性和任意物理损坏的自动修复不在承诺内；不可自动清库。

Architect 继续集中审查：共同版本的启动门控、错误页操作/确认、后端恢复互斥及持久化、诊断与备份区别、通知和未结束考试保护、日期刷新观察项。T3 整体验收前不开始 T4；状态：**待审查**。