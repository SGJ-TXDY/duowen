# 后端交接文档（BACKEND_HANDOFF）

## 2026-11-04 · T3 双方统一集成复测（待审查）

任务/职责：按 REPAIR_DISPATCH §16.4，GPT 固定 Claude 前端 f6bcbcb 与后端 c21b86f/25b3142 的共同版本统一验证。§16 已批准后端阶段与实际契约，覆盖下文历史“接口尚待裁定”的状态；T3 整体验收仍交 Architect。开工 HEAD f6bcbcbbefad1f0dc3653c8ed732defcea3565a8，工作区只有 Architect 原五个未跟踪项，未修改、暂存。

首次完整运行 **210 项、207 通过、3 失败**，0 错误/跳过；Debug 构建成功，Lint 0 错误/5 既有警告。原始失败全部保留：ExamTerminationUiTest 在异步答案保存完成前取旧快照作基线；QuickNotesIsolationTest 在 Progress Flow 首次发射前取空复习列表作基线；StartupTest 未隔离默认工厂静态 Application 缓存，期望 906、实际 1。后者的普通定序对照未复现题数错误，但记录到旧 Application；受控旧 Application 一题夹具再现 906/1，重置工厂后关闭该失效机制，未虚称首轮已经记录到相同上下文路径。

经用户明确授权跨既有测试文件修正，提交 `cb4a70a518514ea5f654eaa2939f8f2b0f5ecdc8`：只增加 5 行。ExamTerminationUiTest 在取保存快照基线前等 examSaveState.Saved；QuickNotesIsolationTest 在操作前等夹具 progress[a].seen==1；StartupTest 创建 Activity 前复用 resetDefaultViewModelFactory。原业务/数据断言全部保留，没有增加 sleep、跳过测试或修改生产业务/UI。三个候选修正的受控定向 **21/21**。其余正式源码、接口、迁移、版本、依赖、共享派发与前端交接均未改。

最终固定 cb4a70a，再必要统一运行 assembleDebug testDebugUnitTest --rerun lintDebug --continue --no-daemon：**210/210 通过，0 失败、0 错误、0 跳过**；28 个测试类，包括新 StartupRecoveryUiTest 8 项与全部既有考试/通知/学习/迁移/速记测试。Debug 构建成功，Lint **0 错误/5 条既有警告**，ID/说明与首轮一致；命令退出 0，BUILD SUCCESSFUL in 1m 46s。105 项 Android 输入在运行前后及隔离副本逐字节一致，Claude 三个交付文件的原指纹全部匹配。正式复测前已删除临时诊断 Suite 并覆盖回正式源码，未把隔离探针混入统计。

证据：[T3 统一集成](../validation/T3-integrated-20261104/README.md)。含首轮完整失败、未复现的定序记录、受控原失败/候选通过、最终完整原始 XML/日志、两轮源码指纹、Lint XML/HTML/TXT、schema 1～5 与独立 Debug 产物摘要。复用 GPT 自有隔离构建目录，没有覆盖 Claude 的 app/build；没有为凑全绿重复不变的全套。

Git 自动后台整理 File exists：只读 fsck --full --no-reflogs 返回 0，仅 dangling blob，未发现缺失/损坏对象。不对报错根因作超出证据的判断，未 gc/prune、删锁、改配置或重写历史；本轮交付提交单次禁用自动整理。

接口与数据：仍为 §16.2/正式 API_CONTRACT 的实际 T3 接口，Room 5、备份 3，无新协议。Claude 错误页、显式重试、诊断/导出与恢复确认已经纳入本次真实全套；恢复 Completed 不当 Ready、不可用 DB 不自动替换、活动/未知考试拒绝、通知优先和原考试保留均维持。日期刷新观察项经审阅只写内存日期，Ready 前无业务订阅；保留原循环，未改 UI，门控变体的测试不空闲根因仍未确定，不冒称已修。

交由 Architect 集中审查共同版本、五行测试修正及证据。Claude 后续配合审查/设备验证；本轮没有另派 UI 开发或改变契约。真机文件选择器/真实存储与强杀、TalkBack、系统字号/配置变化仍待验收。T3 整体验收前不开始 T4；没有推送、标签、Release 或覆盖稳定 APK。

状态：**待审查**。

---

## 2026-11-04 · T3 后端：历史规则隔离、启动诊断与显式恢复（待审查）

任务/职责：用户“修 T3”授权开修，GPT 负责后端；T2 已按共享文档第 15 节验收。起始 HEAD 281d035。原工作区只有 Architect 的五个未跟踪文件/目录，未改或暂存。T3 固定接口尚未补齐，因此先写 T3_BACKEND_PROPOSAL 和实际 API_CONTRACT，供 Architect 审查后 Claude 接入；不冒称新的正式派发已经存在。

修改文件/函数：LearningMigration 3→4 改用新增 HistoricalQuestionV3 的冻结读取/索引，不再调用当前导入校验；Repository 的历史读取、随包更新旧行读取和启动校验；新增 PreservingOpenHelperFactory 及 Data.open 注册，禁用允许丢数据恢复和默认损坏删库；新增 StartupStorageCheck 在 Ready 前只读预检 schema 5 的依赖表/列；QuizViewModel 的分阶段启动、按次结果、显式重试及诊断/恢复门面；NotesStateHolder 只把 VM 首次订阅延后（其他调用方默认行为兼容）；ExamStateHolder 仅允许失败初始化重入原守卫，未知考试不释放保护；新增 HistoricalMigrationTest、StartupRecoveryTest；本文、API_CONTRACT、方案和独立验证目录。没有动 Compose/UI/MainActivity、评分/SRS、OrderedWriter、QuestionDraft、题库、依赖、版本或共享派发。

行为/接口详见 API_CONTRACT 顶部 T3 节：startupState 的 Loading/Ready/Error 与阶段；retryInitialization 只显式受理 Error；awaitInitialization 保留签名，按调用时捕获的尝试返回，旧结果稳定；readStartupDiagnostics 无 Room 前提，exportStartupDiagnostics 流式保留原库现场；restoreStartupBackup 必须确认、DB 可安全使用且无活动/未知考试，替换事务后新初始化，不把 Completed 等同 Ready。所有重工作在 IO，重复任务串行，失败不重复创建订阅，通知请求与考试内容保留。

采用明确失败，不做历史异常容错：坏 JSON、未知题型、无效选项/答案和 ID 不一致使迁移回滚，原记录保留。当前 v4/v5 库启动也验证，不静默覆盖异常行；存储读取走冻结规则，外部导入仍严格。没有声称证明了 v4 新增校验拒绝合法旧数据。

数据库打不开时明确拒绝学习备份替换，提供不依赖数据库打开的诊断 ZIP；不自动建新库替换旧数据，不丢终止台账。ZIP 是诊断现场，不是可直接导入备份；缓存考试值不证明 commit 成功，数据变动/取消/失败可能留下不完整目的文件，但源保持。导出不读取密钥/完整偏好，包含用户数据库内容，须用户主动选择目的地。禁止私有源数据作为目的地。

已有相关启动/考试/迁移回归通过；文件型 1/2/3→5 原字段保留与五类坏历史逐路径回滚通过；12 项新启动恢复业务通过，涵盖真实损坏库字节保留/导出/同实例修复后重试、v4 启动、当前坏条目、通知和考试、重试风暴/独立取消、订阅不重复、恢复确认/失败/台账保留/未知考试拒绝、源目标隔离、owner clear。测试编写中 JUnit setup 末尾返回 Boolean 导致一轮类初始化失败，已改为 Unit，原始失败保留；未跳过或改等待时间。

首次固定 c21b86f 完整测试 201/201 通过后，追加缺依赖故障用例，真实复现了缺表仍发布 Ready；补充只读依赖预检后，缺表/缺列及启动/迁移/通知关联 29/29 通过。原失败保留，不把首轮全绿视为覆盖了该缺口。实现 c21b86f、补修 25b3142；固定补修后完整复测 **202/202，0 失败/错误/跳过**，Debug 构建成功，Lint **0 错误/5 既有警告**。103 项输入运行前后逐字节匹配，最终命令退出 0（7m 58s）。原始 XML/日志、失败对照、源码/schema 指纹及 Debug 产物摘要见 [T3 验证](../validation/T3-backend-20261104/README.md)。当前仅后端完成相关验证，不代表 T3 UI 已经接入或整体验收。

数据与回退：Room 5、备份 3，无新结构，schema 1～5 不改。代码可回退但不得降级 DB 或自动删数据；保留升级前备份及诊断原库。外部直接换库仍不在 T2 去重保证内，v3 备份不含本机台账/运行考试。新的故障恢复未绕开已验收的考试守卫。

Claude 待接入：AppNavigation 的启动分支及必要错误页，使用 startupState 而不只看 ready；Error 时可查看/导出诊断和显式重试；恢复备份先说明替换范围并确认，再调用 restoreStartupBackup(uri,true)，展示 Rejected/Failed，Completed 后继续观察新启动状态；保持 G1 通知优先级，不读 SQL/偏好、不清库。需 Architect 确认本次接口与明确失败/恢复限制，再做 UI 和 Compose 测试。未进行 UI 接入、真机或 T4，不自行发布。

状态：**待审查**。

---

## 2026-11-04 · T2 统一复测（含 64a7cac，待 Architect 集中审查）

固定受测 HEAD：`64a7cac0ea3bf628f4ab17833af61d54c344807f`。包含双方 T2 实现、后端关库修复 ebcffc3 和 Claude 通知竞态补修。工作区仅保留 Architect 原五个未跟踪项，没有暂存或修改。本轮 GPT 不改生产代码/UI/接口/迁移/版本，仅更新本文与新验证目录。

