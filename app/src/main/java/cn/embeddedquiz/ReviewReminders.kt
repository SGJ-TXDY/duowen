package cn.embeddedquiz

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object ReviewReminders {
    const val WORK_NAME = "daily-local-review"
    const val CHANNEL = "local-review"
    const val OPEN_REVIEW = "open_review"

    fun permitted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun schedule(context: Context, settings: LearningSettings, append: Boolean = false) {
        val manager = WorkManager.getInstance(context)
        if (!settings.reminderEnabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = ZonedDateTime.now()
        val next = LearningDates.nextReminder(now, settings.reminderMinute)
        val request =
            OneTimeWorkRequestBuilder<ReviewReminderWorker>()
                .setInitialDelay(
                    Duration.between(now, next).toMillis().coerceAtLeast(0),
                    TimeUnit.MILLISECONDS,
                )
                .addTag(WORK_NAME)
                .build()
        manager.enqueueUniqueWork(
            WORK_NAME,
            if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

class ReviewReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    @android.annotation.SuppressLint(
        "MissingPermission"
    ) // permitted() checks POST_NOTIFICATIONS; revocation is caught below.
    override suspend fun doWork(): Result {
        val db = QuizDatabase.open(applicationContext)
        try {
            val settings = db.dao().getSettings() ?: LearningSettings()
            if (!settings.reminderEnabled) return Result.success()
            val today = LocalDate.now().toEpochDay()
            val count = db.dao().dueCount(today)
            if (
                count > 0 &&
                    settings.lastNotifiedDay != today &&
                    ReviewReminders.permitted(applicationContext)
            ) {
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(
                        ReviewReminders.CHANNEL,
                        "本地复习提醒",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
                val intent =
                    Intent(applicationContext, MainActivity::class.java)
                        .putExtra(ReviewReminders.OPEN_REVIEW, true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pending =
                    PendingIntent.getActivity(
                        applicationContext,
                        40,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                val notification =
                    NotificationCompat.Builder(applicationContext, ReviewReminders.CHANNEL)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("今天还有 $count 道题待复习")
                        .setContentText("点此打开智能复习，稳步巩固记忆。")
                        .setContentIntent(pending)
                        .setAutoCancel(true)
                        .build()
                // Permission is checked immediately before posting; a concurrent revocation is
                // recoverable.
                try {
                    manager.notify(40, notification)
                    db.dao().markNotified(today)
                } catch (_: SecurityException) {}
            }
            val latest = db.dao().getSettings() ?: settings
            ReviewReminders.schedule(applicationContext, latest, append = true)
            return Result.success()
        } catch (e: Exception) {
            LocalErrorLog(applicationContext).record(LogEvent.REMINDER, e)
            return Result.retry()
        } finally {
            db.close()
        }
    }
}
