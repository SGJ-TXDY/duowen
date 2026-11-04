# VC-02 · 测试隔离修复（待审查）

## 固定基线与范围

开工 HEAD：3f69f95（ui-visual-refresh）；生产 VC-01 补修 21e4268、视觉提交 2ff0141。复用 VC-01 的全套四项失败、排除 AI 类、HTTP 清理反例及排序陷阱证据，不重复未修改全套。当前目录全为 GPT 新证据，不改写 VC-01。

开工未提交项为 Architect 的 docs/REPAIR_DISPATCH.md、docs/VISUAL_CHAT_REVIEW_20261104.md 和 validation/visual-chat-review-20261104/，均不编辑、不暂存。正式改动只有两个既有测试的环境/退出处理、一个仅测试 helper 和三个 helper 回归；生产 UI、AiStateHolder、协议、依赖、Gradle、版本、数据库和发布产物不改。

隔离执行位置沿用 GPT 自有 .gpt-t3-backend-20261104，ASCII junction 为 C:\AndroidProjects\EmbeddedQuiz-gpt-t3-backend-20261104；名称沿用旧目录，不是 T3 新开发。未使用 Claude 副本。base-source-fingerprints.csv 的117项在开始时与主工作区逐字节相同；最终119项仅两项原测试变化及两个新增测试文件。诊断 launcher/probe 仅在隔离副本运行，最终全套前已移出 app/src。

## 根因和最短复现

固定顺序：

1. AiConversationUiTest.cacheHitAndSuccessLeaveNoPendingOrDuplicateBubble
2. CodeDraftBoundaryTest.clearingOwnerFlushesTheLastWindowBeforeClosingTheDatabase
3. CodeDraftSaveUiTest.onlyTextChangesAreReportedAndTheCaretSurvivesTheRoundTrip

这三项在05中稳定产生原60秒 AppNotIdleException，07同序恢复后3/3。前缀只含第一项 AI 和编辑器时01通过2/2，加入 Accent 两项的02也通过4/4；不能归因于“网络成功后马上污染”。所有诊断均由 JUnit Request 顺序调用原方法，原方法操作、断言、超时不变，launcher 的 START/END 及类内 XML testcase 顺序保留；不依赖 Gradle 失败优先排序。

在真正阻塞的 setContent 边界，04/05被动探针显示：

- 无 mainClock awaiter、无 Recomposer 待办、无布局/挂载问题；根节点 attached，正常320x304。
- 唯一持续 idle 原因为 Snapshot.hasPendingChanges=true。
- AndroidUiDispatcher.Main 的 toRunTrampolined 有一个 GlobalSnapshotManager consumer continuation，scheduledTrampolineDispatch=true、scheduledFrameDispatch=true；其 Handler 中却没有原 dispatchCallback。
- 07只把这个原 Runnable 重新 post 到原 Handler：pending 1→0，scheduled true→false，globalPending true→false，编辑器原测试通过。

当前 Compose 1.7.6 的 GlobalSnapshotManager/AndroidUiDispatcher 是进程内共享状态；Robolectric 4.14.1 会在测试之间清主消息队列。非 UI 边界测试写 Compose 状态，留下已排队的 consumer。消息被清后，Compose 的 scheduled 标志仍在，后续写入不再 post，正常 UI 的虚拟帧不能送达这个丢失的 Android Handler 回调。framework-bytecode/保存实际缓存 AAR 的 javap 输出；不是根据库名猜测。

只读探针挂在当前 setContent，成功返回立即停止，无延时凑绿、全局监听器或业务状态写入。06初次读取消息队列的诊断方法在 PAUSED Looper 不支持，故该轮失败为探针 UnsupportedOperationException，不是原缺陷再次复现；07改为 Handler.hasCallbacks 后才得到有效交付证据。原始失败仍保留。

## 第四项单独核对

将步骤3单独换为 theExclusiveBoundaryFreezesCodeAndNotesUntilItEnds：08为3项中1失败；09相同顺序恢复后3/3。08实际节点和状态保留在 XML：

- vm.codeDraftEditingBlocked=true，窗口411x1800、根已挂载、无需滚动；代码及笔记区域都已组合。
- 旧节点 code-editor 和个人笔记仍为 IsEditable=true，提示0个；Recomposer=Idle、globalPending=true、dispatcher pending=1。
- 09交付原通知后，提示恰好2个，两个输入 IsEditable=false；随后解除边界、最新文字进入备份、恢复可编辑的原断言也通过。

因此这是该测试没有收到状态通知、读到旧节点；证据不能泛化成所有产品权限路径均无缺陷。未把提示缺失简单算成 AppNotIdle，也未调整等待帧数、滚动、原断言或产品权限。

## 生命周期及文本输入

AiConversationUiTest 创建并拥有 SupervisorJob/Main.immediate、Room 和 MockWebServer；Compose rule 创建/销毁宿主和内容。05中 AI owner 在 cancel 后已经 completed，children为空，随后才出现最小复现，故“AI scope 未退出”不是这条复现的根因；OkHttp 线程/Markdown 也没有被当作根因。

正式清理仍补齐所属 Job 的 completion 屏障，包含 collector、request 和 finally 的退出，再关闭 Room、删除自身数据库和 shutdown 自身 server。使用既有20秒等待机制，不取消任何其他 Recomposer、全局 scope 或观察者。GlobalSnapshotManager 是框架共享 consumer，不能当作 AI 所属任务取消。