按 §14.4 在独立输出中统一运行一次 `assembleDebug testDebugUnitTest --rerun lintDebug --continue --no-daemon`：**187/187**，0 失败/错误/跳过；Debug 构建成功，Lint **0 错误/5 既有警告**。运行前后核对 97 个 Android 输入的逐字节 SHA-256，Claude 本轮两个交付文件指纹匹配；不是拼接定向通过结果，也没有加入临时诊断测试。

原通知 P1 已在该版本补修：ExamLeave 发起时记录 page/practiceSession，flush 返回后发现来源已变化就丢弃成功导航及失败弹窗。两项正式 ExamSaveUiTest 通知回归保留“通知先设置 REVIEW、IO 随后返回、下一帧 dispose 尚未发生”的时序，均在本次全套中通过；成功返回用例关闭原 REVIEW 被 HOME 覆盖的缺口，失败返回用例是保护（旧版也可通过，未虚称第二个复现）。原关库回归亦通过。此前集成段中的 UI 待修状态描述的是旧 HEAD；当前缺口已补并验证，历史证据不删改。

证据：[T2 含通知补修的统一集成复测](../validation/T2-integrated-racefix-20261104/README.md)。含所有原始 XML/日志、Lint XML/HTML/TXT、schema 1～5、源码双份指纹和 Debug APK SHA-256。01-full-command.txt/tested-head.txt 固定实际受测提交；本轮交付提交仅增加文档/证据，不改变受测 Android 源码。

交由 Architect 集中审查：原 T2 事务内终止台账/去重、匿名身份升级与失败放弃、非破坏性迁移及回退、ebcffc3 的关库资源顺序、64a7cac 的离页请求有效性检查。无需按例重复全套来替代审查；真机进程/存储故障、通知/配置变化与 TalkBack 尚未验收，T3/T4 未开展，本轮不解除其他发布条件。

状态：**待审查**，未自行宣布 T2 最终验收；未修改 REPAIR_DISPATCH/FRONTEND_HANDOFF、版本、稳定 APK 或签名，未推送/标签/Release。

---

## 2026-11-04 · T2 双方集成：关库竞态修复，通知离页竞态待 Claude 处理（待审查）

起点 e0e4f1b（GPT 后端交付 + Claude T2 UI），工作区仍仅 Architect 原五个未跟踪项。按 §14.4 固定双方提交统一运行，184 项 183 通过、1 失败，构建成功、Lint 0 错误/5 警告；没有把定向结果当成全套验收。

完整运行发现取消中的 Room personalMetadata 读取访问已关闭连接池，随后通知 UI 测试启动前抛 UncaughtExceptionsBeforeTest。新增 InitializationNotificationTest.clearingOwnerKeepsDatabaseOpenUntilCancelledReadersFinish，以受控 IO 读屏障验证：原代码提前关库失败。修复仅在 QuizViewModel.onCleared：取消本实例任务，独立 IO 收尾等待 owner Job、考试 operation 和 writer 退出后关库，不保持活动 collector、不阻塞 Main。API_CONTRACT 同步实际生命周期保证；未修改 UI 或评分/存储格式。六类关联测试 **68/68** 通过（含原失败通知类、新读任务回归、新考试保存 UI）。关库修复提交 `ebcffc3`；固定此 HEAD 必要完整复测 **185/185**，0 失败/错误/跳过，Debug 构建成功，Lint 0 错误/5 既有警告，完整报告及 97 项指纹见新证据目录。独立通知竞态测试仍失败，未将其排除后冒称功能验收。

另有独立实证的 UI P1，未越界修改：QuestionScreen.kt:263，等待离页 flush 时通知先设 REVIEW，在下一个组合帧 dispose 之前，保存成功回调又执行原 HOME 目的地。隔离实际 Compose 用例期望 REVIEW、实得 HOME，原始 XML 和诊断源码保存在 probe-source/。仅依赖 rememberCoroutineScope 离开组合时取消不足以关闭这个窗口。Claude 需在恢复点核对来源页面/离页请求仍有效，再执行目的地或弹错误；通知已接管就丢弃旧结果。正式加入该回归后再交集成。后台/通知 flush 在 §14.3 是可选项，添加它本身不修复此问题。

证据：[双方集成补充](../validation/T2-integrated-20261104/README.md)，含固定 HEAD、97 项输入指纹、Claude 交付指纹核对、完整首次失败、两个受控原失败、后端修复定向及必要完整复测。隔离 UI 诊断不混入固定 HEAD 的正式套件统计；既有套件全通过也不能冒称该竞态已修。

状态：**待审查；通知竞态待 Claude 修复，T2 尚不能验收。** 未改 REPAIR_DISPATCH、前端交付文档或生产 UI；未暂存 Architect 文件，未推送或发布。

---

## 2026-11-04 · T2 后端异步保存与持久化幂等（待审查）

任务/执行者：REPAIR_DISPATCH §14，GPT 后端。起始 HEAD `e3be2a6bfb13e077182848625e3a64cbc6b6de19`。开工仅有 Architect 的未跟踪派发、方案和验收目录；均未修改或暂存。当前交付代码 SHA、实际验证数和日志见 validation/T2-backend-20261104/README.md 与最终交付回复。

修改范围：ExamStateHolder 的快照、冻结提交、恢复与结束状态；新增 ExamPersistence 有序合并写入器；新增 ExamMigration 的终止实体、4→5 迁移与数据替换守卫；Data 的实体/DAO/迁移注册及 schema 5；Repository 仅增加考试事务和 restore/reset 冲突保护；QuizViewModel 的保存门面、启动守卫、结束任务与 DB 关闭协调。专用 ExamPersistenceTest、ExamMigrationTest；旧 ExamLifecycleContractTest 和 InitializationNotificationTest 仅调整异步完成/会话 ID 升级断言并补启动守卫。PersonalMarksContractTest 仅把当前 Room 版本断言改为 5；NotificationEntryUiTest 仅把后台恢复快照原文比较改为 flush 后语义字段和跨通知/重建稳定 ID 比较，原 UI 操作/页面/可见性断言保持。API_CONTRACT 同步实际实现。没有修改生产 UI/MainActivity、DurablePreferences、评分/SRS、普通 record、依赖、版本或历史 schema/迁移。

实际提交与结果：后端 `5923f4f`，完整回归暴露的两处旧存储契约断言适配 `8b88842`；固定后者复测 **177/177**，0 失败/错误/跳过，Debug 构建成功，Lint 0 错误/5 既有警告。首轮完整 177 项中 5 项旧断言失败的原始报告一并保留。定向后端 **56/56**；双方 T2 UI 集成尚待 Claude 提交，不能以当前基线 UI 全绿冒称新版 UI 已验。精确报告、96 项源码指纹及 schema 见 [验证目录](../validation/T2-backend-20261104/README.md)。

固定接口已实现：examSaveState、retryExamSave、suspend flushExamSave；既有 startExam/answerExam/saveExam/finishExam/abandonExam/retryExamOperation 与 T1 状态/成绩签名保留。详细线程、取消、成功/失败语义以 API_CONTRACT 顶部 T2 节为准。

持久化时序：

1. Main 生成随机 UUID；复制不可变快照，revision 递增，发布 Saving。后台写入器最多一份在途加一份最新待写，不占 busy。
2. IO 构建 JSON、commit。仅成功确认最新快照 Saved；失败保留 Error 和输入，不自动无限重试。flush 等到捕获版本或更高版本可靠写入，调用方取消不取消队列。
3. 交卷同步冻结并 Submitting；保存屏障失败明确“考试保存失败，未提交成绩”，允许显式重试或确认放弃。屏障成功后，同一互斥锁内进入 Room 事务。
4. 事务首先查 sessionId/匿名迁移别名；已有终止结果直接复用。否则整卷校验、仅非空作答调用原 record，并与 SUBMITTED/五字段结果共同提交。未答题只参与考试分母，零学习副作用。
5. 放弃先隔离待写并等在途写完，写 ABANDONED 后删除偏好快照；不以答案保存成功为前提，不写学习表。
6. 终止标记已知后禁止保存，清理失败保持锁定及原结果；启动恢复先查询标记，只进入清理，不重新判分。G1 仍在恢复状态之后统一选页，通知优先 REVIEW。
7. clear 拒绝新输入，有限等待本次考试写入/受保护操作，再关闭 DB；没有无限重试/全局 runBlocking。启动恢复期间也先预留守卫，防止尚未读出考试时 reset/restore 清场。

崩溃/故障点：

| 点位 | 已持久化内容 | 新实例行为 |
| --- | --- | --- |
| 首次/最新快照保存失败 | 可能只有更旧快照；未提交学习 | Error、提交屏障失败；允许放弃。立即强杀前新输入可丢 |
| 旧匿名身份升级失败 | 原匿名快照 | 不允许记账；放弃使用原文散列迁移别名识别旧快照 |
| 成绩事务前失败 | 已确认提交快照 | 无学习记录，恢复后可重提 |
| 事务中 record 或 marker 插入失败 | 整个事务回滚 | Attempt/Progress/DailyCount 不变，恢复后一次重提 |
| 事务提交后、清理前失败 | 学习记录和 SUBMITTED/结果共同存在 | 恢复原成绩，只清理；即使题目改变也不重判 |
| 放弃 marker 后清理失败 | ABANDONED 存在，无学习写入 | 冻结清理态，不能恢复为可答考试 |
| 删除偏好 commit=false、内存键已丢 | 不把内存视作持久化证明 | 本实例守卫继续锁定；独立缓存按磁盘和台账恢复 |
| 终止后旧快照再次出现 | 台账保留 | 再次识别并只清理；不重记、不复活 |

