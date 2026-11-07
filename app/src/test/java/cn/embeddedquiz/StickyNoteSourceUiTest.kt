package cn.embeddedquiz

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 1.7.1 note sources: a note saved from an AI reply shows its question and opens it read only; an
 * updated or deleted question is explained and the note stays readable. Sources are GPT's backend;
 * a fake stands in. Ordinary notes look as before.
 */
@RunWith(RobolectricTestRunner::class)
// Tall enough that all four note cards are composed at once.
@Config(sdk = [28], qualifiers = "w360dp-h1000dp")
class StickyNoteSourceUiTest {
    /**
     * An earlier test can leave the Compose main dispatcher's callback removed by Robolectric's queue
     * reset (probed in validation/UI-171-IDLE-20261108: AiChatScreenTest then EnhancedAiTest, or
     * StartupTest). The compose rule waits for idle as it starts, so the original callback is
     * delivered in an outer rule, before it.
     */
    @get:Rule(order = 0)
    val strandedMainQueue = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                ComposeMainQueue.deliverStrandedCallbacks()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1) val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val question =
        Question(
            id = "q-src",
            stem = "DMA 与 cache 一致性如何保证？",
            kind = Kind.SINGLE,
            source = "UI test",
            options = listOf(Option("A", "A. 传输前清理 cache"), Option("B", "B. 不用处理")),
            correct = setOf("A"),
            explanation = "DMA 前清理、DMA 后失效对应区域。",
        )

    private inner class Sources : StickyNoteSources {
        val opened = mutableListOf<String>()

        override fun source(note: StickyNote) =
            when (note.title) {
                "当前题" -> NoteSourceView("q-src · 单选", NoteSourceStatus.CURRENT)
                "已更新题" -> NoteSourceView("q-src · 单选", NoteSourceStatus.CHANGED)
                "已删除题" -> NoteSourceView("q-gone · 单选", NoteSourceStatus.MISSING)
                else -> null
            }

        override suspend fun open(note: StickyNote): NoteSourceOpen {
            opened += note.title
            return when (note.title) {
                "当前题" -> NoteSourceOpen.Ready(question, changed = false)
                "已更新题" -> NoteSourceOpen.Ready(question, changed = true)
                else -> NoteSourceOpen.Missing
            }
        }
    }

    private val sources = Sources()

    @Before
    fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        File(app.filesDir, "quicknotes-v1").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        ui.runOnUiThread {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        }
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.ready) Thread.sleep(25)
        }
        assertTrue(vm.notice, vm.ready)
        for ((title, body) in listOf("普通便签" to "普通内容", "当前题" to "AI 解析：先清理", "已更新题" to "旧版解析", "已删除题" to "删除前的解析")) {
            ui.runOnIdle {
                vm.newStickyNote()
                vm.updateStickyNote(title, body)
                vm.closeStickyNote()
            }
            await { vm.pendingNoteWrites == 0 && !vm.busy }
        }
        await { vm.stickyNotes.size == 4 }
        ui.setContent { MaterialTheme { StickyNotesScreen(vm, sources) } }
        ui.waitForIdle()
    }

    @After
    fun cleanup() {
        ui.runOnUiThread { store.clear() }
        // The ViewModel closes the AI runtime and database asynchronously; delete test data only after.
        val deadline = System.currentTimeMillis() + 20_000
        while (vm.examResourcesClosed?.isCompleted != true) {
            check(System.currentTimeMillis() < deadline) { "ViewModel resources did not close within 20s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertFalse(vm.db.isOpen)
        app.deleteDatabase("embeddedquiz.db")
    }

    private fun await(condition: () -> Boolean) =
        ui.waitUntil(20000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }

    private fun back() {
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    // A note card merges its children, so the source line's tags are looked up in the unmerged tree;
    // the 回到原题 button is clickable and stays a node of its own.
    private fun openSourceOf(title: String) =
        ui.onNode(hasTestTag("note-open-source") and hasAnyAncestor(hasText(title))).performClick()

    @Test
    fun onlyNotesWithASourceShowItAndBackFromTheQuestionReturnsToTheList() {
        ui.onAllNodesWithTag("note-source", useUnmergedTree = true).assertCountEquals(3)
        ui.onNodeWithText("普通内容").assertIsDisplayed()

        openSourceOf("当前题")
        ui.waitForIdle()
        ui.onNodeWithTag("source-question").assertIsDisplayed()
        ui.onNodeWithText("原题（只读）").assertIsDisplayed()
        ui.onNodeWithText(question.stem).assertIsDisplayed()
        ui.onNodeWithText("A. 传输前清理 cache（参考答案）").assertIsDisplayed()
        ui.onNodeWithText(question.explanation).assertIsDisplayed()
        ui.onAllNodesWithTag("source-question-changed").assertCountEquals(0)
        assertEquals(listOf("当前题"), sources.opened)

        back()
        ui.onAllNodesWithTag("source-question").assertCountEquals(0)
        ui.onAllNodesWithTag("note-source", useUnmergedTree = true).assertCountEquals(3)
        assertNull(vm.noteDraft)
    }

    @Test
    fun anUpdatedQuestionIsFlaggedOnTheNoteAndInTheView() {
        ui.onNodeWithTag("note-source-changed", useUnmergedTree = true).assertIsDisplayed()
        openSourceOf("已更新题")
        ui.waitForIdle()
        ui.onNodeWithTag("source-question-changed").assertIsDisplayed()
        ui.onNodeWithText(question.stem).assertIsDisplayed()
    }

    @Test
    fun aDeletedQuestionIsExplainedAndTheNoteStaysReadable() {
        ui.onNodeWithTag("note-source-missing", useUnmergedTree = true).assertIsDisplayed()
        ui.onNode(hasTestTag("note-open-source") and hasAnyAncestor(hasText("已删除题"))).assertIsNotEnabled()
        ui.onNodeWithText("删除前的解析").assertIsDisplayed()
        assertTrue(sources.opened.isEmpty())
    }

    @Test
    fun theSourceIsAlsoOfferedInsideAnOpenNoteAndBackKeepsItOpen() {
        ui.onNodeWithText("AI 解析：先清理").performClick()
        await { vm.noteDraft?.title == "当前题" }
        ui.onNodeWithTag("note-source").assertIsDisplayed()
        ui.onNodeWithTag("note-open-source").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("source-question").assertIsDisplayed()
        back()
        ui.onAllNodesWithTag("source-question").assertCountEquals(0)
        assertEquals("当前题", vm.noteDraft?.title)
        ui.onNodeWithText("AI 解析：先清理").assertIsDisplayed()
    }
}
