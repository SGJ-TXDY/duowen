# T1 GPT 后端方案（供 Architect 审核）

日期：2026-10-26（Asia/Tokyo）
任务：T1；执行者：GPT；状态：待审查（接口方案阶段，尚未实施业务补丁）。
本稿不是已生效的 API_CONTRACT，也不是 Architect 的审批记录。审核通过后才实现并更新正式接口契约。

## 领取与基线

- 已阅读 docs/REPAIR_DISPATCH.md 全部共享约束、T1/T2 边界与交付模板，以及 docs/API_CONTRACT.md 通用、练习、考试接口约定。
- 起始 HEAD：92d1cd70df874680caef78ca95ccc0a97b6e8b6d（派工基线一致）。
- 开工时 git status --short 仅为：?? docs/REPAIR_DISPATCH.md。保留该未跟踪文件，不编辑、不顺带暂存或提交。
- 本阶段只新增本方案稿；不修改派工表状态，不实施业务代码，不启动其他代理或跨聊天派发任务。
- 预计实施范围：ExamStateHolder.kt 的结果模型、grade 和考试终止支持；QuizViewModel.kt 的 finishExam、新增 abandonExam、对应可观察状态与最小考试命令防冲突；app/src/test/java/cn/embeddedquiz/ 下新增 ExamStateHolderTest.kt、ExamLifecycleContractTest.kt；docs/API_CONTRACT.md 的 T1 接口段及 docs/BACKEND_HANDOFF.md 的交付段。
- 模型留在 ExamStateHolder.kt 同包同文件，不移动或重组文件。Repository.kt 预计无需修改；通用 record、submit、assess 保持原样。QuestionDraft.kt、Compose/UI、路由、依赖、数据库结构、备份格式均不修改。不执行 T2。
- 写入任何既有文件前复核最新 diff；若发现其他执行者同时修改同一函数，先报告冲突，保留对方内容。

## 当前问题的只读证据

ExamStateHolder.grade 对整卷每题执行 selected = answers[q.id].orEmpty()，随后无条件调用 repo.record。Repository.record 将空选项判为错误，并修改 Progress 与插入 Attempt。因此未答题会产生学习与排程副作用。

QuizViewModel.finishExam 当前使用 queue.mapNotNull 取题，会在缺题时缩小分母；grade 在 Room 事务后 clear；之后再查 historyPage，最后发布 finished/result。清理偏好或历史查询失败可能发生在学习记录已提交之后，不能一律解释为“交卷未写入”。

上述证据来自代码阅读，本阶段没有执行修复前/后测试，不把派工文档中的历史测试结果当成本次运行证据。

## 提议的结果与状态模型

以下签名为送审方案，最终以 Architect 裁定为准。

```kotlin
data class ExamResult(
    val total: Int,
    val answered: Int,
    val unanswered: Int,
    val correct: Int,
    val score: Int,
)

enum class ExamOperation { SUBMIT, ABANDON }
enum class ExamFailureStage { VALIDATION, RECORDS, SESSION_CLEAR }

sealed interface ExamOperationState {
    data object Idle : ExamOperationState
    data object Submitting : ExamOperationState
    data class Submitted(val result: ExamResult) : ExamOperationState
    data object Abandoning : ExamOperationState
    data object Abandoned : ExamOperationState
    data class Failed(
        val operation: ExamOperation,
        val stage: ExamFailureStage,
        val recordsCommitted: Boolean,
        val result: ExamResult?,
        val message: String,
    ) : ExamOperationState
}
```

- 不变量：total = answered + unanswered；correct <= answered；score = total 为零时 0，否则 correct * 100 / total（沿用现有整数百分制）。实际可提交考试必须是非空、无重复题号的原始队列。
- answered 仅由固定输入中该题答案集合非空判定；缺键与空集合都属于 unanswered。多选漏选但非空是 answered，按现有严格集合判分计错。
- total 固定为原考试队列长度。所有题都必须成功加载，缺题、重复队列或不可判分题整次校验失败，在学习事务写入前终止，不通过 mapNotNull 缩小分母或擅自计错。
- vm.examOperationState: ExamOperationState 为只读 Compose 可观察状态；vm.examSummary: ExamResult? 为结构化成绩只读入口。操作状态不持久化。
- 保留 examFinished: Boolean、examResult: String 供现有页面兼容，字符串由同一结构化结果生成。Submitted 才代表交卷全部完成；Abandoned 不生成成绩。新考试重置状态与结构化结果，普通练习行为不变。
- 不把成绩已提交但清理失败伪报为 Submitted。此时 examSummary 可供错误提示展示，Failed.result 同时携带已提交的成绩，recordsCommitted=true。

## 门面与内部签名