迁移：Room 4→5 只新增 exam_terminations 表及 legacyHash 可空唯一索引；1/2/3→4 代码、schema 1～4 原封保留，由现有历史迁移测试继续验证到当前 v5。新场次 sessionId 始终随机 UUID。legacyHash 仅为旧匿名快照的迁移一致性别名，不是新场次身份；解决匿名 ID 写失败、放弃清理也失败后的重启识别。终止台账不自动清除。

备份/回退：刷题备份仍 v3，不含本机活动考试、台账或 API Key。活动、恢复中、结束失败或待清理考试时，恢复/重置明确拒绝；成功恢复/重置保留本机台账。升级前结束考试、保存并核验可恢复 v3 备份，保留原 APK/原备份。v4 APK 不能直接读 v5；源码 revert 不降级 DB。优先前向修复并保留 v5 识别和台账。必须退旧版时，在用户明确确认数据替换后，用升级前备份在独立干净旧版环境恢复、核验后再切换；本轮不执行删库/卸载/自动降级。v3 备份不携带去重台账，回旧 APK 或外部替换整份 DB 不受当前幂等协议保证。升级前历史重复无法追溯判定。

测试与证据：独立副本、独立 ASCII 构建入口，原始失败和修复后报告均保留。最终统计在 validation README；不将前轮失败与后轮通过拼接成全绿。故障覆盖真实文件偏好（false 可改缓存、不改磁盘）、重新打开 Room DB/独立偏好、旧 ID 失败/稳定、新场次不同 UUID、事务前/中/marker/后/清理、保存/结束交错、旧完成、新会话、取消 flush、owner clear、备份与冲突。500ms 屏障实际运行主线程事件和连续 answerExam，未仅断言 dispatcher。新 ViewModel 验证提交后恢复原成绩、通知 REVIEW 和无重复学习记录。

Claude 待接入：

- 按 saveState 显示“保存中 / 已保存到本机 / 保存失败，最新修改尚未保存”；Inactive 不显示已保存。
- 保存任务不禁答题/翻题；T1 结束/失败状态先冻结输入，清理失败只显示原结果和 retryExamOperation。
- 主动离页 await flushExamSave=true 再导航，false 保持原页，显式 retryExamSave 后再 flush；后台仅一次尽力异步 flush，不阻塞生命周期，不拦 G1 通知优先级。
- 测试可用 internal examState.persistence.beforeWrite、hooks、sessionPreferences；生产 UI 不访问偏好/DAO。恢复后 result 来自台账，不能自行重提成绩。
- 本仓库尚未出现 Claude 的 T2 UI 聚焦提交。当前后端与基线 UI 的全套回归不代替双方 T2 集成；其提交可用后按 §14.4 固定共同 HEAD 做统一验证。

未验证：Android 真进程强杀/真实设备存储故障、通知/配置变化真机和新版保存 UI；不以文件重开单测替代真机验收。T3/T4 不在范围内。未修改共享派发文档、未覆盖他人改动、未发布。

状态：**待审查**。

---

## 2026-10-26 · G1 后端：统一初始化完成与通知复习顺序（待审查）

任务 ID：G1 后端。执行者：GPT。本轮用户明确要求实施初始化/通知顺序修复并补业务测试；共享派发状态仍由 Architect 维护，没有修改 REPAIR_DISPATCH。

起始 HEAD：d240f437eb4ad32531e655ad5c184c8257ca1dc5。

交付 commit / 未提交说明：本节与后端、测试、契约及 validation/G1-backend-20261026 随同一个聚焦提交，实际 SHA 见交付回复。保留原未跟踪的派发文档、方案和 Architect 审查证据，不顺带暂存。

修改文件与对应函数：QuizViewModel.kt 的 InitializationOutcome、requestNotificationReview、awaitInitialization、init 恢复及完成/失败/取消发布；新增 InitializationNotificationTest.kt（12 项）；API_CONTRACT 顶部 G1 段、本文及独立验证目录。MainActivity/AppNavigation/Compose/Repository/DAO/既有测试均未修改。

接口/行为变化：

- Main 的 requestNotificationReview 合并初始化期间的通知请求；IO 准备完成后在 Main 恢复考试、选定最终页面、设置 ready，再完成一次信号，不在这些步骤之间挂起。通知优先 REVIEW，无通知则保留普通启动恢复 QUESTION 的规则。
- suspend awaitInitialization 返回 Ready(examRestored) 或 Failed(message)，多个等待者共享稳定结果；取消单个等待不取消初始化，ViewModel 生命周期取消尚未完成的信号。
- 初始化期间既有 page=REVIEW 入口也被保留，不依赖 Compose 后置效应来覆盖 QUESTION。初始化后通知即时 REVIEW，不占 busy，不清空/保存考试、不写学习记录。
- 初始化失败保持 ready=false，返回明确 Failed，不由通知命令自动重试或清理考试；未启动 T3。正式详细线程/结果/取消语义见 API_CONTRACT。

是否超出白名单：无后端职责越界；本轮用户已明确授权实施 G1 后端，未修改通知导航 UI、全局任务调度、数据格式或其他 P1。

修复前失败场景与证据：上一轮固定 d240f43/9a3987d 完整回归 143/144，G1 期望 REVIEW 实际 QUESTION；原因是旧入口先写 REVIEW、异步恢复再写 QUESTION、依赖组合效应最后回 REVIEW，ready 不代表通知目标已经应用。本轮后端统一决定页面，旧入口保持兼容。

修复后测试命令、数量、结果、报告位置：在固定基线加本次源码覆盖的独立副本运行。定向 54/54；一次完整 Android 156/156，均 0 失败/错误/跳过；原 ReviewReminderTest 4/4 通过，新增业务 12/12，T1 后端 28/28 与考试 UI 12/12 保持通过。assembleDebug 成功，Lint 0 错误/5 既有警告。精确命令、原始 XML/日志和源码指纹见 [G1 后端验证](../validation/G1-backend-20261026/README.md)。

故障注入/生命周期/并发验证：初始化前/后及重复通知；普通任务 busy 仍受理；多个等待者及独立取消；实际删除题目表触发读取失败，确认 Failed 且考试/学习记录保持；ViewModel 清理取消未完成等待。未答题缺行、原 Progress 所有字段、Attempt 和 DailyCount 均比较不变。没有通过加 sleep、跳过测试或削弱旧断言让结果通过。

尚未验证：MainActivity 显式新门面接入由 Claude 负责（当前旧入口已兼容并实测通过）；实际 onNewIntent UI、配置变更、真机和已知 G1 Activity 自有 Recomposer 取消完成的正式测试清理。一次完整通过不关闭该隔离风险。Python 未改未跑，T2 跨进程窗口保留。

数据兼容与回退：Room 4、备份 3、原考试快照字段不变，没有新增持久化标记；可独立回退本次源码提交，但回退会恢复旧的通知优先级竞争。没有覆盖稳定 APK、签名/版本或原题库，没有发布。

需要 Claude 接入：在 openNotification/onNewIntent 调用 requestNotificationReview 后消费 Intent extra；移除重复直接 page 写入与 reviewAfterLoad/LaunchedEffect 竞态。需要等待时调用 awaitInitialization，不把 ready 或单次 Looper.idle 当通知处理完成。恢复数据读既有 queue/index/examAnswers/examDeadline；不自行清理快照。另按 Architect 分配修复 Activity 自有 Recomposer 测试收尾，不能全局取消其他实例。

状态：G1 后端待审查；不是整个 G1 或稳定发布自行验收。

---


## 2026-10-26 · T1 收尾：隔离调查、G1 只读分析与统一集成（GPT，11.4）

任务 ID：T1 收尾（9.3/11.4）；G1 仅只读分析。执行者：GPT。

起始 HEAD：8b8a0cd1e9b4c6c93682e9293e042017870e973a。Claude 收尾独立提交后，固定集成源码 HEAD 为 9a3987dd6daa5f6bec65c20c2815abd21d9e8de7。

交付 commit / 改动说明：本节随独立的调查/集成证据提交交付，最终 SHA 见交付回复；不暂存 Architect 的 REPAIR_DISPATCH、方案稿或审查目录。本轮正式仓库只改本文并新增两个 validation 证据目录，没有生产或正式测试源码补丁。

修改文件与对应函数：BACKEND_HANDOFF 本节；validation/T1-isolation-investigation-20261026（诊断源码文本、固定顺序 XML/日志、G1 事件与修复范围报告）；validation/T1-integrated-20261026（完整结果、源码指纹、Lint）。临时诊断类只在隔离副本编译，不进入产品测试发现。

接口/行为变化：无。正式 G1 修复未实施，推荐 requestNotificationReview 与 awaitInitialization 契约仅为送审建议，不属于当前可调用接口。

是否超出白名单：无。没有改 Claude 文件或原有 ReviewReminderTest；未改通知导航、共享生命周期、构建依赖或 T2。

修复前失败场景与证据：历史 9 项 UI 超时对应运行将上次失败的 Notes/G1 优先执行；本地 Gradle 实现与原始 XML 时间顺序吻合。最小固定 G1→单个 UI 即可复现进入测试体前超时，G1 自身通过且无新增 T1 后端测试参与，不能再凭观察顺序归咎于后端。

