# G1 合并版本 Architect 集中验收

日期：2026-10-27。源码版本见 head.txt（e3be2a6）；包含后端 d21c381 与前端 e3be2a6。运行前无受跟踪源码改动，已检查没有其他 Gradle 测试 worker。

执行：tools/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','--rerun','lintDebug','--continue','--no-daemon')。

完整测试真实执行：161/161 通过，0 失败、错误、跳过；Debug 构建通过，Lint 0 错误、5 既有警告。full.log 显示 BUILD SUCCESSFUL in 1m 53s。xml/ 为本次原始报告；未拼接定向结果。

已核对双方交付的 7 个源码/契约指纹；审查初始化结果、通知优先级、考试保留、重复通知、配置恢复和测试隔离。G1 功能及自动化验收通过；真机通知与进程恢复仍未验证。

发布审查未通过：API_CONTRACT 和共享任务书中明确的 T2 跨进程重复记账窗口仍存在。ExamStateHolder.executeOperation 先提交学习事务，再删除偏好快照，进程在中间退出可恢复并重复提交。T3/T4 也尚未实施。当前 versionName 仍 1.6.0 / versionCode 7，不是 1.6.1 包。

没有修改业务代码或测试，没有生成 1.6.1 标签、推送或 GitHub Release。用户授权条件为审查无问题后发布；本次发现仍有既定发布阻断，故不把 G1 全绿当作整版发布通过。