```kotlin
// QuizViewModel；主线程调用，返回 Unit 不表示完成。
fun finishExam()
fun abandonExam()
val examOperationState: ExamOperationState
val examSummary: ExamResult?

// ExamStateHolder；内部接口，不供 UI 直接调用。
// 调用方已固定队列与答案；此方法只在 Room 事务内评分及记录，不清会话。
suspend fun grade(
    questions: List<Question>,
    answersSnapshot: Map<String, Set<String>>,
): ExamResult
```

1. finishExam 无参数，手动交卷与现有超时入口使用同一命令；不根据来源改变未答规则。受理时在主线程立即设 Submitting 和 busy，再复制 queue 与答案集合，禁止引用后续可变化集合。
2. 数据库取题、判分、记录与会话偏好清理都在 IO 执行；状态、兼容字段与考试内存清理在 Main 发布。本任务不改现有每次选择的同步 saveExam 持久化方式，不宣称解决其主线程等待。
3. abandonExam 仅在用户二次确认后由 Claude 调用，不能以 finishExam 或空答案交卷代替。确认框取消不调用任何后端命令，因此会话、答案、截止时间与学习数据不变；倒计时继续运行。
4. 全局 busy 或考试结束操作进行中时，拒绝冲突的交卷/放弃，保留当前操作状态，通过既有 notice 说明未受理。无活动考试时不启动事务、不生成零题成绩或成功事件。成功终止后的重复命令不写入任何数据。
5. 结束操作受理后，answerExam/saveExam 对本会话不再接受更新或重建快照；只增加考试边界的最小防护，不改全局 launchTask。交卷与放弃由主线程受理顺序决定，先受理者执行；另一个命令被拒绝，包括超时回调与放弃之间的竞争。
6. 清理失败后冻结该次考试，不允许改答案、开始新考试或另一种结束操作。再次调用失败状态对应命令只重试必要阶段：学习事务未提交可重新交卷；事务已提交只能重试会话清理，绝不再次 grade；放弃失败只重试清理。此为 T1 同实例连续调用防重复所需的内存保护，不是 T2 的持久化幂等协议。

## 交卷、失败与持久化语义

- 受理后先完整加载并校验固定队列，答案以不可变副本为准；校验失败为 Failed(VALIDATION, recordsCommitted=false)，保留原会话，允许修复原因后重试。
- 在同一个外层 Room 事务中，先验证整卷判分资格并计算结果，再仅对非空答案调用原有 repo.record，继续沿用原正确/错误学习规则。空答既不调用 record，也不创建默认 Progress；不改通用 record 的空答语义。任何写入失败整卷回滚，为 Failed(RECORDS, recordsCommitted=false)。
- Room 提交是学习记录完成点。之后以本实例内存终止标记固定结果，再可靠删除偏好 exam。删除成功后才发布 Submitted、examFinished=true、兼容 examResult。不要让额外 historyPage 查询失败否定已经完成的提交；现有 attempts Flow 继续刷新历史。
- 删除失败为 Failed(SESSION_CLEAR, recordsCommitted=true, result=已提交成绩)。保留结果与内存终止标记，重试 finishExam 只清理快照。不能向 Claude 提示“重新交卷”或允许继续答题。
- 取消在学习写入开始前传播，不伪报成功或普通错误；事务取消回滚时没有新增学习记录。一旦进入会产生提交的临界段，使用局限于该结束操作的取消保护，等待事务结果、保存内存提交标记并尝试清理，再发布真实结果，避免 IO 返回时的取消丢掉提交信息；不得扩大到通用 launchTask 或阻塞主线程。阶段失败按上述状态报告。
- Room 成绩与 SharedPreferences 快照不是同一个事务。T1 不添加持久化提交 ID、完成日志、写入队列、版本号或迁移；进程在成绩已提交而快照尚未可靠删除时退出，重建实例后仍可能重复提交。这是明确保留的 T2 阻断窗口，不能宣称 T1 提供跨进程幂等或可发布。

## 放弃、失败与持久化语义

- abandonExam 从受理到完成不调用 grade/record/任何学习表写入，不改变 Attempt、Progress、错题与每日完成统计。
- IO 可靠删除 exam；成功后才清理内存答案、截止时间、本次考试队列与草稿，撤销本次考试上下文，发布 Abandoned；examSummary=null、examResult 为空。终止标记先阻止迟到的考试选择/保存回调重建会话，再清理内存。
- 放弃后的考试不再是可恢复会话，重建 ExamStateHolder/QuizViewModel 不应恢复它。页面离开目标由 Claude 用既有导航决定，后端不新增或修改路由，不将 Abandoned 作为已评分考试。
- 删除失败：Failed(ABANDON, SESSION_CLEAR, recordsCommitted=false, result=null)，不清空当前会话内存、不发布 Abandoned，保留重试入口；持久化是否成功以可靠 commit 结果为准，不依赖 SharedPreferences 的内存 contains 来宣称成功。
- 取消在删除开始前保留原考试；开始删除后等待提交结果再发布真实成功/失败。SharedPreferences commit 失败不等于其内存值自动回滚，失败时保留当前内存考试快照供重试，不谎称可保证崩溃后恢复。