修复后测试/调查命令、数量、结果：无正式源码修复，不冒称已修复。隔离对照 01 为 2/3，02 为 2/2，03 为 1/2，04 为 1/2，05 为 2/2，全部 0 错误/跳过。G1 destroy 后 runningRecomposers 仍为 1；只在诊断副本等待其退出至 0，后续同一 UI 测试通过。具体命令及原始失败/通过证据见 [隔离调查](../validation/T1-isolation-investigation-20261026/README.md)。

故障注入/生命周期/并发验证：按原始 3628e88 建立独立源码副本与 app/build；固定嵌套 Suite 顺序，不改原测试断言或 Espresso 超时。取消完成使用明确状态条件；没有全局取消其他重组器。当前未定位到最终 T1 新后端测试需再修的清理缺陷。正式修复点属于旧 G1 Activity/Compose 测试收尾，按授权只给出范围方案。

统一集成验证：只运行一次固定 9a3987d 的 assembleDebug + 完整 testDebugUnitTest --rerun + lintDebug --continue。144 项中 143 通过，唯一失败为 G1 REVIEW/QUESTION；T1 UI 12/12、新后端 28/28、原 9 项 UI 9/9。Debug 构建成功；Lint 0 错误/5 警告，整体命令因 G1 失败非零退出。日志/XML/源码指纹见 [集成证据](../validation/T1-integrated-20261026/README.md)，不得标全绿。

尚未验证：G1 正式修复、所有其他顺序组合、真机小屏/主题/字号/TalkBack、配置变更/进程边界；Python 未改未重跑。诊断对照不是正式产品修复，也不能以本次全套未复现九项超时掩盖隔离缺口。

数据兼容与回退：本轮仅文档/证据，不改变 Room 4、备份 3、题库、签名或稳定 APK；可独立回退本轮报告提交。原 T1/T2 兼容与跨进程窗口说明继续有效，无发布或稳定标签。

需要另一执行者接入的事项：Architect 一次集中审查根因、固定版本结果及 [G1 最小跨层范围/接口建议](../validation/T1-isolation-investigation-20261026/G1-analysis.md)，再明确分配 MainActivity 通知接入、QuizViewModel 初始化/通知完成协议、ReviewReminderTest 断言等待及 Activity 自有重组器收尾的具体函数所有权。Claude 本轮只读翻题补充 12/12 已在集成版本中；本轮不替其改 UI。

状态：待审查；T1 整体未验收，T2 待派发。

---


## 2026-10-26 · T1 考试后端交付（GPT，待审查）

本节按 REPAIR_DISPATCH.md 第 8 节实施裁定交付。方案稿保留原样；本节与 API_CONTRACT 的 T1 段描述当前实现。没有修改 Architect 维护的派发文档或 Claude 的 UI。

任务 ID：T1。

执行者及职责：GPT；考试结果、学习记录隔离、放弃/重试状态机与业务/存储故障测试。

起始 HEAD：`92d1cd70df874680caef78ca95ccc0a97b6e8b6d`。

交付 commit / 当前未提交改动说明：本节随 T1 聚焦提交交付，实际 SHA 见最终交付回复；原未跟踪 `docs/REPAIR_DISPATCH.md`、`docs/T1_BACKEND_PROPOSAL.md` 不纳入提交，不覆盖其内容。测试报告与日志随同交付。

修改文件与对应函数：

- `ExamStateHolder.kt`：新增 ExamResult、ExamOperation/ExamFailureStage/ExamOperationState；grade 在一个外层 Room 事务内按冻结题号加载整卷并验证资格，只对非空作答调用原 record；accept/acceptRetry/executeOperation 实现结束状态机；startSession/active/save 提供独立内存会话守卫。新增仅考试使用的内部故障接缝。
- `QuizViewModel.kt`：finishExam、abandonExam、retryExamOperation、launchExamOperation，暴露 examOperationState/examSummary；start/startExam/answerExam 只调整考试守卫；onCleared 仅增加考试所属生命周期结束标记，其他队列及数据库关闭方式保持。
- 新增 `ExamStateHolderTest.kt`（8 项）与 `ExamLifecycleContractTest.kt`（20 项）；不修改既有测试。
- `API_CONTRACT.md`：正式 T1 接口、失败/重试权限、线程与兼容字段状态表；本文件与 `validation/T1-20261026/`：交付记录、分次日志/XML/源码指纹。

接口/行为变化：

- `finishExam(): Unit` 保持原入口，Failed/进行中/终止状态重复调用不执行、不刷失败 notice；现有超时轮询不会自动重试失败。
- `abandonExam(): Unit` 供二次确认后调用；Failed(SUBMIT, VALIDATION/RECORDS, false) 仍可放弃；已提交成绩或已进入放弃清理的失败不能转换操作。
- `retryExamOperation(): Unit` 只显式重试当前失败，使用原冻结题号/答案；成绩已提交时只清理会话，绝不再次 grade。
- `examSummary: ExamResult?`、`examOperationState: ExamOperationState` 为只读可观察结果。成绩含 total/answered/unanswered/correct/score；未答计零分且分母仍为整卷，不产生 Attempt/Progress/SRS/错题/每日完成副作用。普通练习空答及手动自评不变。
- 非 suspend 门面在 Main 调用、返回不表示完成；受理立即设置进行中与 busy，评分和可靠清理在 IO，结束结果在 Main 发布。兼容字段每种状态的确切值见 API_CONTRACT 顶部 T1 状态表。
- 会话锁不依赖 prefs.contains；commit=false 已删偏好内存键仍保持冲突保护。新会话前迟到 save/answer 不能复活结束的考试。
- 生命周期取消前置释放占用；临界段只保护一次事务结果确认/内存标记/清理尝试。发现 Room 可能自动重开已关闭数据库后，增加考试 ownerCleared 标记，尚未启动的评分事务不再继续；没有改造数据库生命周期。

是否超出白名单：无。onCleared 单行考试标记落实第 8.2.D 的生命周期边界；没有修改 UI、全局 launchTask、Repository/DAO/算法、数据库/备份/依赖/版本，也未开展 T2 或目录重组。

修复前失败场景与证据：原 grade 无条件对空答 record；空选项按普通 record 判错并污染学习。原 finishExam 用 mapNotNull 缩小缺题分母、事务后依赖清理和 historyPage 再发布成功。原实现未另跑测试；证据来自起始 HEAD 代码核查，不将历史基线结果冒充本次测试。

修复后测试命令、数量、结果、报告位置：最终定向 74/74 通过（含新增 28 项），Lint 0 错误/5 警告；两次全套分别为 130/132、123/132 通过，均未全绿。完整实际命令、计数和结果逐次列于 [T1 验证记录](../validation/T1-20261026/README.md)。保留编译失败、定向失败及各次完整失败运行；不能把后续定向通过合并为“一次全套通过”。Debug 构建仅在标准 build 目录生成产物，不复制或覆盖稳定 APK，不发布。

故障注入/生命周期/并发验证：

- 50 题答 10 题：结果分母 50、恰增 10 Attempt，未答已有 Progress 全对象相等、缺行题不建行，校验每日完成与错题计数。
- 全未答/全答/部分答、个人核对、多选漏选、选择后清空、调用方可变集合复制、手动/超时均覆盖。
- 缺题/判分资格失效后无学习写入，可放弃且新实例不恢复；缺题修复后可显式重试冻结输入。
- SQLite 触发器在第二条 Attempt 插入时失败，整卷回滚且已有学习记录不变；失败后可重试或放弃。
- 假 SharedPreferences 删除真实内存键后返回 commit=false，验证交卷/放弃清理失败仍锁定，自动超时无重试；连续显式清理失败后重试不重复评分。
- 可控 CompletableDeferred 屏障验证临界段前取消、取消显式重试、事务中取消、事务前生命周期清理及成绩提交后关闭数据库；不以盲目 sleep 推断顺序。
- 新生命周期测试在关闭数据库前取消并等待无关 Room 订阅完成，隔离被测考试任务；不吞异常、不修改既有 UI 测试或断言。

尚未验证的内容：Claude 新 UI 尚未接入；确认/取消、返回、状态展示、防连点的 Compose 集成测试和真机验收待 Claude/Architect；完整回归中非 T1 失败逐项保留于验证记录，未擅自修复。跨进程提交幂等、异步快照保存、T2 完成协议未实现；Python 工具未改且本次未运行其测试。生命周期故障测试不等同真实进程强杀保证。

数据兼容与回退方法：Room 仍 4、刷题备份仍 3，原考试快照字段不变；未改稳定 APK、标签、签名身份或原题库。源码可独立回退 T1 提交，但回退会恢复旧版空答污染规则；回退前应结束当前会话。Room 与偏好不是原子提交，成绩已落库而会话未可靠清理时进程退出仍有跨进程重复窗口，留给 T2，不能据本任务宣称可发布。源码回退也不自动回滚已产生的学习记录。

需要另一执行者接入的事项（Architect 审查后再派发 Claude）：

1. 从 examSummary 展示成绩和未答数，确认说明“未答计零分，但不改变复习安排”，不得在前端重新计算学习副作用。
2. 未完成考试入口提供继续与二次确认放弃；取消确认不调用后端，原截止时间继续有效。
3. 观察 busy 和 examOperationState，冻结期间禁用选择、交卷、放弃及新会话；错误重试按钮只调用 retryExamOperation，按 Failed 阶段决定是否还能放弃。
4. Submitted 才展示完成成绩；成绩已写入但清理失败显示真实状态。Abandoned 后使用既有导航离页，后端不替 UI 跳路由。
5. UI 确认/取消、忙时禁用、重复点击、返回/超时和失败重试测试；完整回归中 UI 空闲等待失败需进一步归因，不能当作已通过。

