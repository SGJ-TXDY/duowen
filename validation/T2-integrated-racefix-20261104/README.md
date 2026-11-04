# T2 统一集成复测：通知离页竞态补修（待审查）

固定受测 HEAD：`64a7cac0ea3bf628f4ab17833af61d54c344807f`。包含 GPT 后端关库修复 ebcffc3，以及 Claude 的通知竞态修复与两项正式 UI 回归。开工工作区只有 Architect 原有五个未跟踪文件/目录；它们没有修改、暂存。首次完整结果和原诊断保留在 ../T2-integrated-20261104/，本轮不覆盖旧失败证据。

本轮 GPT 只做统一验证和交付文档更新，没有修改业务代码、UI、接口、迁移、依赖或版本，也没有额外诊断源码覆盖。当前版本仍 1.6.0 / 7，不代表发布 1.6.1。

## 固定输入与执行

独立副本 `.gpt-t2-racefix-20261104`、独立 ASCII junction `C:\AndroidProjects\EmbeddedQuiz-gpt-t2-racefix-20261104` 与独立 app/build。从固定提交 git archive 后，将 97 个受测 Android 输入复制为工作区的精确字节，逐一 SHA-256 核对一致；原 Claude 两个交付文件的指纹也全部匹配。对应 source-fingerprints.csv、frontend-fingerprint-verification.csv、tested-head.txt、initial-status.txt、01-full-command.txt。

实际命令（一次完整运行，不是定向结果拼接）：

```powershell
./tools/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','--rerun','lintDebug','--continue','--no-daemon')
```

离线 JDK 17 / Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / Room 2.7.2，使用仓库 tools/build.ps1，不升级工具或依赖。

实际完整结果：**187/187 通过，0 失败、0 错误、0 跳过**；Debug 构建成功；Lint **0 错误、5 个既有警告**。Gradle `BUILD SUCCESSFUL in 3m 51s`，55 个任务真实执行；本轮只运行这一次完整命令，没有用定向结果替代，也没有重复等待全绿。

原始证据：01-full.log、01-full-xml/（所有测试类）、01-full-summary.csv；lint/ 包含原始 XML/HTML/TXT；schema/ 包含 1～5；source-fingerprints.csv 包含工作区及隔离副本的 97 个输入 SHA-256；debug-apk-fingerprint.json 记录本次独立 Debug APK 指纹（未覆盖稳定产物）。notification-regressions.json 从正式 XML 列出两项通知用例及耗时，确认均实际执行。

ExamSaveUiTest 现在为 9 项（包含两项正式通知回归），InitializationNotificationTest 为 14 项（包含关库回归），本次完整运行都通过；其余持久化、迁移、普通练习、通知/设置等全部保留。此前独立通知探针的实证缺口已由 64a7cac 修复并正式回归通过，不再作为当前版本的未修项；此前失败证据仍保留，未覆写历史。

## 这次关闭的原通知缺口

原位置是 QuestionScreen.kt 中 ExamLeave.leave 的 flush 恢复点。现在在发起离页时捕获来源 page 和 practiceSession，保存返回后先核对两者，外部页面/会话已经接管时丢弃成功目的地和失败弹窗。保持后台写入器独立运行；通知路径不等待保存，G1 的 REVIEW 优先级不变。

ExamSaveUiTest 正式纳入两项回归：

- notificationDuringLeaveWinsWhenTheFlushSucceedsBeforeTheNextFrame：保留“改答 → 等待离页保存 → 通知先设 REVIEW → 放行 IO → 下一帧 dispose 前恢复 Main”的原诊断顺序，保证原 HOME 目的地不覆盖通知；磁盘答案仍保留。
- notificationDuringLeaveWinsAndNoStaleDialogWhenTheFlushFails：同样顺序下保存失败不改页面、不显示旧离页对话框；考试入口仍显示真实保存失败，可显式重试成功。此项在未修旧版上也能通过，属于失败路径守护，不虚称第二个已复现缺陷。

Claude 原始前后对照在 ../T2-frontend-race-20261104/：未修成功返回用例失败（REVIEW/HOME），修复后 2/2；相关 95/95 为其定向证据。本轮完整报告独立读取，不以该定向结果替代集成验证。旧隔离诊断不再额外加入正式套件，修复已由正式回归覆盖。

原关库回归 clearingOwnerKeepsDatabaseOpenUntilCancelledReadersFinish 保留；考试保存、事务去重、Room 1/2/3/4→5、备份/恢复守卫、普通练习、通知和既有 UI 等均由完整套件一并运行。持久化设计、崩溃点及非破坏性回退仍以 API_CONTRACT / BACKEND_HANDOFF 和原 T2 后端交付为准，本轮没有改变数据协议。

## 未验证与状态

无新增真机结果：真实进程被杀、真实存储故障、TalkBack 朗读、设备通知/配置变化仍待设备验收。Python 未改未跑；T3/T4 不在本轮。没有修改稳定 APK、版本/签名、共享 REPAIR_DISPATCH 或 Architect 文件，没有推送/标签/Release。

状态：**待审查**。这份统一自动化证据交由 Architect 集中审查，不自行宣布 T2 最终验收或解除其他发布条件。