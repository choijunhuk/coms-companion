package kr.coms.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("reminderId") ?: return Result.failure()
        val dao = AppDatabase.get(applicationContext).dao()
        val scheduledAt = inputData.getLong("scheduledAt", 0)
        if (scheduledAt > 0 && System.currentTimeMillis() - scheduledAt > TimeUnit.HOURS.toMillis(24)) {
            dao.updateReminder(id, "알림 시각 지남 · 앱에서 확인 필요")
            return Result.success()
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            dao.updateReminder(id, "알림 권한 거부 · 앱에서 확인 필요")
            return Result.success()
        }
        manager.createNotificationChannel(NotificationChannel("operator-reminder", "연락 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val openIntent = Intent(applicationContext, MainActivity::class.java)
            .putExtra("draftId", inputData.getString("draftId"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(applicationContext, id.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(applicationContext, "operator-reminder")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("COM’s Companion · 연락 확인")
            .setContentText("예정한 연락 내용을 앱에서 확인해 주세요")
            .setContentIntent(open).setAutoCancel(true).build()
        try {
            manager.notify(id.hashCode(), notification)
            dao.updateReminder(id, "알림 표시 · 실제 발송 없음")
        } catch (_: SecurityException) {
            dao.updateReminder(id, "알림 권한 거부 · 앱에서 확인 필요")
        }
        return Result.success()
    }
}

fun scheduleReminder(context: Context, reminder: Reminder) {
    val work = OneTimeWorkRequestBuilder<ReminderWorker>()
        .setInitialDelay((reminder.atMillis - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        .setInputData(workDataOf("reminderId" to reminder.id, "draftId" to reminder.draftId, "scheduledAt" to reminder.atMillis)).build()
    WorkManager.getInstance(context).enqueueUniqueWork(reminder.id, ExistingWorkPolicy.REPLACE, work)
}
