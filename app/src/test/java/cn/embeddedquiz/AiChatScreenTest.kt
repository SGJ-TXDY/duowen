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
        ui.onNodeWithText("输入 120 / 输出 80 tokens · 估算 $0.001235").assertExists()
        ui.onNodeWithText("缓存命中 · 本次不计费").assertExists()
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
}
