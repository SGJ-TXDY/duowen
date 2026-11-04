# T4 双方统一集成：完整套件通过，缺题离页待补修（待审查）

固定受测 HEAD：`db90bcc5e3e0a40b650e03fd0a1465a322dcd4c4`。包含 GPT 后端 38cb7c3、证据 42be3e4、Claude 编辑器 ac387ac 与完整前端 db90bcc。开工工作区仍仅 Architect 原五个未跟踪项，未修改或暂存。本轮 GPT 不改生产代码、正式测试、契约、迁移或 UI，只提交此独立验证目录和 BACKEND_HANDOFF。

## 固定完整运行

**正式完整套件 245/245 通过，0 失败、0 错误、0 跳过，31 个测试类。** Debug 构建成功，Lint **0 错误/5 条既有警告**，警告 ID/说明与 T4 后端最终结果一致。完整命令退出 0，BUILD SUCCESSFUL in 3m 29s，55 个任务（17 执行、38 up-to-date）。只对本共同 HEAD 统一运行一次全套，没有拼接定向通过或重复不变版本求绿。

```powershell
./tools/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','--rerun','lintDebug','--continue','--no-daemon')
```

复用 GPT 自有隔离副本 `.gpt-t3-backend-20261104` 和它自己的 app/build，入口 `C:\AndroidProjects\EmbeddedQuiz-gpt-t3-backend-20261104`；没有覆盖 Claude 构建目录。109 项 Android 输入按工作区精确字节复制，运行前/后以及隔离副本 SHA-256 一致，无额外 app/src 源码。Claude 四个交付文件的原指纹全部匹配。

原始完整证据：tested-head.txt、initial-status.txt、01-full-command.txt、01-full.log、01-full-xml/、01-full-summary.csv、01-final-result.json、build-exit-code.txt；source-fingerprints.csv/after-fingerprints.csv、frontend-fingerprint-verification.csv；lint/（XML/HTML/TXT）、schema/（未改的 1～5）、debug-apk-fingerprint.json（独立 Debug，仅摘要，未发布）。工具链沿用离线 JDK 17 / Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / Room 2.7.2，不升级依赖。

CodeDraftSaveUiTest 12、CodeDraftPersistenceTest 11、CodeDraftBoundaryTest 12 均纳入本次正式全套；原考试/通知/启动/迁移/便签/速记/练习全部保留。核心实际写事务计数仍为 100 次/10ms 输入 **1 次提交**，6s/100ms 连续输入在虚拟 **2000/4000/6000ms** 提交 v19/v39/v59，最终字节重开 DB 保持；原始 CodeDraftPersistenceTest XML system-out 可核对。

## 独立验收诊断：普通缺题页绕过草稿等待

**待 Claude 补修的 UI 缺口，不能以正式全套全绿代替这项场景验收。** §18.4 明确要求缺题/加载状态也覆盖离页，不只是已加载题的顶部按钮。当前 QuestionContent 中有 DraftLeave，但 QuestionScreen 的 q==null 分支尚未挂普通草稿返回处理；MissingQuestion 仍仅有 ExamLeave，BackHandler 只在考试开放时启用。普通练习缺题时，系统返回落到 AppNavigation 的直接 HOME，顶部返回经非考试 ExamLeave 同样直接 HOME。

在同 package 的独立诊断类（仅放隔离副本）用真实 ViewModel 重现。夹具在首次加载前只从测试数据库删除 cd-a 源题，保留已经受理的最新草稿；beforeWrite 屏障使保存仍在途或明确失败，不更改生产后端。不是演示真实用户数据库删除，也没有声称已发生永久数据丢失。

| 诊断用例 | 保存状态与操作 | 期望 | 实际 |
| --- | --- | --- | --- |
| missingQuestionSystemBackMustWaitForTheAcceptedDraft | 最新草稿 Saving、IO 屏障未放行，缺题页按系统返回 | 留在 QUESTION 等落盘 | **HOME，失败** |
| missingQuestionTopBackMustStayAndOfferRetryOnSaveFailure | 最新草稿 Error，缺题页点顶部返回 | 留在 QUESTION，按草稿错误协议重试 | **HOME，失败** |

02 独立结果 **2 项、2 失败**，失败均为 QUESTION/HOME 的业务断言，无夹具初始化失败。生产代码保持 db90bcc，诊断源码和两个新增用例不混入正式 245 项。02-probe-command.txt、02-missing-path-probe.log、02-missing-path-probe-xml/、02-probe-summary.csv、probe-source/T4MissingDraftProbeTest.kt 保存完整证据。归档后已删除隔离 app/src 中的临时诊断文件，重新核对正式输入未改；未重跑不变的全套，也没有越界修改生产 UI 或 Claude 的正式测试。

Claude 建议修复范围：QuestionScreen.kt 的加载/缺题分支与普通草稿离页协调器；让当前 queue id 的协调器在完整题目尚未加载/缺失时仍有效，接管缺题页顶部/系统返回并显示真实保存失败/重试。保留考试 ExamLeave 与通知优先，异步结果仍核对 page/practiceSession/index。补上述正式用例，并为加载态及通知接管加对应覆盖；无需修改后端接口或 SQL。源位置见 QuestionScreen.kt:32（加载分支）、:365（DraftLeave）、:429（MissingQuestion）、AppNavigation.kt:32（外壳返回）。

独立诊断只验证了缺题的两条路径；加载态目前是只读审阅发现同样没有协调器，未另行把它算作第三个失败复现。修复及正式测试由 Claude 完成后，GPT 再固定共同版本作必要集成验证，Architect 集中审查。

## 真机与其他边界

[Claude 的 7 步设备清单](../T4-frontend-20261104/README.md) 保留为**尚未执行**：≥200 行持续输入、输入法拼字/选词、切题和所有离页、转后台后保存、备份边界、手动笔记、大字号/深色/TalkBack。输入法组合与真实后台/存储效果不能用 Robolectric 或代码阅读代替。onStop 的非阻塞 flush 已进入本次代码，但本轮没有新增真实设备或专用生命周期注入结果，不宣称已完成后台保存真机验收。

Room 5、备份 3、版本 1.6.0 / 7 保持；未修改共享 REPAIR_DISPATCH、FRONTEND_HANDOFF 或原始交付证据，未自行修复 Git 整理、推送、标签、Release 或覆盖稳定 APK。

状态：**待审查；普通缺题离页遗漏待 Claude 补修，T4 尚不具备完整场景验收结论**。正式完整套件通过与独立诊断失败分别报告，最终验收由 Architect 裁定。