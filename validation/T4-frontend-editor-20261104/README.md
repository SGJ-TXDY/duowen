# T4 前端第一部分：代码编辑器只在文字变化时通知（Claude，REPAIR_DISPATCH §18.4）

日期：2026-11-04（Asia/Tokyo）。起始 HEAD：`77c06bd3eecff468ea15d1e8f4ccb7babe23157c`。
运行源码 = 该 HEAD + 本次改动，两个文件 SHA-256 见 `source-sha256.txt`，与交付提交一致。隔离副本 `C:\AndroidProjects\EmbeddedQuiz-claude-iso`（自有 app/build，`--no-daemon`）。

## 范围

§18.2 的草稿保存门面（`codeDraftSaveState`、`updateCodeDraft`、`flushCodeDrafts` 等）在本 HEAD 尚未由 GPT 实现。本部分只做不依赖该接口的 §18.4 要求：

- `CodeEditor` 的 `onValueChange` 原来对每个 `TextFieldValue` 变化都调用 `onChange(text)`，光标移动、选区与输入法组合更新也会产生一次草稿更新（每次即一次保存请求）。现在只在文字实际变化时调用；选区与组合态保留在本地 `TextFieldValue`。
- 新增 `enabled` 参数（默认 true，PersonalEditors 现有调用不变）：false 时 `readOnly`，文字可读可选，Tab 缩进禁用。供 §18.2 `codeDraftEditingBlocked` 接入。

## 01 前后对照（CodeDraftSaveUiTest 中可在旧编辑器上编译的用例）

| 运行 | CodeEditor.kt | 结果 |
| --- | --- | --- |
| 01a | HEAD 版本 | **失败**：三次纯光标/选区移动被报告为三次改动 `expected:<[]> but was:<[int a;, int a;, int a;]>` |
| 01b | 交付版本 | CodeDraftSaveUiTest 3/3 通过 |

01a 只保留第一个用例（另两个用到新增的 `enabled` 参数，旧代码无法编译），日志记录了所用文件 SHA-256。

## 02 定向回归（交付版本，真实执行）

`02-targeted.log`、`02-targeted-xml/`：7 个类 **45/45 通过**，0 失败/错误/跳过。

| 测试类 | 项数 |
| --- | --- |
| CodeDraftSaveUiTest（新增） | 3 |
| NotesAndScoringUiTest（含代码草稿缩进与便签往返） | 8 |
| PracticeEntriesUiTest（含个人参考解法编辑器） | 4 |
| QuickNotesIsolationTest | 4 |
| ExamSaveUiTest | 9 |
| ExamTerminationUiTest | 12 |
| QuickNotesUiTest | 5 |

CodeDraftSaveUiTest 覆盖：纯光标/选区不报告改动；输入只报告一次；父级回传相同文字后光标不被重置；Tab 在光标处插入为一次改动、光标位于缩进后；禁用时不可编辑（IsEditable=false）、Tab 禁用、无改动，恢复后可编辑。

## 未做 / 未验证

- §18.4 其余部分（保存中/已保存/失败与重试、切题/离页前 flush、失败留页、通知接管失效、排他边界禁输入、普通笔记保存状态）等待 GPT 的 §18.2 实现。
- 输入法组合（composition）在 Robolectric 中无法注入，只经代码审查：组合更新文字不变时不再触发保存，组合态保留在 TextFieldValue；需真机输入法验证。
- 未运行完整套件；无真机验证。
