# T2 前端：考试保存提示、显式重试、离页等待（Claude，REPAIR_DISPATCH §14.3）

日期：2026-11-04（Asia/Tokyo）。起始 HEAD：`831bcbe04ade2efcf54cee0df5a4b979f4f976f4`（GPT T2 后端交付）。
运行源码 = 该 HEAD + 本次未提交改动，三个文件 SHA-256 见 `source-sha256.txt`，与交付提交一致。

隔离：独立 ASCII 副本 `C:\AndroidProjects\EmbeddedQuiz-claude-iso`（robocopy 自本仓库，排除 build/.gradle/.git/validation），使用该副本自己的 `app/build`，`--no-daemon`；不占用共享构建输出。

## 01 定向运行（真实执行）

命令见 `01-targeted.log`（BUILD SUCCESSFUL，testDebugUnitTest 实际执行）；XML `01-targeted-xml/`：

| 测试类 | 项数 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| ExamSaveUiTest（新增） | 7 | 0 | 0 | 0 |
| ExamTerminationUiTest | 12 | 0 | 0 | 0 |
| NotificationEntryUiTest | 5 | 0 | 0 | 0 |
| ReviewReminderTest | 4 | 0 | 0 | 0 |
| PracticeEntriesUiTest | 4 | 0 | 0 | 0 |
| NotesAndScoringUiTest | 8 | 0 | 0 | 0 |
| QuickNotesUiTest | 5 | 0 | 0 | 0 |
| ExamPersistenceTest（GPT，未改） | 14 | 0 | 0 | 0 |
| ExamLifecycleContractTest（GPT，未改） | 20 | 0 | 0 | 0 |
| InitializationNotificationTest（GPT，未改） | 13 | 0 | 0 | 0 |
| 合计 | 92 | 0 | 0 | 0 |

ExamSaveUiTest 用后端 internal 接缝 `examState.persistence.beforeWrite`（在 IO 写入前挂起或抛错）与 `examState.sessionPreferences`（拒绝删除考试快照且不改磁盘），生产 UI 不访问偏好或 DAO。覆盖：

1. 写入被挂起时显示“保存中…”，busy=false，仍可选择、翻题；只有最新 revision 落盘后才显示“已保存到本机”，磁盘快照的 revision/index/answers 与之一致。
2. 写入失败：持续显示“保存失败，最新修改尚未保存”，仍可作答；新修改触发一次新尝试，无新修改时推进 5 秒不再写；点“重试保存”成功后才显示已保存。
3. 离页：写入挂起时点返回或系统返回，留在原页、选项与翻题冻结、重复点击无效；落盘后再导航，磁盘为离开前的答案。
4. 离页保存失败：弹“保存失败，暂未离开”，“留在本页”不离开且答案保留；从“打开全局标签”离开同样被拦下，对话框“重试保存”成功后继续到便签页。
5. 交卷保存屏障失败：`Failed(SUBMIT, RECORDS, false)`，提示“考试保存失败，未提交成绩”；答案冻结，不再提供“重试保存”，无学习记录；失败态离页不拦截，考试入口“重试交卷”后成绩 3/2/1/1/33、每题一条记录。
6. 提交后清理失败（快照仍在磁盘）→ 关闭 ViewModel（等待其数据库关闭）→ 新 ViewModel：恢复原成绩与“成绩已保存，会话清理未完成”，保存状态 Inactive，不显示成绩横幅/交卷/已保存，Attempt 仍为 1；“重试清理”后 Submitted 原成绩，Attempt 仍为 1，快照清除。
7. 320×480dp + 大字号：失败提示可见且带 liveRegion，重试按钮可见可点，选项与交卷按钮仍可用。

## 开发中的失败运行（不计为证据）

- 首次编译失败：`FlowRow(itemVerticalAlignment=…)` 在当前 Compose 版本不存在，改为 Row + weight 让文字换行。
- 首轮 7 项中 4 项失败：
  - 3 项是开始考试后题目尚未加载、答题页（含保存提示）还未组合，测试改为先等待试卷出现。
  - 1 项是重启后点“重试清理”无效。探针（只在隔离副本临时加入，已被同步覆盖）显示：按钮可用、语义点击也不触达新 ViewModel，直接调用 `retryExamOperation()` 则清理成功。原因是测试宿主在同一组合内替换 ViewModel，旧组合保留了绑定旧实例的处理器；生产中重启总是新的组合。改为 `key(vm) { QuizApp(vm) }`，每个 ViewModel 一个新组合。后端重启后的清理重试本身工作正常。
- 未削弱断言、未加等待时长。

## 未做 / 未验证

- 未运行完整套件（§14.4：由 GPT 合入双方提交后统一运行一次）。
- 无真机：真实进程被杀、真实存储故障、TalkBack 实际朗读。
- 按下文交接说明，未在后台/通知路径另加 flush。
