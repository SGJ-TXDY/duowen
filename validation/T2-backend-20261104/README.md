# T2 GPT 后端验证（2026-11-04，待审查）

起始 HEAD：`e3be2a6bfb13e077182848625e3a64cbc6b6de19`。
后端实现提交：`5923f4fce6a720d3cd1362d2386e5629a3e04888`。
最终固定受测 HEAD：`8b88842fdf72a1a23f9c2837e3d9ff1a210353d5`（仅补两处旧存储契约断言与交接说明，生产代码同 5923f4f）。

范围：异步快照、持久化 UUID、事务内考试终止去重、Room 4→5 最小迁移、恢复/重置守卫、生命周期 DB 关闭协调、专用业务测试和正式契约。没有修改生产 Compose/UI/MainActivity、共享派发、普通 record/SRS、DurablePreferences、依赖、版本和历史 schema/迁移。

开工及收尾保留 Architect 原未跟踪文件：docs/REPAIR_DISPATCH.md、docs/T1_BACKEND_PROPOSAL.md、validation/G1-architect-integrated-20261027、validation/T1-architect-review-20261026、validation/T1-frontend-architect-review-20261026。没有暂存这些文件。

构建环境：离线 JDK 17 / Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / Room 2.7.2，仓库 tools/build.ps1；独立副本 `.gpt-t2-backend-20261104`，独立 ASCII junction `C:\AndroidProjects\EmbeddedQuiz-gpt-t2-backend-20261104`，不使用共享 app/build。第一次从基线 git archive，覆盖授权改动。完整运行前复制精确工作区字节并校验 **96** 个 Android 输入文件；此前 archive 的 LF 与原工作区 CRLF 差异已在运行前拦下并同步，没有冒称哈希一致。source-fingerprints.csv 保存工作区及受测副本 SHA-256；tested-head.txt / 06-full-command.txt 保存固定 HEAD 和精确完整命令。

## 实际执行记录

| 轮次 | 命令范围 | 实际结果 | 原始证据 |
| --- | --- | --- | --- |
| 01 | ExamStateHolderTest、ExamLifecycleContractTest、InitializationNotificationTest | 40 项，7 失败 | 01-backend.log、01-backend-xml/ |
| 02 | Exam*Test（含基线 UI）、InitializationNotificationTest、LearningDataTest、StorageAndApiTest、StickyNoteStorageTest | 71 项，1 失败（新类初始化错误） | 02-persistence.log、02-persistence-xml/ |
| 03 | ExamPersistenceTest | 12 项，1 失败 | 03-faults.log、03-faults-xml/ |
| 04 | ExamPersistenceTest | 12/12，0 失败/错误/跳过 | 04-faults.log、04-faults-xml/ |
| 05 | 后端考试四类及初始化业务 | **56/56**，0 失败/错误/跳过 | 05-final-backend.log、05-final-backend-xml/、05-final-backend-summary.csv |
| 06 | 5923f4f 全套 Android + assembleDebug + lintDebug | 177 项，5 失败；构建成功，Lint 0 错误/5 警告 | 06-full.log、06-full-xml/、06-full-summary.csv、06-lint/ |
| 07 | 8b88842 必要完整复测，同一命令 | **177/177**，0 失败/错误/跳过；构建成功，Lint 0 错误/5 既有警告 | 07-full.log、07-full-xml/、07-full-summary.csv、lint/ |

01 的失败：旧测试把恢复后的偏好 JSON 逐字相等当成不变条件，T2 的旧快照 UUID 升级合法改变 JSON。改为等待 flush 后逐项核对题序/index/答案/deadline 和合法 ID，原 Attempt/Progress/DailyCount 断言仍完整保留；初始化失败路径仍逐字核对原快照未动。

02 的失败：新测试 @After 的 runBlocking 末尾返回 file.delete() 的 Boolean，JUnit 要求 void。明确 Unit，未跳过测试。

03 的失败：新测试方法名和 UUID 数据库文件名组合导致 Robolectric/Windows 打开临时 WAL 文件失败。缩短专用数据库文件名及方法名，并在重新创建实例前等待原观察任务完成。04 同一生命周期断言通过。没有改变生产 SQLite 设置、Robolectric SDK、依赖或历史迁移断言。

05 又新增旧版本写失败后 flush 等待更高版本、marker 插入失败回滚、初始化期间拒绝 reset 三项，并核对最后生产边界；代码固定后 56/56 一次运行通过。数量：ExamStateHolderTest 8、ExamLifecycleContractTest 20、ExamPersistenceTest 14、ExamMigrationTest 1、InitializationNotificationTest 13。

