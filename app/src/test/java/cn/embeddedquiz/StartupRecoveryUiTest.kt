package cn.embeddedquiz

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** T3 startup screens on a real ViewModel: error page, diagnostics, export, retry and restore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StartupRecoveryUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: QuizViewModel
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val database get() = app.getDatabasePath("embeddedquiz.db")
    private val bank =
        """{"schemaVersion":1,"questions":[{"id":"su-a","stem":"启动恢复题","source":"UI test","kind":"SINGLE","reviewRequired":false,"options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"correct":["A"]}]}"""
    private val savedExam = """{"ids":["su-a"],"index":0,"deadline":9999999999999,"answers":{"su-a":["A"]}}"""
    private lateinit var backup: String
    private lateinit var originalFile: ByteArray
    private lateinit var oldAttempts: List<Attempt>
    private val temporary = mutableListOf<File>()
    private val picker = PickerRegistry()
    @Volatile private var failBank = true
    @Volatile private var bankGate: CompletableDeferred<Unit>? = null
    @Volatile private var bankCalls = 0

    @Before
    fun setup() {
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
                val repo = QuizRepository(db)
                repo.importBank(bank)
                repo.record(repo.question("su-a")!!, "A", setOf("A"), "old practice")
                backup = repo.backup()
                oldAttempts = repo.dao.allAttempts()
            } finally {
                db.close()
            }
        }
        originalFile = database.readBytes()
        prefs.edit().putInt("bankRevision", 4).putString("categoryMapHash", CategoryMap.fingerprint).commit()
    }

    @After
    fun cleanup() {
        bankGate?.complete(Unit)
        failBank = false
        if (::vm.isInitialized) {
            val old = vm
            ui.runOnUiThread { store.clear() }
            await { old.examResourcesClosed?.isCompleted == true }
        }
        app.deleteDatabase("embeddedquiz.db")
        prefs.edit().clear().commit()
        temporary.forEach { it.delete() }
    }

    /** Creates the ViewModel and installs the stage seam before its first suspension point. */
    private fun create(notification: Boolean = false) {
        ui.runOnUiThread {
            vm =
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
                    QuizViewModel::class.java]
            vm.beforeStartupStage = { stage ->
                if (stage == StartupStage.BANK) {
                    bankCalls++
                    if (failBank) throw IOException("injected startup failure")
                    bankGate?.await()
                }
            }
            if (notification) vm.requestNotificationReview()
        }
    }

    private fun show(fontScale: Float = 1f) {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = picker
        }
        ui.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalDensity provides Density(base.density, fontScale),
            ) {
                QuizApp(vm)
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        try {
            ui.waitUntil(20000) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Throwable) {
            throw AssertionError(
                "startup=${if (::vm.isInitialized) vm.startupState else null}; busy=${if (::vm.isInitialized) vm.startupRecoveryBusy else null}",
                e,
            )
        }
    }

    private fun error(): StartupState.Error {
        await { vm.startupState is StartupState.Error && !vm.startupRecoveryBusy }
        ui.waitForIdle()
        return vm.startupState as StartupState.Error
    }

    private fun page(text: String, substring: Boolean = false) =
        ui.onNode(hasText(text, substring = substring) and !hasAnyAncestor(isDialog())).performScrollTo()

    private fun dialog(text: String) = ui.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun file(text: String = ""): File =
        File.createTempFile("t3-ui-", ".data").also {
            temporary += it
            it.writeText(text)
        }

    private fun attempts() = runBlocking { vm.repo.dao.allAttempts() }

    @Test
    fun failureShowsTheErrorPageAndAnExplicitRetryKeepsNotificationAndExam() {
        prefs.edit().putString("exam", savedExam).commit()
        create(notification = true)
        show()
        val failed = error()
        assertEquals("PREPARATION_FAILED", failed.code)
        assertFalse(vm.ready)
        ui.onNode(hasText("应用未能启动") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        ui.onNode(hasText(failed.message) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
        ui.onNodeWithText("错误代码：PREPARATION_FAILED · 阶段：准备离线题库 · 第 1 次启动").assertExists()
        // Nothing of the app itself: no tab bar, no pages, no exam timer that could submit.
        ui.onNodeWithContentDescription("导航到首页").assertDoesNotExist()
        ui.onNodeWithText("正在准备离线题库").assertDoesNotExist()
        ui.onNode(hasContentDescription("剩余时间", substring = true)).assertDoesNotExist()

        page("查看诊断").performClick()
        await { ui.onAllNodesWithText("diagnosticVersion", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasText("PREPARATION_FAILED", substring = true) and hasText("attempt", substring = true)).assertExists()
        ui.onNodeWithText("injected startup failure", substring = true).assertDoesNotExist()
        page("收起诊断").performClick()
        ui.onNodeWithText("diagnosticVersion", substring = true).assertDoesNotExist()

        // The second attempt is held in its bank stage; a Loading page offers no retry to repeat.
        failBank = false
        bankGate = CompletableDeferred()
        page("重试启动").performClick()
        await { (vm.startupState as? StartupState.Loading)?.stage == StartupStage.BANK }
        ui.onNodeWithText("正在准备离线题库").assertExists()
        ui.onNodeWithText("重试启动").assertDoesNotExist()
        ui.runOnIdle { repeat(5) { vm.retryInitialization() } }
        assertEquals(2, bankCalls)
        bankGate!!.complete(Unit)
        await { vm.startupState == StartupState.Ready(2, true) }
        assertEquals(2, bankCalls)
        assertEquals(Page.REVIEW, vm.page)
        ui.onNodeWithText("智能复习").assertExists()
        ui.onNodeWithContentDescription("导航到首页").assertExists()
        assertEquals(listOf("su-a"), vm.queue)
        assertEquals(mapOf("su-a" to setOf("A")), vm.examAnswers)
        assertEquals(9999999999999, vm.examDeadline)
        assertEquals(oldAttempts, attempts())
    }

    @Test
    fun failedRetryStaysOnTheErrorPageForTheNewAttemptWithDataUnchanged() {
        create()
        show()
        error()
        page("重试启动").performClick()
        val second = error()
        assertEquals(2, second.attempt)
        ui.onNodeWithText("错误代码：PREPARATION_FAILED · 阶段：准备离线题库 · 第 2 次启动").assertExists()
        assertEquals(oldAttempts, attempts())
    }

    @Test
    fun diagnosticsExportWarnsAboutUserDataAndHandlesCancelSuccessAndFailure() {
        create()
        show()
        error()
        page("诊断包包含题库、学习记录与考试缓存，请自行保管；这是诊断包，不能作为学习备份直接导入。", substring = true)
            .assertExists()

        picker.next = null
        page("导出诊断包").performClick()
        assertEquals(1, picker.launched.size)
        assertTrue((picker.launched.single() as String).endsWith(".zip"))
        ui.waitForIdle()
        assertFalse(vm.startupRecoveryBusy)
        ui.onNodeWithText("诊断包已导出", substring = true).assertDoesNotExist()

        val output = file()
        picker.next = Uri.fromFile(output)
        page("导出诊断包").performClick()
        await { ui.onAllNodesWithText("诊断包已导出", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ZipFile(output).use { zip -> assertNotNull(zip.getEntry("manifest.json")) }

        // A destination that cannot be written: the failure is shown and export stays available.
        picker.next = Uri.fromFile(File(output.parentFile, "missing-${System.nanoTime()}/out.zip"))
        page("导出诊断包").performClick()
        await { ui.onAllNodesWithText("目标文件可能不完整", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("诊断包已导出", substring = true).assertDoesNotExist()
        page("导出诊断包").assertIsEnabled()
        // Only the user's own picks were launched; nothing is sent anywhere else.
        assertEquals(3, picker.launched.size)
        assertTrue(vm.startupState is StartupState.Error)
        assertEquals(oldAttempts, attempts())
    }

    @Test
    fun restoreNeedsAPickAndAConfirmationAndThenFollowsTheNewStartup() {
        create()
        show()
        error()
        picker.next = null
        page("选择学习备份").performClick()
        ui.onNodeWithText("用备份替换本机数据？").assertDoesNotExist()

        picker.next = Uri.fromFile(file(backup))
        page("选择学习备份").performClick()
        dialog("用备份替换本机数据？").assertExists()
        ui.onNodeWithText("题库、学习历史和复习安排", substring = true).assertExists()
        dialog("取消").performClick()
        ui.onNodeWithText("用备份替换本机数据？").assertDoesNotExist()
        ui.waitForIdle()
        assertEquals(1, (vm.startupState as StartupState.Error).attempt)

        // Confirmed: the replacement is committed, and the page follows the new attempt, which fails.
        page("选择学习备份").performClick()
        dialog("确认替换").performClick()
        val next = error()
        assertEquals(2, next.attempt)
        ui.onNodeWithText("第 2 次启动", substring = true).assertExists()
        ui.onNodeWithText("备份已写入", substring = true).assertDoesNotExist()
        assertEquals(oldAttempts.map { it.copy(id = 0) }, attempts().map { it.copy(id = 0) })

        failBank = false
        page("重试启动").performClick()
        await { vm.startupState is StartupState.Ready }
        ui.onNodeWithContentDescription("导航到首页").assertExists()
    }

    @Test
    fun activeExamMakesTheBackendRejectTheRestoreAndThePageSaysSo() {
        prefs.edit().putString("exam", savedExam).commit()
        create()
        show()
        error()
        picker.next = Uri.fromFile(file(backup))
        page("选择学习备份").performClick()
        dialog("确认替换").performClick()
        await { ui.onAllNodesWithText("请先结束当前考试，再恢复备份").fetchSemanticsNodes().isNotEmpty() }
        val state = vm.startupState as StartupState.Error
        assertEquals(1, state.attempt)
        assertEquals(oldAttempts, attempts())
        assertEquals(listOf("su-a"), DurableExamSnapshot.decode(requireNotNull(prefs.getString("exam", null))).ids)
    }

    @Test
    fun workInProgressDisablesEveryActionAndRetryUntilItEnds() {
        create()
        show()
        error()
        val release = CountDownLatch(1)
        val blocked = Uri.parse("content://cn.embeddedquiz.test/blocked-backup.json")
        shadowOf(app.contentResolver).registerInputStream(blocked, GatedStream(backup.toByteArray(), release))
        try {
            picker.next = blocked
            page("选择学习备份").performClick()
            dialog("确认替换").performClick()
            await { vm.startupRecoveryBusy }
            page("正在处理，请稍候…").assertExists()
            page("重试启动").assertIsNotEnabled()
            page("导出诊断包").assertIsNotEnabled()
            page("选择学习备份").assertIsNotEnabled()
            page("重试启动").performClick()
            ui.runOnIdle { repeat(5) { vm.retryInitialization() } }
            assertEquals(1, (vm.startupState as StartupState.Error).attempt)
            assertEquals(1, bankCalls)
        } finally {
            release.countDown()
        }
        val next = error()
        assertEquals(2, next.attempt)
        assertEquals(2, bankCalls)
    }

    @Test
    fun unusableDatabaseOffersNoRestoreButStillExportsAndRetriesAfterTheFaultIsGone() {
        val broken = ByteArray(1024) { (it % 239).toByte() }
        database.writeBytes(broken)
        create()
        show()
        val failed = error()
        assertEquals("DATABASE_UNAVAILABLE", failed.code)
        assertFalse(failed.canRestoreBackup)
        page("数据库尚不可安全使用，不能恢复备份。请先导出诊断包保留原库，再重试启动。").assertExists()
        ui.onNodeWithText("选择学习备份").assertDoesNotExist()

        val output = file()
        picker.next = Uri.fromFile(output)
        page("导出诊断包").performClick()
        await { ui.onAllNodesWithText("诊断包已导出", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertArrayEquals(broken, database.readBytes())

        page("重试启动").performClick()
        assertEquals(2, error().attempt)
        assertArrayEquals(broken, database.readBytes())

        database.writeBytes(originalFile)
        failBank = false
        page("重试启动").performClick()
        await { vm.startupState is StartupState.Ready }
        assertEquals(oldAttempts, attempts())
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-night")
    fun errorPageIsReachableOnASmallDarkScreenWithLargeText() {
        create()
        show(fontScale = 1.6f)
        val failed = error()
        page("应用未能启动").assertIsDisplayed()
        page(failed.message).assertIsDisplayed()
        page("重试启动").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        page("查看诊断").assertIsDisplayed().performClick()
        await { ui.onAllNodesWithText("diagnosticVersion", substring = true).fetchSemanticsNodes().isNotEmpty() }
        page("diagnosticVersion", substring = true).assertIsDisplayed()
        page("导出诊断包").assertIsDisplayed().assertIsEnabled()
        page("选择学习备份").assertIsDisplayed().assertIsEnabled()
    }
}

/** Answers each picker launch with [next] and records what was asked for. */
private class PickerRegistry : ActivityResultRegistry() {
    var next: Uri? = null
    val launched = mutableListOf<Any?>()

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        launched += input
        dispatchResult(requestCode, next)
    }
}

/** Holds the restore's read on IO until released, so the work-in-progress state can be observed. */
private class GatedStream(bytes: ByteArray, private val release: CountDownLatch) : InputStream() {
    private val data = bytes.inputStream()

    private fun gate() {
        check(release.await(20, TimeUnit.SECONDS)) { "gated read was never released" }
    }

    override fun read(): Int {
        gate()
        return data.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        gate()
        return data.read(b, off, len)
    }
}
