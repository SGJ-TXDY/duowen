# T4 后端：草稿合并写入、保存边界与关库（待审查）

按 REPAIR_DISPATCH §18 直接实施。开工 HEAD `77c06bd`（T3 已按 §17 验收）；工作区仅 Architect 原五个未跟踪项，未修改或暂存。实施期间 Claude 提交编辑器第一部分 `ac387ac`；本轮 GPT 后端实现 `38cb7c3ee2602df1cf0d3553386aea54d4a46dae` 继承该提交，未修改它的生产 UI。前端第一部分不是完整 T4 UI 接入，不能提前做整体验收。

## 修改清单与实际契约

- 新 CodeDraftPersistence.kt：同题尾沿 300ms、最早未保存改动最长 2000ms，单调时钟；一个在途写事务加每题一个可替换最新待写版本，失败不无限自动重试。
- OrderedWriter.kt 仅替换 CodeDraftStateHolder 部分，保留 CodeDraft 数据类型与通用 OrderedWriter 本体；兼容 writer 的 flush/pending/error/retry/close 委托同一组件，无第二写入通道。通用主体与 77c06bd 原文相同。
- QuizViewModel.kt：§18.2 正式状态/更新/重试/flush 接口；QuestionDraft 镜像衔接；saveNotes 显式 flush 成功才提示；备份/恢复/重置和 T3 启动恢复统一草稿排他边界；成功替换/重置推进代际；clear 先启动独立 drain，等待草稿与既有考试/读取退出后关库。
- 新 CodeDraftPersistenceTest、CodeDraftBoundaryTest；LearningDataTest 只补独立 holder.shutdown().join()，防止新独立生命周期在测试关库后遗留 worker；原字段/顺序断言不减。
- API_CONTRACT 顶部为实际接口、线程、失败/取消、代际与关库契约；BACKEND_HANDOFF 为交付模板。本轮不改 Repository/Data、生产 Compose/MainActivity/QuestionDraft、评分/SRS、历史迁移、schema、版本、依赖或目录结构，不编辑 Architect 派发和 Claude 前端交接。

状态为 Inactive/Saving(revision)/Saved(revision)/Error(revision,message)。Main 接受更新，IO 合并及事务写入，只有最新文字事务成功才 Saved；普通 Saving 不禁输入。相同文本不增 revision 或写入，初始加载仅 seed，不伪报“刚保存”。后台 Progress 流不覆盖缓存中的已受理文本。

保存沿用 repo.update 的事务内读最新 Progress 再 copy code/notes，保留收藏、flagged、评分、计数、SRS 等其他字段。flush 捕获本次版本，立即取窗口；同代际该版本或更新版本成功才 true，最新失败 false，取消等待不取消写入。retry 只重试最新 Error；旧失败不会在新成功后被回放。

备份/恢复/重置进入 editingBlocked 排他边界：拒绝新输入，flush 所有已受理文字，再与写入同一互斥锁执行读取/导出/替换；失败不清缓存、不报成功，异常/取消释放边界。恢复成功清旧 UI/草稿/题目缓存并推进代际，旧等待 false、旧重试不写回；重置按原语义清复习历史，保留最新 code/notes、收藏/flagged/便签，推进代际但保留文字镜像。正常及启动备份恢复均复用此边界，T2/T3 考试与确认守卫保留。

clear 停止新更新、窗口立即强制写入；独立 IO worker 不随 owner 先取消。2500ms drain 预算后协作式取消，join 仍等实际数据库 IO 退出再关库，不阻塞 Main、不无限重试、不伪报失败任务已保存。系统不可取消 IO 卡死不承诺硬限时关库，立即强杀前未落盘文字不保证保全。

## 实际验证

| 轮次 | 固定内容/范围 | 实际结果 |
| --- | --- | --- |
| 01 | 初版核心 10 + 既有学习/启动/便签/考试保存 | 45 项，44 通过、1 失败 |
| 02 | 固定时间屏障后的核心 10 + 边界 11 + 同上回归 | **56/56**，0 失败/错误/跳过 |
| 03 | 固定 38cb7c3，新增取消/目标写出故障后的 11 类关联测试 + Debug + Lint | **105/105**，0 失败/错误/跳过；构建成功，Lint 0 错误/5 既有警告 |

01 失败在新持续输入测试：同一虚拟时刻先唤醒 timer 又接受新输入，精确 payload 断言假定了不存在的顺序（v41/v40），原保存时间断言已满足上限。02 在 2s 边界等定时提交完成后再送同刻输入，保留精确时间与 payload 断言；没有增加 sleep、放宽超时或跳过。原始失败和修正后日志/XML/逐类 CSV 分别保留。01-command.txt/02-command.txt/03-command.txt 记录确切调用；01-initial-test-source/ 保存相较最终版本有差异的两份首轮测试精确源码，其字节 SHA-256 与 01-source-fingerprints.csv 完全匹配，其余首轮输入与最终相同。generic-writer-verification.json 记录通用主体与基线的 LF 规范化原文指纹相同。

