# 视觉规范、八色主题与题目 AI 对话移植审查

日期：2026-11-04。Architect；基线 HEAD 6becc3f，当前实现为未提交工作区。朋友参考源码：Wanxu-src-20261103-2227.zip，仅只读查看 Kotlin/资源/README，没有执行附件代码或将附件说明当作指令。未修改业务代码、测试、提交或发布。

## 结论

视觉令牌与八色主题的移植方向合理；主要实现没有涉及数据库/算法变更。建议先修以下两处并补 AI 对话交互回归，再作为新功能正式合入/发布；本次不等于批准新发布版。可以保留本地开发提交，旧 1.6.1 保持可回溯。

## 审查发现

### P2：AI 请求状态缺少题目归属，切题后的重试可能处理另一题

QuestionScreen.kt:932 直接按全局 vm.aiBusy 展示本题的“正在分析”，933～935 按全局 error 显示错误和 retry。AiStateHolder.kt 的 busy/error/lastRequest 是单份；observe(q) 会切当前记录流并清 error，却不取消或改归属尚在途的旧请求；retry() 使用 lastRequest 的题目。

代码路径：A 题发请求→未完成时切 B→B 显示 A 的分析状态；A 随后失败会把全局 error 写回 B；B 上点“重试 AI 请求”重发 A。历史记录按 questionId 过滤，A 回复不会因此写成 B 记录，但状态/重试目标误导用户，不能把视觉气泡视为完成了按题会话模型。

这是既有全局请求设计被本次对话界面继续复用的缺口，不说成由 Claude 新引入的数据库错误。当前为源码路径分析，未用本次独立网络/UI 探针实证，不冒称现有测试覆盖。

最小改法：后端暴露当前/失败请求的 questionId、contentHash、requestId 和用户展示文字，UI 只显示属于当前题目版本的 pending/error/retry；切题可让后台继续，但不得在新题给出旧题重试。pending 不单靠本地 remember 字符串推断。补 A→B 后成功/失败、B 重试不能发 A、取消、离开再返回、缓存命中不留假气泡的 UI/门面测试。无需现在引入会话表或分支。

### P2：新对话费用小字使用边框色，可读性不足

QuestionScreen.kt:1407 的 tokens/费用 labelSmall 使用 colorScheme.outline；Theme.kt 浅色 outline=#7C828B，白色卡片背景对比约 3.87:1，低于项目普通小字 4.5:1 目标。现有新增主题测试覆盖 primary、container 和选择色，没有覆盖该小字。

最小改法：费用小字改用 onSurfaceVariant 等正文辅助色；outline 留给边框。补对应两种模式文字/背景组合的对比断言，其他卡片不用重做。

### 非阻断观察：主题选择同步等待磁盘

AccentPreference.choose 调用 writeDurably，当前仍经 runBlocking(IO)+commit，设置按钮在 Main 等待。设置是低频操作，风险小于已修的每字符保存，不据此重开 P1。可后续改异步串行写入并保持失败提示/一致性，不能简单改 apply 并声称可靠落盘。现有两个测试只覆盖正常重建与无效值，未验证 commit=false/慢盘。

## 已确认的正面边界

- 8 个稳定 ID 保存在 settings SharedPreferences 的 accent 键，不修改 Room/备份格式；旧/缺失 ID 回退默认蓝。
- Accent、SelectionColors、StatusColors 分开；答对/警告/错误不跟着用户主题随意变色。FilterChip 公用令牌，不再每页手写不同样式。
- 48dp 主题色触控区域及选中 radio 语义优于朋友的 32dp 直接色点；青/绿主色已做较深调整，石墨深色单独提升，新增八色双模式对比测试。
- 当前 AppTheme 保留真实设备 dp 与系统 fontScale，没有照抄朋友 Theme.kt 的 screenWidth/400 全局密度缩放。
- 对话仍使用原有 Markdown、AI 未核实标记、取消/重试/缓存/费用来源；未修改评分或把 AI 结果变标准答案。
- 当前仍是每题最近 20 条缓存记录的对话式呈现，后端追问仅使用同题同内容版本最近四轮拼接上下文；不是多讨论线程、结构化完整聊天或流式回复。