所有测试轮次都通过 `tools/build.ps1 -Tasks @('testDebugUnitTest', '--tests', <表中完整类名/通配符>..., '--no-daemon')` 运行。01～05 各自执行新输入后必要复测，原始失败未删除。06/07 精确命令：

```powershell
./tools/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','--rerun','lintDebug','--continue','--no-daemon')
```

06 的五项失败全部定位到批准的新契约替代的旧断言：NotificationEntryUiTest 四项要求匿名旧快照 JSON 原文不变；PersonalMarksContractTest 一项要求当前 DB 版本仍是 4。8b88842 只把前者的后台保存断言改为 await flush 后比较语义字段、合法且跨通知/重建稳定的 ID，后者版本改 5；原 UI 操作、导航、可见性与全部学习数据断言均保留，未修改 UI 实现或历史迁移校验。

07 对上述变化进行必要的完整复测，不拼接前轮通过结果：**177 项，0 失败/错误/跳过**，Debug 构建成功，Lint **0 错误/5 既有警告**，Gradle BUILD SUCCESSFUL（2m38s）。所有 24 类原始 XML、原始 Lint XML/HTML/TXT、schema 1～5、96 项源码双份指纹和 Debug APK SHA-256 已归档。APK 仅在独立构建中生成，未覆盖稳定产物、未发布。完整报告对应 fixed HEAD 8b88842；此后仅更新证据与交接文档。

## 业务覆盖与证据定位

- 500ms 可控写入屏障下，Main Handler 事件和实际 answerExam/index/saveExam 持续推进，busy 仍 false；不是只断言 dispatcher。
- 后台最多一份在途和一份合并待写。flush 后关闭 holder/DB，重新打开文件偏好及 DB，核对 sessionId/revision/题序/index/deadline/答案；旧完成不盖新状态。捕获旧版本的 flush 在旧写失败而有较新尝试时等待后者。
- ExamFilePreferences 使用真正文件和 fsync；false 只改当前缓存、不改磁盘。独立实例证明未保存，没有用 contains 或旧缓存伪装重启。错误可改答/显式重试；取消等待者不取消受理写入。
- 无 ID 快照身份保存失败时零记账；放弃不要求保存成功。连清理也失败、原匿名文件重现时，通过 legacyHash 终止别名保持不可作答。成功升级重开 ID 稳定；相同试卷新考试 UUID 不同，分别计一次。
- 事务前、事务中、终止标记插入、事务后、清理失败故障。独立 holder、文件 DB/偏好重开以及真实新 ViewModel 恢复：原结果保持，Attempt/Progress/DailyCount 不重复，未答题仍无行。旧快照迟到只进入清理；题目缺失也不重新判分；SUBMITTED 不能改为 ABANDONED。
- 保存与放弃交错、终止后迟到事件、新场次、重复重试、owner clear 事务暂停/释放，保证先完成考试临界工作再关 DB，无关闭竞争。新实例中的通知仍 REVIEW，考试保留为清理阶段。
- 文件型 v4→5：每个旧表、每个字段种入值，重开迁移前后逐项核对，新台账与唯一键可用；schema-comparison.txt 核对 8 张旧表的建表/索引 SQL 和有效字段不变。v5 exporter 省略空数组和默认 false，不代表结构变化；schema 1～4 原文件未改。
- 原 StorageAndApiTest（1→当前）、StickyNoteStorageTest（2→当前）、LearningDataTest（3→当前）历史迁移断言在 02 已通过，完整运行再次使用原断言。备份仍 v3，不导出考试/台账；活动/待清理/启动恢复期间拒绝恢复或重置，成功替换仍保留台账。

## 契约、迁移和接入

详见 docs/API_CONTRACT.md、docs/BACKEND_HANDOFF.md 顶部 T2 节：固定接口、线程/成功/失败/取消语义、持久化时序与崩溃点结果表、迁移和回退步骤。v4 APK 不能直接打开 v5 DB；源码 revert 不降级数据，禁止自动删库/破坏性回退。升级前结束考试并保留可恢复 v3 备份；优先继续识别 v5/台账的前向修复。旧匿名散列仅为迁移别名，新考试始终随机 UUID；不追溯升级前历史重复。

Claude 仍需接入新版保存提示、Error 重试、主动离页 await flush、生命周期尽力 flush，并按 T1 状态优先冻结输入。当前仓库没有其 T2 UI 聚焦提交，06 仅验证后端和现有基线 UI，**不是双方 T2 集成验收**。其提交到位后再按 §14.4 固定共同 HEAD 做一次统一验收；没有用旧 UI 结果冒称新 UI 已验。

未验证：真机进程强杀、存储故障、通知与配置变更，新版保存 UI 可访问性/小屏；Python 未修改未运行，T3/T4 不在范围。未更换稳定 APK、版本、签名，未 push/tag/Release。

状态：**待审查**。