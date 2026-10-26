# T1 收尾：测试隔离调查（11.4，待审查）

日期：2026-10-26（Asia/Tokyo）。本轮起始 HEAD：8b8a0cd1e9b4c6c93682e9293e042017870e973a；工作期间 Claude 独立提交补充回归，集成源码固定为 9a3987dd6daa5f6bec65c20c2815abd21d9e8de7。

本轮不修改生产代码、既有 UI 测试、通知导航或正式接口。诊断在原始后端提交 3628e8816bbfe07445c3c78e7161942b4df81973 的隔离副本运行；只增加临时诊断测试，未改原有测试断言、等待上限、依赖或构建配置。诊断源码以 *.kt.txt 保留在本目录，不参与产品测试发现。

## 结论

1. 已将原先 9 项进入测试体前的 Compose 超时缩小到一个可复现的前置条件：先运行原有 G1 通知 Activity 测试，再运行一个普通练习 UI 测试。完全不运行新增 T1 后端测试也会失败。
2. G1 Activity destroy 后，窗口已移除、数据库已关闭，但 Compose 的 runningRecomposers 仍有 1 个。仅处理可见页面/组合帧仍不能消除后续超时；销毁后让主线程处理取消，等待该 Recomposer 真正退出后，后续同一 UI 测试通过。
3. 因而当前证据定位的是既有 G1 测试的 Compose 生命周期收尾与测试顺序交互；没有定位到最终 T1 新增后端测试需要再修正的清理缺陷。本结论针对本次复现路径，不声称排除了所有其他资源/并发问题。
4. G1 的 REVIEW/QUESTION 断言失败是另一个需要单独修复的完成信号问题；不能把九项超时简单计作该断言失败。详见 G1-analysis.md。

## 固定顺序实验

| 运行 | 同一 JVM 中的确切顺序 | 实际结果 | 证据 |
| --- | --- | --- | --- |
| 01 | Notes.textDraft… → Review.notificationReview… → Practice.homeEntries… | 2/3 通过；G1 通过，最后 UI 在测试体前 60 秒超时 | 01-order-without-backend.log、01-without-backend-xml/ |
| 02 | 相同 Notes → 相同 Practice；不运行 G1 | 2/2 通过 | 02-notes-then-ui.log、02-notes-ui-xml/ |
| 03 | 相同 G1 → 相同 Practice；不运行 Notes 或新增 T1 后端测试 | 1/2 通过；G1 通过，最后 UI 在测试体前超时 | 03-g1-then-ui.log、03-g1-ui-xml/ |
| 04 | G1 观测副本（显式推进组合帧，按原方式 destroy）→ 相同 Practice | 1/2 通过；观测通过，后续 UI 仍超时；destroy 后 runningRecomposers=1 | 04-g1-trace-then-ui.log、04-trace-ui-xml/ |
| 05 | 同一观测副本，仅增加 destroy 后等待 Recomposer 取消完成 → 相同 Practice | 2/2 通过；destroy 后 1 → 等待完成后 0 | 05-g1-drained-then-ui.log、05-drained-ui-xml/ |

所有运行错误/跳过均为 0。01/03/04 的失败均保留原始 XML；05 不覆盖失败记录。没有扩大 Espresso 60 秒超时，也没有删除或削弱原断言。05 等待的是明确取消完成条件，原有界限 15 秒；不是用固定 sleep 掩盖问题。该等待只存在诊断副本，尚未改入正式 G1 测试。

临时 Suite 使用同一组原测试方法并明确顺序，避免 Gradle 上次失败优先策略干扰对照。01–03 直接执行原方法；04/05 用观测副本替代 G1 方法，其余测试方法不改。g1-trace-probe.kt.txt 与 g1-drained-trace-probe.kt.txt 的有效差异是销毁后等待取消完成，类名不同用于选择运行。02 和 05 的通过不能拼接成全套通过。

### 已复用的证据

- Architect 对最终后端 28 项 + 原 9 项 UI 强制执行 37/37 通过：validation/T1-architect-review-20261026/。
- Claude 的 56/56 与 142/143 完整运行，以及 Architect 11/11：原审查目录与 FRONTEND_HANDOFF；未当作本轮重新执行。
- 原 04 运行的连接池关闭后未捕获异常，发生于加入 clearModel 订阅取消等待之前；最终 3628e88 已包含该修正。本轮没有为旧的已修正缺陷再改代码。

## 历史运行顺序证据

原 validation/T1-20261026/04-full-xml 的顺序中，G1 靠后，后续没有上述两个 Compose 测试类；原 05-full-xml 中，Notes 和 Review 首先执行，之后才是 Domain、新增 T1、其他后端、Practice 和 QuickNotes。原 05 九项超时不是简单的“新增后端后面出现 UI 失败”因果证据。

本地 Gradle 8.9 的 RunPreviousFailedFirstTestClassProcessor 会先分发上次失败类，再分发其余类；javap 证据在 gradle-failed-first-bytecode.txt。原 04 正好失败于 Notes 和 Review，所以原 05 会先运行它们。顺序变化和最小二测试对照共同解释了为什么单类/37 项组合可过、某次完整运行却连续九项超时。仍未穷举所有完整顺序，不能据此宣布所有测试隔离问题均已解决。

## 复现方式与源码

隔离副本：由 git archive 3628e8816bbfe07445c3c78e7161942b4df81973 创建，独立 app/build；ASCII 联接为 C:\AndroidProjects\EmbeddedQuiz-gpt-t1-isolation，未改共享联接或共享构建输出。probe-source-tree.txt 记录原始生产/测试源码 blob。诊断按阶段的源码分别保存在 ordered-probes*.kt.txt、g1-*probe.kt.txt。

每组命令均在隔离副本执行：

```powershell
$env:EQ_ASCII_LINK = 'C:\AndroidProjects\EmbeddedQuiz-gpt-t1-isolation'
& ./tools/build.ps1 -Tasks @('testDebugUnitTest','--tests','cn.embeddedquiz.<ProbeClass>','--rerun','--no-daemon')
```

依次使用 ExamOrderWithoutBackendProbeTest、ExamNotesThenUiProbeTest、ExamG1ThenUiProbeTest、ExamG1TraceThenUiProbeTest、ExamG1DrainedThenUiProbeTest。ExamOrderWithBackendProbeTest 虽保留于早期诊断源码，未运行；对应组合已有 Architect 37/37，未为凑次数重跑。所有有效运行 testDebugUnitTest 均真实执行，非 UP-TO-DATE。

实际顺序为 01→02→03→04→一次正式集成→05（取消完成的局部对照）；没有同时运行两个测试 worker。05 只改变诊断副本，正式被测源码未变，此后不再运行完整套件。唯一完整集成运行见 ../T1-integrated-20261026/README.md。最终生产源码未掺入任何诊断类或清理策略；私有副本可按上述源码与命令重建。

## 后续范围裁定需求

发现的清理点属于既有 ReviewReminderTest 的 Activity/Compose 生命周期，不是新增 T1 后端测试。按 9.3/11.4 当前权限，本轮提交根因和最小方案，不擅自修改该既有 UI/生命周期测试或通知生产代码。具体函数归属、业务接口和验收建议见 G1-analysis.md，由 Architect 统一派发。

状态：待审查；T1 整体未验收，T2 未开始，未发布。

原始日志、诊断源码和事件输出按运行时原样归档，保留 Gradle 输出的行尾空白及原始 EOF。差异格式检查针对正式交付 Markdown，不为消除格式提示改写原始证据。
