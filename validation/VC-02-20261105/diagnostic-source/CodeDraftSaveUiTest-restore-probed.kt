package cn.embeddedquiz

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T4 draft editing UI. The editor reports text changes only; caret and selection stay local. On a
 * real ViewModel: save status, explicit retry, leaving a question only after its draft is on disk,
 * a notification winning over a late leave, the exclusive boundary and manual notes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalTestApi::class)
class CodeDraftSaveUiTest {
    @org.junit.Before fun vc02RestoreQueuedCallback() { Vc02StateProbe.restoreQueuedCallback("CodeDraftSaveUiTest setup") }
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    // ---- The editor on its own ----

    private var code by mutableStateOf("")
    private var enabled by mutableStateOf(true)
    private val changes = mutableListOf<String>()
    private lateinit var state: CodeEditorState

    /** The caller's side as QuestionScreen wires it: keep the text, forward each change. */
    private fun showEditor(initial: String) {
        code = initial
        state = CodeEditorState(initial)
        val probe = Vc02StateProbe.watch(ui)
        try { ui.setContent {
            MaterialTheme {
                CodeEditor(code, state, enabled) {
                    code = it
                    changes += it
                }
            }
        } } finally { probe.close() }
    }

    private val editor get() = ui.onNodeWithTag("code-editor")

    @Test
    fun onlyTextChangesAreReportedAndTheCaretSurvivesTheRoundTrip() {
        showEditor("int a;")
        editor.performTextInputSelection(TextRange(3))
        editor.performTextInputSelection(TextRange(0, 3))
        editor.performTextInputSelection(TextRange(6))
        ui.waitForIdle()
        assertEquals("moving the caret or selecting is not a draft change", emptyList<String>(), changes)

        editor.performTextInput(" b;")
        ui.waitForIdle()
        assertEquals(listOf("int a; b;"), changes)
        // The parent's value echo must not reset the field and throw the caret to a new position.
        assertEquals(TextRange(9), state.field.value.selection)

        editor.performTextInputSelection(TextRange(4))
        ui.waitForIdle()
        assertEquals(1, changes.size)
        assertEquals(TextRange(4), state.field.value.selection)
        editor.assertTextEquals("int a; b;", includeEditableText = true)
    }

    @Test
    fun indentInsertsAtTheCaretAsOneChange() {
        showEditor("ab")
        editor.performTextInputSelection(TextRange(1))
        ui.onNodeWithContentDescription("插入四个空格缩进").performClick()
        ui.waitForIdle()
        assertEquals(listOf("a    b"), changes)
        assertEquals(TextRange(5), state.field.value.selection)
    }

    @Test
    fun disabledEditorKeepsTheTextReadableButAcceptsNoEdit() {
        showEditor("keep")
        enabled = false
        ui.waitForIdle()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
        editor.assertTextEquals("keep", includeEditableText = true)
        ui.onNodeWithContentDescription("插入四个空格缩进").assertIsNotEnabled().performClick()
        ui.waitForIdle()
        assertTrue(changes.isEmpty())
        assertEquals("keep", code)

        enabled = true
        ui.waitForIdle()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
    }

