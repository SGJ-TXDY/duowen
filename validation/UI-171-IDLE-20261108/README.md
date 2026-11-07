# 1.7.1 两组新界面测试的 AppNotIdle：复现、定位与补修（Claude，2026-11-08）

依据 `docs/PLAN-1.7.1.md`“联合收尾”中给 Claude 的要求。只改了 `QuickNoteReaderAiTest.kt` 和 `StickyNoteSourceUiTest.kt` 两个测试文件，生产代码、断言、超时和共享 helper 都没有改。

## 1. 在原输入上复现

原输入是 `20c463c4` 加当时 GPT 的 47 个本地文件，按 `../UI-171-WIRING-20261108/input-fingerprints.csv` 逐个核对，0 个不一致。另加一个只读探针 `diag-probe-src/DiagComposeQueueProbe.kt.txt`，用反射读取 `AndroidUiDispatcher.Main` 的状态，不改动它。探针不提交。

`01-original-input-full-with-probes-9-failed.log`：全量 418 项中 9 项失败，与原失败完全相同，即 QuickNoteReaderAiTest 5 项和 StickyNoteSourceUiTest 4 项。探针在每个测试开始时记录到的状态如下。原 XML 已被后续诊断运行覆盖，这里按当时读取的输出照录：

```
DIAG QuickNoteReaderAiTest.before …: scheduledTrampoline=true toRunTrampolined=1 scheduledFrame=true handlerHasCallback=false handlerLooperIsMain=true mainLooperIdle=true
DIAG QuickNotesUiTest.before …:      scheduledTrampoline=true toRunTrampolined=1 scheduledFrame=true handlerHasCallback=false handlerLooperIsMain=true mainLooperIdle=true
```

含义：dispatcher 认为它的回调已经安排好了，待执行队列里也有任务，但这个回调在主线程 Handler 中已经不存在，主线程队列是空的。也就是 `ComposeMainQueue` 注释描述的“原回调丢失”，满足任务单允许复用它的条件。对照类 QuickNotesUiTest 处在同样的状态却能通过，失败只出现在不经过 `QuizApp`、直接渲染页面的这两个类。

## 2. 触发前缀与最小触发顺序

探针类 `ZzzDiagProbeTest` 排在最后执行，只报告状态，不等待空闲。按全量的类顺序（`full-run-class-order.txt`）取前 k 个类，再加上这个探针（`diag-logs/`）：

| 运行 | 结果 |
| --- | --- |
| 只跑探针（k0） | 正常 |
| 前 10 / 15 / 17 个类 | 正常 |
| 前 18 / 19 / 38 个类 | 原回调丢失 |
| 只跑第 18 个类 `EnhancedAiTest` | 正常 |
| 只跑 `ExamLifecycleContractTest` | 正常 |
| `AiChatScreenTest` → `EnhancedAiTest` | 原回调丢失 |
| `ComposeMainQueueTest` → `EnhancedAiTest` | 原回调丢失 |

- **最小触发顺序一**：任一 Compose 界面测试之后，接一个会写 Compose 快照状态的非界面测试（`EnhancedAiTest`）。直接复现失败：`AiChatScreenTest` → `EnhancedAiTest` → `StickyNoteSourceUiTest` 中有 2 项 AppNotIdle，卡在 setup 里的等待（`diag-min-repro.log`）。
- **最小触发顺序二**：`StartupTest`（启动真实 `MainActivity`）→ `StickyNoteSourceUiTest`，4 项全部失败，并且卡在 Compose 测试规则启动时的空闲等待，早于任何 `@Before`。这一次压下的待执行任务有 6 项（`diag-startup-sticky.log`）。全量里便签类正好排在 `StartupTest` 之后。

## 3. 补修

1. **外层规则补送原回调。** 两个类都加了 `@get:Rule(order = 0)`，在 Compose 规则（`order = 1`）启动之前调用 `ComposeMainQueue.deliverStrandedCallbacks()`。它只补送 dispatcher 自己的那个原回调，不丢弃快照变化，也不重置 dispatcher。最初补送放在 `setup()` 里，能修好顺序一，但修不好顺序二，因为 Compose 规则在 `@Before` 之前就已经开始等待空闲（`02-0160b153-first-fix-4-failed.log`）。之后改为外层规则。
2. **按顺序清理。** 先停测试自己的 scope；阅读器测试先把页面撤下，断言 `quick-note-reader` 已不存在（WebView 在 `onRelease` 中销毁）；再 `store.clear()`，等 `vm.examResourcesClosed` 完成（上限 20 秒，超时判失败），断言 `vm.db.isOpen == false`；最后才删除测试库和速记目录。

补修后两组最小触发顺序都通过（`diag-chat-enh-fixed.log`、`diag-startup-sticky-fixed.log`、`diag-min-fixed.log`）。

## 4. 最终验证

输入为 GPT 的提交 `0160b153`（以 `20c463c4` 为基线）加上本次两个测试文件，指纹见 `final-input-fingerprints.csv`。

`03-0160b153-final-full-418-pass.log`：`assembleDebug testDebugUnitTest lintDebug --rerun` 结果 BUILD SUCCESSFUL。共 52 个类、418 项，0 失败 / 0 错误 / 0 跳过（XML 在 `final-test-xml/`）。Lint 0 错误、7 条警告，与 v1.7.0 相同。

## 未闭环

- 原回调丢失本身来自 Robolectric 在测试之间重置主线程队列，而 Compose 1.7.6 会在同一沙箱里沿用同一个主 dispatcher。这是测试环境的问题，生产代码不受影响。本次只在这两个新类里补送；其他界面测试碰到同样状态时没有失败，所以没有改动它们。
- 便签类本身也会给后续测试留下同样状态（补修后的顺序二结束时探针仍报告丢失），全量中排在它后面的都是非界面测试，因此没有影响。以后如果在它之后新增界面测试，需要同样的外层规则。
- UI-04A 中 `AiConversationUiTest` 4 项和 1.7.0 中出现过的间歇 AppNotIdle，与这里的特征一致，很可能是同一机制，但没有为它们单独复现，不作为结论。
