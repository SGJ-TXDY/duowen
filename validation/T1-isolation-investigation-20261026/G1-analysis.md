# G1 只读根因与最小修复方案（待 Architect 裁定）

本文件是分析/接口建议，不是已实施接口。遵守 REPAIR_DISPATCH 10.3/11.4：没有更改 MainActivity、AppNavigation、QuizViewModel、ReviewReminderTest 或生产通知导航。

## 两个问题必须分开

### A. 后续 Compose 测试被前置 Activity 测试影响

原有 ReviewReminderTest.notificationReviewEntryWinsAfterUnfinishedExamIsRestored 自身通过，仍足以使随后的 PracticeEntriesUiTest.homeEntriesOpenBanksAndTabBarOffersExam 在进入测试体前超时（03，1/2）。没有新增 T1 后端测试参与。

观测（04/05）：

```text
before-destroy: windows=1, decorAttached=true, recomposers=1, pending=0
after-destroy:  windows=0, decorAttached=false, recomposers=1, pending=0
after-cancellation-drained: windows=0, decorAttached=false, recomposers=0, pending=0
```

仅推进初始组合帧且按原方式销毁，后续 UI 仍超时；销毁后等待 Recomposer 从运行集合退出，同一 UI 通过（05，2/2）。这是当前复现路径的直接生命周期隔离证据。窗口移除、数据库关闭、pending=0 都不等于 Recomposer 已完成退出；不能只检查这几个条件宣称测试收尾完成。

最小修复范围建议：ReviewReminderTest.notificationReviewEntryWinsAfterUnfinishedExamIsRestored 的 finally 收尾与一个仅测试使用的辅助函数。由 Architect 明确分配该函数的 UI/生命周期测试所有权。保留原业务断言，在 destroy 后处理主线程取消，等待本 Activity 创建的重组器退出。正式辅助函数应保存 Activity 创建前的重组器集合，等待本 Activity 增量集合消失，避免要求全局集合为空或取消别的 Activity。诊断中只有一个 Activity，所以使用空集合条件；不能原样推广到多 Activity 产品生命周期。

不修改全局 Compose 工厂、超时、Gradle 配置或生产数据库关闭策略。验收至少保留 G1→单个 UI 的失败/修复对照，再验证原九项类与新增 T1 的组合；该修复属于下一次已分配修改后才新增验证的理由，本轮不为凑绿重复全套。

### B. 通知与考试恢复有两个导航写入阶段，ready 不表示通知已应用

源码位置：

- MainActivity.onCreate/openNotification：先设置 reviewAfterLoad=true 和 vm.page=REVIEW，随后消费 Intent extra。
- QuizViewModel.init：异步准备后恢复考试 queue/index/answers/deadline，并写 page=QUESTION；然后 ready=true。
- MainActivity 的 LaunchedEffect(vm.ready, reviewAfterLoad)：只有组合效应执行后才再次写 REVIEW 并清待处理标记。
- ReviewReminderTest：等待 ready/theme，visible + 一次 Looper.idle 后直接断言 REVIEW；没有等待通知处理完成信号。

g1-event-trace.txt 的实际轨迹：

```text
after-create-start-resume: ready=false, page=REVIEW, queue=[]
ready-before-visible:     ready=true, page=QUESTION, queue=[remind]
after-visible:            ready=true, page=REVIEW, queue=[remind]
```

队列/答案、原截止时间和偏好快照在诊断断言中保持；该次轨迹随后到 REVIEW，不证明永久丢失通知。完整集成原测试仍实际失败于 expected REVIEW / actual QUESTION。已确定的缺口是：初始化完成与通知导航完成不同步，测试和外部消费者没有统一的可等待完成点；最终页面取决于组合效应何时执行。不能仅称“加点等待就好”，也不能凭这一轨迹声称所有设备必然永久错页。

## 推荐的一次性跨层修复契约（未实施）

由 Architect 确认后，按函数分配：

**GPT：QuizViewModel.init 的恢复/最终初始页面块，加通知业务入口和完成信号；对应业务/恢复测试。** 建议接口：

```kotlin
fun requestNotificationReview(): Unit // Main；不受普通写任务 busy 影响
suspend fun awaitInitialization(): InitializationOutcome

sealed interface InitializationOutcome {
    data class Ready(val examRestored: Boolean) : InitializationOutcome
    data class Failed(val message: String) : InitializationOutcome
}
```

语义：请求在 Main 同步受理。初始化未完成时记住待打开复习的请求；完成时先恢复整个考试，再在同一 Main 收尾中依据通知优先级确定 page（有请求 REVIEW，否则保留普通启动的 QUESTION/默认页），最后发布 ready/完成结果。初始化完成后再次收到请求直接切 REVIEW；重复同目标请求不写学习记录或重置考试。错误完成明确返回 Failed，等待取消向上传播，不把错误当成功或永远悬挂。Ready.examRestored 只说明本实例恢复结果，队列和截止时间仍读取既有单一状态。没有新 DB/备份字段，也不开展 T2。

**Claude：MainActivity.onCreate/openNotification/onNewIntent 的最小接入，以及相应通知 UI/生命周期测试。** 调用上述入口并消费通知 extra；移除“先直接写 REVIEW、后依赖 LaunchedEffect 再覆盖”的竞争写法。既有 Page/导航结构不重组，不把未完成考试清掉。AppNavigation 原有通知无关分支不需要改。

**共同测试交界：** Architect 按具体函数指定 ReviewReminderTest 的断言等待与 Activity 清理各由谁修改，避免两人同时编辑同一方法。业务完成等待使用 awaitInitialization 结果；真正的 UI 呈现等待使用受控组合/生命周期事件。A 的重组器取消等待仍须单独落实，B 的业务入口不能代替测试隔离修复。

## 后续验收要求

1. 携带通知且有未交卷考试：最终 REVIEW，queue、index、answers、deadline 和考试偏好保留。
2. 普通启动：QUESTION 恢复考试；没有考试的普通启动保持原入口。
3. 初始化前/后收到通知，以及运行中 onNewIntent、重复通知，均有确定完成点。
4. 初始化失败：失败结果可等待，考试快照不静默清除；错误恢复另遵循既有授权，不顺带启动 T3。
5. G1 Activity 销毁后，所创建的 Recomposer 真正结束；固定 G1→UI 顺序不再污染后续测试。
6. 修复集成后再按新任务契约运行一次完整验证；本轮的 143/144 不能标全绿。

状态：分析已交付，正式 G1 修复尚未实施/未被本轮权限启动。真机、配置变更与进程重建行为尚未验证。
