# T3 后端交付与验证（待审查）

用户“修 T3”授权，起点 `281d035`，T2 已由 Architect 按共享文档第 15 节验收。GPT 本轮只负责后端。开工工作区原有的 Architect 五个未跟踪项未修改、暂存；REPAIR_DISPATCH、FRONTEND_HANDOFF、Compose/UI、MainActivity、版本、依赖、OrderedWriter、QuestionDraft 均未修改，未开展 T4 或发布。

## 固定实现与范围

- `c21b86fa95c7189e5c09cbead23dd6e30acee762`：冻结历史解码/索引规则，迁移与外部导入规则解耦；原 JSON 与旧学习字段保留，无法解释时明确失败、整个迁移回滚。当前库同样先验证，外部导入仍严格。
- `25b3142bfee0c5d5f083d6b1e2566ec9ad999dd2`：启动前只读预检 schema 5 的九张依赖表/列，避免损坏的当前库被标成 Ready 后才由订阅报错。
- 分阶段 Loading/Ready/Error、显式重试、按次初始化等待；失败前不订阅，成功后一次订阅；保留通知 REVIEW 优先及未结束考试。
- 独立于 Room 打开的诊断与流式现场 ZIP；损坏回调不删库，禁用允许丢数据恢复。恢复备份必须明确确认、数据库可用且无活动/未知考试；沿用原子恢复并保留终止台账。数据库打不开时明确拒绝替换，不自动重建/清库。

接口、线程、取消、成功/失败及持久化语义见 [API_CONTRACT](../../docs/API_CONTRACT.md) 顶部 T3 节；[方案](../../docs/T3_BACKEND_PROPOSAL.md) 在实施前固定选择及双方范围。共享文档尚未补齐 T3 固定目标接口；这些是 GPT 实现契约，待 Architect 审查，不能称已获新的 Architect 裁定。

## 实际执行与原始证据

| 轮次 | 范围 | 结果 | 说明 |
| --- | --- | --- | --- |
| 01 | 既有启动/学习迁移/考试四类 | 49/49 | 实施过程中的相关回归 |
| 02 | 新故障测试与相关五类 | 26 项，25 通过、1 失败 | StartupRecoveryTest 的 JUnit setup 误返回 Boolean，类初始化失败；修正为 Unit，保留原报告 |
| 03 | 启动恢复业务 | 11/11 | 修正测试 setup 后实际执行 |
| 04 | 固定 c21b86f 全套 + Debug + Lint | 201/201，0 失败/错误/跳过 | 构建成功；Lint 0 错误、5 既有警告 |
| 05 | 缺依赖真实故障，生产代码仍为 c21b86f | 1 项失败 | 实际 Ready，期望失败；这是实现缺口，不是测试问题 |
| 06 | 补修后的启动/历史迁移/通知三类 | 29/29 | 新用例同时验证缺表和缺列 |
| 07 | 固定 25b3142 完整复测 + Debug + Lint | **202/202，0 失败/错误/跳过** | **构建成功，Lint 0 错误、5 既有警告** |

最终 07 完整命令退出码 0，BUILD SUCCESSFUL in 7m 58s，55 个任务（11 执行、44 up-to-date）；测试任务真实重跑，27 个测试类，103 项输入运行前后指纹一致。Lint 五条警告的 ID/说明与 04 完全相同。07-final-result.json 汇总结果，07-after-fingerprints.csv 为运行后核对；lint/ 为最终原始 XML/HTML/TXT，schema/ 为未改的 1～5，debug-apk-fingerprint.json 仅记录独立 Debug 产物（未发布）。07-running-thread-dump.txt 是本次测试框架加载较慢时的只读线程快照，不是失败报告。

各轮原始日志、XML 和逐类 CSV 分别保留，不删失败，不将分轮通过拼接成完整通过；04 的全绿也不能证明已覆盖 05 后来发现的缺口。没有跳过测试、削弱断言或增加等待时间规避问题。

完整命令：

```powershell
./tools/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','--rerun','lintDebug','--continue','--no-daemon')
```

独立副本 `.gpt-t3-backend-20261104`，独立 ASCII junction `C:\AndroidProjects\EmbeddedQuiz-gpt-t3-backend-20261104`，独立 app/build；使用仓库离线 JDK 17 / Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / Room 2.7.2。04 固定 102 个 Android 输入，07 固定 103 个，工作区与副本逐字节 SHA-256 匹配。`04-*` 和 `07-*` 分别记录各自提交、命令、原始结果和源码指纹；早期未编号 tested-head/source-fingerprints 属于 04 的历史输入。

## 业务覆盖及数据边界

HistoricalMigrationTest 的文件型 1/2/3→5 验证逐字段保留所有原表值、原始 JSON、学习历史、Progress、AI、便签；五类坏条目逐路径验证数据库 userVersion 和原列/行保持，所有 ALTER 一并回滚。已为 v4/v5 的启动另有覆盖。没有声称已证明 v4 新增校验拒绝正常旧数据。

StartupRecoveryTest 12 项覆盖：重试合并、旧结果稳定/新尝试可等待、独立等待取消、无重复订阅、通知和原考试；缺表/缺列在订阅前失败；owner clear；恢复确认/考试未知或活动拒绝/失败原数据保持/成功保留台账；真实损坏文件 1024 字节保留、无需 Room 的诊断导出、修复后同实例重试；私有源目标隔离；部分考试恢复失败和守卫重入。成功备份恢复不承诺保留 Attempt 内部自增 ID（备份 v3 不含它），断言所有实际导出字段。

Room 仍 5、备份仍 3，无新 schema 或迁移版本。旧 schema 1～5 未改。诊断 ZIP 不是应用可导入备份，不读密钥/完整偏好；原库包含用户记录，必须用户主动选目的地；考试缓存不证明 commit 成功。源文件变化/取消/写出失败可留下不完整目的文件，不写源。数据库打不开时没有自动换库入口，必须保留现场，再作有授权的修复。

回退仅代码，不降级 DB、不自动删除/卸载。保留升级前学习备份及诊断原库，优先保留 v5 的前向修复；旧损坏删库路径不具备本次保数据保证。v3 备份不包含运行考试或本机终止台账，外部直接替换整库仍不在 T2 幂等保证内。

## Claude 接入与未验证项

Architect 确认接口后，Claude 接 AppNavigation 启动分支及独立错误页：Loading/Ready/Error；诊断查看/用户选文件导出/显式重试；确认替换影响后调用 restoreStartupBackup(uri,true)，展示 Rejected/Failed，Completed 后继续观察新启动。考虑 startupRecoveryBusy/canRestoreBackup，保留通知优先，不能直接清库或读 SQL/偏好。

本轮没有接入错误页，当前 ready=false 旧 UI 仍只显示加载，不能称 T3 已整体验收。ViewModel 构造前的工厂/资源错误不在状态协议内；只读依赖预检不保证后续所有运行时查询永不失败。未做大规模自定义题库性能测量、真机强杀/真实存储故障、TalkBack/设备配置变化验收；T4 未做。没有推送、标签、Release 或覆盖稳定 APK。

状态：**待审查**。