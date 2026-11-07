package cn.embeddedquiz

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class StartupRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val store = ViewModelStore()
    private var model: QuizViewModel? = null
    private lateinit var backup: String
    private lateinit var originalFile: ByteArray
    private lateinit var oldAttempts: List<Attempt>
    private lateinit var oldProgress: List<Progress>
    private val temporary = mutableListOf<File>()
    private val bank = """{"schemaVersion":1,"questions":[{"id":"a","stem":"old question","source":"T3","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"yes"},{"key":"B","text":"no"}],"correct":["A"]}]}"""
    private val savedExam = """{"ids":["a"],"index":0,"deadline":9999999999999,"answers":{"a":["A"]}}"""

    @Before fun setup() = runBlocking {
        app.deleteDatabase("embeddedquiz.db"); prefs.edit().clear().commit()
        val db = QuizDatabase.open(app)
        try {
            val repo = QuizRepository(db); repo.importBank(bank)
            repo.record(repo.question("a")!!, "A", setOf("A"), "old practice")
            repo.dao.putStickyNote(StickyNote("note", "keep", "original note", 1, 2))
            repo.dao.putSettings(LearningSettings(theme = "DARK"))
            repo.dao.addExamTermination(ExamTermination("old-terminal", "ABANDONED", null, null, null, null, null, 1, 1, null))
            backup = repo.backup(); oldAttempts = repo.dao.allAttempts(); oldProgress = repo.dao.allProgress()
        } finally { db.close() }
        originalFile = app.getDatabasePath("embeddedquiz.db").readBytes()
        prefs.edit().putInt("bankRevision", 4).putString("categoryMapHash", CategoryMap.fingerprint)
            .putString("credentialSentinel", "DO_NOT_EXPORT_PRIVATE_PREFS").commit()
        Unit
    }
    @After fun cleanup() {
        model?.let { vm -> store.clear(); waitFor { vm.examResourcesClosed?.isCompleted == true } }
        app.deleteDatabase("embeddedquiz.db"); prefs.edit().clear().commit()
        temporary.forEach { it.delete() }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); LockSupport.parkNanos(1_000_000)
        }
        assertTrue("startup=${model?.startupState}; recovery=${model?.startupRecoveryBusy}", condition())
    }
    private fun create(): QuizViewModel {
        val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[QuizViewModel::class.java]
        model = vm; return vm
    }
    private fun <T> call(action: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        return try {
            val result = scope.async(start = CoroutineStart.UNDISPATCHED) { action() }
            waitFor { result.isCompleted }; runBlocking { result.await() }
        } finally { scope.cancel() }
    }
    private fun error(vm: QuizViewModel): StartupState.Error {
        waitFor { vm.startupState is StartupState.Error }
        return vm.startupState as StartupState.Error
    }
    private fun file(text: String = ""): File = File.createTempFile("t3-", ".data").also {
        temporary += it; it.writeText(text)
    }
    private fun unchanged(vm: QuizViewModel) = runBlocking {
        assertEquals(oldAttempts, vm.repo.dao.allAttempts()); assertEquals(oldProgress, vm.repo.dao.allProgress())
        assertEquals("original note", vm.repo.dao.allStickyNotes().single().content)
        assertNotNull(vm.repo.dao.examTermination("old-terminal"))
    }

    @Test fun retryIsSingleFlightAndRetainsNotificationExamAndOldOutcome() {
        prefs.edit().putString("exam", savedExam).commit()
        val vm = create()
        var fail = true; var calls = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        vm.beforeStartupStage = { stage ->
            if (stage == StartupStage.BANK) {
                calls++
                if (fail) throw IOException("PRIVATE_EXCEPTION_MESSAGE")
                entered.complete(Unit); release.await()
            }
        }
        vm.requestNotificationReview()
        val first = error(vm)
        assertEquals(StartupStage.BANK, first.stage)
        assertFalse(vm.ready)
        assertEquals("SYSTEM", vm.learning.settings.theme)
        assertTrue(vm.notesState.stickyNotes.isEmpty())
        val oldResult = call { vm.awaitInitialization() }
        assertTrue(oldResult is InitializationOutcome.Failed)
        val diagnostics = call { vm.readStartupDiagnostics() }
        assertFalse(diagnostics.contains("PRIVATE_EXCEPTION_MESSAGE"))
        assertFalse(diagnostics.contains("DO_NOT_EXPORT_PRIVATE_PREFS"))
        assertEquals(1, JSONObject(diagnostics).getInt("attempt"))
        unchanged(vm)
        fail = false
        repeat(20) { vm.retryInitialization() }
        waitFor { entered.isCompleted }
        assertEquals(2, calls)
        assertEquals(2, (vm.startupState as StartupState.Loading).attempt)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val cancelled = scope.async { vm.awaitInitialization() }
            val waiting = scope.async { vm.awaitInitialization() }
            cancelled.cancel()
            release.complete(Unit)
            waitFor { waiting.isCompleted && vm.ready && vm.learning.settings.theme == "DARK" && vm.notesState.stickyNotes.size == 1 }
            assertEquals(InitializationOutcome.Ready(true), runBlocking { waiting.await() })
            assertTrue(cancelled.isCancelled)
        } finally { release.complete(Unit); scope.cancel() }
        assertEquals(StartupState.Ready(2, true), vm.startupState)
        assertEquals(Page.REVIEW, vm.page)
        assertEquals(listOf("a"), vm.queue)
        assertEquals(mapOf("a" to setOf("A")), vm.examAnswers)
        assertEquals(9999999999999, vm.examDeadline)
        assertTrue(call { vm.flushExamSave() })
        val children = vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive }
        repeat(20) { vm.retryInitialization() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(children, vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive })
        assertEquals(2, calls)
        assertTrue(oldResult is InitializationOutcome.Failed)
        unchanged(vm)
    }

    @Test fun missingDependencyFailsBeforeReadyOrSubscriptions() {
        for (missingColumn in listOf(false, true)) {
            val raw = app.openOrCreateDatabase("embeddedquiz.db", 0, null)
            try {
                raw.execSQL("ALTER TABLE sticky_notes RENAME TO preserved_notes")
                if (missingColumn) raw.execSQL("CREATE TABLE sticky_notes (id TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)")
            } finally { raw.close() }
            val vm = create()
            try {
                waitFor { vm.ready || vm.startupState is StartupState.Error }
                assertTrue("Missing dependency must not publish Ready: ${vm.startupState}", vm.startupState is StartupState.Error)
                val error = vm.startupState as StartupState.Error
                assertEquals(StartupStage.DATABASE, error.stage)
                assertFalse(error.canRestoreBackup)
                assertFalse(vm.ready); assertTrue(vm.notesState.stickyNotes.isEmpty())
                assertEquals(oldAttempts, runBlocking { vm.repo.dao.allAttempts() })
                assertEquals(oldProgress, runBlocking { vm.repo.dao.allProgress() })
                vm.db.openHelper.readableDatabase.query("SELECT content FROM preserved_notes").use {
                    assertTrue(it.moveToFirst()); assertEquals("original note", it.getString(0))
                }
            } finally {
                store.clear(); waitFor { vm.examResourcesClosed?.isCompleted == true }
                val repair = app.openOrCreateDatabase("embeddedquiz.db", 0, null)
                try {
                    if (missingColumn) repair.execSQL("DROP TABLE sticky_notes")
                    repair.execSQL("ALTER TABLE preserved_notes RENAME TO sticky_notes")
                } finally { repair.close() }
            }
        }
    }
    @Test fun versionFourCanStartWithLearningDataUnchanged() {
        val raw = app.openOrCreateDatabase("embeddedquiz.db", 0, null)
        try {
            raw.execSQL("DROP TABLE exam_terminations")
            for (table in listOf("ai_response_cache", "ai_commands", "ai_runs", "ai_messages", "ai_sessions")) {
                raw.execSQL("DROP TABLE $table")
            }
            val schema = JSONObject(File("schemas/cn.embeddedquiz.QuizDatabase/4.json").readText()).getJSONObject("database")
            val entities = schema.getJSONArray("entities")
            val stickySchema = (0 until entities.length()).map(entities::getJSONObject)
                .single { it.getString("tableName") == "sticky_notes" }
            raw.execSQL("ALTER TABLE sticky_notes RENAME TO current_notes")
            raw.execSQL(stickySchema.getString("createSql").replace("\u0024{TABLE_NAME}", "sticky_notes"))
            raw.execSQL("INSERT INTO sticky_notes SELECT id,title,content,created,updated FROM current_notes")
            raw.execSQL("DROP TABLE current_notes")
            raw.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42", arrayOf(schema.getString("identityHash")))
            raw.version = 4
        } finally { raw.close() }
        val vm = create(); waitFor { vm.ready }
        assertEquals(StartupState.Ready(1, false), vm.startupState)
        assertEquals(7, vm.db.openHelper.readableDatabase.version)
        assertEquals(oldAttempts, runBlocking { vm.repo.dao.allAttempts() })
        assertEquals(oldProgress, runBlocking { vm.repo.dao.allProgress() })
        assertEquals("original note", runBlocking { vm.repo.dao.allStickyNotes().single().content })
    }

    @Test fun clearingRetryCancelsWaitersAndNextOwnerCanStart() {
        prefs.edit().putString("exam", savedExam).commit()
        val vm = create()
        vm.beforeStartupStage = { if (it == StartupStage.BANK) throw IOException("first failure") }
        error(vm)
        val entered = CompletableDeferred<Unit>()
        vm.beforeStartupStage = { if (it == StartupStage.EXAM) { entered.complete(Unit); awaitCancellation() } }
        vm.retryInitialization(); waitFor { entered.isCompleted }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val waiting = scope.async(start = CoroutineStart.UNDISPATCHED) { vm.awaitInitialization() }
            store.clear(); waitFor { waiting.isCompleted && vm.examResourcesClosed?.isCompleted == true }
            assertTrue(waiting.isCancelled); assertFalse(vm.ready)
            assertEquals(savedExam, prefs.getString("exam", null))
        } finally { scope.cancel() }
        val next = create(); waitFor { next.ready }
        assertTrue(next.examState.active()); assertEquals(listOf("a"), next.queue)
        unchanged(next)
    }

    @Test fun recoveryIsSingleFlightAndDoesNotImplicitlyRetryStartup() {
        val vm = create()
        vm.beforeStartupStage = { if (it == StartupStage.BANK) throw IOException("temporary") }
        error(vm)
        val firstFile = file(); val secondFile = file()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val first = scope.async(start = CoroutineStart.UNDISPATCHED) { vm.exportStartupDiagnostics(Uri.fromFile(firstFile)) }
            assertTrue(vm.startupRecoveryBusy)
            val second = scope.async(start = CoroutineStart.UNDISPATCHED) { vm.exportStartupDiagnostics(Uri.fromFile(secondFile)) }
            repeat(10) { vm.retryInitialization() }
            waitFor { first.isCompleted && second.isCompleted }
            assertEquals(StartupRecoveryResult.Completed, runBlocking { first.await() })
            assertTrue(runBlocking { second.await() } is StartupRecoveryResult.Rejected)
            assertEquals(0, secondFile.length())
            assertEquals(1, (vm.startupState as StartupState.Error).attempt)
            assertFalse(vm.startupRecoveryBusy)
            unchanged(vm)
        } finally { scope.cancel() }
    }
    @Test fun corruptFileIsPreservedAndDiagnosticsNeedNoRoom() {
        val broken = ByteArray(1024) { (it % 239).toByte() }
        val source = app.getDatabasePath("embeddedquiz.db"); source.writeBytes(broken)
        val vm = create(); val failed = error(vm)
        assertEquals(StartupStage.DATABASE, failed.stage)
        assertFalse(failed.canRestoreBackup); assertFalse(vm.db.isOpen)
        assertArrayEquals(broken, source.readBytes())
        val diagnostic = call { vm.readStartupDiagnostics() }
        assertTrue(JSONObject(diagnostic).getBoolean("databaseExists"))
        val output = file()
        assertEquals(StartupRecoveryResult.Completed, call { vm.exportStartupDiagnostics(Uri.fromFile(output)) })
        ZipFile(output).use { zip ->
            assertArrayEquals(broken, zip.getInputStream(zip.getEntry("database/main.sqlite")).readBytes())
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()
            assertTrue(JSONObject(manifest).getBoolean("diagnosticOnly"))
            assertFalse(manifest.contains("DO_NOT_EXPORT_PRIVATE_PREFS"))
            assertTrue(zip.entries().asSequence().none { it.name.contains("settings") || it.name.contains("key", true) })
        }
        assertFalse(vm.db.isOpen); assertArrayEquals(broken, source.readBytes())
        val input = file(backup)
        val rejected = call { vm.restoreStartupBackup(Uri.fromFile(input), true) }
        assertTrue(rejected is StartupRecoveryResult.Rejected)
        assertArrayEquals(broken, source.readBytes())
        // The external fault is removed; retry, not a new ViewModel, must now open the same preserved path.
        source.writeBytes(originalFile)
        vm.retryInitialization(); waitFor { vm.ready }
        assertEquals(StartupState.Ready(2, false), vm.startupState)
        unchanged(vm)
    }

    @Test fun alreadyCurrentInvalidRowFailsWithoutBundleOverwriteAndCanRestore() {
        val raw = app.openOrCreateDatabase("embeddedquiz.db", 0, null)
        try { raw.execSQL("UPDATE questions SET json=? WHERE id='a'", arrayOf("{bad historical JSON")) }
        finally { raw.close() }
        val vm = create(); val failed = error(vm)
        assertEquals(StartupStage.VALIDATION, failed.stage)
        assertEquals("STORED_DATA_INVALID", failed.code); assertTrue(failed.canRestoreBackup)
        assertEquals("{bad historical JSON", runBlocking { vm.repo.dao.question("a")!!.json })
        unchanged(vm)
        val input = file(backup)
        assertTrue(call { vm.restoreStartupBackup(Uri.fromFile(input)) } is StartupRecoveryResult.Rejected)
        assertEquals("{bad historical JSON", runBlocking { vm.repo.dao.question("a")!!.json })
        vm.requestNotificationReview()
        assertEquals(StartupRecoveryResult.Completed, call { vm.restoreStartupBackup(Uri.fromFile(input), true) })
        waitFor { vm.ready && vm.questions.size == 1 }
        assertEquals(StartupState.Ready(2, false), vm.startupState)
        assertEquals(Page.REVIEW, vm.page); assertEquals("old question", call { vm.repo.question("a") }!!.stem)
        // Backup v3 deliberately recreates surrogate Attempt IDs; compare every exported field.
        assertEquals(oldAttempts.map { it.copy(id = 0) }, runBlocking { vm.repo.dao.allAttempts() }.map { it.copy(id = 0) })
        assertEquals(oldProgress, runBlocking { vm.repo.dao.allProgress() })
        assertNotNull(runBlocking { vm.repo.dao.examTermination("old-terminal") })
    }

    @Test fun invalidBackupAndActiveExamNeverClearOriginalData() {
        prefs.edit().putString("exam", savedExam).commit()
        val vm = create()
        vm.beforeStartupStage = { if (it == StartupStage.BANK) throw IOException("temporary") }
        error(vm)
        val input = file(backup)
        assertTrue(call { vm.restoreStartupBackup(Uri.fromFile(input), true) } is StartupRecoveryResult.Rejected)
        val guarded = DurableExamSnapshot.decode(requireNotNull(prefs.getString("exam", null)))
        assertEquals(listOf("a"), guarded.ids)
        assertEquals(mapOf("a" to setOf("A")), guarded.answers)
        assertEquals(9999999999999, guarded.deadline)
        assertTrue(vm.examState.active()); unchanged(vm)
        vm.beforeStartupStage = {}; vm.retryInitialization(); waitFor { vm.ready }
        assertTrue(vm.examState.active()); unchanged(vm)
    }

    @Test fun invalidBackupRollbackKeepsCurrentRowsAndLedger() {
        val vm = create()
        vm.beforeStartupStage = { if (it == StartupStage.BANK) throw IOException("temporary") }
        error(vm)
        val input = file("not a valid backup")
        assertTrue(call { vm.restoreStartupBackup(Uri.fromFile(input), true) } is StartupRecoveryResult.Failed)
        unchanged(vm); assertTrue(vm.startupState is StartupState.Error)
        vm.beforeStartupStage = {}; vm.retryInitialization(); waitFor { vm.ready }
        unchanged(vm)
    }

    @Test fun diagnosticDestinationCannotOverwritePrivateSource() {
        val vm = create()
        vm.beforeStartupStage = { if (it == StartupStage.BANK) throw IOException("temporary") }
        error(vm)
        val source = app.getDatabasePath("embeddedquiz.db")
        val original = source.readBytes()
        assertTrue(call { vm.exportStartupDiagnostics(Uri.fromFile(source)) } is StartupRecoveryResult.Failed)
        assertArrayEquals(original, source.readBytes()); unchanged(vm)
    }

    @Test fun partialExamLookupFailureCanRetryTheReservedSession() {
        prefs.edit().putString("exam", savedExam).commit()
        val vm = create()
        var inject = true
        vm.beforeStartupStage = {
            if (it == StartupStage.EXAM && inject) {
                inject = false
                vm.db.openHelper.writableDatabase.execSQL("DROP TABLE exam_terminations")
            }
        }
        assertEquals(StartupStage.EXAM, error(vm).stage)
        assertTrue(vm.examState.active()); assertFalse(vm.ready)
        // This fixture's dropped ledger is re-created to remove the injected read fault.
        ExamMigration.migrate(vm.db.openHelper.writableDatabase)
        vm.beforeStartupStage = {}; vm.requestNotificationReview(); vm.retryInitialization()
        waitFor { vm.ready }
        assertEquals(StartupState.Ready(2, true), vm.startupState)
        assertEquals(Page.REVIEW, vm.page); assertEquals(listOf("a"), vm.queue)
        assertEquals(oldAttempts, runBlocking { vm.repo.dao.allAttempts() })
    }

    @Test fun unreadableExamBlocksReplacementAndSurvivesRepeatedRetry() {
        prefs.edit().putString("exam", "{broken exam").commit()
        val vm = create(); assertEquals(StartupStage.EXAM, error(vm).stage)
        val input = file(backup)
        assertTrue(call { vm.restoreStartupBackup(Uri.fromFile(input), true) } is StartupRecoveryResult.Rejected)
        repeat(3) { index ->
            vm.retryInitialization(); waitFor { (vm.startupState as? StartupState.Error)?.attempt == index + 2 }
        }
        assertEquals("{broken exam", prefs.getString("exam", null)); assertTrue(vm.examState.active())
        unchanged(vm)
    }
}