## 测试与证据计划（审核通过后执行）

新增后端测试，不改既有 UI 测试，也不削弱现有断言：

1. 50 题只答 10 题，确认结果分母 50、answered=10/unanswered=40、Attempt 精确增加 10；未答的已有 Progress 整对象逐字段相等，缺行题仍缺行；每日完成与错题只反映已答题。
2. 全未答、全答、部分答，多选漏选、选择后清空，个人核对答案，手动与超时均覆盖；缺题/失去判分资格整次失败且零新增写入。
3. 放弃前后比较全部 Attempt/Progress 与统计；新实例不恢复；未调用 abandonExam 模拟取消确认，答案、队列和截止时间保留。
4. 同实例连续交卷、交卷/放弃争用、结束中的选择/保存回调无重复写入或会话复活；错误后重试只执行允许阶段。
5. 故障注入：学习事务中途失败整卷回滚；学习已提交但偏好 commit=false 时明确报告已提交、重试清理不重复记录；放弃清理失败保留状态、重试成功后不恢复；取消覆盖写入前与临界段。
6. 普通练习自动判分空答仍按原规则记错，手动自评 CORRECT/WRONG/UNKNOWN 规则不变；运行原有相关回归。T1 连点测试不替代 T2 跨进程故障测试。

拟运行命令（仓库根目录；先核验工具链与 ASCII 联接确实指向本仓库，发现错误只报告，不开展 D7）：

```powershell
& .\tools\build.ps1 testDebugUnitTest --tests cn.embeddedquiz.ExamStateHolderTest --tests cn.embeddedquiz.ExamLifecycleContractTest
& .\tools\build.ps1 testDebugUnitTest --tests cn.embeddedquiz.DomainTest --tests cn.embeddedquiz.StorageAndApiTest --tests cn.embeddedquiz.LearningDataTest --tests cn.embeddedquiz.LearningDatesAndReadingTest
& .\tools\build.ps1 assembleDebug testDebugUnitTest
```

交付时记录实际命令、时间、测试数量、失败及 XML/HTML 路径。预期标准位置为 app/build/test-results/testDebugUnitTest 与 app/build/reports/tests/testDebugUnitTest；需复制本次结果至独立 validation/T1-* 目录，保留失败与复测分开证据。已知 G1 基线失败如仍出现，照实报告并留给对应任务，不越界修复或宣称全套通过。当前上述命令尚未执行。

## 待 Claude 接入（以审核并实施后的正式契约为准）

- 读取 examSummary 展示 total/answered/unanswered/correct/score，不自行计算学习副作用。确认说明未答计零分且不改变复习安排。
- 二次确认放弃才调用 abandonExam；取消无需后端调用，继续原考试。
- 观察 examOperationState 与 busy，交卷/放弃期间阻止冲突操作；Submitted 展示成绩，Abandoned 后使用既有导航离开；失败显示实际阶段，已提交但清理失败只能重试清理。
- 未完成考试入口提供继续/放弃；由 Claude 实施确认/取消、连点、返回与超时交互测试。GPT 不修改 Compose/UI。

## 本阶段交付记录（统一模板）

任务 ID：T1（接口方案阶段）
执行者及职责：GPT，仅考试后端方案与后续业务/存储测试。
起始 HEAD：92d1cd70df874680caef78ca95ccc0a97b6e8b6d。
交付 commit / 当前未提交改动说明：无提交；只新增 docs/T1_BACKEND_PROPOSAL.md，保留原未跟踪 docs/REPAIR_DISPATCH.md。
修改文件与对应函数：本方案文档；业务函数未修改。预计实施范围见上。
接口/行为变化：本稿提出结构化成绩、finishExam/abandonExam 与可观察操作状态；尚未生效。
是否超出白名单（如是，附 Architect 裁定）：无业务越界；新增任务审查稿用于接口先行，未调整正式契约或派工表。
修复前失败场景与证据：只读确认 grade 无条件 record 空答；没有新增测试运行证据。
修复后测试命令、数量、结果、报告位置：尚未实施、未执行，计划见上。
故障注入/生命周期/并发验证：列入计划，尚未验证。
尚未验证的内容：所有方案实现与测试、Claude UI 集成、真机行为；跨进程幂等明确属于 T2。
数据兼容与回退方法：本阶段只有新增 Markdown；后续设计保持 Room 4、备份 3 和既有快照字段。后续业务补丁独立提交，不混入既有未提交文件；按提交回退源码，不覆盖稳定 APK 或建立稳定标签。
需要另一执行者接入的事项：Architect 审核本稿的方法/状态/失败与范围边界后，GPT 再实施；Claude 随后按正式契约接入上述 UI 项。
状态：待审查。