ComposeMainQueue 仅在主线程、存在待执行工作、scheduled=true 且原 Handler 不含回调时重新投递原回调。锁内读取调度状态，不写私有字段、不清快照、不改 sent/started、不注册新观察者、不吞异常、不清文本/焦点/选择。反射限定于测试 helper，依赖当前 Compose 私有字段；后续升级必须重审，字段变化会显式失败。

新增 ComposeMainQueueTest：用普通 BasicTextField 的真实状态写入，移除其已排队的原回调模拟队列丢失，恢复后核对多行文字和界面 TextSelectionRange；另外验证正常已排队回调只执行一次、空闲调度器不动。此复现不依赖 AI、Markdown、Room、网络或产品输入实现。原三个编辑器用例继续核对选区、缩进、禁用/重新启用行为。

## 对照与实际结果

run-summary.csv 从原始 XML 汇总。所有失败、无效探针、首轮和最终轮均保留。

| 轮次 | 用途 | 实际结果 |
| --- | --- | --- |
| 01 | 缓存成功 AI→纯编辑器 | 2/2 |
| 02 | Accent 两项→缓存成功 AI→编辑器 | 4/4 |
| 03 | 原前缀→原四项 | 33项，4失败 |
| 04 | 缩短到 Boundary 全类前缀，被动采样 | 16项，1失败 |
| 05 | 最短三项，采样 owner/调度/idle | 3项，1原超时 |
| 06 | 首次队列探针不适用 PAUSED | 3项，1探针异常 |
| 07 | 05同序，正常重新投递 | 3/3 |
| 08 | 最短前缀→原第四项，节点采样 | 3项，1原提示缺失 |
| 09 | 08同序，正常重新投递 | 3/3 |
| 10 | 正式修正+原回归+三个新增回归 | 48/48 |
| 11 | 最终源码，补界面选区断言并消除 Kotlin 警告 | 48/48 |
| 12 | 移除诊断后的统一完整套件、Debug、Lint | 34类259/259；Debug成功；Lint 0错误/5既有警告；退出0，2m21s |

03与11的前12个步骤完全相同（fixed-prefix-comparison.json）：Accent2→原AI4（slow、version、color、cache）→CodeDraftBoundary12→CodeDraftPersistence11→原四项。共33项在03四项失败，11均通过；11其后追加 EnhancedAiTest5、LearningDatesAndReadingTest7、ComposeMainQueueTest3，合计48。VC-01原18项为 Accent2+AI4+Enhanced5+LearningDates7，未删减。10到11仅加严实际界面选区断言和清理新代码的 nullable 警告，没有重复不变全套。

最终12的正常全套实际前缀仍为 Accent→AI→CodeDraftBoundary→CodeDraftPersistence→CodeDraftSave，后者15/15，并非把失败类挪到 AI 前。12-class-results.csv 和全部34类XML保留时间与方法顺序；259项全部执行，0失败/错误/跳过。5条Lint警告的ID、严重度、说明与VC-01完全相同（lint-comparison.json）。119项输入在运行前后及主/隔离副本逐字节一致。未另跑不变全套。Gradle二进制报告缓存未纳入交付，原始XML、stdout、失败栈完整保留。

## 命令与证据索引

全部使用既有 tools/build.ps1 的本地离线工具链、原依赖/配置、正常单次 test worker；没有 forkEvery、独立 JVM 配置、拆测试任务兜底、跳过或延长超时。

定向诊断（把 diagnostic-source 中所需 launcher/probe 放入隔离副本 app/src/test/java/cn/embeddedquiz；正式测试环境 helper 按所在轮次源码使用）：

```powershell
$env:EQ_ASCII_LINK='C:\AndroidProjects\EmbeddedQuiz-gpt-t3-backend-20261104'
$env:VC02_MODE='accent-allai-draftprefix-four-regressions' # 最终11；03不含-regressions
./tools/build.ps1 -Tasks @('testDebugUnitTest','--tests','cn.embeddedquiz.Vc02FixedOrderTest','--rerun','--no-daemon')
```

05/07模式 oneboundary；08模式 oneboundary-exclusiveonly；09加 -repair。07在setup调用正常交付；08/09同一诊断源码只按repair模式启用恢复。最终10/11为正式无条件setup，保留原操作断言。

最终先删除 app/src 下的 Vc02FixedOrderTest.kt、Vc02StateProbe.kt，仅保留四项正式测试改动，核对119个输入，再执行一次：

```powershell
./tools/build.ps1 -Tasks @('testDebugUnitTest','assembleDebug','lintDebug','--rerun','--no-daemon')
```

- NN-*.log：每次实际构建输出；对应 -xml/ 为完整原始测试XML，包含失败栈和诊断节点。
- NN-*-order.txt：XML原样导出顺序输出；类内真实方法见对应XML testcase顺序。
- 03-failure-thread-dump.txt：原超时边界的线程栈。
- diagnostic-source/：隔离诊断源码，不进入正式测试集合。
- framework-bytecode/：当前实际依赖的调度/快照/idle字节码。
- base/final-source-fingerprints-*.csv：基线与最终实际运行输入，及隔离副本逐项比对。
- fixed-prefix-comparison.json、fixed-case-order-comparison.json、run-summary.csv：机器可读前缀/计数。

当前状态：**待审查**。不改版本、不推送、不合 main、不发布或覆盖1.6.1；不把既有1.6.1真机回报等同于当前视觉/AI分支验收。Claude无需为本次测试环境修正接入产品接口，Architect集中复审。