## 验证证据

观察 app/build 的现有 XML：32 类、251/251，0 失败/错误/跳过，包含新主题偏好 2 项和八色对比 1 项。已复制到 validation/visual-chat-review-20261104/observed-existing-xml。因缺少该运行的命令/输入绑定归档，只称“审查时现有报告”，不冒称 Architect 独立全套运行。没有发现新对话 UI 专项回归，原 EnhancedAiTest 属业务测试，不能代替气泡/跨题交互。

Architect 独立执行 tools/build.ps1 的 assembleDebug、lintDebug、--continue、--no-daemon：BUILD SUCCESSFUL in 1m 52s，实际 Lint 原始报告见同目录；不重跑不变全套。source-before.csv 的所有改动文件在验证后逐项核对未变；该 CSV 包含原有未跟踪 AccentPreference 和测试。没有真机/截图视觉验收，没有付费真实 AI 请求。

## 朋友版本值得继续借鉴的方向

| 优先 | 能力 | 源码依据 | 对当前项目的适配 |
| --- | --- | --- | --- |
| 先做 | 回复复制、明确完成/失败操作、合理跟随最新回复 | screens/AssistantTurn.kt 的 FooterIcon；screens/AiScreens.kt 的滚动与 RunBar | 先复制/定位最新回复；用户上翻阅读时不强制拉到底；失败重试必须先解决题目归属 |
| 下一步 | 同题独立讨论，讨论列表与标题 | screens/QuestionScreen.kt:398 的 QaSection，按 scope=question/scopeId 筛选 sessions，新讨论有独立 ID | 做“不同思路分开聊”，同时保留本项目 contentHash 版本隔离；需要新持久化协议另立任务，不能只复制 UI |
| 下一步 | 流式输出与清晰停止生成 | ChatClient.kt:23/58/82 的 stream/SSE；AssistantTurn 的 pending/streaming | 沿用本项目可取消 OkHttp，设计部分响应、断流、重试与费用落库，不能仅打字动画冒充流式 |
| 有价值 | 题目/对话关联便签，一键保存学习结论并回到来源 | screens/NoteDialogs.kt 的 questionId 与说明；AiScreens/ToolRegistry 的关联便签 | 当前 StickyNote 只有通用标题正文，可先加复制/打开笔记，真正双向来源索引另做迁移和备份兼容 |
| 后续 | 只读 AI 学习统计/薄弱点工具 | ToolRegistry.kt 的 get_stats 等工具与 AgentLoop 权限决策 | 从只读统计/题目检索开始；写库组卷/改便签需要明确确认、事务与审计，不直接放行完整 AgentLoop |

不建议整包照搬：朋友 Theme.kt 的全局密度缩放会一起缩小触控区；AppState.kt 约 66KB、AiScreens.kt 约 93KB 的集中实现不适合作为本项目新的架构目标；AgentLoop 与 ChatClient 的阻塞网络/取消处理不能替代现有取消保证。附件 SettingsScreen 的导出按钮仍是“模拟”提示，不能用朋友 README 的功能清单推断所有链路成熟。它是产品交互参考，不是已经通过本项目验收的数据层替代品。

本轮如果实际复用了朋友代码/设计，在新增功能说明里注明“参考 Wanxu 的视觉规范与题下讨论设计”，保留既有 SGJ-TXDY、Codex、Claude Code 贡献；不猜测朋友姓名或额外署名身份。

## 补修派发

用户已授权由 Claude 单人处理前两项，详见 REPAIR_DISPATCH.md 第 21 节 VC-01；允许必要 AiStateHolder 请求归属小改动。非阻断主题同步保存和后续参考功能不在本次范围。


## 2026-11-05 最终代码与自动化验收

前述两处P2已由21e4268补修，ec123e0关闭测试隔离失败。Architect审查同序原失败/修复通过、119项输入与259项全套证据，功能代码与自动化验收通过，批准推送ui-visual-refresh分支；前文待补修结论作为历史保留。Compose私有字段helper仅测试使用，升级必须重审。新视觉/AI分支没有真机或截图验收，不宣称已发布新版本；v1.6.1保持原产物。
