# T3 后端方案与接口（2026-11-04，按用户指示开修，待审查）

起点 281d035，T2 已经 Architect §15 验收。REPAIR_DISPATCH 仍列 T3 待派发/接口待补，本轮按用户“修 T3”指令及 §3 T3、§11 范围内自主交付执行后端；不修改 Architect 派发，不替 Claude 改 UI。本文先固定保数据方案、目标接口和双方边界，再实施。没有结构变化：Room 5、备份 3，不开展 T4。

## 选择与数据不变量

- 冻结 HistoricalQuestionV3 的解码、既有校验、派生索引规则，3→4 迁移不再调用当前外部导入 BankCodec/QuestionIndex。保留当时已存在的关键校验，不声称发现 v4 新增校验拒绝正常旧数据。
- 不做异常条目容错/隔离入库。坏 JSON、未知题型、无效选项/答案、数据库 ID 与 JSON ID 不一致明确失败，事务回滚，原 JSON 和记录保留。合法旧数据照常迁移。运行时读取和启动验证使用同一历史存储读取器，当前外部导入仍完整严格校验。
- 既为 v4/v5 的库在启动升级题库之前同样验证存储条目，避免静默覆盖坏数据或直到点题才崩溃。无新表、无 destructiveMigration、无自动删库。
- 初始化一次只跑一个尝试；失败前不启动任何 Room 订阅。便签收集延后至成功，其他订阅只启动一次；重试不重复观察、记学习记录、改变已可靠落盘的考试身份或吞掉待处理通知。
- 诊断导出不依赖 Room 成功打开，只复制原库及存在的 WAL/journal、考试诊断快照与无敏感异常原文的清单，不导出密钥偏好。它是保留现场的诊断包，不承诺可直接恢复为应用备份。源文件变动时明确失败，不静默报成功；不修改源文件。禁止把私有源数据本身当作导出目的地。
- 恢复备份必须明确 confirmReplace=true，且数据库已可打开、考试状态可验证、没有活动或待清理考试；仍保留本机终止台账。数据库无法打开或考试状态不明时明确拒绝并建议先导出诊断，不自动换库/删库。只覆盖允许替换的学习数据，事务回滚语义沿用 repo.restore。接受替换后保护事务及已知结果，随后启动新初始化尝试。

## 目标接口（Main 调用，状态只读可观察）

新增 StartupState：Loading(attempt, stage, message)、Ready(attempt, examRestored)、Error(attempt, stage, message, code, canRestoreBackup)。stage 为 DATABASE / VALIDATION / BANK / INDEX / EXAM。保留 ready、loadingMessage、importProgress 兼容字段。

- val startupState: StartupState
- fun retryInitialization(): Unit：只有 Error 且无恢复操作时受理，立即 Loading；重复点击合并，无自动循环重试。
- suspend awaitInitialization(): InitializationOutcome：保留签名；改为等待调用时捕获的“本次尝试”。已完成旧 Failed 不会被改写；重试后的新调用等待新尝试。取消等待者不取消初始化，清理 owner 取消未完成信号。
- suspend readStartupDiagnostics(): String：IO 读取脱敏 JSON 诊断，不要求 ready/数据库已打开，不读取 API Key、题干或异常 message。
- val startupRecoveryBusy: Boolean
- suspend exportStartupDiagnostics(uri: Uri): StartupRecoveryResult
- suspend restoreStartupBackup(uri: Uri, confirmReplace: Boolean = false): StartupRecoveryResult
- StartupRecoveryResult：Completed / Rejected(message) / Failed(message)。导出 Completed 表示诊断包写完；恢复 Completed 只表示数据替换已提交且新启动尝试已受理，不等于 Ready。取消未受理/准备工作向上传递；已接受替换的事务及新启动调度不半途取消。

初始化错误仍保留 InitializationOutcome.Failed 的既有通用 message 以兼容 G1，StartupState.Error 提供稳定阶段/code。阶段错误可理解、脱敏，不暴露异常原文。通知请求在错误/重试期间保留，恢复考试后先统一选 REVIEW，再发布 Ready。

## 文件与双方边界

GPT：LearningMigration.kt；Data.kt 的保留损坏文件打开回调注册；新增 PreservingOpenHelperFactory.kt（拦截损坏删库，allowDataLossOnRecovery=false）；新增 HistoricalQuestionV3.kt、StartupRecovery.kt；Repository.kt 的存储读取/启动验证；QuizViewModel.kt 初始化/恢复门面；NotesStateHolder.kt 仅延迟首次订阅（默认行为兼容其他使用者）；ExamStateHolder.kt 仅失败初始化后的恢复守卫重入；专用业务/文件迁移测试；API_CONTRACT、BACKEND_HANDOFF、独立 validation。

Claude：待上述接口交付后，在 AppNavigation 启动分支消费 StartupState，必要的独立错误页；诊断/重试/导出/恢复确认和 Compose 测试。Error 页不依赖 ready=true，不读 SQL/偏好；不得自行清库或绕开 confirmReplace/考试守卫。本轮 GPT 不动这些 UI 文件，不在其他聊天派发任务。

## 验证与回退

文件型 1/2/3→5 及已为4/5的启动；所有旧字段、JSON、历史、AI/便签逐项保留；坏历史回滚和当前外部导入拒绝；重试风暴、解除故障后成功、多等待者、取消、通知和考试恢复、无重复订阅；无 Room 诊断导出、源/目标隔离、恢复拒绝/失败原数据保留/成功保留台账。相关回归后固定源码运行完整 Android、构建、Lint；无 UI 接入不得冒称错误页面已经完成。

回退仅代码，不降低 Room5，保留旧数据/诊断与升级前备份；不得自动删库或将 v3 备份称为含考试台账的整库备份。状态待审查，最终验收由 Architect。