状态：待审查。未自行验收或发布。

---


## 2026-10-25（第五轮）· WebView 故障复盘、最终 APK 与提交（Codex）

用户反馈“修的好”，并明确要求记录 Codex 改动与 Opus 的问题，授权生成最终 APK 并提交 GitHub。本节为最新交付状态，下面各轮“未提交 / 待接入”保留为当时的历史记录，不代表当前状态。源码本轮没有再改动；只补全文档、整理最终产物并提交已有功能。

### Opus 实现中的具体问题

1. **请求分类错误。** `QuickNoteReader.kt` 原 `ReaderClient.shouldInterceptRequest` 只识别 `/quicknotes/{id}/assets/` 图片路径，其余统一返回 HTTP 404；没有先识别阅读正文的主框架请求。与此同时 `ReaderWeb.load` 使用 `loadDataWithBaseURL`，Android WebView 的内部 `data:` 引导请求也会经过拦截器，于是正文被本应用自己拒绝。用户看到 `data:text/html;charset=utf-8;base64,` 和 `ERR_HTTP_RESPONSE_CODE_FAILURE`。
2. **测试覆盖没有到加载协议。** 原 `QuickNotesUiTest` 检查阅读页容器、目录弹层和 HTML 文本 / CSS，并不验证 WebView 正文请求实际得到成功响应。Robolectric 本身不执行真正 Chromium 的页面加载和脚本，所以“页面外壳可见 + 编译 / lint 通过”未能发现正文 404。这是验证范围的缺口，不应据此宣称真实阅读已经验收。
3. **交接规则也有遗漏。** Codex 最初的渲染交接将未匹配资源拒绝规则写得不完整，没有明确正文请求例外；本轮已在 API_CONTRACT 和 QUICK_NOTES_RENDERING 中一起勘误。不能只把错误归结为用户文档或全部归给前端一方。

### Codex 已做的修复

- `ReaderWeb` 新增固定本地正文地址 `https://appassets.androidplatform.net/quicknotes/{id}/document.html` 和 volatile UTF-8 内存快照；改用 `loadUrl` 打开这一个地址，避免 data: 引导与资源拦截混用。
- `ReaderClient` 在图片分支前，仅对**精确匹配当前文档地址且为主框架**的请求返回 `200 / text/html / utf-8`，正文来自内存，不访问网络。
- 原图片 readAsset 分支保持；未知 / 跨域 / 跨文档 / data / file / content 和子框架索取正文继续拒绝。没有粗暴放开全部主请求或全部 HTTPS。
- 未改 Compose 布局、CSS、主题、字号、CSP、nonce、复制与位置脚本；未修改题库、速记存储、阅读位置、数据库版本、备份格式、App 版本或签名。修复是在用户明确授权本次最小前端修复后接入的。
- 新增 `QuickNoteWebTransportTest`，修订接口与渲染约定，保留可复查补丁和验证记录；既有导入文档可继续读，不用重新导入或重置数据。

### 验证与后续验收口径

- 请求路由回归在 Android API 28 / 33 各 6 项，共 12 项；既有速记 UI 测试 5 项，**合计 17 项全过**。覆盖实际 Linux 文档 UTF-8 正文、固定主 URL、主题重载、片段锚点、图片、缺图以及外部 / 跨文档 / 子框架拒绝。
- `assembleDebug`、`lintDebug` 通过，lint 0 错误、5 条已有提示。速记后端另有上一轮 37 项相关测试记录，两组分开执行，不宣称重复跑过完整套件。
- 用户已认可这次正文加载修复；Codex 未执行真实手机的完整设备 / 主题 / 字号矩阵。后续前端仍须在真实 WebView 验收脚本、目录跳转、代码复制、横向滚动与阅读位置；不要把 Robolectric 路由测试等同完整浏览器验收。
- working 时 leave 被拒绝、操作完成结果不明确、TalkBack 滚动记录和既有通知测试时序问题仍按 FRONTEND_HANDOFF 保留为后续事项，本轮不顺手扩展修复。

### 最终 APK 与源码交付

- 最终交付：项目上级目录 `EmbeddedQuiz-1.5.0-quicknotes-final-20261025-debug.apk`；与用户认可的 webfix 包逐字节相同。当前源码与产生该验证包的副本一致，本轮只有文档变化，因此另存已验证二进制，不重复无意义的构建 / 测试。
- 包名 `cn.embeddedquiz`，版本 1.5.0 / versionCode 6；签名 SHA-256 `2c73c905e6d127dd92fbfaf5351058572cb8e78e98beb477eb1a0f37ee43b151`，与原版本一致，可覆盖安装并保留数据。
- APK SHA-256：`7ff99df7ba02ea1f4ed62b9bc966fdcd917b65b61025cd4809549d92b65b0205`，旁有同名 `.apk.sha256` 文件；旧试装包 / 稳定包均保留。APK 二进制不放进 Git 源码历史。
- 本次源码提交包括先前尚未提交的速记后端、Opus 前端及本次加载修复，避免仅提交修复文件而遗漏模块依赖。贡献说明：速记 Compose 前端由 Claude Opus 实现；速记后端、渲染引擎、加载修复及本轮回归由 Codex（OpenAI）实现。
- GitHub 提交 / 推送状态在最终回复报告；本节的提交授权取代此前各轮的“暂不提交”限制。

署名：**Codex（OpenAI）**。

---

## 2026-10-25（第四轮）· 速记导入后 WebView 正文 404 故障（Codex）

### 复现、原因与分工

用户报告：导入实际 Linux 示例 Markdown 并打开后，WebView 显示 `data:text/html;charset=utf-8;base64,`，错误 `net::ERR_HTTP_RESPONSE_CODE_FAILURE`。

核查当前前端 `QuickNoteReader.kt`：ReaderWeb.load 使用 loadDataWithBaseURL；ReaderClient.shouldInterceptRequest 只返回 assets 图片，未命中一律 404，包括正文引导请求。Android 官方文档明确该回调也接收 data: 请求，见 [WebViewClient API](https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,%20android.webkit.WebResourceRequest))。这个确定的拦截缺陷与用户报错吻合；并非 UTF-8、Markdown 表格、题库或速记导入损坏。此前后端渲染交接“未匹配资源返回 404”没有写清正文例外，已同步勘误。

gpt.docx 规定“Claude 负责前端 UI、视觉设计和展示交互。除非我明确授权，否则你不能擅自修改 Claude 负责的前端代码。”因此先在独立构建副本准备并验证最小补丁。随后用户明确授权“本次最小修复”，现已仅将已验证的加载 / 路由改动接入当前 QuickNoteReader.kt，并新增对应回归测试；没有改 Compose 布局 / CSS / 数据。

### 具体修复补丁

`validation/backend-2026-10-25-webview-load/QuickNoteReader-fix.patch`：仅修改 ReaderWeb 的文档加载和 ReaderClient 的请求响应，不修改任何 @Composable 函数、布局、颜色、字号、CSS、文档正文或数据库。

- 当前文档 HTML 转 UTF-8，保存在 volatile 内存快照中；用固定的本地虚拟 document.html 地址加载，不依赖 data: 引导加载。
- 在图片逻辑前，仅为精确匹配当前文档地址的主框架请求返回 200 / text/html / UTF-8；正文不去网络。
- 图片继续走 readAsset，未知、跨文档、跨域、file/content/data 和子框架索取正文仍为 404；CSP、nonce、剪贴板和阅读位置脚本不改。
- 原导入文档和进度可继续使用，无需让用户重新导入或重置数据。

### 回归验证与实际接入状态

隔离副本共 **17 项相关测试通过，0 失败 / 跳过**：新的请求路由回归在 API 28 / 33 各 6 项（共 12），既有 QuickNotesUiTest 5 项。覆盖实际 Linux 文档 UTF-8 正文响应、固定主 URL、主题重载、页内片段、图片、缺图及外部 / 跨文档 / 子框架请求拒绝。首轮检查发现三参数 WebResourceResponse 的状态码未设置，正文已明确返回 200；图片保留原响应语义。没有重复运行完整套件，没有真实手机验证。

用户授权后已应用，当前源码和回归测试与通过 17 项验证的副本逐字节一致，所以未重复运行相同套件。以开发前指纹核对，仅 QuickNoteReader.kt 的已授权加载部分发生变化，另新增 QuickNoteWebTransportTest；其余前端、数据、构建配置、前端交接、历史 APK、Git HEAD 和 index 保持不变。summary.json 记录 patchAppliedToWorkspace=true。独立新试装包已生成：项目上级目录 `EmbeddedQuiz-1.5.0-quicknotes-webfix-20261025-debug.apk`；assembleDebug / lintDebug 通过（0 错误、5 条警告）。版本仍为 1.5.0 / 6，签名 SHA-256 `2c73c905e6d127dd92fbfaf5351058572cb8e78e98beb477eb1a0f37ee43b151` 与原速记包一致，可覆盖安装并保留现有数据。安装包 SHA-256 `7ff99df7ba02ea1f4ed62b9bc966fdcd917b65b61025cd4809549d92b65b0205`。没有覆盖任何旧 APK，没有修改版本或签名，没有提交 / 推送。当时尚未进行完整真机验收；用户后续反馈及源码提交状态见本文件第五轮。已有文档无需重新导入。

署名：Codex（OpenAI）。

