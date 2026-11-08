package cn.embeddedquiz

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import android.os.Looper
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 更多 › AI 讨论 as a workbench (PLAN-1.7.4) on the interim allSessions data, with the in-memory facade. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp")
class AiWorkbenchTest {
    /** An earlier test class can strand the Compose main dispatcher's callback; deliver it before the compose rule waits for idle. */
    @get:Rule(order = 0)
    val strandedMainQueue = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                ComposeMainQueue.deliverStrandedCallbacks()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1) val ui = createComposeRule()
    private val facade = FakeAiConversationFacade()
    private val questions = mutableMapOf<String, QuestionSummary>()
    private val documents = mutableMapOf<String, QuickNoteSummary>()
    private lateinit var wb: AiWorkbenchController
    private lateinit var back: OnBackPressedDispatcher
    private val loadedQuestions = mutableListOf<String>()
    private val openedDocuments = mutableListOf<String>()
    private var fullQuestion: Question? = null

    private val interrupt = Question("q1", "中断上下文能否睡眠？\n第二行", Kind.SINGLE, "test", options = listOf(Option("A", "能"), Option("B", "不能")), correct = setOf("B"))
    private val listQ = Question("p1", "反转链表", Kind.PROGRAMMING, "test")
    private val linux = QuickNoteSummary("00000000-0000-4000-8000-000000000001", "Linux 常用命令", "linux.md", "d".repeat(64), 0)

    private fun summary(q: Question) =
        QuestionSummary(q.id, q.stem, q.kind, q.source, "", "", "", "", false, "", false, q.contentHash, "")

    private fun owner(q: Question) = QuestionOwner(q.id, q.contentHash)

    private fun bank(vararg qs: Question) = qs.forEach { questions[it.id] = summary(it) }

    /** Questions and documents as the bank has them now: a missing one is deleted, another hash is updated. */
    private fun resolve(s: SessionView, label: String): AiDiscussionSource {
        val id = s.question.questionId
        val group = AiDiscussionGroupId(s.question.kind, id)
        fun status(current: String?) =
            when (current) {
                null -> AiSourceStatus.DELETED
                s.question.contentHash -> AiSourceStatus.CURRENT
                else -> AiSourceStatus.UPDATED
            }
        return when (s.question.kind) {
            AiOwnerKind.QUESTION -> {
                val q = questions[id]
                AiDiscussionSource(
                    group, if (q?.kind == Kind.PROGRAMMING) AiDiscussionType.PROGRAMMING else AiDiscussionType.QUESTION,
                    q?.stem ?: label, status(q?.contentHash), q?.let { QuestionOwner(id, it.contentHash) },
                )
            }
            AiOwnerKind.QUICK_NOTE -> {
                val d = documents[id]
                AiDiscussionSource(group, AiDiscussionType.QUICK_NOTE, d?.title ?: label, status(d?.contentHash), d?.let { facade.documentOwner(it) })
            }
            else -> facade.defaultSource(s, label)
        }
    }

    private fun show() {
        facade.sourceOf = ::resolve
        ui.setContent {
            MaterialTheme {
                back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                val scope = rememberCoroutineScope()
                val c = remember { AiWorkbenchController({ facade }, scope, model = { "m" }, searchDelayMs = 0) }
                wb = c
                AiWorkbenchContent(c) { item, onLeave ->
                    val chat =
                        remember(item.sessionId) {
                            AiChatController({ facade }, aiDiscussionSource(item.discussion, { questions[it] }, { documents[it] }, { "m" }, { "" }), scope)
                        }
                    AiWorkbenchDetail(
                        c, chat, item, onLeave,
                        loadQuestion = { id ->
                            loadedQuestions += id
                            fullQuestion
                        },
                        openDocument = { id, _ -> openedDocuments += id },
                    )
                }
            }
        }
        ui.waitForIdle()
    }

    /** Lets the search debounce run out. */
    private fun settle() {
        ui.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        ui.waitForIdle()
    }

    private fun group(title: String) = hasTestTag("ai-group-head") and hasText(title)

    private fun expand(title: String) {
        ui.onNodeWithTag("ai-workbench").performScrollToNode(group(title))
        ui.onNode(group(title)).performClick()
        ui.waitForIdle()
    }

    private fun child(title: String) = hasTestTag("ai-child") and hasText(title)

    private fun openChild(title: String) {
        ui.onNodeWithTag("ai-workbench").performScrollToNode(child(title))
        ui.onNode(child(title)).performClick()
        ui.waitForIdle()
    }

    /** Every group the workbench holds, including those the lazy list has not composed. */
    private fun groupTitles() = wb.groups.map { aiSourceLine(it.source.label) }

    private fun editable(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** Two questions, a programming one, a document and two documents that share a title. */
    private fun seedMixed() {
        bank(interrupt, listQ)
        documents[linux.id] = linux
        val twinA = QuickNoteSummary("00000000-0000-4000-8000-00000000000a", "同名文档", "a.md", "a".repeat(64), 0)
        val twinB = QuickNoteSummary("00000000-0000-4000-8000-00000000000b", "同名文档", "b.md", "b".repeat(64), 0)
        documents[twinA.id] = twinA
        documents[twinB.id] = twinB
        facade.seed(owner(interrupt), "中断讨论", sourceLabel = interrupt.stem)
        facade.seed(owner(listQ), "链表讨论", sourceLabel = listQ.stem)
        facade.seed(facade.documentOwner(linux), "命令讨论", sourceLabel = linux.title)
        facade.seed(facade.documentOwner(twinA), "同名一", sourceLabel = twinA.title)
        facade.seed(facade.documentOwner(twinB), "同名二", sourceLabel = twinB.title)
        facade.seed(owner(interrupt), "中断讨论二", sourceLabel = interrupt.stem)
    }

    @Test
    fun groupsRecentCardsAndFiltersFollowTheSources() {
        seedMixed()
        show()

        assertEquals(listOf("中断讨论二", "同名二", "同名一", "命令讨论", "链表讨论", "中断讨论"), wb.recent.map { it.title })
        ui.onAllNodesWithTag("ai-recent-card").onFirst().assert(hasText("中断讨论二"))
        // Grouped by source id, newest discussion first; same-named documents stay two groups.
        assertEquals(listOf("中断上下文能否睡眠？", "同名文档", "同名文档", "Linux 常用命令", "反转链表"), groupTitles())
        ui.onNode(group("中断上下文能否睡眠？")).assert(hasText("题目 · 2 条讨论"))
        ui.onNodeWithTag("ai-workbench").performScrollToNode(group("反转链表"))
        ui.onNode(group("反转链表")).assert(hasText("编程 · 1 条讨论"))

        ui.onNodeWithTag("ai-filter-PROGRAMMING").performClick()
        ui.waitForIdle()
        assertEquals(listOf("反转链表"), groupTitles())
        assertEquals(listOf("链表讨论"), wb.recent.map { it.title })
        ui.onNodeWithText("编程来源").assertExists()

        ui.onNodeWithTag("ai-filter-QUICK_NOTE").performClick()
        ui.waitForIdle()
        assertEquals(listOf("同名文档", "同名文档", "Linux 常用命令"), groupTitles())

        // No free conversations until the backend has them; the page says so instead of a blank.
        ui.onNodeWithTag("ai-filter-FREE").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-all-empty").assert(hasAnyDescendant(hasText("还没有自由对话")))
        ui.onAllNodesWithTag("ai-recent-card").assertCountEquals(0)

        ui.onNodeWithTag("ai-filter-ALL").performClick()
        ui.waitForIdle()
        assertEquals(5, groupTitles().size)
        ui.onNodeWithTag("ai-filter-ALL").assertIsSelected()
    }

    @Test
    fun aDiscussionOpensUnderItsSourceAndTheOriginalOpensReadOnlyOverIt() {
        seedMixed()
        fullQuestion = interrupt
        show()
        expand("中断上下文能否睡眠？")
        ui.onAllNodesWithTag("ai-child").assertCountEquals(2)

        openChild("中断讨论")
        ui.onNodeWithTag("ai-title").assertTextEquals("中断讨论")
        assertEquals("s1", facade.state.value.selectedSessionId)
        assertTrue(facade.sends.isEmpty())
        ui.onNodeWithTag("ai-source-card").assert(hasAnyDescendant(hasText("题目")))
        ui.onNodeWithTag("ai-source-status").assert(hasAnyDescendant(hasText("当前版本")))
        ui.onNodeWithTag("ai-source-title").assertTextEquals("中断上下文能否睡眠？")
        ui.onNodeWithTag("ai-draft").performTextInput("还没问完")

        // The original opens read only over the discussion, which stays open with its draft.
        ui.onNodeWithTag("ai-source-open").performClick()
        ui.waitForIdle()
        assertEquals(listOf("q1"), loadedQuestions)
        ui.onNodeWithTag("source-question").assertIsDisplayed()
        ui.onAllNodesWithTag("source-question-changed").assertCountEquals(0)
        ui.onNodeWithText("不能（参考答案）").assertExists()
        ui.onNodeWithContentDescription("返回讨论").performClick()
        ui.waitForIdle()
        ui.onAllNodesWithTag("source-question").assertCountEquals(0)
        assertEquals("还没问完", editable("ai-draft"))
        assertEquals(0, facade.deselects)
        assertEquals("s1", facade.state.value.selectedSessionId)

        // Back lands on the workbench as it was: the group is still open.
        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()
        assertNull(facade.state.value.selectedSessionId)
        assertEquals("还没问完", facade.session("s1").draft.text)
        ui.onAllNodesWithTag("ai-child").assertCountEquals(2)
    }

    @Test
    fun theSourceCardSaysWhenTheSourceWasUpdatedOrDeleted() {
        bank(interrupt)
        documents[linux.id] = linux
        // An old version of q1, a question no longer in the bank, and a document discussion.
        facade.seed(QuestionOwner("q1", "old-hash"), "旧版讨论", readOnly = true, sourceLabel = "旧题干")
        facade.seed(QuestionOwner("gone", "h-gone"), "孤儿讨论", readOnly = true, sourceLabel = "已删掉的题")
        facade.seed(facade.documentOwner(linux), "命令讨论", sourceLabel = linux.title)
        fullQuestion = interrupt
        show()

        ui.onNode(group("已删掉的题")).assert(hasText("题目 · 1 条讨论 · 来源已删除"))
        ui.onNode(hasTestTag("ai-group-new") and hasContentDescription("来源已删除，不能新建讨论")).assertIsNotEnabled()

        expand("中断上下文能否睡眠？")
        ui.onNode(child("旧版讨论")).assert(hasText("旧版来源 · 只读"))
        openChild("旧版讨论")
        ui.onNodeWithTag("ai-source-status").assert(hasAnyDescendant(hasText("来源已更新")))
        ui.onNodeWithTag("ai-source-hint").assertTextEquals("讨论之后来源已更新，旧消息只读；仍可改名和删除。")
        ui.onNodeWithTag("ai-read-only").assertExists()
        // The current version opens, marked as such.
        ui.onNodeWithTag("ai-source-open").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("source-question-changed").assertTextEquals("讨论开始后题目已更新，下面是题库中的当前版本。")
        ui.onNodeWithContentDescription("返回讨论").performClick()
        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()

        expand("已删掉的题")
        openChild("孤儿讨论")
        ui.onNodeWithTag("ai-source-status").assert(hasAnyDescendant(hasText("来源已删除")))
        ui.onNodeWithTag("ai-source-title").assertTextEquals("已删掉的题")
        ui.onAllNodesWithTag("ai-source-open").assertCountEquals(0)
        // Still renamable.
        ui.onNodeWithContentDescription("讨论操作").performClick()
        ui.onNodeWithTag("ai-detail-rename").performClick()
        ui.onNodeWithTag("ai-rename-field").performTextReplacement("留档")
        ui.onNodeWithText("保存标题").performClick()
        ui.waitForIdle()
        assertEquals("留档", facade.session("s2").title)
        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()

        // A 速记 discussion goes back to its document after saving the draft and letting go of the discussion.
        expand("Linux 常用命令")
        openChild("命令讨论")
        ui.onNodeWithTag("ai-source-card").assert(hasAnyDescendant(hasText("速记")))
        ui.onNodeWithTag("ai-source-hint").assertTextEquals("回答会标明实际引用的段落。")
        ui.onNodeWithTag("ai-source-open").assert(hasText("原文")).performClick()
        ui.waitForIdle()
        assertEquals(listOf(linux.id), openedDocuments)
        assertNull(facade.state.value.selectedSessionId)
    }

    @Test
    fun aGroupPlusStartsAnEmptyDiscussionAboutTheCurrentSource() {
        seedMixed()
        show()
        ui.onNodeWithTag("ai-workbench").performScrollToNode(group("Linux 常用命令"))
        ui.onNodeWithContentDescription("在「Linux 常用命令」下新建讨论").performClick()
        ui.waitForIdle()

        ui.onNodeWithTag("ai-title").assertTextEquals("新讨论 7")
        val created = facade.session("s7")
        assertEquals(facade.documentOwner(linux), created.question)
        assertEquals("s7", facade.state.value.selectedSessionId)
        assertTrue(facade.sends.isEmpty())
        ui.onNodeWithTag("ai-source-title").assertTextEquals("Linux 常用命令")

        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()
        // The group is open with the new discussion, and the other discussion of that document is untouched.
        ui.onNode(group("Linux 常用命令")).assert(hasText("速记 · 2 条讨论"))
        ui.onNode(child("新讨论 7")).assertExists()
        ui.onNode(child("命令讨论")).assertExists()
    }

    @Test
    fun deletingFromAGroupTakesItOffRecentTheGroupAndSearch() {
        seedMixed()
        show()
        expand("中断上下文能否睡眠？")
        ui.onNodeWithContentDescription("更多操作：中断讨论二").performClick()
        ui.onNodeWithTag("ai-child-delete").performClick()
        ui.onNodeWithTag("ai-delete-source").assertTextEquals("来源：题目 · 中断上下文能否睡眠？")
        ui.onNodeWithTag("ai-delete-confirm").assertTextEquals("删除").performClick()
        ui.waitForIdle()

        assertEquals(listOf("s6" to owner(interrupt)), facade.deletes)
        ui.onNodeWithTag("ai-all-info").assert(hasText("已删除讨论「中断讨论二」。"))
        ui.onAllNodes(child("中断讨论二")).assertCountEquals(0)
        assertEquals(5, wb.recent.size)
        assertTrue(wb.recent.none { it.title == "中断讨论二" })
        ui.onNode(group("中断上下文能否睡眠？")).assert(hasText("题目 · 1 条讨论"))

        // The last discussion of a source takes its group with it.
        expand("反转链表")
        ui.onNodeWithContentDescription("更多操作：链表讨论").performClick()
        ui.onNodeWithTag("ai-child-delete").performClick()
        ui.onNodeWithTag("ai-delete-confirm").performClick()
        ui.waitForIdle()
        assertFalse("反转链表" in groupTitles())

        ui.onNodeWithTag("ai-search-open").performClick()
        ui.onNodeWithTag("ai-search-field").performTextInput("讨论二")
        settle()
        ui.onNodeWithTag("ai-search-none").assertExists()
    }

    @Test
    fun deletingTheOpenDiscussionStopsItsRequestAndReturnsToTheSamePlace() {
        repeat(12) { i ->
            val q = Question("q$i", "第 $i 题", Kind.SHORT, "test")
            bank(q)
            facade.seed(owner(q), "讨论 $i", sourceLabel = q.stem)
        }
        show()
        expand("第 6 题")
        val before = wb.listState.layoutInfo.visibleItemsInfo.first().key
        assertNotEquals("phase", before)
        assertTrue(wb.listState.firstVisibleItemIndex > 0)
        openChild("讨论 6")
        facade.busyOn(facade.session("s7"))
        ui.waitForIdle()

        ui.onNodeWithContentDescription("讨论操作").performClick()
        ui.onNodeWithTag("ai-detail-delete").performClick()
        ui.onNodeWithTag("ai-delete-running").assertExists()
        ui.onNodeWithTag("ai-delete-source").assertTextEquals("来源：题目 · 第 6 题")
        ui.onNodeWithTag("ai-delete-confirm").assertTextEquals("停止并删除").performClick()
        ui.waitForIdle()

        assertEquals(1, facade.cancels.size)
        assertEquals("s7", facade.deletes.single().first)
        assertNull(facade.state.value.selectedSessionId)
        assertNull(facade.state.value.globalBusy)
        // Back on the workbench where it was, without the deleted discussion or its now empty source.
        ui.onNodeWithTag("ai-workbench").assertIsDisplayed()
        assertFalse("第 6 题" in groupTitles())
        assertEquals(before, wb.listState.layoutInfo.visibleItemsInfo.first().key)
        assertEquals("已删除讨论「讨论 6」。", wb.info)
    }

    @Test
    fun aFailedDeleteKeepsTheOpenDiscussionAndItsDraft() {
        seedMixed()
        show()
        expand("Linux 常用命令")
        openChild("命令讨论")
        ui.onNodeWithTag("ai-draft").performTextInput("留着")
        facade.deleteRejection = Rejection(RejectCode.STORAGE_FAILED, true, RecoveryAction.NONE)
        ui.onNodeWithContentDescription("讨论操作").performClick()
        ui.onNodeWithTag("ai-detail-delete").performClick()
        ui.onNodeWithTag("ai-delete-confirm").performClick()
        ui.waitForIdle()

        ui.onNodeWithTag("ai-notice").assertExists()
        ui.onNodeWithTag("ai-title").assertTextEquals("命令讨论")
        assertEquals("留着", editable("ai-draft"))
        assertEquals("s3", facade.state.value.selectedSessionId)
    }

    @Test
    fun searchFindsOneResultPerDiscussionAndLeavingItRestoresTheWorkbench() {
        seedMixed()
        show()
        ui.onNodeWithTag("ai-filter-QUICK_NOTE").performClick()
        ui.waitForIdle()
        expand("Linux 常用命令")

        ui.onNodeWithTag("ai-search-open").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-search-scope").assertTextEquals("搜索所有已保存讨论的标题、来源、你的提问和 AI 回复；临时对话不在其中。")
        // "命令" is in the title and in the source; the discussion is listed once.
        ui.onNodeWithTag("ai-search-field").performTextInput("命令")
        settle()
        ui.onAllNodesWithTag("ai-search-hit").assertCountEquals(1)
        ui.onNodeWithTag("ai-search-snippet", useUnmergedTree = true).assertTextEquals("标题：命令讨论")

        // Search is not limited by the filter.
        ui.onNodeWithTag("ai-search-field").performTextReplacement("能否睡眠")
        settle()
        ui.onAllNodesWithTag("ai-search-hit").assertCountEquals(2)
        ui.onAllNodesWithTag("ai-search-snippet", useUnmergedTree = true).onFirst().assertTextEquals("来源：中断上下文能否睡眠？ 第二行")

        ui.onAllNodesWithTag("ai-search-hit").onFirst().performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertTextEquals("中断讨论二")
        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()
        // Back in the results with the query, then out of search to the same filter and open group.
        assertEquals("能否睡眠", editable("ai-search-field"))
        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()
        ui.onNodeWithTag("ai-filter-QUICK_NOTE").assertIsSelected()
        ui.onNode(child("命令讨论")).assertExists()

        ui.onNodeWithTag("ai-search-open").performClick()
        ui.onNodeWithTag("ai-search-field").performTextInput("没有这个")
        settle()
        ui.onNodeWithTag("ai-search-none").assertTextEquals("没有找到包含「没有这个」的讨论。")
    }

    @Test
    fun aStaleRenameOnASecondPageOfAGroupKeepsTheEditorAndTheTypedTitle() {
        bank(interrupt)
        val target = facade.seed(owner(interrupt), "第二页那条", sourceLabel = interrupt.stem)
        repeat(AI_PAGE_SIZE) { facade.seed(owner(interrupt), "讨论 ${it + 1}", sourceLabel = interrupt.stem) }
        show()
        expand("中断上下文能否睡眠？")
        ui.onNodeWithTag("ai-workbench").performScrollToNode(hasText("加载更多讨论"))
        // At the card's bottom edge, so the click goes through its action rather than a screen position.
        ui.onNodeWithText("加载更多讨论").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ui.waitForIdle()
        assertEquals(AI_PAGE_SIZE + 1, wb.children.getValue(AiDiscussionGroupId(AiOwnerKind.QUESTION, "q1")).items.size)
        ui.onNodeWithTag("ai-workbench").performScrollToNode(child("第二页那条"))
        ui.onNodeWithContentDescription("更多操作：第二页那条").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ui.onNodeWithTag("ai-child-rename").performClick()
        runBlocking { facade.rename("elsewhere", target.sessionId, 0, "别处的新名") }
        ui.onNodeWithTag("ai-list-rename-field").performTextReplacement("这里输入的名")
        ui.onNodeWithTag("ai-rename-confirm").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ui.waitForIdle()

        ui.onNodeWithText("讨论名称已在别处修改，上方已显示当前名称；确认后再保存。").assertExists()
        ui.onNodeWithTag("ai-workbench").performScrollToNode(hasTestTag("ai-list-rename"))
        ui.onNodeWithText("重命名「别处的新名」").assertIsDisplayed()
        assertEquals("这里输入的名", editable("ai-list-rename-field"))
        assertEquals(AI_PAGE_SIZE + 1, wb.children.getValue(AiDiscussionGroupId(AiOwnerKind.QUESTION, "q1")).items.size)

        ui.onNodeWithTag("ai-rename-confirm").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ui.waitForIdle()
        assertEquals("这里输入的名", facade.session(target.sessionId).title)
        ui.onAllNodesWithTag("ai-list-rename").assertCountEquals(0)
        ui.onNode(child("这里输入的名")).assertExists()
        // Recent cards follow the new name too.
        assertTrue(wb.recent.none { it.title == "第二页那条" })
    }

    @Test
    fun backingOutWhileADiscussionOpensLeavesNothingSelected() {
        bank(interrupt)
        facade.seed(owner(interrupt), "慢慢打开", sourceLabel = interrupt.stem)
        facade.seed(owner(interrupt), "随后打开", sourceLabel = interrupt.stem)
        show()
        expand("中断上下文能否睡眠？")
        facade.setPhase(ServicePhase.INITIALIZING)
        ui.onNode(child("慢慢打开")).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-opening").assertExists()
        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()
        ui.onAllNodesWithTag("ai-child").assertCountEquals(2)
        facade.setPhase(ServicePhase.READY)
        ui.waitForIdle()
        assertNull(facade.state.value.selectedSessionId)
        assertNull(facade.state.value.activeEditorEpoch)

        ui.onNode(child("随后打开")).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertTextEquals("随后打开")
        assertEquals("s2", facade.state.value.selectedSessionId)
    }

    @Test
    fun theBottomPlusStartsASavedFreeConversationOnItsFirstSend() {
        seedMixed()
        show()
        ui.onNodeWithContentDescription("新建自由对话").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-new-free-pane").assertExists()
        ui.onNodeWithTag("ai-source-hint").assertTextEquals("自由对话 · 未关联题目或速记")
        // Nothing is stored before the first send.
        assertTrue(facade.freeStarts.isEmpty())
        ui.onNodeWithTag("ai-free-send").assertIsNotEnabled()

        ui.onNodeWithTag("ai-free-field").performTextInput("DMA 和 cache 一致性怎么保证？")
        ui.onNodeWithTag("ai-free-send").performClick()
        ui.waitForIdle()

        val start = facade.freeStarts.single()
        assertEquals("DMA 和 cache 一致性怎么保证？", start.input)
        assertEquals("m", start.model)
        assertNull(start.groupId)
        assertNull(start.referenceSessionId)
        // It opens like any saved discussion, with the first question already sent.
        ui.onNodeWithTag("ai-title").assertTextEquals("DMA 和 cache 一致性怎么保证？")
        ui.onNodeWithTag("ai-source-card").assert(hasAnyDescendant(hasText("自由对话")))
        ui.onAllNodesWithTag("ai-source-open").assertCountEquals(0)
        val sent = facade.sends.single()
        assertEquals(AiOwnerKind.FREE, sent.question.kind)
        assertEquals(sent.sessionId, facade.state.value.selectedSessionId)

        // Saved under 对话, where it can be opened again.
        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()
        ui.onNodeWithTag("ai-filter-FREE").performClick()
        ui.waitForIdle()
        assertEquals(listOf("DMA 和 cache 一致性怎么保证？"), groupTitles())
        expand("DMA 和 cache 一致性怎么保证？")
        openChild("DMA 和 cache 一致性怎么保证？")
        assertEquals(sent.sessionId, facade.state.value.selectedSessionId)
    }

    @Test
    fun aFailedFirstSendKeepsTheNewConversationAndItsText() {
        show()
        ui.onNodeWithContentDescription("新建自由对话").performClick()
        ui.onNodeWithTag("ai-free-field").performTextInput("留着这句")
        facade.busyOn(facade.seed(owner(interrupt), "占着发送", sourceLabel = interrupt.stem))
        ui.onNodeWithTag("ai-free-send").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-all-notice").assertExists()
        ui.onNodeWithTag("ai-new-free-pane").assertExists()
        assertEquals("留着这句", editable("ai-free-field"))
        // Back leaves the unsent conversation without storing anything.
        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()
        ui.onNodeWithTag("ai-workbench").assertExists()
        assertTrue(facade.state.value.selectedSessionId == null)
    }

    @Test
    fun theFreeGroupPlusAsksWhichDiscussionToQuoteOrStartsEmpty() {
        show()
        // Two conversations under one topic.
        ui.onNodeWithContentDescription("新建自由对话").performClick()
        ui.onNodeWithTag("ai-free-field").performTextInput("RTOS 选型")
        ui.onNodeWithTag("ai-free-send").performClick()
        ui.waitForIdle()
        facade.finish("先看实时性和生态。")
        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()
        val first = facade.freeStarts.single()
        val topic = facade.session(facade.sends.single().sessionId)
        val groupId = topic.question.questionId

        ui.onNodeWithContentDescription("在「RTOS 选型」下新建讨论").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-reference-choice").assertExists()
        ui.onNode(hasTestTag("ai-reference-option") and hasText("引用「RTOS 选型」")).performClick()
        ui.onNodeWithTag("ai-reference-confirm").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-new-free-pane").assertExists()
        ui.onNodeWithTag("ai-source-hint").assertTextEquals("参考「RTOS 选型」；这份引用只用于本对话。")
        ui.onNodeWithTag("ai-free-field").performTextInput("FreeRTOS 和 Zephyr 哪个合适？")
        ui.onNodeWithTag("ai-free-send").performClick()
        ui.waitForIdle()
        val second = facade.freeStarts.last()
        assertEquals(groupId, second.groupId)
        assertEquals(topic.sessionId, second.referenceSessionId)
        assertNotEquals(first.commandId, second.commandId)
        ui.onNodeWithTag("ai-source-hint").assertTextEquals("参考「RTOS 选型」；这份引用只用于本对话。")
        facade.finish("看资源和认证需求。")
        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()

        // "不引用" starts an empty conversation in the same topic.
        ui.onNodeWithContentDescription("在「RTOS 选型」下新建讨论").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-reference-none").performClick()
        ui.onNodeWithTag("ai-reference-confirm").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-source-hint").assertTextEquals("自由对话 · 未关联题目或速记")
        ui.onNodeWithTag("ai-free-field").performTextInput("再聊一个")
        ui.onNodeWithTag("ai-free-send").performClick()
        ui.waitForIdle()
        assertEquals(groupId, facade.freeStarts.last().groupId)
        assertNull(facade.freeStarts.last().referenceSessionId)
    }

    @Test
    fun theTemporaryChatSendsAtOnceKeepsNoRecordAndClosesOnLeaving() {
        seedMixed()
        show()
        ui.onNodeWithTag("ai-temp-field").performTextInput("DMA 和 cache 一致性？")
        ui.onNodeWithTag("ai-temp-send").performClick()
        ui.waitForIdle()

        ui.onNodeWithTag("ai-temp-chat").assertExists()
        val temp = facade.temporaryId!!
        val sent = facade.sends.single()
        assertEquals(temp, sent.sessionId)
        assertEquals("DMA 和 cache 一致性？", sent.input)
        assertEquals(AiOwnerKind.TEMPORARY, sent.question.kind)
        ui.onNodeWithTag("ai-source-hint").assertTextEquals("不保留记录：退出后清除正文和草稿，费用仍计入统计。")
        // Nothing to rename or delete; it is discarded on leaving.
        ui.onAllNodesWithContentDescription("讨论操作").assertCountEquals(0)
        assertEquals("", wb.temporaryText)

        ui.runOnIdle { back.onBackPressed() }
        ui.waitForIdle()
        assertEquals(listOf(temp), facade.temporaryCloses.distinct())
        assertNull(facade.temporaryId)
        assertNull(facade.state.value.selectedSessionId)
        ui.onNodeWithTag("ai-workbench").assertExists()
        assertTrue(wb.recent.none { it.sessionId == temp })
        ui.onNodeWithTag("ai-search-open").performClick()
        ui.onNodeWithTag("ai-search-field").performTextInput("一致性")
        settle()
        ui.onNodeWithTag("ai-search-none").assertExists()
    }

    @Test
    fun searchFindsAKeywordThatOnlyAnOldReplyHas() {
        seedMixed()
        facade.add("s3", MessageRole.USER, "fork 之后发生了什么？")
        facade.add("s3", MessageRole.ASSISTANT, "子进程获得父进程地址空间的写时复制副本。")
        show()
        ui.onNodeWithTag("ai-search-open").performClick()
        ui.onNodeWithTag("ai-search-field").performTextInput("写时复制")
        settle()
        ui.onAllNodesWithTag("ai-search-hit").assertCountEquals(1)
        ui.onNodeWithTag("ai-search-snippet", useUnmergedTree = true).assertTextEquals("内容：子进程获得父进程地址空间的写时复制副本。")
        ui.onNodeWithTag("ai-search-hit").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-title").assertTextEquals("命令讨论")
    }

    // ---- PLAN-1.7.4 Leader acceptance: the two returned items ----

    /** The workbench page can come and go while its controller (as in the ViewModel) stays. */
    private val pageShown = androidx.compose.runtime.mutableStateOf(true)

    private fun showLeavable() {
        facade.sourceOf = ::resolve
        ui.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val c = remember { AiWorkbenchController({ facade }, scope, model = { "m" }, searchDelayMs = 0) }
                wb = c
                if (pageShown.value) AiWorkbenchContent(c) { _, _ -> }
            }
        }
        ui.waitForIdle()
    }

    @Test
    fun leavingWhileATemporaryChatOpensDiscardsItAndWhatWasTyped() {
        showLeavable()
        facade.temporaryGate = kotlinx.coroutines.CompletableDeferred()
        val gate = facade.temporaryGate!!
        ui.onNodeWithTag("ai-temp-field").performTextInput("离开后不能发出")
        ui.onNodeWithTag("ai-temp-send").performClick()
        ui.waitForIdle()
        assertTrue(wb.openingTemporary)

        ui.runOnIdle { pageShown.value = false }
        ui.waitForIdle()
        assertEquals("", wb.temporaryText)
        ui.runOnIdle { gate.complete(Unit) }
        ui.waitForIdle()

        assertNull(wb.temporary)
        assertNull(facade.temporaryId)
        assertNull(facade.state.value.selectedSessionId)
        assertNull(facade.state.value.activeEditorEpoch)
        assertTrue(facade.sends.isEmpty())
        assertEquals(1, facade.temporaryCloses.size)

        // Coming back shows the workbench with an empty box and sends nothing.
        ui.runOnIdle { pageShown.value = true }
        ui.waitForIdle()
        ui.onNodeWithTag("ai-workbench").assertExists()
        ui.onAllNodesWithTag("ai-temp-chat").assertCountEquals(0)
        assertEquals("", editable("ai-temp-field"))
        assertTrue(facade.sends.isEmpty())
    }

    @Test
    fun aQuickReturnAfterLeavingKeepsTheNewTemporaryChatAndSendsOnlyTheNewText() {
        showLeavable()
        facade.temporaryGate = kotlinx.coroutines.CompletableDeferred()
        val gate = facade.temporaryGate!!
        ui.onNodeWithTag("ai-temp-field").performTextInput("旧的输入")
        ui.onNodeWithTag("ai-temp-send").performClick()
        ui.waitForIdle()
        ui.runOnIdle { pageShown.value = false }
        ui.waitForIdle()
        ui.runOnIdle { pageShown.value = true }
        ui.waitForIdle()

        // Back before the first open answered, and asking again.
        ui.onNodeWithTag("ai-temp-field").performTextInput("新的输入")
        ui.onNodeWithTag("ai-temp-send").performClick()
        ui.waitForIdle()
        ui.runOnIdle { gate.complete(Unit) }
        ui.waitForIdle()

        // The late open was closed first; the new one got its own chat and only the new text went out.
        ui.onNodeWithTag("ai-temp-chat").assertExists()
        val current = facade.temporaryId!!
        assertEquals(current, wb.temporary?.sessionId)
        assertEquals(current, facade.state.value.selectedSessionId)
        assertEquals(listOf("新的输入"), facade.sends.map { it.input })
        assertEquals(current, facade.sends.single().sessionId)
        assertFalse(current in facade.temporaryCloses)
        assertEquals(1, facade.temporaryCloses.size)
    }

    @Test
    fun aDiscussionPastTheFirstPageOfAFreeGroupCanBeQuoted() {
        val group = "00000000-0000-4000-8000-00000000f00d"
        val owner = QuestionOwner(group, "free-$group", AiOwnerKind.FREE)
        val oldest = facade.seed(owner, "最早的一条", sourceLabel = "RTOS 选型")
        repeat(AI_PAGE_SIZE + 1) { facade.seed(owner, "对话 ${it + 1}", sourceLabel = "RTOS 选型") }
        show()
        ui.onNodeWithTag("ai-filter-FREE").performClick()
        ui.waitForIdle()
        ui.onNodeWithContentDescription("在「RTOS 选型」下新建讨论").performClick()
        ui.waitForIdle()
        ui.onAllNodesWithTag("ai-reference-option").assertCountEquals(AI_PAGE_SIZE)
        assertTrue(wb.choosing!!.options.none { it.sessionId == oldest.sessionId })

        // A choice made on the first page stays chosen while older ones load.
        ui.onNode(hasTestTag("ai-reference-option") and hasText("引用「对话 21」")).performClick()
        ui.onNodeWithTag("ai-reference-more").performScrollTo().performClick()
        ui.waitForIdle()
        ui.onAllNodesWithTag("ai-reference-option").assertCountEquals(AI_PAGE_SIZE + 2)
        ui.onAllNodesWithTag("ai-reference-more").assertCountEquals(0)
        ui.onNode(hasTestTag("ai-reference-option") and hasText("引用「对话 21」")).assertIsSelected()

        ui.onNode(hasTestTag("ai-reference-option") and hasText("引用「最早的一条」")).performScrollTo().performClick()
        ui.onNode(hasTestTag("ai-reference-option") and hasText("引用「对话 21」")).assertIsNotSelected()
        ui.onNodeWithTag("ai-reference-confirm").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("ai-source-hint").assertTextEquals("参考「最早的一条」；这份引用只用于本对话。")
        ui.onNodeWithTag("ai-free-field").performTextInput("接着那条聊")
        ui.onNodeWithTag("ai-free-send").performClick()
        ui.waitForIdle()
        assertEquals(oldest.sessionId, facade.freeStarts.single().referenceSessionId)
        assertEquals(group, facade.freeStarts.single().groupId)
    }

    @Test
    fun anEmptyWorkbenchSaysWhereDiscussionsStart() {
        show()
        ui.onNodeWithTag("ai-all-empty").assert(hasAnyDescendant(hasText("还没有 AI 讨论")))
        ui.onAllNodesWithTag("ai-recent").assertCountEquals(0)
        ui.onNodeWithTag("ai-bottom-bar").assertIsDisplayed()
    }
}
