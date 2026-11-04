# T4 补修后统一集成复测（待审查）

固定受测 HEAD：`492b361b8a2729ac2ce25b49dab427f17f798ffa`。包含后端 38cb7c3、前端 ac387ac/db90bcc 和缺题/加载离页补修 492b361。开工工作区仍只有 Architect 原五个未跟踪项，未修改或暂存。本轮 GPT 只做修复后的必要统一复测、原证据核对及交付文档更新，没有新增生产、UI、测试或接口改动。

## 固定输入与实际运行

复用 GPT 自有隔离副本 `.gpt-t3-backend-20261104` 和自有 app/build，入口 `C:\AndroidProjects\EmbeddedQuiz-gpt-t3-backend-20261104`，不覆盖 Claude 构建目录。109 项 Android 输入工作区/副本逐字节复制、SHA-256 核对，Claude 两个补修交付文件指纹匹配；无额外 app/src，也未把原隔离诊断加入正式套件。tested-head.txt、initial-status.txt、source-fingerprints.csv、frontend-fingerprint-verification.csv、01-full-command.txt 固定实际输入。

```powershell
./tools/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','--rerun','lintDebug','--continue','--no-daemon')
```

实际完整结果：**248/248 通过，0 失败、0 错误、0 跳过，31 个测试类**；CodeDraftSaveUiTest 15 项和三个新增缺题回归均实际执行并通过。Debug 构建成功，Lint **0 错误/5 个既有警告**（ID/说明与上轮一致）。命令退出 0，BUILD SUCCESSFUL in 3m 44s，55 个任务（17 执行、38 up-to-date）。109 项 Android 输入运行前/后及隔离副本逐字节一致。

原始证据：01-full.log、01-full-xml/、01-full-summary.csv、01-final-result.json、build-exit-code.txt；after-fingerprints.csv 为运行后核对，missing-question-regressions.json 从正式 XML 列出三个新用例及耗时。lint/ 保存原始 XML/HTML/TXT，schema/ 为未改的 1～5，debug-apk-fingerprint.json 仅记录独立 Debug 产物。actual-draft-write-evidence.txt 为本轮核心 XML 原输出：100 次输入实际提交 1 次、持续输入虚拟 2000/4000/6000ms 阶段保存。

使用离线 JDK 17 / Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / Room 2.7.2，没有改工具或依赖。此前 db90bcc 的完整 245/245 与独立缺题 2/2 失败完整保留在 ../T4-integrated-20261104/；此次因为真实 UI 修复和三个新正式回归改变集成内容而必要复测，不重复不变版本凑全绿，不拼接结果。

## 原缺题缺口与覆盖边界

原缺口为 QuestionContent 挂 DraftLeave，而 q==null 加载/缺题分支没有普通草稿处理，系统返回落外壳、顶部返回走非考试 ExamLeave 直接 HOME。

补修后，加载分支与 MissingQuestion 都按当前 queue id 使用 rememberPracticeDraftLeave：普通练习挂 DraftLeaveDialog 和系统 BackHandler；缺题页顶部返回同样委托它，等待时禁用返回，显示真实草稿保存状态/错误与重试。考试保持 ExamLeave，异步结果仍核对 page/practiceSession/index，通知接管后丢弃旧导航/弹窗。只读审阅确认这些分支已接同一套协议，不需要新增后端接口。

CodeDraftSaveUiTest 新增三个正式用例：缺题时系统返回等待落盘、失败后顶部/系统返回留页并显式重试、等待期间通知接管不被旧回调覆盖；该类现有 15 项进入本轮正式全套。

Claude 原样复用 GPT 诊断类后 2/2 通过，GPT 核对了其报告 SHA-256 与原 probe-source 的实际字节哈希完全一致（92F2CB6997B89DAC79D4D9B11647839B168CB8926E50AD2B024AC91FE06B6C72），以及原始 XML 2 项、0 失败/错误/跳过。见 original-probe-verification.json 及 ../T4-frontend-missing-20261104/ 的原始日志/XML；本轮不重复运行已可信记录的定向诊断。

新增三个正式测试在旧版上的失败发生于新状态行断言，早于离页行为，**不将它们称为旧导航缺陷的独立复现**。原导航失败依据仍为 GPT 的 2/2 QUESTION/HOME 对照，修复后原样诊断通过。加载中分支与缺题页共用协调器，但没有受控加载停顿的独立正式测试，只经代码审查，不计作单独测试通过。

## 尚待设备与集中审查

[7 步真机清单](../T4-frontend-20261104/README.md) 仍未执行：长代码输入延迟、输入法拼字/选词/光标、切题与离页、后台保存、备份边界、手动笔记、大字号/深色/TalkBack。尤其输入法组合和实际转后台保存效果不能由本轮 Robolectric 通过代替；onStop 代码已纳入受测版本，未增加专用生命周期或设备执行证据。

Room 5、备份 3、版本 1.6.0 / 7 保持；代码回退不撤销已写文本，不清库/降级。未编辑共享 REPAIR_DISPATCH/FRONTEND_HANDOFF、旧证据、版本或签名；未推送、打标签、Release 或覆盖稳定 APK。本轮没有新增需要 Claude 接手的确定代码问题，直接交 Architect 集中审查；加载分支的独立测试及真机限制按上文如实保留。状态：**待审查**。