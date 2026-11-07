package cn.embeddedquiz

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Conversation UI on the frozen facade, driven by an in-memory fake (no backend, no network). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp")
class AiChatScreenTest {
    @get:Rule val ui = createComposeRule()
    private val q = QuestionOwner("q1", "h1")
    private val facade = FakeAiConversationFacade()

    private fun show() {
        ui.setContent {
            MaterialTheme { AiChatScreen(rememberAiChatController(facade, q), Modifier.padding(16.dp)) }
        }
        ui.waitForIdle()
    }

    private fun openRow(title: String) {
        ui.onAllNodesWithTag("ai-session-row").filter(hasText(title)).onFirst().performClick()
        ui.waitForIdle()
    }

    private fun draftStatus() =
        ui.onNodeWithTag("ai-draft-status").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }

    private fun type(text: String) = ui.onNodeWithTag("ai-draft").performTextInput(text)

    private fun editable() =
        ui.onNodeWithTag("ai-draft").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    private lateinit var controller: AiChatController
    private var code = ""

    /** Like [show], with the send context a question page would give and access to the controller. */
    private fun showWith(programming: Boolean = false) {
        ui.setContent {
            MaterialTheme {
                val c = rememberAiChatController(facade, q, programming, sendContext = { AiSendContext(setOf("A"), code, "deepseek-test") })
                controller = c
                AiChatScreen(c, Modifier.padding(16.dp))
            }
        }
        ui.waitForIdle()
    }

    @Test
    fun listShowsTitlesAndStateAndNewSessionOpens() {
        facade.seed(q, "中断与轮询")
        facade.seed(q.copy(contentHash = "h0"), "旧题讨论", readOnly = true)
        facade.seed(q, "带草稿", draft = "还没问完\n第二行")
        show()

        ui.onAllNodesWithTag("ai-session-row").assertCountEquals(3)
        ui.onNodeWithText("只读 · 旧版题目").assertIsDisplayed()
        ui.onNodeWithText("草稿：还没问完").assertIsDisplayed()

        ui.onNodeWithTag("ai-new-session").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertTextEquals("新讨论 4")
        assertEquals("s4", facade.state.value.selectedSessionId)

        ui.onNodeWithContentDescription("返回讨论列表").performClick()
        ui.waitForIdle()
        ui.onAllNodesWithTag("ai-session-row").assertCountEquals(4)
        assertEquals(1, facade.deselects)
        assertNull(facade.state.value.selectedSessionId)
    }

    @Test
    fun renameUpdatesTitleWithinEightyCodePointsAndShowsStaleRejection() {
        facade.seed(q, "旧标题")
        show()
        openRow("旧标题")

        ui.onNodeWithContentDescription("重命名讨论").performClick()
        ui.onNodeWithTag("ai-rename-field").performTextReplacement("  DMA 与 cache 一致性  ")
        ui.onNodeWithText("保存标题").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertTextEquals("DMA 与 cache 一致性")
        assertEquals(1, facade.session("s1").sessionRevision)

        // 80 code points (emoji count once each) is accepted; 81 is not offered.
        ui.onNodeWithContentDescription("重命名讨论").performClick()
        ui.onNodeWithTag("ai-rename-field").performTextReplacement("😀".repeat(81))
        ui.onNodeWithText("保存标题").assertIsNotEnabled()
        ui.onNodeWithTag("ai-rename-field").performTextReplacement("😀".repeat(80))
        ui.onNodeWithText("保存标题").assertIsEnabled()
        ui.onNodeWithText("取消").performClick()

        facade.renameRejection = Rejection(RejectCode.STALE_SESSION, false, RecoveryAction.REOPEN)
        ui.onNodeWithContentDescription("重命名讨论").performClick()
        ui.onNodeWithTag("ai-rename-field").performTextReplacement("新标题")
        ui.onNodeWithText("保存标题").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-notice").assertExists()
        ui.onNodeWithText("讨论已在别处更新，请返回列表后重新打开。").assertIsDisplayed()
        ui.onNodeWithTag("ai-title").assertTextEquals("DMA 与 cache 一致性")
    }

    @Test
    fun messagesShowOldestFirstWithRunStatusAndHonestUsage() {
        val s = facade.seed(q, "用量")
        facade.add(s.sessionId, MessageRole.USER, "第一问")
        facade.add(s.sessionId, MessageRole.ASSISTANT, "已知用量的回答", facade.run(s.sessionId, q, FakeAiConversationFacade.usage(120, 80, "0.0012345")))
        facade.add(s.sessionId, MessageRole.USER, "第二问")
        facade.add(s.sessionId, MessageRole.ASSISTANT, "缓存回答", facade.run(s.sessionId, q, FakeAiConversationFacade.usage(null, null, null, UsageState.NOT_APPLICABLE), source = RunSource.CACHE))
        val failed =
            facade.add(s.sessionId, MessageRole.ASSISTANT, "半截", facade.run(s.sessionId, q, FakeAiConversationFacade.usage(null, null, null, UsageState.UNKNOWN), RunStatus.FAILED))
        facade.replace(failed.copy(completion = TextState.PARTIAL, run = failed.run!!.copy(errorCode = "timeout")))
        show()
        openRow("用量")

        ui.onNodeWithTag("ai-messages").performScrollToIndex(0)
        val question = ui.onNodeWithText("第一问").fetchSemanticsNode().boundsInRoot.top
        val answer = ui.onNodeWithText("已知用量的回答").fetchSemanticsNode().boundsInRoot.top
        assertTrue(question < answer)
        val list = ui.onNodeWithTag("ai-messages")
        list.performScrollToNode(hasText("输入 120 / 输出 80 tokens · 估算 $0.001235"))
        list.performScrollToNode(hasText("缓存命中 · 本次不计费"))
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasText("用量未知 · 费用未知"))
        ui.onNodeWithText("· 失败（timeout）").assertIsDisplayed()
        ui.onNodeWithTag("ai-partial").assertIsDisplayed()
    }

    @Test
    fun draftSavesAfterAQuietPauseAtMostEveryTwoSecondsAndIsFlushedOnLeave() {
        facade.seed(q, "草稿")
        show()
        openRow("草稿")
        ui.mainClock.autoAdvance = false
        try {
            type("中断")
            ui.mainClock.advanceTimeBy(200)
            assertEquals(0, facade.saves.size)
            ui.mainClock.advanceTimeBy(200)
            assertEquals(1, facade.saves.size)
            assertEquals("中断", facade.saves.last().text)
            assertEquals("草稿已保存", draftStatus())

            // Typing every 230ms never leaves a 300ms pause; the 2s ceiling still saves once, between
            // the 9th keystroke (1840ms) and the 10th (2070ms), well away from either.
            repeat(10) {
                type("字")
                ui.mainClock.advanceTimeBy(230)
            }
            assertEquals(2, facade.saves.size)
            assertEquals("中断" + "字".repeat(9), facade.saves.last().text)
            assertEquals("正在保存草稿…", draftStatus())
            ui.mainClock.advanceTimeBy(400)
            assertEquals(3, facade.saves.size)
            assertEquals("中断" + "字".repeat(10), facade.saves.last().text)
            // Versions only grow and the expected revision follows each acknowledgement.
            assertEquals(listOf(0L, 1L, 2L), facade.saves.map { it.expectedDraftRevision })
            assertTrue(facade.saves.zipWithNext().all { (a, b) -> a.token.localEditVersion < b.token.localEditVersion })

            // Leaving right after a keystroke saves that keystroke first, then flushes, then deselects.
            type("尾")
            ui.onNodeWithContentDescription("返回讨论列表").performClick()
            ui.mainClock.advanceTimeBy(50)
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertEquals("中断" + "字".repeat(10) + "尾", facade.session("s1").draft.text)
        assertEquals(listOf("s1" to 4L), facade.flushes)
        assertEquals(1, facade.deselects)
        ui.onNodeWithText("草稿：中断" + "字".repeat(10) + "尾").assertIsDisplayed()
    }

    @Test
    fun aLateAcknowledgementNeverMarksNewerTextSavedAndFailuresKeepTheEditorOpen() {
        facade.seed(q, "迟到")
        show()
        openRow("迟到")
        val gate = CompletableDeferred<Unit>()
        facade.saveGate = gate
        ui.mainClock.autoAdvance = false
        try {
            type("one")
            ui.mainClock.advanceTimeBy(350)
            assertEquals(1, facade.saves.size)
            type("two")
            gate.complete(Unit)
            ui.mainClock.advanceTimeBy(16)
            ui.onNodeWithTag("ai-draft").assertTextEquals("onetwo")
            assertEquals("正在保存草稿…", draftStatus())
            ui.mainClock.advanceTimeBy(350)
            assertEquals("草稿已保存", draftStatus())
        } finally {
            ui.mainClock.autoAdvance = true
        }
        assertEquals("onetwo", facade.session("s1").draft.text)

        facade.saveRejection = Rejection(RejectCode.STORAGE_FAILED, true, RecoveryAction.NONE)
        type("three")
        ui.mainClock.advanceTimeBy(400)
        ui.waitForIdle()
        assertEquals("草稿未保存：AI 存储写入失败，内容未保存。", draftStatus())
        ui.onNodeWithContentDescription("返回讨论列表").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertExists()
        ui.onNodeWithTag("ai-draft").assertTextEquals("onetwothree")
        assertEquals(0, facade.deselects)
        assertTrue(facade.flushes.isEmpty())
    }

    @Test
    fun copyPutsTheStoredTextOnTheClipboardAndSaysSo() {
        val s = facade.seed(q, "复制")
        facade.add(s.sessionId, MessageRole.USER, "怎么写 ISR？")
        facade.add(s.sessionId, MessageRole.ASSISTANT, "**要点**：ISR 要短。", facade.run(s.sessionId, q, FakeAiConversationFacade.usage(1, 1, "0")))
        show()
        openRow("复制")

        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithContentDescription("复制回复").performClick()
            ui.mainClock.advanceTimeBy(16)
            assertEquals("**要点**：ISR 要短。", clipboard.primaryClip!!.getItemAt(0).text.toString())
            ui.onNodeWithText("已复制").assertIsDisplayed()
            ui.mainClock.advanceTimeBy(2_100)
            ui.onAllNodesWithText("已复制").assertCountEquals(0)
        } finally {
            ui.mainClock.autoAdvance = true
        }

        ui.onNodeWithContentDescription("复制提问").performClick()
        assertEquals("怎么写 ISR？", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun jumpToLatestReplyAppearsOnlyWhenItIsOffScreenAndScrollingUpIsNeverOverridden() {
        val s = facade.seed(q, "长对话")
        // 8 pairs plus 2 added below = 20 messages: one page, so index 0 is the first question.
        for (i in 1..8) {
            facade.add(s.sessionId, MessageRole.USER, "问 $i")
            facade.add(s.sessionId, MessageRole.ASSISTANT, "答 $i\n\n" + "说明。".repeat(30))
        }
        show()
        openRow("长对话")

        // Opening lands on the newest message, so the latest reply is on screen.
        ui.onNodeWithText("答 8", substring = true).assertIsDisplayed()
        ui.onAllNodesWithTag("ai-jump-latest").assertCountEquals(0)

        // While at the bottom a new reply is followed.
        facade.add(s.sessionId, MessageRole.USER, "问 9")
        facade.add(s.sessionId, MessageRole.ASSISTANT, "答 9 新到")
        ui.waitForIdle()
        ui.onNodeWithText("答 9 新到", substring = true).assertIsDisplayed()

        // After scrolling up, a newer reply does not pull the list down; the jump button appears.
        ui.onNodeWithTag("ai-messages").performScrollToIndex(0)
        ui.waitForIdle()
        facade.add(s.sessionId, MessageRole.USER, "问 10")
        facade.add(s.sessionId, MessageRole.ASSISTANT, "答 10 最新")
        ui.waitForIdle()
        ui.onNodeWithText("问 1").assertIsDisplayed()
        ui.onNodeWithTag("ai-jump-latest").assertIsDisplayed().performClick()
        ui.waitForIdle()
        ui.onNodeWithText("答 10 最新", substring = true).assertIsDisplayed()
        ui.onAllNodesWithTag("ai-jump-latest").assertCountEquals(0)
    }

    @Test
    fun olderMessagesLoadByCursorAndMergeInOrder() {
        val s = facade.seed(q, "分页")
        for (i in 1..30) {
            facade.add(s.sessionId, MessageRole.USER, "问 $i")
            facade.add(s.sessionId, MessageRole.ASSISTANT, "答 $i")
        }
        show()
        openRow("分页")

        // The newest page holds pairs 21..30.
        ui.onNodeWithTag("ai-messages").performScrollToIndex(0)
        ui.onNodeWithTag("ai-load-older").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasText("问 11"))
        ui.onNodeWithText("问 11").assertIsDisplayed()
        ui.onAllNodesWithText("问 10").assertCountEquals(0)
        ui.onNodeWithTag("ai-load-older").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-messages").performScrollToIndex(0)
        ui.onNodeWithText("问 1").assertIsDisplayed()
        ui.onAllNodesWithTag("ai-load-older").assertCountEquals(0)
        val tops = listOf("问 1", "答 1", "问 2").map { ui.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun readOnlySessionShowsItsDraftButNeverSaves() {
        facade.seed(q.copy(contentHash = "h0"), "旧版本", readOnly = true, draft = "旧草稿")
        show()
        openRow("旧版本")

        ui.onNodeWithTag("ai-read-only").assertIsDisplayed()
        ui.onNodeWithTag("ai-draft").assertTextEquals("旧草稿")
        ui.onNodeWithTag("ai-draft").assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.SetText))
        assertTrue(facade.saves.isEmpty())
        ui.onAllNodesWithTag("ai-draft-status").assertCountEquals(0)

        ui.onNodeWithContentDescription("返回讨论列表").performClick()
        ui.waitForIdle()
        assertTrue(facade.flushes.isEmpty())
        assertEquals(1, facade.deselects)
    }

    @Test
    fun notReadyBlocksNewSessionsAndExplainsWhy() {
        facade.setPhase(ServicePhase.INITIALIZING)
        show()
        ui.onNodeWithTag("ai-phase").assertTextEquals("AI 存储准备中…")
        ui.onNodeWithTag("ai-new-session").assertIsNotEnabled()
        ui.onNodeWithTag("ai-sessions-empty").assertIsDisplayed()
    }

    @Test
    fun sendSavesAndConsumesTheDraftThenStopAndRetryUseTheBackendOwner() {
        facade.seed(q, "发送")
        showWith()
        openRow("发送")
        type("volatile 有什么用？")
        ui.onNodeWithContentDescription("发送追问").performClick()
        ui.waitForIdle()

        val sent = facade.sends.single()
        assertEquals(AiRequestType.FOLLOW_UP, sent.requestType)
        assertEquals("volatile 有什么用？", sent.input)
        assertEquals(setOf("A"), sent.choices)
        assertEquals("", sent.code)
        assertEquals("deepseek-test", sent.model)
        assertEquals(0L, sent.expectedContextRevision)
        // The draft was saved first and the send names exactly that saved version.
        assertEquals(facade.saves.last().token, sent.draftToken)
        assertEquals(1L, sent.savedDraftRevision)
        assertEquals("", editable())
        assertEquals("", facade.session("s1").draft.text)
        ui.onNodeWithText("volatile 有什么用？").assertExists()

        // One request at a time: sending, one-tap requests and retry wait for the slot.
        ui.onNodeWithTag("ai-stop").assertIsDisplayed()
        type("再问")
        ui.onNodeWithContentDescription("发送追问").assertIsNotEnabled()
        ui.onNodeWithText("生成详细解析").assertIsNotEnabled()

        ui.onNodeWithTag("ai-stop").performClick()
        ui.waitForIdle()
        val owner = facade.cancels.single()
        assertEquals("s1", owner.sessionId)
        ui.onNodeWithText("· 已取消").assertExists()
        ui.onAllNodesWithTag("ai-stop").assertCountEquals(0)

        ui.onNodeWithTag("ai-retry").performClick()
        ui.waitForIdle()
        assertEquals(owner, facade.retries.single().owner)
        ui.onAllNodesWithTag("ai-retry").assertCountEquals(0)
        facade.finish("防止编译器优化掉对寄存器的访问。")
        ui.waitForIdle()
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasText("防止编译器优化掉对寄存器的访问。", substring = true))
        ui.onNodeWithContentDescription("发送追问").assertIsEnabled()
    }

    @Test
    fun textTypedWhileASendIsInFlightIsKeptAndSaved() {
        facade.seed(q, "并发")
        showWith()
        openRow("并发")
        type("one")
        val gate = CompletableDeferred<Unit>()
        facade.sendGate = gate
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithContentDescription("发送追问").performClick()
            ui.mainClock.advanceTimeBy(16)
            assertEquals(1, facade.sends.size)
            type("two")
            gate.complete(Unit)
            ui.mainClock.advanceTimeBy(16)
            // The backend consumed "one"; the newer text is not cleared by that receipt.
            assertEquals("onetwo", editable())
            ui.mainClock.advanceTimeBy(400)
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertEquals("onetwo", facade.session("s1").draft.text)
        assertEquals("草稿已保存", draftStatus())
    }

    @Test
    fun oneTapRequestsUseTheLatestEditableDiscussionAndCodeReviewSendsTheCode() {
        facade.seed(q.copy(contentHash = "h0"), "旧版", readOnly = true)
        facade.seed(q, "最近")
        code = "int main(void) { return 0; }"
        showWith(programming = true)

        ui.runOnIdle { controller.quick(AiRequestType.CODE_REVIEW) }
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertTextEquals("最近")
        val review = facade.sends.single()
        assertEquals(AiRequestType.CODE_REVIEW, review.requestType)
        assertEquals(code, review.code)
        assertEquals("", review.input)
        assertNull(review.draftToken)
        facade.finish("建议检查返回值。")
        ui.waitForIdle()

        // A typed draft is left alone by one-tap requests, and only code review carries the code.
        type("留着")
        ui.onNodeWithText("出一道同知识点变式题").performClick()
        ui.waitForIdle()
        assertEquals(AiRequestType.VARIANT, facade.sends.last().requestType)
        assertEquals("", facade.sends.last().code)
        assertEquals("留着", editable())
    }

    @Test
    fun aOneTapRequestCreatesADiscussionWhenNoneIsEditable() {
        facade.seed(q.copy(contentHash = "h0"), "旧版", readOnly = true)
        showWith(programming = true)
        ui.runOnIdle { controller.quick(AiRequestType.EXPLANATION) }
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertTextEquals("新讨论 2")
        assertEquals("s2", facade.sends.single().sessionId)
        assertEquals(AiRequestType.EXPLANATION, facade.sends.single().requestType)
    }

    @Test
    fun aFirstTapWhileTheBackendStartsWaitsInsteadOfBeingRefused() {
        facade.setPhase(ServicePhase.INITIALIZING)
        code = "int x;"
        showWith(programming = true)
        ui.runOnIdle { controller.quick(AiRequestType.CODE_REVIEW) }
        ui.waitForIdle()
        assertTrue(facade.sends.isEmpty())
        ui.onNodeWithTag("ai-phase").assertTextEquals("AI 存储准备中…")
        ui.onAllNodesWithTag("ai-notice").assertCountEquals(0)

        facade.setPhase(ServicePhase.READY)
        ui.waitForIdle()
        assertEquals(AiRequestType.CODE_REVIEW, facade.sends.single().requestType)
        ui.onNodeWithTag("ai-title").assertExists()
    }

    @Test
    fun aRequestRunningElsewhereBlocksSendingHereButCanBeStopped() {
        facade.seed(q, "等待")
        showWith()
        openRow("等待")
        facade.busyElsewhere(q)
        ui.waitForIdle()
        ui.onNodeWithText("AI 正在处理其他讨论，完成后才能在这里发送。").assertIsDisplayed()
        ui.onNodeWithText("生成详细解析").assertIsNotEnabled()
        type("x")
        ui.onNodeWithContentDescription("发送追问").assertIsNotEnabled()
        ui.onNodeWithTag("ai-stop").assertIsDisplayed()
    }

    @Test
    fun preUpgradeRecordsOfThisQuestionAreListedReadOnlyAndCopyable() {
        facade.legacyEntries +=
            LegacyEntryView("k1", "q1", null, "旧问题", "旧回答 **粗体**", 1_700_000_000_000, "deepseek-chat", java.math.BigDecimal("0.000123"))
        facade.legacyEntries += LegacyEntryView("k2", "q-other", "h1", "别的题", "不应出现", 0, "m", java.math.BigDecimal.ZERO)
        showWith()

        ui.onNodeWithText("早期记录（只读）").assertIsDisplayed()
        ui.onAllNodesWithTag("ai-legacy-row").assertCountEquals(1)
        ui.onNodeWithText("题目版本未知", substring = true).assertIsDisplayed()
        ui.onNodeWithText("旧估算 $0.000123", substring = true).assertIsDisplayed()
        ui.onNodeWithText("旧问题").performClick()
        ui.onNodeWithContentDescription("复制早期回复").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals("旧回答 **粗体**", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    // ---- 1.7.1: document sources and saving replies to sticky notes ----

    /** A Markdown document as a discussion source; the fake facade keys its discussions by owner. */
    private class DocSource(id: String, version: String) : AiChatSource {
        val owner = QuestionOwner("doc:$id", version)
        override val context = AiSourceContext("文档：Linux 速记", "以当前章节「进程」为主，按需引用相关段落；只在你发送时提交。")
        override val quickTypes = listOf(AiRequestType.EXPLANATION)
        override val staleLabel = "旧版文档"

        override suspend fun sessions(facade: AiConversationFacade, cursor: PageCursor?, limit: Int) =
            facade.sessions(owner.questionId, true, cursor, limit)

        override suspend fun create(facade: AiConversationFacade, commandId: String) = facade.createSession(commandId, owner)

        override fun current(session: SessionView) = session.question == owner

        override suspend fun send(facade: AiConversationFacade, request: AiSendRequest): ResultOf<AcceptedReceipt> {
            val s = request.session
            return facade.send(
                SendCommand(request.commandId, s.sessionId, s.question, s.contextRevision, request.draftToken,
                    request.savedDraftRevision, request.input, emptySet(), "", request.type, "doc-model")
            )
        }

        override fun citation(message: MessageView) =
            if (message.role == MessageRole.ASSISTANT && message.text.isNotEmpty()) "「进程」第 2～3 段" else null
    }

    private class FakeSaver : AiNoteSaver {
        val calls = mutableListOf<Pair<String, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var reject: Rejection? = null

        override suspend fun save(commandId: String, reply: MessageView): ResultOf<StickyNote> {
            calls += commandId to reply.messageId
            gate?.let {
                gate = null
                it.await()
            }
            reject?.let {
                reject = null
                return ResultOf.Rejected(it)
            }
            return ResultOf.Ok(StickyNote("n-1", "volatile 解析", reply.text, 1, 1))
        }
    }

    private var openedNote: StickyNote? = null

    private fun showSource(source: AiChatSource, saver: AiNoteSaver? = null) {
        ui.setContent {
            MaterialTheme {
                val c = rememberAiChatController(facade, source, saver)
                controller = c
                AiChatScreen(c, Modifier.padding(16.dp), onOpenNote = { openedNote = it })
            }
        }
        ui.waitForIdle()
    }

    @Test
    fun aDocumentDiscussionShowsWhatItCitesAndKeepsOldVersionsReadOnly() {
        val doc = DocSource("linux", "v2")
        facade.seed(QuestionOwner("doc:linux", "v1"), "旧版讨论", readOnly = true)
        showSource(doc)

        ui.onNodeWithTag("ai-source-context").assertIsDisplayed()
        ui.onNodeWithText("文档：Linux 速记").assertIsDisplayed()
        ui.onNodeWithText("只读 · 旧版文档").assertIsDisplayed()

        // A one-tap request never reuses the old version's discussion.
        ui.runOnIdle { controller.quick(AiRequestType.EXPLANATION) }
        ui.waitForIdle()
        val sent = facade.sends.single()
        assertEquals("doc-model", sent.model)
        assertEquals(doc.owner, facade.session(sent.sessionId).question)
        ui.onNodeWithTag("ai-source-context").assertIsDisplayed()
        facade.finish("进程由 PCB 描述。")
        ui.waitForIdle()
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasText("引用：「进程」第 2～3 段"))
        // No saver here: document replies offer no save button.
        ui.onAllNodesWithTag("ai-save-note").assertCountEquals(0)
    }

    @Test
    fun aFinishedReplyIsSavedToANoteOnceAndTheNoteCanBeOpened() {
        val s = facade.seed(q, "保存")
        facade.add(s.sessionId, MessageRole.USER, "volatile 有什么用？")
        val reply = facade.add(s.sessionId, MessageRole.ASSISTANT, "**防止优化**：每次都从内存读。", facade.run(s.sessionId, q, FakeAiConversationFacade.usage(1, 1, "0")))
        facade.add(s.sessionId, MessageRole.USER, "再说说")
        facade.add(s.sessionId, MessageRole.ASSISTANT, "", facade.run(s.sessionId, q, FakeAiConversationFacade.usage(null, null, null, UsageState.UNKNOWN), RunStatus.FAILED))
        val saver = FakeSaver()
        val gate = CompletableDeferred<Unit>()
        saver.gate = gate
        showSource(QuestionAiSource(q), saver)
        openRow("保存")

        // Only the finished, complete reply offers saving; the failed one does not.
        ui.onAllNodesWithTag("ai-save-note").assertCountEquals(1)
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasTestTag("ai-save-note"))
        ui.onNodeWithTag("ai-save-note").performClick()
        ui.waitForIdle()
        ui.onNodeWithText("正在保存…").assertIsNotEnabled()
        ui.onNodeWithTag("ai-save-note").performClick()
        gate.complete(Unit)
        ui.waitForIdle()

        assertEquals(listOf(saver.calls.single().first to reply.messageId), saver.calls)
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasTestTag("ai-saved-note"))
        ui.onNodeWithText("已保存到便签「volatile 解析」").assertIsDisplayed()
        ui.onAllNodesWithTag("ai-save-note").assertCountEquals(0)
        ui.onNodeWithText("打开便签").performClick()
        assertEquals("n-1", openedNote?.id)
    }

    @Test
    fun aFailedSaveIsExplainedAndRetriedUnderTheSameCommand() {
        val s = facade.seed(q, "重存")
        facade.add(s.sessionId, MessageRole.USER, "问")
        facade.add(s.sessionId, MessageRole.ASSISTANT, "答", facade.run(s.sessionId, q, FakeAiConversationFacade.usage(1, 1, "0")))
        val saver = FakeSaver()
        saver.reject = Rejection(RejectCode.STORAGE_FAILED, true, RecoveryAction.NONE)
        showSource(QuestionAiSource(q), saver)
        openRow("重存")

        ui.onNodeWithTag("ai-messages").performScrollToNode(hasTestTag("ai-save-note"))
        ui.onNodeWithTag("ai-save-note").performClick()
        ui.waitForIdle()
        ui.onNodeWithText("未保存到便签：AI 存储写入失败，内容未保存。").assertExists()
        ui.onNodeWithTag("ai-save-note").performClick()
        ui.waitForIdle()
        assertEquals(2, saver.calls.size)
        assertEquals(saver.calls[0].first, saver.calls[1].first)
        ui.onAllNodesWithTag("ai-save-note-error").assertCountEquals(0)
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasTestTag("ai-saved-note"))
    }

    // ---- 1.7.1 wiring: the real document source on GPT's interface ----

    private val linuxV2 = QuickNoteSummary("doc-linux", "Linux 速记", "linux.md", "hash-v2", 0)
    private val processHeading = QuickNoteHeading("h-process", 2, "进程")

    @Test
    fun theDocumentSourceSendsFollowUpsAboutTheSectionAndShowsWhatTheReplyCited() {
        facade.seed(QuestionOwner("doc-linux", "hash-v1", AiOwnerKind.QUICK_NOTE), "旧版讨论", readOnly = true)
        showSource(DocumentAiSource(linuxV2, processHeading) { "doc-model" })

        ui.onNodeWithText("只读 · 旧版文档").assertIsDisplayed()
        ui.onNodeWithTag("ai-new-session").performClick()
        ui.waitForIdle()
        assertEquals(facade.documentOwner(linuxV2), facade.state.value.selected!!.question)
        // Documents offer no question one-tap requests.
        ui.onAllNodesWithText("生成详细解析").assertCountEquals(0)

        type("进程和线程的区别？")
        ui.onNodeWithContentDescription("发送追问").performClick()
        ui.waitForIdle()
        val sent = facade.sends.single()
        assertEquals(AiRequestType.FOLLOW_UP, sent.requestType)
        assertEquals("h-process", sent.documentSectionId)
        assertEquals(emptySet<String>(), sent.choices)
        assertEquals("", sent.code)
        assertEquals("doc-model", sent.model)

        facade.finish("线程共享地址空间。")
        val reply = facade.state.value.firstMessages.items.last()
        val refs =
            listOf(
                QuickNoteAiReference("h-process", "进程", 2, 2),
                QuickNoteAiReference("h-process", "进程", 3, 4),
                QuickNoteAiReference("h-thread", "线程", 1, 1),
            )
        facade.replace(reply.copy(run = reply.run!!.copy(documentReferences = refs)))
        ui.waitForIdle()
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasText("引用：「进程」第 2～4 段；「线程」第 1 段"))
    }

    @Test
    fun citedRangesMergeOnlyWhenTheyTouch() {
        assertEquals(
            "「进程」第 1 段、第 3～5 段",
            quickNoteReferenceText(listOf(QuickNoteAiReference("p", "进程", 4, 5), QuickNoteAiReference("p", "进程", 1, 1), QuickNoteAiReference("p", "进程", 3, 3))),
        )
    }
}
