package com.activity.manager

import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure list/report helpers shared by the UI and the exporter.
 *
 * Records now come from on-disk hook logs via [HookEventReader] rather than a
 * ContentProvider, so nothing here touches Android IPC and it all stays JVM-testable.
 */
object RecordsRepository {

    /**
     * Newest first.
     *
     * Sorts by timestamp instead of reversing the list: the reader already returns
     * newest-first, and the old `asReversed()` therefore produced **oldest-first** export
     * files (the UI showed newest-first while the report was the opposite). Sorting is
     * idempotent, so callers may pass either order.
     */
    fun newestFirst(all: List<ActivityRecord>): List<ActivityRecord> {
        if (all.size < 2) return all
        return all.sortedByDescending { it.timestamp }
    }

    fun filterRecords(all: List<ActivityRecord>, query: String): List<ActivityRecord> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter { MonitorFilters.matches(it, q) }
    }

    fun formatReport(records: List<ActivityRecord>, generatedAt: Long): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder(records.size * 96 + 128)
        sb.append("=== Activity Monitor Report ===\n")
        sb.append("Generated: ").append(df.format(Date(generatedAt))).append('\n')
        sb.append("Total: ").append(records.size).append(" activities\n\n")
        records.forEachIndexed { index, r ->
            sb.append(index + 1).append(". [")
                .append(df.format(Date(r.timestamp))).append("] ")
                .append(r.packageName).append('\n')
            sb.append("   Activity: ").append(r.activityClass).append("\n\n")
        }
        return sb.toString()
    }

    fun writeReport(writer: Writer, records: List<ActivityRecord>, generatedAt: Long) {
        writer.write(formatReport(records, generatedAt))
    }
}
