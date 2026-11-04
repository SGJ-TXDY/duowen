# T1 Architect 独立审查证据

日期：2026-10-26（Asia/Tokyo）。被审查提交：3628e8816bbfe07445c3c78e7161942b4df81973。

## 结论边界

业务代码与新增测试审查未发现阻止 Claude 按正式契约接入的确定性缺陷；T1 后端阶段通过，可进入前端集成。T1 整体未验收，完整回归失败尚未归因，不允许发布或开始 T2。

已逐一核对两个业务文件及两个新增测试文件 SHA-256，与交付的 source-sha256.txt 一致。变更限于 ExamStateHolder、QuizViewModel 考试相关方法、两份测试和文档/证据；未改 Repository 通用判分、UI、数据格式、题库或依赖。针对源码与 docs 的 git diff --check 通过。

## 独立运行

01-targeted.log：Gradle 报 testDebugUnitTest UP-TO-DATE；没有形成新的测试运行，不能计作独立通过证据。

02-targeted-rerun.log：使用 PowerShell 显式 -Tasks 数组传入参数，对 testDebugUnitTest 使用 --rerun，测试任务真实执行。选择：

- cn.embeddedquiz.ExamStateHolderTest
- cn.embeddedquiz.ExamLifecycleContractTest
- cn.embeddedquiz.PracticeEntriesUiTest
- cn.embeddedquiz.QuickNotesUiTest

Gradle BUILD SUCCESSFUL in 51s。独立 XML 见 02-targeted-xml：28 项 T1 后端 + 9 项原有 UI，共 37/37，通过；0 失败、0 错误、0 跳过。这 9 项就是交付运行 05 的两个超时测试类。

命令参数：testDebugUnitTest --tests cn.embeddedquiz.ExamStateHolderTest --tests cn.embeddedquiz.ExamLifecycleContractTest --tests cn.embeddedquiz.PracticeEntriesUiTest --tests cn.embeddedquiz.QuickNotesUiTest --rerun

联接已核实：C:\AndroidProjects\EmbeddedQuiz → 当前 F 盘 EmbeddedQuiz 仓库。未改联接、源码或测试。

## 不能推导的结论

本次定向运行不能证明全套测试顺序、资源与生命周期隔离没有问题。交付中 04 全套 130/132、05 全套 123/132 的原始 XML 已核对，不能用本次 37/37 覆盖。

05 失败栈显示 Compose 测试环境在运行测试规则时等待空闲超时；现有线索不足以归因于 UI、后端或运行环境。GPT 仍负责完整运行失败的调查闭环；涉及 UI 或既有测试修改必须先提交最小方案，由 Architect 分配。

本次未重复构建 APK、Lint 或 Python；相应结果仅引用交付证据，未冒充本次复测。无真机验证，不是稳定版本发布验收。