---

## 2026-10-25（第三轮）· 独立 Markdown“速记”后端（Codex）

### 范围与结果边界

先阅读项目规则、gpt.docx、现有前后端交接及用户实际 Markdown 后开发。速记与刷题并列且数据隔离，不把文档转题，不计入答题记录、每日目标、成绩、错题或 SRS。本轮只实现后端、语义渲染和业务测试，**底部入口、文档列表、阅读容器仍需 Claude 接入**，不能宣称整套用户界面已完成。

未修改 Compose 页面、UI 测试、正式签名、App 版本号或已有 APK；不提交、不推送 GitHub。构建仅增加固定 CommonMark 核心 / 两个 GFM 扩展的本地 JAR 文件依赖，来源、哈希与 BSD 许可证在 app/libs，便于既有离线工具链构建。

### 已完成

- 独立 `QuickNotesRepository` 内部文件存储与原子索引：.md / 配套 ZIP 导入、UTF-8 / 中文、文档列表、删除、哈希校验、每文档阅读位置与重启读取。
- 重复正文 / 同源文件名返回明确冲突，支持取消和保留两份，不实现静默覆盖。
- `QuickNoteMarkdown` 用成熟 CommonMark 解析器生成语义 HTML、中文 / 重复标题目录 ID、复制代码原文与缺图 / 不支持语法警告。表格和代码有语义横向区域标记，不用原始 Markdown 替代阅读正文。
- `vm.quickNotes` 独立 loading / working / error / 冲突状态、有序位置保存与失败重试；系统 ContentResolver 导入 / 导出适配、系统剪贴板复制接口；不使用刷题 busy / notice。
- 独立速记 ZIP 备份与原子恢复：默认合并 / 相同内容跳过，用户明确确认后可 replace，只替换速记库。题库 Room 4 和原备份 3 保持不变，旧备份不删除速记。
- `vm.openQuickNotes()` 只改变模块 visible 状态；保留 Page、练习草稿、队列、考试答案 / 截止时间。没有增加 Page 枚举来迫使现有 Compose 改动。

### 实际 Markdown 与支持范围

用户示例审计为 7 个标题、**5 张表格**、1 个 C 围栏代码块，包含转义下划线、实体和中文，没有图片或数学公式。原文嵌入图形只有占位文字，不能还原图形，正文与警告明确说明。审计 SHA 和样本在 validation / 测试资源中，原文不修改。

第一版语义支持标题、段落、粗体 / 强调 / 删除线、引用、列表 / 嵌套列表、分割线、HTTP(S)/mailto 与文档内链接、GFM 表格、行内代码、围栏 / 缩进代码、包内 PNG/JPEG/GIF/WebP 图片。无公式排版、语法高亮、Mermaid / 原始 HTML 布局、SVG / 自动远程图片、跨 Markdown 文件导航等扩展承诺。详见 `QUICK_NOTES_RENDERING.md`，不能把语法引擎测试说成 Android 页面已完成视觉验收。

### Claude 接入清单

1. `AppNavigation.kt`：底部五项改为 **首页、模拟考试、速记、错题、更多**，只把原“复习”槽换成速记，不删除 Page.REVIEW、首页复习入口、ReviewScreen 或提醒。由于 Page 枚举本轮不改，可为速记槽单独调用 `vm.openQuickNotes()`，以 `quickNotes.state.visible` 高亮并在内容区选择独立模块。其他底部项先 `quickNotes.leave { vm.navigate(target) }`。复习页面可归首页高亮。
2. 保持 QuizLayout 的日期刷新、通知与考试计时 effects 挂载，**不要为了切换速记把整个考试计时外壳移出组合**。只切换内容；到期时间依旧按墙钟推进，阅读文档不能暂停或重建考试。
3. 文档列表读取 state.documents，展示标题与打开 / 删除 / 导入；SAF OpenDocument 接受 .md / 配套 .zip，取消不调用后端。明确区分配套文档 ZIP 与独立速记备份。loading / working / error / retry 各有反馈。
4. 冲突 state.conflict 非空时显示明确选择：取消 → cancelImport，保留两份 → confirmKeepBoth。不得默认覆盖。删除须说明会删除该文档的正文、图片与阅读位置。
5. 阅读页显示 rendered.htmlBody，提供返回（closeDocument）和目录（headings）；代码本地动作调用 copyCode，图片按 readAsset 拦截。**不可只用 Text 显示 Markdown**。横向表格 / 代码、浅深主题和大字号要求按渲染文档实施。
6. 阅读位置按 anchor + CSS offset + fraction 节流入队；离页 / 后台先提交最后位置并等待已有队列，失败提示 / 重试，不能把布局变化误存成新位置。图片与布局稳定后恢复，字号变化时锚点优先、比例回退。
7. 加入独立“导出 / 恢复速记备份”操作。默认合并；replace=true 必须明确确认，解释只替换速记库。明确原刷题备份不包含速记，避免用户误以为原备份会保护这些文件。
8. 按 renderer 文档限制 WebView 导航 / 资源来源，禁止原始脚本或 file:// 通用访问；缺图、损坏图片、无效锚点和不支持扩展需可见提示。

### 前端验收建议

使用用户实际 Linux 文档和 syntax.md：核对中文与 5 张表格、C 代码缩进 / 原样复制；小屏大字号横向查看宽表格 / 长代码；中文 / 重复标题目录跳转；单 .md 缺图提示与配套 ZIP 图文导入；重启 / 切文档位置恢复；取消 / 保留两份；默认合并和明确替换恢复；浅色 / 深色；从未交卷考试切入速记再返回、考试到时交卷；首页复习和到期提醒仍可用。

### 测试与已知限制

最终通过 **37 项相关测试，0 失败 / 跳过**：QuickNoteMarkdownTest 6、QuickNotesRepositoryTest 8、QuickNotesIsolationTest 4、既有 FrontendQueryContractTest 10、LearningDataTest 7，以及 ReviewReminderTest 的到期通知 / 通知权限两个方法。新增 18 项；未运行完整套件或既有 Compose UI 测试。新增离线 JAR 的 `mergeExtDexDebug` 与 `mergeDebugJavaResource` 也通过，未执行 APK 生成 / 签名。

首轮发现字符转义的编译错误；随后实际验收发现 GFM 表格扩展抢先渲染导致横向容器缺失，已修正注册顺序。新测试曾在主线程阻塞旧 loadQuestion，并遇到 Robolectric 内容流注册差异，均只修正新测试。对应诊断与日志保留。完整相关组完成后，仅补跑文件适配所在类和长标题边界所在渲染类，没有重复跑完整套件。

另外使用独立 Edge HTML 验证外壳验证浅色、深色 / 大字号和语法样例共 3 个场景：390px 宽正文不横向溢出，实际文档 5 张表格和长代码区域可独立横向滚动，中文目录 / 位置几何恢复和样例图片加载成功。参考图与结果在 validation 的 reference-html-check，测试 CSS 不属于产品前端；此结果不能替代 Claude 的 Android WebView / Compose 接入与真机验收。

开发前指纹核对表明，只变更 5 个既有文件（ViewModel、app 的离线依赖声明、依赖说明、两份后端文档），其余为新模块 / 测试 / 文档 / 依赖 / 验证文件。Compose、UI 测试、前端交接、原始 Markdown、Room schema、App 版本与签名、已有 APK / 源码包均未改；实际构建输入与当前源码一致。Git HEAD 和 index 未变，没有暂存、提交或推送，也未执行 assemble / APK 签名任务。验证汇总在 `validation/backend-2026-10-25-quicknotes/summary.json`。

已知限制：前端未接入前不能在现有 App 中直接点到此模块；Android 容器的视觉、实际滚动采集与恢复、横向滚动手势仍待 Claude / 真机验证。已完成的原子位置写入可重启恢复，尚未入队 / 未完成的最后一小段滚动在立即杀进程时可能丢失。

署名：Codex（OpenAI）。

---

## 2026-10-25（第二轮）· 修复参考解法的过期快照覆盖风险（Codex）

### 本轮范围与现状核查

依照 `gpt.docx` 与用户本轮限制，只修改后端、相关业务测试和本交接 / 接口文档；不提交、不推送 GitHub，不修改 Compose 页面、UI 测试、版本号、签名、构建配置，不覆盖已有 APK。

用户描述的工作台 `PersonalMarksEditor` 已在当前工作区被 Claude 移除：`AnswerWorkbench.kt` 现在调用 `PerceivedDifficultyChips`，`PersonalEditors.kt` 只保留难度控件和参考解法编辑器。`FRONTEND_HANDOFF.md` 最新节也记录了这一变化。**本轮保留这些未提交前端工作，没有重复修改。**

仍存在的跨层问题：`VerifiedSolutionEditor` 的保存回调继续调用 `vm.saveMetadata(meta.copy(verifiedCode=code, codeVerified=verified))`。复现：编辑器读取旧 meta → 另一处更新难度 → 点击保存解法 → 整行替换将旧难度写回。分类卡片仍将旧 `meta.tags` 显示为“标签：…”，与全局独立标签口径容易混淆。

### 后端已完成

- 新增 `vm.saveVerifiedSolution(id, code, verified)` 及 Repository 同名 suspend 接口。Room 事务内读取最新个人资料，只更新解法和核对状态，保留难度与旧标签；不存在资料时按默认值建立，题目不存在或校验失败时不写入。
- 难度继续使用已有 `savePerceivedDifficulty`；两个 Repository 增量接口事务并发互不覆盖。ViewModel 依旧受全局 busy 控制，忙时新命令会被忽略，不提供写入队列承诺。
- 旧 `saveMetadata` 的签名、校验、通知和整行替换语义保留；源码补充遗留接口提示，**未单方面改为忽略 tags 或强制合并**。
- 新接口详细输入、Unit / busy / notice、异常、状态变化和兼容约定均已写入 `API_CONTRACT.md` 的“第二轮”增补。

