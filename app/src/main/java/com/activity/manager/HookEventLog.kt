package com.activity.manager

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Append-only writer used from inside hooked app processes.
 *
 * Each hooked app writes to **its own** external files directory
 * (`Android/data/<that app's package>/files/activity_monitor/events.log`). An app may
 * always write there; writing into the *module's* directory is what Android 11+ forbids.
 *
 * The hook runs on the target app's main thread during Activity startup, so writes are
 * cheap, best-effort and never throw: a failed write must not break the host app.
 */
class HookEventLog(context: Context) {

    private val logFile: File? = context.getExternalFilesDir(null)?.let { filesDir ->
        File(File(filesDir, HookEventCodec.DIRECTORY_NAME), HookEventCodec.FILE_NAME)
    }

    /**
     * Appends one event. Safe to call from any thread.
     *
     * @return true when the line was written; false when the host blocks external storage,
     * in which case the caller should simply drop the event.
     */
    fun append(event: HookEvent): Boolean = synchronized(this) {
        val target = logFile ?: return false
        try {
            val parent = target.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false

            // Mode "append" keeps the file small in memory terms and is atomic enough for
            // single-line writes from one process.
            FileOutputStream(target, true).use { out ->
                out.write((HookEventCodec.encode(event) + "\n").toByteArray())
                out.flush()
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
