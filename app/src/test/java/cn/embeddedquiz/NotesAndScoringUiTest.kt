package cn.embeddedquiz

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NotesAndScoringUiTest {
    @get:Rule val ui=createComposeRule()
    private val store=ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val bank="""{"schemaVersion":1,"questions":[
        {"id":"ui-choice","stem":"选择测试题","source":"UI test","kind":"SINGLE","options":[{"key":"A","text":"选项甲"},{"key":"B","text":"选项乙"}],"correct":["A"],"reviewRequired":false},
        {"id":"ui-short","stem":"简答测试题","source":"UI test","kind":"SHORT","reviewRequired":true}]}
    """

    @Before fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings",0).edit().clear().commit()
        ui.runOnUiThread { vm=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java] }
        runBlocking { vm.repo.importBank(bank) }
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if(!vm.ready||vm.questions.none {it.id=="ui-choice"}) Thread.sleep(25)
        }
        assertTrue(vm.notice,vm.ready)
        ui.setContent { QuizApp(vm) }
    }
    @After fun cleanup() {
        ui.runOnUiThread {store.clear()}
        app.deleteDatabase("embeddedquiz.db")
    }
    private fun start(id: String) = ui.runOnIdle {vm.start(listOf(vm.questions.first {it.id==id}),"功能验证")}
    private fun await(condition: ()->Boolean) {
        try {
            ui.waitUntil(20000) {shadowOf(Looper.getMainLooper()).idle();condition()}
        } catch(e: Exception) {
            throw AssertionError("notice=${vm.notice}; noteError=${vm.noteSaveError}; writes=${vm.pendingNoteWrites}; busy=${vm.busy}",e)
        }
    }
    private fun waitForRecord() {await {vm.attempts.isNotEmpty()&&!vm.busy}}
    private fun questionText(text: String): SemanticsNodeInteraction {
        ui.onNodeWithTag("question-list").performScrollToNode(hasText(text))
        return ui.onNodeWithText(text)
    }

    @Test fun choiceSurvivesNotesAndSubmissionRecordsExactlyOnce() {
        start("ui-choice")
        questionText("□ 选项乙").performClick()
        questionText("打开便签").performClick()
        ui.onNodeWithText("新建便签").performClick()
        ui.onNodeWithText("便签标题").performTextInput("复习要点")
        ui.onNodeWithText("记下思路、易错点或待办").performTextInput("检查指针的生命周期")
        ui.onNodeWithText("继续答题").performClick()
        ui.onNodeWithText("☑ 选项乙").assertExists()
        questionText("提交答案").performClick()
        waitForRecord()
        questionText("自动判分：错误").assertExists()
        ui.onNodeWithText("手动判断本次回答（只记录你的掌握情况，不修改参考答案）").assertDoesNotExist()
        ui.onNodeWithText("提交答案").assertDoesNotExist()
        assertEquals(1,vm.attempts.size)
        assertEquals(false,vm.attempts.single().correct)
        assertEquals("AGAIN",vm.attempts.single().feedback)
        // Returning again must retain the submitted state rather than permit another record.
        ui.runOnIdle {vm.openStickyNotes()}
        ui.onNodeWithText("记下思路、易错点或待办").assertTextContains("检查指针的生命周期")
        ui.onNodeWithText("继续答题").performClick()
        questionText("自动判分：错误").assertExists()
        assertEquals(1,vm.attempts.size)
        await {vm.pendingNoteWrites==0&&vm.stickyNotes.any {it.title=="复习要点"}}
        assertEquals("检查指针的生命周期",vm.stickyNotes.single().content)
    }

    @Test fun textDraftAndManualAssessmentSurviveNotesAndQuestionNavigation() {
        ui.runOnIdle {vm.start(listOf(vm.questions.first {it.id=="ui-short"},vm.questions.first {it.id=="ui-choice"}),"功能验证")}
        questionText("先写下自己的回答").performTextInput("尚未完成的回答")
        ui.runOnIdle {vm.openStickyNotes()}
        ui.onNodeWithText("继续答题").performClick()
        ui.onNodeWithText("先写下自己的回答").assertTextContains("尚未完成的回答")
        questionText("下一题").performClick()
        questionText("上一题").performClick()
        questionText("先写下自己的回答").assertTextContains("尚未完成的回答")
        questionText("查看参考答案").performClick()
        questionText("不会").performClick()
        waitForRecord()
        assertEquals("UNKNOWN",vm.attempts.single().assessment)
        assertEquals("尚未完成的回答",vm.attempts.single().response)
        assertTrue(vm.progress["ui-short"]!!.wrong)
        questionText("已记录 · 自评：不会").assertExists()
    }

    @Test fun correctAnswerIsRecordedWithoutExtraFeedback() {
        start("ui-choice")
        questionText("□ 选项甲").performClick()
        questionText("提交答案").performClick()
        waitForRecord()
        assertEquals(true,vm.attempts.single().correct)
        assertEquals("GOOD",vm.attempts.single().feedback)
        assertEquals("",vm.attempts.single().assessment)
        questionText("自动判分：正确").assertExists()
        ui.onNodeWithText("简单").assertDoesNotExist()
        ui.onNodeWithText("困难").assertDoesNotExist()
        ui.onNodeWithText("记住了").assertDoesNotExist()
    }

    @Test fun examExpiresAndRecordsWhileNotesAreOpen() {
        ui.runOnIdle {
            vm.startExam(listOf(vm.questions.first {it.id=="ui-choice"}),1,1)
            vm.answerExam("ui-choice",setOf("A"))
            vm.openStickyNotes()
            vm.examDeadline=System.currentTimeMillis()-1
        }
        await {vm.examFinished&&!vm.busy}
        assertEquals("便签",vm.page)
        assertEquals(1,vm.attempts.size)
        assertEquals(true,vm.attempts.single().correct)
        ui.onNodeWithText("继续答题").performClick()
        questionText("答对 1 / 1，得分 100").assertExists()
    }

    @Test fun rapidNoteEditsAreSavedInOrderAndDeletionWins() {
        ui.runOnIdle {
            vm.openStickyNotes();vm.newStickyNote()
            repeat(60) {vm.updateStickyNote("标题","第 $it 版")}
        }
        await {vm.pendingNoteWrites==0&&vm.stickyNotes.singleOrNull()?.content=="第 59 版"}
        ui.runOnIdle {
            val note=requireNotNull(vm.noteDraft)
            vm.updateStickyNote(note.title,"最后修改")
            vm.deleteStickyNote(requireNotNull(vm.noteDraft))
        }
        await {vm.pendingNoteWrites==0&&vm.stickyNotes.isEmpty()}
        assertNull(vm.noteDraft)
        assertEquals("",vm.noteSaveError)
    }
}
