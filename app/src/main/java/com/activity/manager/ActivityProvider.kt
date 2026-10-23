package com.activity.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/**
 * Cross-process ContentProvider that bridges Xposed hooks (in target app process)
 * with the module's own UI process.
 *
 * Used by [ActivityHooker] to record Activity.onCreate calls from hooked apps.
 */
class ActivityProvider : ContentProvider() {

    private val records = mutableListOf<ActivityRecord>()
    private var nextId = 1L

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) return null
        val packageName = values.getAsString("package_name") ?: return null
        val activityClass = values.getAsString("activity_class") ?: return null
        val timestamp = values.getAsLong("timestamp") ?: System.currentTimeMillis()

        val record = ActivityRecord(
            id = nextId++,
            packageName = packageName,
            activityClass = activityClass,
            timestamp = timestamp
        )

        synchronized(records) {
            records.add(0, record)
            // Cap at 5000 to avoid OOM
            while (records.size > 5000) records.removeLast()
        }

        context?.contentResolver?.notifyChange(uri, null)
        return Uri.withAppendedPath(CONTENT_URI, record.id.toString())
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val filter = selection // optional package/activity filter string

        val cols = arrayOf("_id", "package_name", "activity_class", "timestamp")
        val cursor = MatrixCursor(cols)

        synchronized(records) {
            val filtered = if (filter.isNullOrBlank()) {
                records.toList()
            } else {
                records.filter { r ->
                    r.packageName.contains(filter, ignoreCase = true) ||
                    r.activityClass.contains(filter, ignoreCase = true)
                }
            }
            filtered.forEach { r ->
                cursor.addRow(arrayOf(r.id, r.packageName, r.activityClass, r.timestamp))
            }
        }
        cursor.setNotificationUri(context?.contentResolver, CONTENT_URI)
        return cursor
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val count: Int
        synchronized(records) {
            count = records.size
            records.clear()
        }
        context?.contentResolver?.notifyChange(uri, null)
        return count
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.lspo.activity"
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.activity.manager.provider"
        val CONTENT_URI = Uri.parse("content://$AUTHORITY/activities")
    }
}
