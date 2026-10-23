package cn.embeddedquiz

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReviewReminderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun initialize() {
        context.deleteDatabase("embeddedquiz.db")
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
    }

    @Test
    fun onlyDueQuestionsNotifyAndTapOpensReview() = runBlocking {
        initialize()
        val db = QuizDatabase.open(context)
        try {
            val repo = QuizRepository(db)
            repo.importBank(
                """{"schemaVersion":1,"questions":[{"id":"remind","stem":"提醒测试","kind":"SHORT","source":"测试"}]}"""
            )
            repo.dao.putSettings(LearningSettings(reminderEnabled = true))
            val manager = context.getSystemService(NotificationManager::class.java)
            val worker = TestListenableWorkerBuilder<ReviewReminderWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            repo.update("remind") { it.copy(seen = 1, due = LocalDate.now().toEpochDay()) }
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            val notification = shadowOf(manager).allNotifications.single()
            assertTrue(
                shadowOf(notification.contentIntent)
                    .savedIntent
                    .getBooleanExtra(ReviewReminders.OPEN_REVIEW, false)
            )
            assertEquals(LocalDate.now().toEpochDay(), repo.dao.getSettings()!!.lastNotifiedDay)
        } finally {
            db.close()
            context.deleteDatabase("embeddedquiz.db")
        }
    }

    @Test
    @Config(sdk = [33])
    fun deniedNotificationPermissionDoesNotNotifyOrResetAskedFlag() = runBlocking {
        initialize()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val db = QuizDatabase.open(context)
        try {
            val repo = QuizRepository(db)
            repo.importBank(
                """{"schemaVersion":1,"questions":[{"id":"remind","stem":"提醒测试","kind":"SHORT","source":"测试"}]}"""
            )
            repo.dao.putSettings(
                LearningSettings(reminderEnabled = true, notificationPermissionAsked = true)
            )
            repo.update("remind") { it.copy(seen = 1, due = LocalDate.now().toEpochDay()) }
            assertFalse(ReviewReminders.permitted(context))
            val worker = TestListenableWorkerBuilder<ReviewReminderWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertTrue(
                shadowOf(context.getSystemService(NotificationManager::class.java))
                    .allNotifications
                    .isEmpty()
            )
            assertTrue(repo.dao.getSettings()!!.notificationPermissionAsked)
        } finally {
            db.close()
            context.deleteDatabase("embeddedquiz.db")
        }
    }

    @Test
    fun mergedManifestDoesNotRequestExtraWorkManagerOrAlarmPermissions() {
        val requested =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .toSet()
        val forbidden =
            setOf(
                "android.permission.WAKE_LOCK",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.SCHEDULE_EXACT_ALARM",
                "android.permission.USE_EXACT_ALARM",
            )
        assertTrue((requested intersect forbidden).isEmpty())
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in requested)
    }

    @Test
    fun notificationReviewEntryWinsAfterUnfinishedExamIsRestored() = runBlocking {
        initialize()
        val db = QuizDatabase.open(context)
        QuizRepository(db)
            .importBank(
                """{"schemaVersion":1,"questions":[{"id":"remind","stem":"提醒测试","kind":"SHORT","source":"测试"}]}"""
            )
        db.dao().putSettings(LearningSettings(theme = "DARK"))
        val prefs = context.getSharedPreferences("settings", 0)
        val saved =
            """{"ids":["remind"],"index":0,"deadline":${System.currentTimeMillis()+60000},"answers":{}}"""
        prefs.writeDurably {
            clear()
            putInt("bankRevision", 4)
            putString("exam", saved)
        }
        val snapshot = ExamStateHolder(prefs, QuizRepository(db)).restore()
        assertEquals("保存的考试快照必须可恢复", listOf("remind"), snapshot?.ids)
        db.close()
        val intent =
            Intent(context, MainActivity::class.java).putExtra(ReviewReminders.OPEN_REVIEW, true)
        val controller =
            Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume()
        try {
            val vm = ViewModelProvider(controller.get())[QuizViewModel::class.java]
            repeat(120) {
                shadowOf(Looper.getMainLooper()).idle()
                if (!vm.ready || vm.learning.settings.theme != "DARK") Thread.sleep(25)
            }
            assertTrue(vm.notice, vm.ready)
            controller.visible()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Page.REVIEW, vm.page)
            assertEquals(
                "考试快照：${prefs.getString("exam",null)}；提示：${vm.notice}",
                listOf("remind"),
                vm.queue,
            )
            assertEquals("模拟考试", vm.mode)
            assertFalse(
                WindowCompat.getInsetsController(
                        controller.get().window,
                        controller.get().window.decorView,
                    )
                    .isAppearanceLightStatusBars
            )
        } finally {
            controller.pause().stop().destroy()
            prefs.writeDurably { remove("exam") }
            context.deleteDatabase("embeddedquiz.db")
        }
    }
}