### 旧题目 tags 决策与依赖审计

保留为**只读历史兼容数据**，新产品入口停止编辑；不删列、不清空、不迁移成全局标签。旧标签与全局便签原本是不同数据，保留能避免旧库和旧备份的信息丢失。只读属于新入口政策：旧 saveMetadata 与备份恢复仍可写入合法历史值，保证调用与数据兼容。

- 搜索：继续查旧 `QuestionPersonal.tags`，保留匹配片段及既有搜索结果；契约把“个人标签”改称“旧版题目标记”。全局标签 / 便签仍独立使用 `searchNotes`，不按题目过滤。
- 分类卡片：当前 `BrowseScreen.kt` 生成 `"标签：$tags"` 的历史字段仍显示，交由 Claude 删除这一分支；保留难度感受。不要为隐藏展示去清空数据库，也不要从摘要中伪造全局标签关联。旧标记仍可在搜索片段中出现，以保留历史检索能力。
- 全量备份：备份 3 原样保留 `questionPersonal[].tags` 与解法 / 难度，独立便签在 `stickyNotes`；旧备份 1/2 的既有恢复规则不变。独立个人答案文件仍只导入 / 导出核对答案。
- LearningCodec：保持 tags 字符串原样读写和原有有效性校验，不增加“tags 必须为空”的限制。已核对却空白的解法仍拒绝，无破坏性放宽。
- 数据库版本 4、备份格式 3、Room 导出 schema 保持不变。

### Claude 需要接入（本轮没有修改这些文件）

1. `PersonalEditors.kt` 的 `VerifiedSolutionEditor`：将唯一保存回调替换为 `vm.saveVerifiedSolution(q.id, code, verified)`。该组件同时用于编程区和待核对工作台，统一替换后两处都受益；保持忙时禁用、人工核对含义和本地编辑状态。不要再从 meta 快照整行写回。
2. `BrowseScreen.kt` 的卡片 `personal = listOfNotNull(...)`：删除读取 `it.tags` 并拼接“标签：…”的分支，保留 `it.perceivedDifficulty`。题目级标签不再展示为当前产品字段。搜索匹配片段仍可能含历史标签，这一兼容口径已在契约说明。
3. 工作台现在已是 `PerceivedDifficultyChips`，保持现状；不要恢复 PersonalMarksEditor 或题目标签输入。全局标签继续走 `openStickyNotes`，不增加题号绑定。
4. 完成接入后，针对“保存解法不回退刚修改的难度”、卡片不展示题目标签补充前端验收，并更新前端交接记录。**在该回调尚未替换前，旧页面仍有快照覆盖风险，本轮后端接口不会自动替换旧调用。**

### 测试与保护检查

只执行一次相关业务测试：`testDebugUnitTest --tests cn.embeddedquiz.PersonalMarksContractTest --tests cn.embeddedquiz.FrontendQueryContractTest --tests cn.embeddedquiz.LearningDataTest --offline`，**27 项通过、0 失败、0 跳过**（10 + 10 + 7），新增 8 项。不运行 UI 测试或完整套件，不执行 assemble / APK 打包或签名任务。

覆盖：仅改解法保留最新难度、旧标签和每题笔记；并发解法 / 难度写入；未核对草稿与清空；已核对空白解法、缺题和空题号失败回滚；数据库 4 与备份 3 的旧数据往返、备份 1/2 兼容；旧标签与全局便签搜索独立；ViewModel 状态 / notice、busy 忽略规则和旧 saveMetadata 的整行替换兼容。

验证实际测试源码与当前工作区一致，Room schema 4 导出文件未变化；以开发前指纹核对，只修改本轮预期的 6 个既有文件（Repository、ViewModel、两个业务测试类及两个文档）。Compose 页面、已有及未提交 UI 测试、前端交接、版本 / 构建配置、既有 APK / 源码 ZIP / gpt.docx 均未变；Git HEAD 与 index 指纹未变，没有提交、暂存或推送。原始日志、JUnit XML 和检查汇总见 `validation/backend-2026-10-25-verified-solution/summary.json`。

署名：Codex（OpenAI）。

---

## 2026-10-25 · 新图标、练习入口调整与答题页精简（Codex）

### 用户要求与责任边界

本轮依据用户提供的 `gpt.docx` 分工：Codex 完成业务接口、数据可靠性、图标素材与启动资源；Compose 页面、布局和展示交互由 Claude 实现。用户本轮已授权测试后提交 GitHub。**以下页面改动尚待 Claude 接入，不能把本轮 APK 当作全部界面需求已完成的版本。**

用户补充确认：**标签是全局独立内容，不属于某一道题；笔记属于每一道题。** 截图顶部纸张图标在代码中实际调用 `vm.openStickyNotes()`，打开全局独立便签；这是用户所指的全局标签入口。本轮沿用该全局独立能力，不新增题目与标签的绑定。旧版 `QuestionPersonal.tags` 只是已有的题目级字段，保留旧数据和兼容能力，不能误当成此次全局标签的实现。

### Codex 已完成

- 生成原创日式动漫女性头像并接入 Android 启动图标，包含 432 px 前景、适配所有支持版本（最低 Android 26）的自适应图标与圆形图标引用。原图：`assets/branding/launcher-anime-source.png`；生成方式、提示词和再生成资源命令见同目录 `README.md`。
- 增量新增 `vm.savePerceivedDifficulty(id, difficulty)`，按数据库最新记录只保存当前题的难度感受，保留旧题目标签、已核对解法和核对标记。对应 Repository 操作使用 Room 事务；无表结构变更。
- 验证首页分类 / 编程专区 / 考试目标页面已有业务支持，无需创建第二套题库、考试状态或全局标签存储。
- 补充业务回归：难度设置 / 清空、无效输入、缺失题号、并发题目笔记与难度写入、旧数据 / 备份格式兼容、ViewModel 发布状态，以及移动入口后练习草稿与考试截止时间保留。

### Claude 实施清单（需要修改 Compose，Codex 本轮未改）

#### 1. 首页“练习方式”增加两个入口

位置：`HomeScreen.kt` 的练习方式区。保留学习新题、随机练习、顺序练习、薄弱知识点，新增：

| 展示 | 调用 | 预期 |
| --- | --- | --- |
| 编程专区 | `vm.navigate(Page.PROGRAMMING)` | 打开现有编程题筛选页，不混入日常复习 |
| 分类练习 | `vm.navigate(Page.BROWSE)` | 打开现有分类筛选页，保留 `learning.filters[Page.BROWSE]` |

布局由 Claude 按现有视觉系统处理，建议沿用双列卡片。统计区的编程题数量继续读取首页汇总，不用新建计数逻辑。

#### 2. 底部“刷题”改为“模拟考试”

位置：`AppNavigation.kt` 的 `bottom` 列表。最终五项为：**首页、模拟考试、复习、错题、更多**。

- 第二项目标改为 `Page.EXAM`，标签“模拟考试”，选择适当现有图标；不要把 `Page.QUESTION` 枚举改名或改为考试页。
- `Page.QUESTION` 继续作为所有练习和考试的内部答题页面，保留已有聚焦答题行为和首页“继续上次练习”。
- `MoreScreen` 中删除 `Page.BROWSE`、`Page.PROGRAMMING`、`Page.EXAM` 三个重复入口；保留全局便签 / 标签、工作台、统计、费用与设置。
- 修改选中项映射：分类和编程页归入“首页”；考试设置页选中“模拟考试”。从分类、编程、考试设置页系统返回时回首页，不能继续回已移除相关入口的“更多”。
- 复习角标继续使用当天 `todaySummary`；不修改考试计时、判分和 SRS。
- 用户有未交卷考试时，“模拟考试”进入现有考试设置 / 继续机制；不得重新开始而覆盖队列、答案、截止时间。

#### 3. 答题页下方只保留一个“笔记与难度感受”区

位置：`QuestionScreen.kt` 当前两个 `ExpandableSection`（“我的笔记”“标签与难度感受”），以及 `PersonalEditors.kt` 中的 `PersonalMarksEditor`。

- 合并为一个展开区，标题“笔记与难度感受”，包含当前题笔记输入和难度感受（未设置 / 易 / 中 / 难）。删除下方独立“标签与难度感受”区及其题目标签输入。
- 笔记继续读 `vm.questionDraft(q).notes`，沿用 `vm.saveNotes(q.id, code, notes)` 和现有代码草稿自动保存语义；切题时不能把上一题笔记带入下一题。
- 难度从 `vm.learning.metadata[q.id]?.perceivedDifficulty.orEmpty()` 读取，保存使用新增 `vm.savePerceivedDifficulty(q.id, value)`；“未设置”传空字符串。不要从过期 `meta.copy(...)` 写回整个对象。
- 笔记和难度仍是不同持久化操作，合并展示不表示原子保存。若用两个保存动作，应各自受 `vm.busy` 控制；不得在一个点击回调中连续调用两个 `launchTask` 命令（第二个可能因 busy 被忽略）。如设计成单个“保存全部”，需先向后端请求统一事务接口，不得仅连续调用假装一起保存。
- 考试作答中保持已有个人编辑隐藏规则；完成后的查看行为保持原有判分与查看逻辑。

