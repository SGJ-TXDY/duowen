package cn.embeddedquiz

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

class QuickNotesTestProvider : ContentProvider() {
    companion object { lateinit var root: File }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(uri.lastPathSegment)) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File(root, requireNotNull(uri.lastPathSegment))
        require(file.canonicalFile.parentFile == root.canonicalFile)
        val flags = if ('w' in mode) ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_READ_ONLY
        return ParcelFileDescriptor.open(file, flags)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickNotesIsolationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private var vm: QuizViewModel? = null

    @Before
    fun setup() {
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        File(app.filesDir, "quicknotes-v1").deleteRecursively()
        QuickNotesTestProvider.root = File(app.cacheDir, "quicknotes-provider").apply { mkdirs() }
        ShadowContentResolver.registerProviderInternal("quicknotes-test", Robolectric.buildContentProvider(QuickNotesTestProvider::class.java).create().get())
        runBlocking {
            val db = QuizDatabase.open(app)
            try {
                val repo = QuizRepository(db)
                repo.importBank("""{"schemaVersion":1,"questions":[
                    {"id":"a","stem":"objective","source":"测试","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]},
                    {"id":"b","stem":"subjective","source":"测试","kind":"SHORT"},
                    {"id":"code","stem":"programming","source":"测试","kind":"PROGRAMMING"}]}""")
                repo.dao.putProgress(Progress("a", seen = 1, due = LocalDate.now().toEpochDay()))
            } finally { db.close() }
        }
        app.getSharedPreferences("settings", 0).edit().putInt("bankRevision", 4).commit()
    }
    @After
    fun cleanup() {
        store.clear()
        File(app.filesDir, "quicknotes-v1").deleteRecursively()
        QuickNotesTestProvider.root.deleteRecursively()
        app.deleteDatabase("embeddedquiz.db")
        app.getSharedPreferences("settings", 0).edit().clear().commit()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("等待超时：${vm?.quickNotes?.state?.error}", condition())
    }
    private fun model(): QuizViewModel {
        val value = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        vm = value
        await { value.ready && value.questions.size == 3 && value.todaySummary != null && !value.quickNotes.state.loading }
        return value
    }
    private fun import(model: QuizViewModel): QuickNoteSummary {
        val text = javaClass.getResource("/quicknotes/linux.md")!!.readBytes()
        model.quickNotes.importPackage(QuickNotesRepository.parsePackage("linux.md", text))
        await { !model.quickNotes.state.working && model.quickNotes.state.documents.isNotEmpty() }
        return model.quickNotes.state.documents.first()
    }

    @Test
    fun quickNotesDoNotAffectQuizBackupPracticeDraftsOrHomeReview() {
        val model = model()
        model.start(model.questions.filter { it.daily }, "顺序练习")
        val question = runBlocking { model.repo.question("a")!! }
        val draft = model.questionDraft(question)
        draft.notes.value = "尚未保存的每题笔记"
        val queue = model.queue
        val session = model.practiceSession
        val due = model.due().map { it.id }
        val summary = model.todaySummary
        val backup = runBlocking { model.repo.backup() }
        model.openQuickNotes()
        val item = import(model)
        model.quickNotes.openDocument(item.id)
        await { !model.quickNotes.state.working && model.quickNotes.state.reader != null }
        assertTrue(model.quickNotes.copyCode("code-0"))
        val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals(model.quickNotes.state.reader!!.rendered.codeBlocks["code-0"], clipboard.primaryClip!!.getItemAt(0).text.toString())
        val anchor = model.quickNotes.state.reader!!.rendered.headings.last().id
        model.quickNotes.savePosition(item.id, QuickNotePosition(anchor, 12.0, 0.9))
        await { model.quickNotes.pendingPositionWrites == 0 }
        model.quickNotes.closeDocument()
        await { !model.quickNotes.state.working && model.quickNotes.state.reader == null }
        model.quickNotes.leave()
        await { !model.quickNotes.state.working && !model.quickNotes.state.visible }
        assertEquals(Page.QUESTION, model.page)
        assertEquals(queue, model.queue)
        assertEquals(session, model.practiceSession)
        assertSame(draft, model.questionDraft(question))
        assertEquals("尚未保存的每题笔记", draft.notes.value)
        assertEquals(due, model.due().map { it.id })
        assertEquals(summary, model.todaySummary)
        assertEquals(backup, runBlocking { model.repo.backup() })
        assertFalse(model.busy)
        model.navigate(Page.REVIEW)
        assertEquals(Page.REVIEW, model.page)
        assertEquals(listOf("a"), model.due().map { it.id })
    }

    @Test
    fun unfinishedExamAndReadingPositionSurviveModuleSwitchAndModelRestart() {
        val model = model()
        model.startExam(model.questions, 1, 30)
        model.answerExam("a", setOf("A"))
        val queue = model.queue
        val answers = model.examAnswers
        val deadline = model.examDeadline
        model.openQuickNotes()
        val item = import(model)
        model.quickNotes.openDocument(item.id)
        await { !model.quickNotes.state.working && model.quickNotes.state.reader != null }
        val position = QuickNotePosition(model.quickNotes.state.reader!!.rendered.headings[1].id, 50.0, 0.4)
        model.quickNotes.savePosition(item.id, position)
        await { model.quickNotes.pendingPositionWrites == 0 }
        model.quickNotes.leave()
        await { !model.quickNotes.state.working && !model.quickNotes.state.visible }
        assertEquals(queue, model.queue)
        assertEquals(answers, model.examAnswers)
        assertEquals(deadline, model.examDeadline)
        assertFalse(model.examFinished)
        store.clear()
        val restarted = model()
        assertEquals(queue, restarted.queue)
        assertEquals(answers, restarted.examAnswers)
        assertEquals(deadline, restarted.examDeadline)
        assertFalse(restarted.examFinished)
        assertEquals(position, restarted.quickNotes.state.documents.single().position)
        restarted.openQuickNotes()
        assertEquals(Page.QUESTION, restarted.page)
    }

    @Test
    fun duplicateConflictIsExplicitAndDoesNotUseGlobalQuizBusyOrNotice() {
        val model = model()
        val first = import(model)
        val notice = model.notice
        val pack = QuickNotesRepository.parsePackage("linux.md", javaClass.getResource("/quicknotes/linux.md")!!.readBytes())
        model.quickNotes.importPackage(pack)
        await { !model.quickNotes.state.working && model.quickNotes.state.conflict.isNotEmpty() }
        assertEquals(listOf(first.id), model.quickNotes.state.documents.map { it.id })
        model.quickNotes.cancelImport()
        assertTrue(model.quickNotes.state.conflict.isEmpty())
        model.quickNotes.importPackage(pack)
        await { !model.quickNotes.state.working && model.quickNotes.state.conflict.isNotEmpty() }
        model.quickNotes.confirmKeepBoth()
        await { !model.quickNotes.state.working && model.quickNotes.state.documents.size == 2 }
        assertFalse(model.busy)
        assertEquals(notice, model.notice)
    }

    @Test
    fun systemContentUrisAreCopiedAndIndependentBackupRestoresSavedPosition() {
        val model = model()
        val input = File(QuickNotesTestProvider.root, "input.md").apply { writeText("# SAF 中文\n\n正文", Charsets.UTF_8) }
        val inputUri = Uri.parse("content://quicknotes-test/input.md")
        shadowOf(app.contentResolver).registerInputStreamSupplier(inputUri) { input.inputStream() }
        model.quickNotes.importUri(inputUri)
        await { !model.quickNotes.state.working && model.quickNotes.state.documents.size == 1 }
        val item = model.quickNotes.state.documents.single()
        assertTrue(input.delete())
        model.quickNotes.openDocument(item.id)
        await { !model.quickNotes.state.working && model.quickNotes.state.reader != null }
        val position = QuickNotePosition(model.quickNotes.state.reader!!.rendered.headings.single().id, 5.0, 0.5)
        model.quickNotes.savePosition(item.id, position)
        await { model.quickNotes.pendingPositionWrites == 0 }
        val uri = Uri.parse("content://quicknotes-test/backup.zip")
        val backupFile = File(QuickNotesTestProvider.root, "backup.zip")
        shadowOf(app.contentResolver).registerOutputStreamSupplier(uri) { backupFile.outputStream() }
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { backupFile.inputStream() }
        model.quickNotes.exportBackupUri(uri)
        await { !model.quickNotes.state.working }
        assertTrue(model.quickNotes.state.error.isEmpty())
        assertTrue(File(QuickNotesTestProvider.root, "backup.zip").length() > 0)
        model.quickNotes.deleteDocument(item.id)
        await { !model.quickNotes.state.working && model.quickNotes.state.documents.isEmpty() }
        model.quickNotes.restoreBackupUri(uri)
        await { !model.quickNotes.state.working && model.quickNotes.state.documents.size == 1 }
        assertEquals(position, model.quickNotes.state.documents.single().position)
    }
}
