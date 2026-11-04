package cn.embeddedquiz

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CodeDraftBoundaryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val store = ViewModelStore()
    private var model: QuizViewModel? = null
    private val clock = ManualDraftClock()
    private val temporary = mutableListOf<File>()
    private lateinit var backup: String
    @Volatile private var failBank = false
    private val bank = """{"schemaVersion":1,"questions":[{"id":"a","stem":"draft question","source":"T4","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]}]}"""
    @Before fun setup() = runBlocking {
        app.deleteDatabase("embeddedquiz.db"); prefs.edit().clear().commit()
        val db = QuizDatabase.open(app)
        try {
            val repo = QuizRepository(db); repo.importBank(bank)
            repo.update("a") { it.copy(code = "old code", notes = "old notes", favorite = true, flagged = true) }
            repo.record(repo.question("a")!!, "A", setOf("A"), "seed practice")
            repo.dao.putStickyNote(StickyNote("memo", "keep", "sticky text", 1, 1))
            backup = repo.backup()
        } finally { db.close() }
        prefs.edit().putInt("bankRevision", 4).putString("categoryMapHash", CategoryMap.fingerprint).commit()
        Unit
    }
    @After fun cleanup() {
        model?.let { vm -> store.clear(); waitFor { vm.examResourcesClosed?.isCompleted == true } }
        temporary.forEach { it.delete() }
        app.deleteDatabase("embeddedquiz.db"); prefs.edit().clear().commit()
    }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle(); LockSupport.parkNanos(1_000_000)
        }
        assertTrue("startup=${model?.startupState}; busy=${model?.busy}; draft=${model?.codeDraftSaveState("a")}", condition())
    }
    private fun <T> call(action: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        return try {
            val task = scope.async(start = CoroutineStart.UNDISPATCHED) { action() }
            waitFor { task.isCompleted }; runBlocking { task.await() }
        } finally { scope.cancel() }
    }
    private fun create(error: Boolean = false): QuizViewModel {
        failBank = error
        val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        model = vm
        vm.codeDrafts.persistence.clock = clock
        vm.beforeStartupStage = { if (it == StartupStage.BANK && failBank) throw IOException("injected startup fault") }
        if (error) waitFor { vm.startupState is StartupState.Error }
        else waitFor { vm.ready && vm.progress["a"]?.seen == 1 }
        return vm
    }
    private fun file(text: String = "") = File.createTempFile("t4-boundary-", ".json").also {
        it.writeText(text); temporary += it
    }
    private fun replacement(): String = JSONObject(backup).also {
        it.getJSONArray("progress").getJSONObject(0).put("code", "restored code").put("notes", "restored notes")
    }.toString()
    private fun row(vm: QuizViewModel) = runBlocking { vm.repo.dao.getProgress("a")!! }
    private fun reopened(): Progress {
        val db = QuizDatabase.open(app)
        return try { runBlocking { db.dao().getProgress("a")!! } } finally { db.close() }
    }
    private fun settle(vm: QuizViewModel) = waitFor { !vm.busy && !vm.codeDraftEditingBlocked }

    @Test fun backupFlushesTheWindowAndBlocksNewDraftAcceptanceUntilExportEnds() {
        val vm = create(); val q = call { vm.loadQuestion("a")!! }; val mirror = vm.questionDraft(q)
        vm.updateCodeDraft("a", "latest backup code", "latest backup notes")
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        vm.codeDrafts.persistence.beforeBoundary = { entered.complete(Unit); gate.await() }
        val output = file("sentinel")
        vm.backupUri(Uri.fromFile(output)); waitFor { entered.isCompleted }
        try {
            assertTrue(vm.codeDraftEditingBlocked)
            vm.updateCodeDraft("a", "rejected", "rejected")
            assertEquals("latest backup code", mirror.code.value)
            assertEquals("latest backup code", vm.codeDrafts.drafts["a"]!!.code)
            assertEquals("sentinel", output.readText())
        } finally { gate.complete(Unit) }
        settle(vm)
        val p = JSONObject(output.readText()).getJSONArray("progress").getJSONObject(0)
        assertEquals("latest backup code", p.getString("code"))
        assertEquals("latest backup notes", p.getString("notes"))
        assertEquals("备份完成（不包含 API Key）", vm.notice)
    }

    @Test fun saveFailureAbortsBackupRestoreAndResetWithoutClearingTheLatestDraft() {
        val vm = create(); val original = row(vm)
        vm.codeDrafts.persistence.beforeWrite = { error("injected draft failure") }
        vm.updateCodeDraft("a", "unsaved latest", "unsaved notes")
        val output = file("keep destination")
        vm.backupUri(Uri.fromFile(output)); settle(vm)
        assertEquals("keep destination", output.readText())
        assertTrue(vm.codeDraftSaveState("a") is CodeDraftSaveState.Error)
        vm.restoreUri(Uri.fromFile(file(replacement()))); settle(vm)
        assertEquals(original, row(vm)); assertFalse(vm.notice == "备份已恢复")
        vm.resetProgress(); settle(vm)
        assertEquals(original, row(vm)); assertEquals(1, runBlocking { vm.repo.dao.allAttempts().size })
        assertEquals("unsaved latest", vm.codeDrafts.drafts["a"]!!.code)
        assertTrue(vm.codeDraftSaveState("a") is CodeDraftSaveState.Error)
        assertFalse(vm.codeDraftEditingBlocked)
    }

    @Test fun restoreDrainsOldIoThenInvalidatesOldStateAndCannotReplayOldText() {
        val vm = create(); val question = call { vm.loadQuestion("a")!! }; val oldMirror = vm.questionDraft(question)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        vm.codeDrafts.persistence.beforeWrite = { entered.complete(Unit); gate.await() }
        vm.updateCodeDraft("a", "old generation pending", "old pending notes")
        vm.restoreUri(Uri.fromFile(file(replacement()))); waitFor { entered.isCompleted }
        try {
            assertTrue(vm.codeDraftEditingBlocked)
            assertEquals("old code", row(vm).code)
            vm.updateCodeDraft("a", "cannot enter", "cannot enter")
            assertEquals("old generation pending", oldMirror.code.value)
        } finally { gate.complete(Unit) }
        settle(vm)
        assertEquals("备份已恢复", vm.notice)
        assertEquals("restored code", row(vm).code)
        assertTrue(vm.codeDrafts.drafts.isEmpty())
        assertEquals(CodeDraftSaveState.Inactive, vm.codeDraftSaveState("a"))
        vm.retryCodeDrafts(); clock.at(10000)
        assertTrue(call { vm.flushCodeDrafts() })
        assertEquals("restored code", row(vm).code)
        val loaded = call { vm.loadQuestion("a")!! }
        assertNotSame(oldMirror, vm.questionDraft(loaded))
        assertEquals("restored code", vm.questionDraft(loaded).code.value)
    }

    @Test fun invalidRestoreKeepsCacheAndTheReliablySavedOriginalText() {
        val vm = create(); val q = call { vm.loadQuestion("a")!! }; val old = vm.questionDraft(q)
        vm.updateCodeDraft("a", "keep latest", "keep notes")
        vm.restoreUri(Uri.fromFile(file("{bad backup"))); settle(vm)
        assertSame(old, vm.questionDraft(q))
        assertEquals("keep latest", row(vm).code)
        assertEquals("keep notes", row(vm).notes)
        assertEquals("keep latest", vm.codeDrafts.drafts["a"]!!.code)
        assertTrue(vm.codeDraftSaveState("a") is CodeDraftSaveState.Saved)
        assertFalse(vm.notice == "备份已恢复")
        assertEquals(1, runBlocking { vm.repo.dao.allAttempts().size })
    }

    @Test fun resetFlushesLatestTextAndPreservesPersonalAndStickyContent() {
        val vm = create()
        vm.updateCodeDraft("a", "latest reset code", "latest reset notes")
        vm.resetProgress(); settle(vm)
        val p = row(vm)
        assertEquals(0, p.seen); assertEquals(0, p.repetitions); assertEquals(0, p.lapses)
        assertEquals("latest reset code", p.code); assertEquals("latest reset notes", p.notes)
        assertTrue(p.favorite); assertTrue(p.flagged)
        assertTrue(runBlocking { vm.repo.dao.allAttempts().isEmpty() })
        assertEquals("sticky text", runBlocking { vm.repo.dao.allStickyNotes().single().content })
        assertEquals("latest reset code", vm.codeDrafts.drafts["a"]!!.code)
        assertEquals(CodeDraftSaveState.Inactive, vm.codeDraftSaveState("a"))
    }

    @Test fun ordinaryNotesRemainExplicitAndSuccessNoticeRequiresACommittedVersion() {
        val vm = create(); val q = call { vm.loadQuestion("a")!! }; val mirror = vm.questionDraft(q)
        mirror.notes.value = "local not yet saved"
        clock.at(10000)
        assertEquals(CodeDraftSaveState.Inactive, vm.codeDraftSaveState("a"))
        assertEquals("old notes", row(vm).notes)
        vm.codeDrafts.persistence.beforeWrite = { error("injected") }
        vm.saveNotes("a", "old code", mirror.notes.value)
        waitFor { vm.codeDraftSaveState("a") is CodeDraftSaveState.Error }
        waitFor { vm.notice == "草稿保存失败，请重试" }
        assertFalse(vm.notice == "代码与笔记已保存")
        assertEquals("local not yet saved", mirror.notes.value)
        vm.codeDrafts.persistence.beforeWrite = {}
        vm.retryCodeDrafts("a")
        vm.saveNotes("a", "old code", mirror.notes.value)
        waitFor { vm.notice == "代码与笔记已保存" }
        assertEquals("local not yet saved", row(vm).notes)
    }

    @Test fun startupRestoreUsesTheSameDraftBoundaryAndGenerationRules() {
        val vm = create(error = true)
        vm.updateCodeDraft("a", "startup pending", "startup notes")
        val outcome = call { vm.restoreStartupBackup(Uri.fromFile(file(replacement())), true) }
        assertEquals(StartupRecoveryResult.Completed, outcome)
        waitFor { (vm.startupState as? StartupState.Error)?.attempt == 2 }
        assertEquals("restored code", row(vm).code)
        assertEquals(CodeDraftSaveState.Inactive, vm.codeDraftSaveState("a"))
        assertTrue(vm.codeDrafts.drafts.isEmpty())
        vm.retryCodeDrafts(); assertTrue(call { vm.flushCodeDrafts() })
        assertEquals("restored code", row(vm).code)
    }

    @Test fun failedStartupDraftFlushDoesNotReplaceDataOrStartANewAttempt() {
        val vm = create(error = true); val original = row(vm)
        vm.codeDrafts.persistence.beforeWrite = { error("injected") }
        vm.updateCodeDraft("a", "startup unsaved", "startup unsaved notes")
        assertTrue(call { vm.restoreStartupBackup(Uri.fromFile(file(replacement())), true) } is StartupRecoveryResult.Failed)
        assertEquals(1, (vm.startupState as StartupState.Error).attempt)
        assertEquals(original, row(vm))
        assertTrue(vm.codeDraftSaveState("a") is CodeDraftSaveState.Error)
        assertEquals("startup unsaved", vm.codeDrafts.drafts["a"]!!.code)
        assertFalse(vm.codeDraftEditingBlocked)
    }

    @Test fun clearingOwnerFlushesTheLastWindowBeforeClosingTheDatabase() {
        val vm = create()
        vm.updateCodeDraft("a", "clear final", "clear final notes")
        var mainContinues = false
        store.clear()
        Handler(Looper.getMainLooper()).post { mainContinues = true }
        waitFor { mainContinues }
        waitFor { vm.examResourcesClosed?.isCompleted == true }
        assertEquals("clear final", reopened().code)
        assertTrue(vm.codeDraftEditingBlocked)
    }

    @Test fun clearingOwnerWaitsForInFlightDraftIoWithoutBlockingMain() {
        val vm = create(); val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        vm.codeDrafts.persistence.beforeWrite = { entered.complete(Unit); gate.await() }
        vm.updateCodeDraft("a", "inflight final", "inflight notes")
        clock.at(300); waitFor { entered.isCompleted }
        var mainContinues = false
        store.clear(); Handler(Looper.getMainLooper()).post { mainContinues = true }
        waitFor { mainContinues }
        try { assertFalse(vm.examResourcesClosed?.isCompleted == true) }
        finally { gate.complete(Unit) }
        waitFor { vm.examResourcesClosed?.isCompleted == true }
        assertEquals("inflight final", reopened().code)
    }

    @Test fun clearingOwnerAfterFailureDoesNotRetryForeverOrClaimSaved() {
        val vm = create(); var writes = 0
        vm.codeDrafts.persistence.beforeWrite = { writes++; error("injected") }
        vm.updateCodeDraft("a", "failed clear", "failed clear notes")
        assertFalse(call { vm.flushCodeDrafts() })
        store.clear(); waitFor { vm.examResourcesClosed?.isCompleted == true }
        assertEquals(1, writes)
        assertTrue(vm.codeDraftSaveState("a") is CodeDraftSaveState.Error)
        assertEquals("old code", reopened().code)
    }

    @Test fun failedExportDestinationReleasesEditingWithoutReportingBackupSuccess() {
        val vm = create()
        vm.updateCodeDraft("a", "saved despite export failure", "saved notes")
        val missing = File(file().parentFile, "absent-${System.nanoTime()}/backup.json")
        vm.backupUri(Uri.fromFile(missing)); settle(vm)
        assertFalse(missing.exists())
        assertFalse(vm.notice == "备份完成（不包含 API Key）")
        assertEquals("saved despite export failure", row(vm).code)
        assertTrue(vm.codeDraftSaveState("a") is CodeDraftSaveState.Saved)
        assertFalse(vm.codeDraftEditingBlocked)
        vm.updateCodeDraft("a", "editing resumed", "resumed notes")
        assertTrue(call { vm.flushCodeDrafts() })
        assertEquals("editing resumed", row(vm).code)
    }
}