#### 4. 顶部保留全局独立标签入口

位置：`QuestionScreen.kt` 的 `QuestionTopBar`，现有纸张图标 `onNotes = vm::openStickyNotes`，语义描述当前是“打开便签”。

- 保留顶部入口并继续调用现有全局独立便签能力；根据用户“全局标签”的叫法统一可见文案 / 无障碍描述，避免把它做成当前题的标签编辑框。
- 下方合并区不再重复显示全局标签入口，也不提供题目级标签输入。
- `openStickyNotes/leaveStickyNotes` 已能恢复来源页，且不修改练习队列、未保存题目草稿、考试答案和截止时间。全局列表不应按当前 `q.id` 过滤。
- `PersonalMarksEditor` 可拆成仅难度控件供合并区复用；核对 `AnswerWorkbench.kt` 的旧编辑器调用点，避免误删其他已存在能力。旧题目级标签的删除 / 迁移不在本轮范围，历史备份保持可读。

### 前端验收步骤

1. 首页练习方式出现分类练习和编程专区，分别进入正确题库，返回首页；“更多”不再有分类、编程专区、模拟考试。
2. 底部只出现要求的五项；模拟考试能配置、开始、继续和交卷；切页、回首页再进入不重置倒计时。
3. 一道题设置笔记和难度，切另一道题显示其自己的内容；返回后保存值正确，重启后仍在。
4. 答题页只有一个“笔记与难度感受”区；无下方重复标签区。顶部打开同一份全局标签 / 便签，换题后列表相同；从中返回恢复原题和草稿。
5. 检查浅色 / 深色、小屏、大字号、导航返回和 TalkBack；检查手机桌面的圆形 / 圆角新图标。这些视觉与真机检查本轮未完成。
6. Claude 完成后补充相关 UI 测试与 `FRONTEND_HANDOFF.md`，再构建包含全部页面变更的试装包。

### 接口、兼容及测试

完整契约见 `API_CONTRACT.md` 的 2026-10-25 增补。旧 `saveMetadata/saveNotes` 和所有页面枚举继续存在；数据库版本仍为 4，备份格式仍为 3。未改签名、版本号、权限、题库、Compose 页面和已有 UI 测试；不覆盖历史稳定 APK。

- `assembleDebug` 通过；`lintDebug` 通过（0 个错误、5 个警告，详情见 `validation/backend-2026-10-25/lint-results-debug.xml`）。
- 相关业务测试共 **24 项通过、0 失败、0 跳过**：DomainTest 5、LearningDataTest 7、FrontendQueryContractTest 8、PersonalMarksContractTest 4。其中新增 6 项。初次新增测试数据遗漏必填 `source` 导致 4 项失败，修正后仅重跑这 4 项并通过；先前已通过的 20 项结果保留，未重复运行完整套件。
- SHA-256 指纹核对 40 个受保护文件与稳定产物均未改变；实际构建的源码与项目最终源码一致。
- 独立试装包：项目上级目录 `EmbeddedQuiz-1.5.0-icon-backend-20261025-debug.apk`，版本仍为 1.5.0 / 6。签名 SHA-256 为 `2c73c905e6d127dd92fbfaf5351058572cb8e78e98beb477eb1a0f37ee43b151`，与现有 home-browse 版本一致。
- 该试装包包含新图标和后端难度接口，**首页、导航和答题页仍等待 Claude 按上文清单修改**；没有运行 UI 测试或真机安装验收。
- 原始构建日志、最终成功的 JUnit XML、静态检查、签名与汇总见 `validation/backend-2026-10-25/summary.json`。图标缩放和圆形裁切经过本地图片预览检查；桌面真实效果仍需设备确认。Android 13+ 的单色主题图标暂未提供，系统仍可显示彩色自适应图标；剩余图标 Lint 提示包含单色图标缺失和前景位图铺满；自适应蒙版已本地预览，仍须真机确认。

署名：**Codex（OpenAI）**，2026-10-25。

---

## 2026-10-24 · 前端查询边界与首页业务汇总

依据 `FRONTEND_HANDOFF.md` 中 2026-10-24 的四项后端请求开发。本轮只修改业务、DAO、Repository、ViewModel、单元测试和文档；不改 Claude 的页面、配色、组件、交互或现有 UI 测试。

### 已完成

1. 建立 `API_CONTRACT.md`，梳理既有状态和命令，补齐输入、返回、异步、异常、状态变化及兼容规则。
2. 新增 ViewModel suspend 搜索入口，内部切 IO，保留 SQL 筛选、AND 搜索、命中片段和独立便签搜索口径。原 Repository 接口保留。
3. 新增首页只读汇总，四项数量由同一条 SQL 计算，并随题库和进度更新；包含加载、失败和重试状态，沿用本地日期刷新入口。
4. 新增当前练习题摘要，便签往返时仍可读取刚才题目的规范分类。

### 新增接口和调用方式

完整类型及边界以 `API_CONTRACT.md` 为准：

| 入口 | 前端用法 |
| --- | --- |
| `vm.searchQuestions(filter, page)` | 分类页的协程中替换 `vm.repo.search(...)`，传当前 `Page`，保留现有 160ms 防抖 |
| `vm.searchNotes(text)` | 替换 `vm.repo.searchNotes(text)`，搜索为空时仍跳过便签查询 |
| `vm.todaySummaryState` / `vm.todaySummary` | 读取加载 / 错误 / 成功状态；成功时读 `dueCount/unseenCount/dailyCount/programmingCount` |
| `vm.retryTodaySummary()` | 首页汇总失败后的重试按钮；正常情况下不需要调用 |
| `vm.currentQuestionSummary` | “继续练习”卡读取 `?.normalizedCategory`，null 时省略分类 |

现有 `learning.refreshDate(today)` 已由应用外壳每秒调用；本轮无需增设计时循环。汇总的 `day` 是本地 epochDay，前端接入时应与正在展示的日期比对。四项计数不替代开始练习所需题目列表：点击到期复习仍可调用 `vm.due(day)`，点击学习新题仍可临时筛选未学题。

### 需要 Claude 接入的前端改动（本轮未修改）

- `BrowseScreen.kt` 的查询 `LaunchedEffect`：换用两个 ViewModel 搜索函数。查询取消应重新抛出 `CancellationException`；真实错误提供重试并恢复 searching，避免无限进度条。当前界面没有专门的错误状态，这是原有代码的边界风险，并非本轮已修复。
- 同一 effect 目前监听 `filter/questions/progress/learning.metadata`，独立便签变化未作为 key。复现：打开含便签命中的搜索 → 进入便签修改标题 / 正文使其不匹配 → 返回。若组合仍保留，结果可能停留旧列表。建议把 `vm.stickyNotes` 加入 effect key，或在返回分类页时重新查询；后端查询每次都返回已落盘的当前数据。
- `HomeScreen.kt`：成功后使用汇总显示四项数量，避免每次重组遍历；加载和失败时不要把 null 直接显示为零。今日目标、连续天数继续读现有 `learning` 状态，不变更口径。
- `AppNavigation.kt`：到期角标可读汇总 `dueCount`，替代 `derivedStateOf { vm.due(...).size }`。加载 / 失败是否隐藏角标由 Claude 决定。
- `HomeScreen.kt` 的继续练习卡可显示 `currentQuestionSummary?.normalizedCategory`。
- 分类页底部导航高亮、其余页面重设计、深色真机视觉检查仍属于前端工作，本轮没有改变。

### 数据与兼容

- 数据库仍为 4，不新增表或列，Room 查询新增不要求迁移；备份格式仍为 3，旧版 1、2 的恢复逻辑未改。
- 所有旧接口继续存在，现有前端可以暂不接入新接口，行为保持原样。
- 汇总排除编程题和悬空进度；未学是无记录或 `seen=0`；到期是已学且到期日不晚于当天。闰日 / 跨月 / 跨年通过日期参数计算，不按固定 24 小时累加。
- 没有修改题库、签名、版本号、权限、依赖；没有提交或推送 GitHub，没有覆盖稳定 APK 或发布源码包。

### 验证记录

开发前相关基线 `DomainTest`（5 项）和 `LearningDataTest`（7 项）共 12 项通过。新增 `FrontendQueryContractTest` 覆盖空题库、计数口径、悬空进度、闰日 / 跨月 / 跨年、数据库变化订阅、搜索门面与旧查询一致性、非法页面、取消传播、当前题上下文和便签往返。

最终离线执行 `testDebugUnitTest`，只选择下列相关业务测试类，Kotlin 与 Room 查询编译通过：

| 测试类 | 通过 | 失败 / 跳过 |
| --- | --- | --- |
| `DomainTest` | 5 | 0 / 0 |
| `LearningDataTest` | 7 | 0 / 0 |
| `FrontendQueryContractTest`（新增） | 6 | 0 / 0 |
| 合计 | **18** | **0 / 0** |

原始基线、最终构建日志、JUnit XML、开发前文件指纹和汇总保存在 `validation/backend-2026-10-24/`，汇总入口为 `summary.json`。本轮基线和最终各执行一次，没有重复完整套件；未运行 UI 测试、Lint、真机安装或排版验收，也未生成新版发布包。

以开发开始时的 SHA-256 为准核对：Claude 的前端文件、已有 UI 测试、`FRONTEND_HANDOFF.md`、构建配置、两个稳定 APK 和发布源码 ZIP 都保持不变。原有未提交的前端改动完整保留；本轮后端及文档也留在工作区供审阅，没有混合提交或推送。
