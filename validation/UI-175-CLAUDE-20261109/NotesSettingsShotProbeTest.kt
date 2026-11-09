package cn.embeddedquiz

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** TEMPORARY design probe (not kept in the source): renders the 1.7.5 pages in both themes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w393dp-h851dp-xhdpi")
class NotesSettingsShotProbeTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private lateinit var view: android.view.View
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val out = File(System.getProperty("shotDir") ?: "build/shots").also { it.mkdirs() }
    private val bank =
        """{"schemaVersion":1,"questions":[
        {"id":"shot-q","stem":"中断上下文中能否调用可能睡眠的函数？为什么？","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"不能，中断上下文没有可调度的进程上下文"},{"key":"B","text":"能，只要关闭抢占"}],"correct":["A"],"reviewRequired":false}]}
    """

    /** Written the way the app writes one: a new draft, saved. */
    private fun note(title: String, body: String) {
        ui.runOnIdle {
            vm.quickNotes.startNew()
            vm.quickNotes.editDraft { it.copy(title = title, blocks = QuickNoteBlocks.parse(body).map { b -> b.copy(lines = null) }) }
            vm.quickNotes.saveDraft()
        }
        await { vm.quickNotes.draft == null && !vm.quickNotes.state.working }
        ui.runOnIdle { vm.quickNotes.closeDocument() }
        await { !vm.quickNotes.state.working }
    }

    private fun await(condition: () -> Boolean) =
        ui.waitUntil(20000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            condition()
        }

    private fun setup(dark: Boolean) {
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        File(app.filesDir, "quicknotes-v1").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        ui.runOnUiThread { vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java] }
        runBlocking { vm.repo.importBank(bank) }
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.ready || vm.questions.isEmpty()) Thread.sleep(25)
        }
        if (dark) {
            ui.runOnIdle { vm.updateSettings { it.copy(theme = "DARK") } }
            await { vm.learning.settings.theme == "DARK" && !vm.busy }
        }
        ui.setContent {
            view = LocalView.current
            QuizApp(vm)
        }
        await { !vm.quickNotes.state.loading && !vm.quickNotes.state.working }
        note("嵌入式面试突击问答", "建议按“先摸底 → 补理论 → 动手验证”的顺序复习。\n")
        note("中断与下半部", "上半部尽快返回，耗时工作交给 tasklet 或工作队列。\n\n- [ ] 复习 softirq\n- [x] 写一个 tasklet 示例\n")
        note("DMA 与 cache 一致性", "传输前 clean，接收后 invalidate；注意 cache line 对齐。\n")
        await { vm.quickNotes.state.documents.size == 3 }
        val first = vm.quickNotes.state.documents.first { it.title == "嵌入式面试突击问答" }
        ui.runOnIdle { vm.quickNotes.setPinned(first.id, true) }
        await { !vm.quickNotes.state.working && vm.quickNotes.state.documents.any { it.pinned } }
        ui.runOnIdle { vm.quickNotes.leave() }
        await { !vm.quickNotes.state.working }
    }

    @After
    fun cleanup() {
        ui.runOnUiThread { store.clear() }
        val deadline = System.currentTimeMillis() + 20_000
        while (vm.examResourcesClosed?.isCompleted != true && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        app.deleteDatabase("embeddedquiz.db")
        File(app.filesDir, "quicknotes-v1").deleteRecursively()
    }

    private fun shot(name: String) {
        ui.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        ui.waitForIdle()
        val root = view.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        ui.runOnIdle { root.draw(android.graphics.Canvas(bmp)) }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun pages(theme: String) {
        ui.onNodeWithContentDescription("导航到更多").performClick()
        await { vm.page == Page.MORE }
        shot("more-$theme")
        ui.runOnIdle { vm.navigate(Page.SETTINGS) }
        await { vm.page == Page.SETTINGS }
        shot("settings-$theme")
        ui.runOnIdle { vm.navigate(Page.SETTINGS_AI) }
        await { vm.page == Page.SETTINGS_AI }
        shot("settings-ai-$theme")
        ui.runOnIdle { vm.navigate(Page.SETTINGS_REVIEW) }
        await { vm.page == Page.SETTINGS_REVIEW }
        shot("settings-review-$theme")
        ui.runOnIdle { vm.navigate(Page.HOME) }
        ui.onNodeWithContentDescription("导航到速记").performClick()
        await { vm.quickNotes.state.visible }
        shot("notes-$theme")
        val doc = vm.quickNotes.state.documents.first { it.title == "中断与下半部" }
        ui.runOnIdle { vm.quickNotes.openDocument(doc.id) }
        await { vm.quickNotes.state.reader != null && !vm.quickNotes.state.working }
        ui.runOnIdle { vm.quickNotes.beginEdit(doc.id) }
        await { vm.quickNotes.draftShown() && !vm.quickNotes.state.working }
        shot("editor-$theme")
        ui.runOnIdle { vm.quickNotes.discardDraft() }
        ui.runOnIdle { vm.quickNotes.leave() }
        await { !vm.quickNotes.state.visible && !vm.quickNotes.state.working }
        ui.runOnIdle { vm.start(vm.questions.filter { it.id == "shot-q" }, "顺序练习") }
        await { vm.page == Page.QUESTION && ui.onAllNodesWithTag("ai-mascot").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("ai-mascot").performClick()
        shot("question-mascot-$theme")
    }

    @Test fun light() {
        setup(dark = false)
        pages("light")
    }

    @Test fun dark() {
        setup(dark = true)
        pages("dark")
    }
}
