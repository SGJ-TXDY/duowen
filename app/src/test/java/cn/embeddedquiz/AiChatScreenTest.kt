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
import kotlinx.coroutines.runBlocking
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
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasText("「进程」第 2～3 段"))
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
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasText("「进程」第 2～4 段；「线程」第 1 段"))
    }

    @Test
    fun citedRangesMergeOnlyWhenTheyTouch() {
        assertEquals(
            "「进程」第 1 段、第 3～5 段",
            quickNoteReferenceText(listOf(QuickNoteAiReference("p", "进程", 4, 5), QuickNoteAiReference("p", "进程", 1, 1), QuickNoteAiReference("p", "进程", 3, 3))),
        )
    }

    // ---- 1.7.2: what the reply was given, and deleting discussions ----

    @Test
    fun citedParagraphsSitApartFromTheAnswerWithTheirCoverageAndVanishWhenNoneWereGiven() {
        showSource(DocumentAiSource(linuxV2, null) { "doc-model" })
        ui.onNodeWithTag("ai-new-session").performClick()
        ui.waitForIdle()
        type("TCP 和三次握手是什么？")
        ui.onNodeWithContentDescription("发送追问").performClick()
        ui.waitForIdle()
        facade.finish("TCP 是面向连接的可靠传输；握手为 SYN → SYN-ACK → ACK。")
        ui.waitForIdle()
        // No paragraph of the notes was relevant: the answer stands alone, no citation block.
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasText("TCP 是面向连接的可靠传输", substring = true))
        ui.onAllNodesWithTag("ai-citation-block").assertCountEquals(0)

        type("总结整份文档")
        ui.onNodeWithContentDescription("发送追问").performClick()
        ui.waitForIdle()
        facade.finish("这份笔记讲了 UDP。")
        val reply = facade.state.value.firstMessages.items.last()
        facade.replace(
            reply.copy(
                run = reply.run!!.copy(
                    documentReferences = listOf(QuickNoteAiReference("h-udp", "UDP", 1, 2)),
                    documentCoverage = QuickNoteAiCoverage(QuickNoteAiScope.WHOLE_DOCUMENT, partial = true, includedSections = 3, totalSections = 9),
                )
            )
        )
        ui.waitForIdle()
        ui.onNodeWithTag("ai-messages").performScrollToNode(hasTestTag("ai-citation-block"))
        ui.onNodeWithText("速记依据").assertIsDisplayed()
        ui.onNodeWithTag("ai-citation").assertTextEquals("「UDP」第 1～2 段")
        ui.onNodeWithTag("ai-coverage").assertTextEquals("整份文档较长：本次使用目录和代表性摘录，覆盖 3/9 节，可能有遗漏。")
    }

    @Test
    fun coverageNotesSayHowMuchOfTheDocumentWasGiven() {
        assertEquals("本次提供了整份文档（共 4 节）。", quickNoteCoverageText(QuickNoteAiCoverage(QuickNoteAiScope.WHOLE_DOCUMENT, false, 4, 4)))
        assertEquals("本节较长，只提供了其中一部分段落。", quickNoteCoverageText(QuickNoteAiCoverage(QuickNoteAiScope.SECTION, true, 1, 1)))
        assertNull(quickNoteCoverageText(QuickNoteAiCoverage(QuickNoteAiScope.SECTION, false, 1, 1)))
        assertNull(quickNoteCoverageText(QuickNoteAiCoverage(QuickNoteAiScope.SEARCH, false, 2, 2)))
    }

    private fun openMenuOf(title: String) {
        ui.onNodeWithContentDescription("更多操作：$title").performClick()
        ui.onNodeWithTag("ai-session-delete").performClick()
        ui.waitForIdle()
    }

    @Test
    fun aDiscussionIsDeletedOnlyAfterConfirmingAndTheListRefreshes() {
        val keep = facade.seed(q, "保留")
        val gone = facade.seed(q.copy(contentHash = "h0"), "旧版待删", readOnly = true)
        showSource(QuestionAiSource(q))

        // Cancelling changes nothing.
        openMenuOf("旧版待删")
        ui.onNodeWithText("将删除「旧版待删」的全部消息和草稿，无法恢复。").assertIsDisplayed()
        ui.onNodeWithText("已经保存到便签的内容仍可查看；已产生的 AI 费用仍计入费用统计。").assertIsDisplayed()
        ui.onNodeWithText("取消").performClick()
        assertTrue(facade.deletes.isEmpty())
        ui.onAllNodesWithTag("ai-session-row").assertCountEquals(2)

        // Read-only discussions of an older version can be deleted, with their own owner.
        openMenuOf("旧版待删")
        ui.onNodeWithTag("ai-delete-confirm").assertTextEquals("删除").performClick()
        ui.waitForIdle()
        assertEquals(listOf(gone.sessionId to gone.question), facade.deletes)
        ui.onAllNodesWithTag("ai-session-row").assertCountEquals(1)
        ui.onNodeWithText("保留").assertIsDisplayed()
        ui.onNodeWithTag("ai-info").assertTextEquals("已删除讨论「旧版待删」。")
        assertNotNull(facade.session(keep.sessionId))
    }

    @Test
    fun aRunningDiscussionIsStoppedAndCleanedUpBeforeItIsDeleted() {
        val s = facade.seed(q, "生成中")
        showSource(QuestionAiSource(q))
        facade.busyOn(facade.session(s.sessionId))
        ui.waitForIdle()

        openMenuOf("生成中")
        ui.onNodeWithTag("ai-delete-running").assertIsDisplayed()
        ui.onNodeWithTag("ai-delete-confirm").assertTextEquals("停止并删除").performClick()
        ui.waitForIdle()
        assertEquals(listOf(s.sessionId), facade.cancels.map { it.sessionId })
        assertTrue(facade.awaitIdleCalls >= 1)
        assertEquals(listOf(s.sessionId), facade.deletes.map { it.first })
        ui.onAllNodesWithTag("ai-session-row").assertCountEquals(0)
    }

    @Test
    fun aFailedDeletionKeepsTheDiscussionAndSaysWhy() {
        facade.seed(q, "删不掉")
        facade.deleteRejection = Rejection(RejectCode.STORAGE_FAILED, true, RecoveryAction.NONE)
        showSource(QuestionAiSource(q))
        openMenuOf("删不掉")
        ui.onNodeWithTag("ai-delete-confirm").performClick()
        ui.waitForIdle()
        ui.onNodeWithText("讨论未删除：AI 存储写入失败，内容未保存。").assertIsDisplayed()
        ui.onAllNodesWithTag("ai-session-row").assertCountEquals(1)
        ui.onAllNodesWithTag("ai-info").assertCountEquals(0)
    }

    // ---- PLAN-1.7.3: rename from the original lists, and 更多 › AI 讨论 ----

    private fun typeListRename(text: String) = ui.onNodeWithTag("ai-list-rename-field").performTextReplacement(text)

    private fun listRenameText() =
        ui.onNodeWithTag("ai-list-rename-field").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    @Test
    fun aDiscussionIsRenamedFromItsListWithoutOpeningIt() {
        facade.seed(q, "旧标题")
        facade.seed(q, "另一条")
        showSource(QuestionAiSource(q))

        ui.onNodeWithContentDescription("更多操作：旧标题").performClick()
        ui.onNodeWithTag("ai-session-rename").performClick()
        // Prefilled; the unchanged name and one over 80 code points are not offered for saving.
        assertEquals("旧标题", listRenameText())
        ui.onNodeWithTag("ai-rename-confirm").assertIsNotEnabled()
        typeListRename("😀".repeat(81))
        ui.onNodeWithTag("ai-rename-confirm").assertIsNotEnabled()
        typeListRename("  中断上下文  ")
        ui.onNodeWithTag("ai-rename-confirm").performClick()
        ui.waitForIdle()

        assertEquals("中断上下文", facade.session("s1").title)
        assertEquals(1, facade.session("s1").sessionRevision)
        assertNull(facade.state.value.selectedSessionId)
        ui.onAllNodesWithTag("ai-list-rename").assertCountEquals(0)
        ui.onAllNodesWithTag("ai-session-row").filter(hasText("中断上下文")).assertCountEquals(1)
        ui.onAllNodesWithTag("ai-session-row").filter(hasText("旧标题")).assertCountEquals(0)

        openRow("中断上下文")
        ui.onNodeWithTag("ai-title").assertTextEquals("中断上下文")
    }

    @Test
    fun aStaleListRenameKeepsTheTypedTextAndSavesOnlyAfterConfirmingAgain() {
        facade.seed(q, "列表标题")
        showSource(QuestionAiSource(q))
        ui.onNodeWithContentDescription("更多操作：列表标题").performClick()
        ui.onNodeWithTag("ai-session-rename").performClick()
        // Renamed elsewhere (e.g. from the global list) after this list was read.
        runBlocking { facade.rename("other", "s1", 0, "别处改的") }
        typeListRename("这里改的")
        ui.onNodeWithTag("ai-rename-confirm").performClick()
        ui.waitForIdle()

        ui.onNodeWithText("讨论名称已在别处修改，上方已显示当前名称；确认后再保存。").assertIsDisplayed()
        assertEquals("别处改的", facade.session("s1").title)
        // The editor stays with what was typed and now names the current title.
        assertEquals("这里改的", listRenameText())
        ui.onNodeWithText("重命名「别处改的」").assertIsDisplayed()

        ui.onNodeWithTag("ai-rename-confirm").performClick()
        ui.waitForIdle()
        assertEquals("这里改的", facade.session("s1").title)
        assertEquals(2, facade.session("s1").sessionRevision)
        ui.onAllNodesWithTag("ai-list-rename").assertCountEquals(0)
    }

    private val doc = QuestionOwner("00000000-0000-4000-8000-000000000001", "d".repeat(64), AiOwnerKind.QUICK_NOTE)
    private var allController: AiDiscussionsController? = null
    private var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

    /** The global list with the same opening path as the app, minus the ViewModel lookups. */
    private fun showAll() {
        ui.setContent {
            MaterialTheme {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val c = androidx.compose.runtime.remember { AiDiscussionsController({ facade }, scope) }
                allController = c
                backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                AiDiscussionsContent(c) { item, onLeave ->
                    val chat =
                        androidx.compose.runtime.remember(item.sessionId) {
                            AiChatController({ facade }, aiDiscussionSource(item, { null }, { null }, { "m" }, { "" }), scope)
                        }
                    AiDiscussionChat(chat, item, onLeave)
                }
            }
        }
        ui.waitForIdle()
    }

    @Test
    fun theGlobalListMixesBothKindsAndOpensTheExactDiscussionThenReturns() {
        facade.seed(q, "题目讨论", sourceLabel = "中断上下文能否睡眠？\n第二行")
        facade.seed(doc, "速记讨论", sourceLabel = "Linux 常用命令")
        facade.seed(q.copy(contentHash = "h0"), "旧版讨论", readOnly = true, sourceLabel = "旧题干")
        showAll()

        // Newest first, both kinds, each with its source; old versions are marked read only.
        ui.onAllNodesWithTag("ai-all-row").assertCountEquals(3)
        assertEquals(listOf("s3", "s2", "s1"), allController!!.items.map { it.sessionId })
        ui.onNodeWithText("题目 · 中断上下文能否睡眠？").assertIsDisplayed()
        ui.onNodeWithText("速记 · Linux 常用命令").assertIsDisplayed()
        ui.onNode(hasTestTag("ai-all-row") and hasText("旧版讨论")).assert(hasText("题目已更新或删除 · 只读"))

        ui.onNode(hasTestTag("ai-all-row") and hasText("速记讨论")).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertTextEquals("速记讨论")
        assertEquals("s2", facade.state.value.selectedSessionId)
        // Opening sends nothing; a document discussion has no question one-tap requests.
        assertTrue(facade.sends.isEmpty())
        ui.onAllNodesWithText(AiRequestType.EXPLANATION.label).assertCountEquals(0)

        // Renamed inside the discussion: back shows the list again, with the new name.
        ui.onNodeWithContentDescription("重命名讨论").performClick()
        ui.onNodeWithTag("ai-rename-field").performTextReplacement("命令速查")
        ui.onNodeWithText("保存标题").performClick()
        ui.waitForIdle()
        ui.onNodeWithContentDescription("返回讨论列表").performClick()
        ui.waitForIdle()
        assertNull(facade.state.value.selectedSessionId)
        ui.onAllNodesWithTag("ai-all-row").assertCountEquals(3)
        ui.onNode(hasTestTag("ai-all-row") and hasText("命令速查")).assertExists()

        // A question discussion opens with the question's one-tap requests; an old one is read only.
        ui.onNode(hasTestTag("ai-all-row") and hasText("题目讨论")).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertTextEquals("题目讨论")
        ui.onNodeWithText(AiRequestType.EXPLANATION.label).assertExists()
        ui.onNodeWithContentDescription("返回讨论列表").performClick()
        ui.waitForIdle()
        ui.onNode(hasTestTag("ai-all-row") and hasText("旧版讨论")).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-read-only").assertExists()
    }

    @Test
    fun theGlobalListSaysWhenEmpty() {
        showAll()
        ui.onNodeWithTag("ai-all-empty").assertIsDisplayed()
        ui.onAllNodesWithTag("ai-all-row").assertCountEquals(0)
    }

    @Test
    fun theGlobalListPagesRenamesAndKeepsTheEditorWhenSavingFails() {
        repeat(25) { facade.seed(if (it % 2 == 0) q else doc, "讨论 $it") }
        showAll()
        val c = allController!!
        assertEquals(AI_PAGE_SIZE, c.items.size)
        assertTrue(c.hasMore)
        ui.onNodeWithTag("ai-all-sessions").performScrollToNode(hasText("加载更多讨论"))
        ui.onNodeWithText("加载更多讨论").performClick()
        ui.waitForIdle()
        assertEquals(25, c.items.size)
        assertFalse(c.hasMore)

        ui.onNodeWithTag("ai-all-sessions").performScrollToIndex(0)
        ui.onNodeWithContentDescription("更多操作：讨论 24").performClick()
        ui.onNodeWithTag("ai-all-rename").performClick()
        typeListRename("DMA 与 cache")
        ui.onNodeWithTag("ai-rename-confirm").performClick()
        ui.waitForIdle()
        assertEquals("DMA 与 cache", facade.session("s25").title)
        ui.onNodeWithTag("ai-all-info").assertTextEquals("已重命名为「DMA 与 cache」。")
        ui.onAllNodesWithTag("ai-all-row").filter(hasText("DMA 与 cache")).assertCountEquals(1)

        facade.renameRejection = Rejection(RejectCode.CAPACITY_EXCEEDED, false, RecoveryAction.NONE)
        ui.onNodeWithContentDescription("更多操作：DMA 与 cache").performClick()
        ui.onNodeWithTag("ai-all-rename").performClick()
        typeListRename("存不下")
        ui.onNodeWithTag("ai-rename-confirm").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-all-notice").assertExists()
        ui.onAllNodesWithTag("ai-all-info").assertCountEquals(0)
        assertEquals("存不下", listRenameText())
        assertEquals("DMA 与 cache", facade.session("s25").title)
    }

    // ---- PLAN-1.7.3 Leader acceptance: the two returned items ----

    /** Leader probe 1, as written: a stale rename of a second-page discussion keeps its target. */
    @Test
    fun staleRenameRetainsTargetFromSecondPage() = runBlocking {
        val owner = QuestionOwner("question", "version")
        repeat(AI_PAGE_SIZE) { facade.seed(owner, "Discussion ${it + 1}") }
        val target = facade.seed(owner, "Second page target")
        val chat = AiChatController({ facade }, owner, this)
        chat.refresh()
        chat.moreSessions().join()
        assertTrue(chat.sessions.any { it.sessionId == target.sessionId })
        assertTrue(facade.rename("elsewhere", target.sessionId, 0, "New stored title") is ResultOf.Ok)

        chat.rename(target.sessionId, "Title typed here") {}.join()

        assertEquals("New stored title", chat.sessions.firstOrNull { it.sessionId == target.sessionId }?.title)
        // Both pages stay loaded, and confirming again saves against the current revision.
        assertEquals(AI_PAGE_SIZE + 1, chat.sessions.size)
        var done = false
        chat.rename(target.sessionId, "Title typed here") { done = true }.join()
        assertTrue(done)
        assertEquals("Title typed here", facade.session(target.sessionId).title)
    }

    /** Leader probe 2, as written: backing out while the open waits leaves nothing selected. */
    @Test
    fun backingOutWhileOpeningDoesNotLeaveAnEditorSelected() = runBlocking {
        val owner = QuestionOwner("question", "version")
        val session = facade.seed(owner, "Pending open")
        val chat = AiChatController({ facade }, owner, this)
        facade.setPhase(ServicePhase.INITIALIZING)
        val opening = chat.open(session.sessionId)
        kotlinx.coroutines.yield()

        chat.back().join()
        facade.setPhase(ServicePhase.READY)
        opening.join()

        assertNull(facade.state.value.selectedSessionId)
        assertNull(facade.state.value.activeEditorEpoch)
        assertNull(chat.opened)
    }

    /** An open already at the backend when the page left is released, without touching a later one. */
    @Test
    fun aLateOpenIsReleasedAndDoesNotClearTheDiscussionOpenedAfterIt() = runBlocking {
        val first = facade.seed(q, "先打开")
        val second = facade.seed(q, "后打开")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        facade.openGate = gate
        val leaving = AiChatController({ facade }, q, this)
        val staying = AiChatController({ facade }, q, this)

        val late = leaving.open(first.sessionId)
        kotlinx.coroutines.yield() // the open is now waiting inside the backend
        leaving.back().join()
        val next = staying.open(second.sessionId)
        kotlinx.coroutines.yield()
        gate.complete(Unit)
        late.join()
        next.join()

        assertNull(leaving.opened)
        assertEquals(second.sessionId, staying.opened?.sessionId)
        assertEquals(second.sessionId, facade.state.value.selectedSessionId)
        assertNotNull(facade.state.value.activeEditorEpoch)
        // The released open left no editor behind: only the second discussion can save its draft.
        staying.edit("仍可保存")
        staying.leaveNow()
        assertEquals("仍可保存", facade.session(second.sessionId).draft.text)
    }

    @Test
    fun aStaleRenameOnTheSecondPageKeepsTheEditorAndTheTypedTitle() {
        repeat(AI_PAGE_SIZE) { facade.seed(q, "讨论 ${it + 1}") }
        val target = facade.seed(q, "第二页那条")
        showSource(QuestionAiSource(q))
        ui.onNodeWithTag("ai-sessions").performScrollToNode(hasText("加载更多讨论"))
        ui.onNodeWithText("加载更多讨论").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-sessions").performScrollToNode(hasText("第二页那条"))
        ui.onNodeWithContentDescription("更多操作：第二页那条").performClick()
        ui.onNodeWithTag("ai-session-rename").performClick()
        runBlocking { facade.rename("elsewhere", target.sessionId, 0, "别处的新名") }
        typeListRename("这里输入的名")
        ui.onNodeWithTag("ai-rename-confirm").performClick()
        ui.waitForIdle()

        ui.onNodeWithText("讨论名称已在别处修改，上方已显示当前名称；确认后再保存。").assertIsDisplayed()
        ui.onNodeWithTag("ai-list-rename").assertIsDisplayed()
        ui.onNodeWithText("重命名「别处的新名」").assertIsDisplayed()
        assertEquals("这里输入的名", listRenameText())
        assertEquals("别处的新名", facade.session(target.sessionId).title)

        ui.onNodeWithTag("ai-rename-confirm").performClick()
        ui.waitForIdle()
        assertEquals("这里输入的名", facade.session(target.sessionId).title)
        ui.onAllNodesWithTag("ai-list-rename").assertCountEquals(0)
    }

    @Test
    fun leavingTheGlobalPageWhileADiscussionOpensLeavesNothingSelected() {
        facade.seed(q, "慢慢打开")
        facade.seed(q, "随后打开")
        showAll()
        facade.setPhase(ServicePhase.INITIALIZING)
        ui.onNode(hasTestTag("ai-all-row") and hasText("慢慢打开")).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-opening").assertExists()
        // Back before the service is ready: the list returns at once.
        ui.runOnIdle { backDispatcher!!.onBackPressed() }
        ui.waitForIdle()
        ui.onAllNodesWithTag("ai-all-row").assertCountEquals(2)
        facade.setPhase(ServicePhase.READY)
        ui.waitForIdle()
        assertNull(facade.state.value.selectedSessionId)
        assertNull(facade.state.value.activeEditorEpoch)

        // The next discussion opens and stays open.
        ui.onNode(hasTestTag("ai-all-row") and hasText("随后打开")).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertTextEquals("随后打开")
        assertEquals("s2", facade.state.value.selectedSessionId)
    }
}