**实际写事务证据**：测试数据库触发器审计已提交 progress 文字变化；100 次/每 10ms 更新只写 **1 次**，最终空白/换行逐字匹配，重新打开真实数据库仍一致。6s/每 100ms 连续输入在虚拟 **2000、4000、6000ms** 提交，code 分别 v19、v39、v59，最终保存不迟于停止后 300ms。见 CodeDraftPersistenceTest 原始 XML system-out；不是排队次数或仅断言 dispatcher。

覆盖 A/B 交替、慢 IO 期间 1000 个新 A/100 个 B 只保留最新版本且实际写入串行、窗口内立即 flush、逐题/批量重试、旧失败→新成功、独立取消等待、代际失效不伪报 Saved、与判分/收藏事务并发、真实数据库重开；备份含最新文字/失败无成功提示、恢复等待旧 IO/失败留缓存/新代际不被旧任务污染、重置保留文字而清历史、普通笔记仍显式保存、T3 恢复复用、clear 窗口/在途/失败/超时与 Main 可继续。

最终 03 命令退出 0，BUILD SUCCESSFUL in 2m 44s（55 个任务：13 执行、42 up-to-date）。11 类包含新核心 11 项/边界 12 项，及原学习、启动恢复、便签、考试保存/结束/迁移、通知、速记隔离与 NotesAndScoring UI 回归。03-targeted-xml/、03-targeted-summary.csv、03-final-result.json、lint/ 为本轮原始证据；03-after-fingerprints.csv 确认 109 项输入前后未变，schema/ 保存未改的 1～5，Debug 仅记录独立产物摘要，没有覆盖稳定产物。

03 使用 GPT 自有 `.gpt-t3-backend-20261104` 隔离副本和独立 app/build（沿用原入口，无另一 Gradle 并行覆盖），109 个 Android 输入逐字节复制，运行源码固定提交。命令在 03-command.txt；该轮为定向测试，**不是完整套件**。按 §18.5 等 Claude 剩余 UI 交付可用后，GPT 固定共同 HEAD 统一运行一次完整 Android + Debug + Lint；不拿本轮定向结果或 T3 的 210/210 替代。

## 故障矩阵

| 点位 | 数据/内存与后续 |
| --- | --- |
| 合并窗口中离页/显式保存 | flush 立即强制当前版本；失败保留文本/Error，显式 retry 后再 flush |
| 旧版本写失败、新输入已受理 | 不把旧错误降级到新状态；按既定窗口写最新版本 |
| 最新版本失败，无修改 | 保持 Error，不自动循环；retry 只用最新 payload |
| flush 等待者取消 | 等待向上传播取消；独立写入仍继续 |
| 备份 flush 失败 | 中止导出、已有目的内容不改、不报成功、释放编辑边界 |
| 目的文件写出失败 | 文字已可靠落库，但导出不报成功，编辑边界释放 |
| 恢复校验/事务失败 | 原草稿缓存和数据库保持，已 flush 文字保留，不先 clear |
| 恢复成功 | 新代际、旧缓存清理；旧等待/重试不宣称新数据已保存、不写回 |
| 重置成功 | 学习/历史清零，最新代码/笔记及收藏/flagged/便签保留 |
| clear 窗口/在途 | 强制最后受理版本，IO drain 后再关 DB，Main 可推进 |
| clear 失败/超时 | 有界尝试/协作取消，不无限重试或报 Saved，等实际 IO 退出 |

## Claude 接入与未验证项

正式门面已实际实现，可直接继续 §18.4：QuestionScreen 状态/重试/离页协调器，AppNavigation 外壳路径，必要生命周期接入；使用 VM 门面，不直接访问 writer。CodeEditor 第一部分 ac387ac 已在共同历史中，尚待完整 UI 交付及新 CodeDraftSaveUiTest 扩充；不要等后端阶段批准才继续。

普通题笔记保持显式保存；前端比对本地未保存文字，不能把后端旧 Saved 当作当前未点击保存编辑已持久化。Saving 可编辑；editingBlocked 或等待主动离页才暂时冻结。普通练习和考试保存协调不能互相绕过；外部通知/新页面/会话接管后丢弃旧导航/弹窗，保存继续。

没有真机长代码输入、输入法组合、后台强杀、系统字号或 TalkBack 证据；可控时钟/真实测试数据库不等同设备性能或物理磁盘故障。Room 5、备份 3，无格式迁移；回退仅代码，已写 code/notes 不回滚，不自动清库或降级。未推送、打标签、发布或覆盖稳定 APK。

状态：**后端待审查；前端剩余接入及统一完整验证待交付后执行**。