    // ---- On a real ViewModel ----

    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val temporary = mutableListOf<File>()
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"cd-a","stem":"草稿第一题","source":"UI test","kind":"PROGRAMMING","reviewRequired":true},
        {"id":"cd-b","stem":"草稿第二题","source":"UI test","kind":"PROGRAMMING","reviewRequired":true},
        {"id":"cd-n","stem":"笔记客观题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"correct":["A"],"reviewRequired":false}]}
    """

    // Write seam: the draft worker calls it on IO before each code/notes transaction.
    @Volatile private var failWrites = false
    @Volatile private var gate: CompletableDeferred<Unit>? = null
    private val writes = AtomicInteger()

    private fun openModel(fontScale: Float = 1f) {
        app.deleteDatabase("embeddedquiz.db")
        prefs.edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        runBlocking {
            val db = QuizDatabase.open(app)
            try {
                QuizRepository(db).importBank(bank)
            } finally {
                db.close()
            }
        }
        prefs.edit().putInt("bankRevision", 4).commit()
        ui.runOnUiThread {
            vm =
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
                    QuizViewModel::class.java]
        }
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.ready || vm.questions.size != 3) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
        vm.codeDrafts.persistence.beforeWrite = {
            writes.incrementAndGet()
            gate?.await()
            if (failWrites) throw IOException("injected draft write failure")
        }
        ui.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) { QuizApp(vm) }
        }
    }

    @After
    fun cleanup() {
        gate?.complete(Unit)
        failWrites = false
        if (::vm.isInitialized) {
            val old = vm
            ui.runOnUiThread { store.clear() }
            await { old.examResourcesClosed?.isCompleted == true }
            app.deleteDatabase("embeddedquiz.db")
            prefs.edit().clear().commit()
        }
        temporary.forEach { it.delete() }
    }

    private fun await(condition: () -> Boolean) {
        try {
            ui.waitUntil(20000) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Throwable) {
            throw AssertionError(
                "page=${vm.page}; index=${vm.index}; a=${vm.codeDraftSaveState("cd-a")}; b=${vm.codeDraftSaveState("cd-b")}; blocked=${vm.codeDraftEditingBlocked}; writes=${writes.get()}",
                e,
            )
        }
    }

    private fun back() {
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private val inList = hasAnyAncestor(hasTestTag("question-list"))

    private fun questionNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        await {
            ui.onAllNodes(
                    hasTestTag("question-list") and
                        hasContentDescription("题目${vm.queue.getOrNull(vm.index)}")
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        if (ui.onAllNodes(matcher and !inList).fetchSemanticsNodes().isEmpty()) {
            // Scrolls the item in, then the node itself: items here can be taller than the window.
            ui.onNodeWithTag("question-list").performScrollToNode(matcher)
            ui.onNode(matcher).performScrollTo()
        }
        return ui.onNode(matcher)
    }

    private fun bar(label: String) =
        questionNode((hasText(label) or hasContentDescription(label)) and hasClickAction() and !hasAnyAncestor(isDialog()))

    private fun inDialog(text: String) = ui.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun draftEditor() = questionNode(hasTestTag("code-editor"))

    private fun practice(vararg ids: String) {
        ui.runOnIdle { vm.start(ids.map { id -> vm.questions.first { it.id == id } }, "编程专区") }
        questionNode(hasTestTag("question-list"))
    }

    private fun savedCode(id: String) = runBlocking { vm.repo.dao.getProgress(id)?.code }

    private fun savedNotes(id: String) = runBlocking { vm.repo.dao.getProgress(id)?.notes }

    private fun type(text: String) {
        draftEditor().performTextInput(text)
        ui.waitForIdle()
    }

    @Test
    fun typingShowsSavingThenSavedAndCaretMovesDoNotWrite() {
        openModel()
        practice("cd-a")
        gate = CompletableDeferred()
        type("int a;")
        type(" int b;")
        await { vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Saving }
        questionNode(hasText("草稿保存中…")).assertExists()
        // Saving never blocks typing.
        draftEditor().assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
        gate!!.complete(Unit)
        await { vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Saved }
        questionNode(hasText("草稿已保存到本机")).assertExists()
        assertEquals("int a; int b;", savedCode("cd-a"))

        val saved = vm.codeDraftSaveState("cd-a")
        val count = writes.get()
        draftEditor().performTextInputSelection(TextRange(2))
        draftEditor().performTextInputSelection(TextRange(0, 3))
        ui.mainClock.advanceTimeBy(3_000)
        ui.waitForIdle()
        assertEquals(saved, vm.codeDraftSaveState("cd-a"))
        assertEquals(count, writes.get())
    }

    @Test
    fun errorStaysVisibleKeepsTextAndCaretAndRetrySaves() {
        openModel()
        practice("cd-a")
        failWrites = true
        type("broken")
        await { vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Error }
        questionNode(hasText("草稿保存失败，最新文字尚未保存")).assertExists()
        draftEditor().performTextInputSelection(TextRange(3))
        ui.waitForIdle()
        draftEditor().assertTextEquals("broken", includeEditableText = true)
        draftEditor().assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
        assertNull(savedCode("cd-a")?.takeIf { it.isNotEmpty() })

        failWrites = false
        questionNode(hasText("重试保存") and hasClickAction()).performClick()
        await { vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Saved }
        assertEquals("broken", savedCode("cd-a"))
        draftEditor().assertTextEquals("broken", includeEditableText = true)
        ui.onNodeWithText("草稿保存失败，最新文字尚未保存").assertDoesNotExist()
    }

    @Test
    fun movingOnWaitsForTheDraftAndFreezesItsInputUntilItLands() {
        openModel()
        practice("cd-a", "cd-b")
        gate = CompletableDeferred()
        type("first")
        bar("下一题").performClick()
        ui.waitForIdle()
        assertEquals(0, vm.index)
        draftEditor().assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
        bar("下一题").assertIsNotEnabled()
        gate!!.complete(Unit)
        await { vm.index == 1 }
        assertEquals("first", savedCode("cd-a"))

        // The system back gesture takes the same path.
        gate = CompletableDeferred()
        type("second")
        back()
        assertEquals(Page.QUESTION, vm.page)
        gate!!.complete(Unit)
        await { vm.page == Page.HOME }
        assertEquals("second", savedCode("cd-b"))
    }

    @Test
    fun failedSaveKeepsTheUserOnTheQuestionUntilAnExplicitRetrySucceeds() {
        openModel()
        practice("cd-a")
        failWrites = true
        type("unsaved")
        await { vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Error }

        bar("返回首页").performClick()
        await { ui.onAllNodesWithText("草稿保存失败，暂未离开").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Page.QUESTION, vm.page)
        inDialog("留在本页").performClick()
        ui.onNodeWithText("草稿保存失败，暂未离开").assertDoesNotExist()
        draftEditor().assertTextEquals("unsaved", includeEditableText = true)

        bar("打开全局标签").performClick()
        await { ui.onAllNodesWithText("草稿保存失败，暂未离开").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Page.QUESTION, vm.page)
        failWrites = false
        inDialog("重试保存").performClick()
        await { vm.page == Page.NOTES }
        assertEquals("unsaved", savedCode("cd-a"))
    }

    /** Notification first, then the draft write returns before the next frame disposes the page. */
    private fun notifyWhileLeaving(saveFails: Boolean) {
        openModel()
        practice("cd-a")
        gate = CompletableDeferred()
        type("late")
        failWrites = saveFails
        bar("返回首页").performClick()
        ui.waitForIdle()
        assertEquals(Page.QUESTION, vm.page)

        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread {
                vm.requestNotificationReview()
                assertEquals(Page.REVIEW, vm.page)
                gate!!.complete(Unit)
            }
            await {
                val s = vm.codeDraftSaveState("cd-a")
                if (saveFails) s is CodeDraftSaveState.Error else s is CodeDraftSaveState.Saved
            }
            repeat(50) {
                shadowOf(Looper.getMainLooper()).idle()
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
            }
            assertEquals("A late leave must not overwrite the notification's page", Page.REVIEW, vm.page)
        } finally {
            gate!!.complete(Unit)
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertEquals(Page.REVIEW, vm.page)
        ui.onNodeWithText("智能复习").assertExists()
        ui.onNodeWithText("草稿保存失败，暂未离开").assertDoesNotExist()
    }

    @Test
    fun notificationDuringLeaveWinsWhenTheDraftSaves() {
        notifyWhileLeaving(saveFails = false)
        assertEquals("late", savedCode("cd-a"))
    }

    @Test
    fun notificationDuringLeaveWinsAndTheFailureIsShownBackOnTheQuestion() {
        notifyWhileLeaving(saveFails = true)
        failWrites = false
        // Saving goes on in the background; returning to the question shows the failure.
        ui.runOnIdle { vm.page = Page.QUESTION }
        questionNode(hasText("草稿保存失败，最新文字尚未保存")).assertExists()
        questionNode(hasText("重试保存") and hasClickAction()).performClick()
        await { vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Saved }
        assertEquals("late", savedCode("cd-a"))
    }

    /** Tall window: editor and notes are both composed, so no scrolling is needed while held. */
    @Test
    @Config(qualifiers = "w411dp-h1800dp")
    fun theExclusiveBoundaryFreezesCodeAndNotesUntilItEnds() {
        openModel()
        practice("cd-a")
        type("before backup")
        questionNode(hasContentDescription("展开笔记与难度感受")).performClick()
        questionNode(hasText("个人笔记（自动保存）")).assertExists()
        val hold = CompletableDeferred<Unit>()
        vm.codeDrafts.persistence.beforeBoundary = { hold.await() }
        val output = File.createTempFile("t4-ui-", ".json").also { temporary += it }
        val editable = SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false)
        // Backup sets the global busy bar, which is indeterminate; a paused clock lets assertions idle.
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { vm.backupUri(Uri.fromFile(output)) }
            await { vm.codeDraftEditingBlocked }
            repeat(3) { ui.mainClock.advanceTimeByFrame() }
            Vc02StateProbe.sample(ui, "exclusive-after-3-frames blocked=${vm.codeDraftEditingBlocked}")
            println("VC02 EXCLUSIVE TREE " + ui.onRoot(useUnmergedTree = true).printToString())
            ui.onAllNodesWithText("正在备份、恢复或重置学习数据，代码和笔记暂时不能修改。").assertCountEquals(2)
            ui.onNodeWithTag("code-editor").assert(editable)
            ui.onNode(hasText("个人笔记（自动保存）")).assert(editable)
            ui.onNode(hasText("保存代码与笔记") and hasClickAction()).assertIsNotEnabled()
        } finally {
            hold.complete(Unit)
            ui.mainClock.autoAdvance = true
        }
        await { !vm.codeDraftEditingBlocked && !vm.busy }
        assertTrue("备份应包含边界前受理的最新草稿", output.readText().contains("before backup"))
        draftEditor().assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
        ui.onAllNodesWithText("正在备份、恢复或重置学习数据，代码和笔记暂时不能修改。").assertCountEquals(0)
    }

    @Test
    fun manualNotesAreNeverShownAsSavedBeforeTheButtonIsPressed() {
        openModel()
        ui.runOnIdle { vm.start(listOf(vm.questions.first { it.id == "cd-n" }), "功能验证") }
        questionNode(hasContentDescription("展开笔记与难度感受")).performClick()
        questionNode(hasText("个人笔记（手动保存）")).performTextInput("记住边界")
        ui.waitForIdle()
        questionNode(hasText("笔记有未保存的修改，点“保存笔记”后才会写入")).assertExists()
        ui.onNodeWithText("草稿已保存到本机").assertDoesNotExist()
        assertEquals(CodeDraftSaveState.Inactive, vm.codeDraftSaveState("cd-n"))
        assertTrue(savedNotes("cd-n").isNullOrEmpty())

        questionNode(hasText("保存笔记") and hasClickAction()).performClick()
        await { vm.codeDraftSaveState("cd-n") is CodeDraftSaveState.Saved }
        questionNode(hasText("草稿已保存到本机")).assertExists()
        assertEquals("记住边界", savedNotes("cd-n"))

        questionNode(hasText("个人笔记（手动保存）")).performTextInput("，再改")
        ui.waitForIdle()
        questionNode(hasText("笔记有未保存的修改，点“保存笔记”后才会写入")).assertExists()
        ui.onNodeWithText("草稿已保存到本机").assertDoesNotExist()
        assertEquals("记住边界", savedNotes("cd-n"))
    }

    /**
     * The missing-question page of a practice question whose draft is already accepted: only the
     * source question is removed before it is first loaded; the write is held or fails at the seam.
     */
    private fun showMissingQuestionWithAnAcceptedDraft(fail: Boolean) {
        openModel()
        val question = vm.questions.first { it.id == "cd-a" }
        vm.db.openHelper.writableDatabase.execSQL("DELETE FROM questions WHERE id='cd-a'")
        gate = CompletableDeferred()
        failWrites = fail
        ui.runOnUiThread {
            vm.updateCodeDraft("cd-a", "latest missing-question text", "latest missing notes")
            vm.start(listOf(question), "编程练习")
        }
        await { writes.get() > 0 }
        await { ui.onAllNodesWithText("本题无法加载，题目可能已从题库移除。").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Page.QUESTION, vm.page)
        assertTrue(vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Saving)
        ui.onNodeWithText("草稿保存中…").assertExists()
    }

    @Test
    fun missingQuestionSystemBackWaitsForTheAcceptedDraft() {
        showMissingQuestionWithAnAcceptedDraft(fail = false)
        back()
        assertEquals("System back must not leave while the accepted draft is still writing", Page.QUESTION, vm.page)
        ui.onNodeWithContentDescription("返回首页").assertIsNotEnabled()
        gate!!.complete(Unit)
        await { vm.page == Page.HOME }
        assertTrue(vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Saved)
        assertEquals("latest missing-question text", savedCode("cd-a"))
    }

    @Test
    fun missingQuestionTopBackStaysOnFailureAndLeavesAfterAnExplicitRetry() {
        showMissingQuestionWithAnAcceptedDraft(fail = true)
        gate!!.complete(Unit)
        await { vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Error }
        ui.onNodeWithText("草稿保存失败，最新文字尚未保存").assertExists()
        ui.onNodeWithContentDescription("返回首页").performClick()
        await { ui.onAllNodesWithText("草稿保存失败，暂未离开").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Top back must not bypass the draft Error", Page.QUESTION, vm.page)
        inDialog("留在本页").performClick()
        assertEquals(Page.QUESTION, vm.page)
        assertEquals("latest missing-question text", vm.codeDrafts.drafts["cd-a"]!!.code)

        // System back is held the same way; the dialog's retry then saves and leaves.
        back()
        await { ui.onAllNodesWithText("草稿保存失败，暂未离开").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Page.QUESTION, vm.page)
        failWrites = false
        inDialog("重试保存").performClick()
        await { vm.page == Page.HOME }
        assertTrue(vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Saved)
        assertEquals("latest missing-question text", savedCode("cd-a"))
    }

    @Test
    fun missingQuestionLeaveYieldsToANotificationThatArrivesWhileWaiting() {
        showMissingQuestionWithAnAcceptedDraft(fail = false)
        back()
        assertEquals(Page.QUESTION, vm.page)
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread {
                vm.requestNotificationReview()
                assertEquals(Page.REVIEW, vm.page)
                gate!!.complete(Unit)
            }
            await { vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Saved }
            repeat(50) {
                shadowOf(Looper.getMainLooper()).idle()
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
            }
            assertEquals("A late leave must not overwrite the notification's page", Page.REVIEW, vm.page)
        } finally {
            gate!!.complete(Unit)
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertEquals(Page.REVIEW, vm.page)
        ui.onNodeWithText("草稿保存失败，暂未离开").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun aDraftFailureIsReachableOnASmallScreenWithLargeText() {
        openModel(fontScale = 1.6f)
        practice("cd-a")
        failWrites = true
        type("x")
        await { vm.codeDraftSaveState("cd-a") is CodeDraftSaveState.Error }
        questionNode(hasText("草稿保存失败，最新文字尚未保存"))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        questionNode(hasText("重试保存") and hasClickAction()).assertIsDisplayed().assertIsEnabled()
    }
}
