package com.activity.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes the activity report into the public Downloads collection via MediaStore.
 *
 * Only the MediaStore path is implemented: with `minSdk = 34` the legacy
 * `getExternalStoragePublicDirectory` branch (Android 9 and below) is unreachable.
 */
class ReportExporter(private val context: Context) {

    sealed interface Result {
        data class Success(val fileName: String) : Result
        data class Failure(val message: String) : Result
        data object Empty : Result
    }

    suspend fun export(records: List<ActivityRecord>): Result = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext Result.Empty

        val fileName = "activity_monitor_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.txt"

        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                // Hide the file until it is fully written.
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext Result.Failure("无法创建文件")

            try {
                resolver.openOutputStream(uri)?.use { os ->
                    os.bufferedWriter().use { writer ->
                        RecordsRepository.writeReport(writer, records, System.currentTimeMillis())
                    }
                } ?: return@withContext Result.Failure("无法写入文件")
            } finally {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            notifySaved(fileName)
            Result.Success(fileName)
        } catch (e: Exception) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun notifySaved(fileName: String) {
        createChannelIfNeeded()
        val nm = NotificationManagerCompat.from(context)
        // On API 33+ notifications are dropped without the runtime grant; don't pretend otherwise.
        if (!nm.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Saved to Downloads")
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            nm.notify(NOTIFICATION_ID.getAndIncrement(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted.
        }
    }

    private fun createChannelIfNeeded() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Activity Monitor",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Activity export notifications" }
        nm.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "activity_monitor_save"
        val NOTIFICATION_ID = AtomicInteger(1000)
    }
}
