# T2 集成审查补充（待审查，有通知竞态待修）

共同起点 e0e4f1b（Claude UI 基于 GPT 831bcbe）；工作区只有 Architect 原有五个未跟踪项，未修改/暂存。首次统一验证前固定完整 HEAD，97 个 Android 输入逐字节匹配独立副本；Claude 三个交付文件 SHA-256 也全部匹配。

## 现有套件与新增边界必须分别看待

第一次固定共同 HEAD 的完整运行：184 项，183 通过、1 失败，0 错误/跳过。Debug 构建成功，Lint 0 错误/5 既有警告。失败是 NotificationEntryUiTest.notificationWithoutExamOpensReviewAndOrdinaryStartStaysHome 启动前报告 UncaughtExceptionsBeforeTest；suppressed 栈为 Room personalMetadata 查询访问已关闭连接池、LearningStateHolder.start 的收集任务尚在退出。不能用 Claude 定向 92/92 或此前 GPT 177/177 替代此次完整结果。

原始证据：01-full.log、01-full-xml/、01-full-summary.csv、01-lint/、source-fingerprints.csv、frontend-fingerprint-verification.csv、schema/。

两个受控回归在原生产代码上均失败（02-probes.log、02-probes-xml/）：

1. 新后端业务用例 clearingOwnerKeepsDatabaseOpenUntilCancelledReadersFinish：取消调用方后，模拟已经进入 SQLite 的读任务仍在 IO 收尾，hold/release 后执行真实 DAO 读取；DB 先关，断言“Database closed while a cancelled reader still owns it”失败。固定释放屏障，finally 始终放行，不遗留测试任务。
2. 新隔离 UI 诊断 notificationWinsWhileLeaveFlushFinishesBeforeTheNextFrame：实际答题页改答 → 写入屏障 → 点返回（等待 flush）→ 通知 requestNotificationReview 设置 REVIEW → 保存成功返回 → 下一次 Compose 帧尚未销毁旧页作用域，原 target() 将页面改成 HOME。期望 REVIEW，实得 HOME。mainClock.autoAdvance=false 只控制帧间事件顺序，没有替换后端/生产导航。诊断源码在 probe-source/，仅加入隔离副本，不加入 app 的正式测试输入。

## 已修后端关库

仅修改 QuizViewModel.onCleared：先取消所属 scope，独立 IO 收尾除了等待考试操作与写入器，还 join 已取消 owner Job，等正在退出的 Room 读取/写入结束后关库。取消 collector 后不保持活动收集，也不增加自动重试、Main 等待或全局生命周期重构。API_CONTRACT 更新实际顺序；新增回归在 InitializationNotificationTest。生产 UI 未改。

后端修复提交 **ebcffc30ea5d9aebeeb099e902d99513622cc223**。03 六类关联测试 **68/68**，0 失败/错误/跳过；04 固定此提交作必要完整复测，**185/185**，0 失败/错误/跳过，Debug 构建成功，Lint **0 错误/5 既有警告**（BUILD SUCCESSFUL，1m54s）。新增一项业务回归使正式套件从 184 增至 185，没有减少/跳过旧测试。

04 前从 app/src 删除仅用于诊断的 ExamLeaveNotificationProbeTest，核对实际源文件集合没有额外输入，97 个 Android 输入逐字节匹配固定 HEAD；失败源码与 XML 仍归档在本验证目录。其未修复的通知问题须作为独立失败读取，不能把 185/185 说成这个问题也通过。

| 轮次 | 来源与范围 | 结果 |
| --- | --- | --- |
| 01 | e0e4f1b 完整正式套件 + 构建 + Lint | 183/184，通过构建；Lint 0/5；关库异常导致 1 项失败 |
| 02 | 原生产代码 + 受控关库回归及独立通知 UI 探针 | 两项都失败；分别实证提前关库、REVIEW 被 HOME 覆盖 |
| 03 | 关库修复，六类关联回归 | 68/68；不运行通知探针，不冒称修 UI |
| 04 | ebcffc3 完整正式套件 + 构建 + Lint | 185/185，通过构建；Lint 0/5；独立 UI 缺口仍未修 |

原始证据：03-close-fix.log、03-close-fix-xml/、03-close-fix-summary.csv；04-full.log、04-full-xml/、04-full-summary.csv、04-tested-head.txt、04-source-fingerprints.csv、lint/、debug-apk-fingerprint.json。首次的指纹/HEAD 保留在 source-fingerprints.csv/tested-head.txt，未覆盖为修复后的版本。最终核对 Claude 三个 UI/测试源文件指纹仍未变化，生产 UI 没有被 GPT 改动。

全套精确命令（01、04）：`tools/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','--rerun','lintDebug','--continue','--no-daemon')`。02/03 的精确选择器见对应 command.txt。仅在实证失败、新增回归与修复后必要复测，没有重复全套来等待随机全绿。UI 探针仍明确失败，状态继续待修。

## Claude 待修：迟到离页回调覆盖通知（P1）

位置：QuestionScreen.kt，ExamLeave.leave 的 `if (saved) target() else blocked = target`。

页面切换到 REVIEW 不会同步完成 Compose dispose，rememberCoroutineScope 的取消要等后续组合帧。因而“页面移出组合会取消等待，所以绝不会迟到跳转”的论断不成立。持久化队列本身继续运行是正确行为，问题是 UI 等待者没有在执行目的地之前重新核对本次离页意图仍归当前页面所有。

建议 Claude 在恢复点检查来源页面/请求所有权仍有效，页面已被通知或其他外部导航接管就丢弃成功回调及失败弹窗；不能只再加生命周期 flush，也不能在通知路径等待保存而改变 REVIEW 优先级。以本目录隔离诊断改成正式回归，保留“通知先设 REVIEW，保存返回在下一帧之前”的顺序。补充失败返回同样不能在新页面触发旧弹窗/导航。

本轮不越界改 QuestionScreen/AppNavigation/MainActivity。该问题尚未修复；最终现有套件即使全通过，也不是 T2 功能验收通过。需要 Claude 聚焦修复提交后再做必要验证，Architect 集中审查。

## 对交接中三点的核对

- T1 Failed/结束阶段由原结束面板恢复，允许离页，与既有 T1 可离页契约一致；retryExamSave 冻结时无操作，不能要求它成功才能解锁该阶段。
- 保存失败没有“仍然离开”，符合 §14.3 的 false 留页、显式重试规则；放弃仍可通过结束协议清场。
- §14.3 写“可”发起后台/通知 flush，不添加不构成违反固定接口。flush 只等待，不隐式写入或重试，不必加无业务效果的生命周期调用。但这不能证明当前通知/离页竞态安全，已由上述实际失败反证。

迁移/回退仍按 GPT 原交付：Room 5、备份 3，台账保留，旧 v4 APK 不能直接读取 v5；没有变更历史迁移/结构、依赖、版本或稳定产物。未推送/发布，T3/T4 与真机验收不在本轮。

状态：**待审查；通知竞态待 Claude 修复，T2 不予自行验收。**