package com.activity.manager

import java.util.Calendar
import java.util.TimeZone

/** Time window offered by the filter bar. */
enum class TimeWindow(val label: String) {
    ALL("全部"),
    FIVE_MINUTES("5 分钟"),
    ONE_HOUR("1 小时"),
    TODAY("今天");

    /**
     * Lower bound (inclusive) for [now], or null when the window is unbounded.
     *
     * "今天" is a calendar day in the device's zone, not "the last 24 hours" — that is what a
     * user means when they pick it, and it stays stable while the list is open.
     */
    fun since(now: Long, timeZone: TimeZone = TimeZone.getDefault()): Long? = when (this) {
        ALL -> null
        FIVE_MINUTES -> now - 5 * 60_000L
        ONE_HOUR -> now - 60 * 60_000L
        TODAY -> startOfDay(now, timeZone)
    }

    companion object {
        fun startOfDay(now: Long, timeZone: TimeZone): Long {
            val cal = Calendar.getInstance(timeZone)
            cal.timeInMillis = now
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}

/** Everything the filter panel can narrow by. */
data class MonitorFilter(
    /** Substring match on package or class, case-insensitive. */
    val query: String = "",
    val timeWindow: TimeWindow = TimeWindow.ALL,
    /** Empty means "every package". */
    val packages: Set<String> = emptySet(),
    /** Matched against the fully qualified class and its simple name. */
    val activityPrefix: String = "",
    val excludeSystem: Boolean = false
)

/** One package's slice of the list, used by the grouped view. */
data class PackageGroup(val packageName: String, val records: List<ActivityRecord>) {
    val size: Int get() = records.size
}

/**
 * Filtering and grouping, kept pure so it is unit-tested without a device.
 *
 * The system-package question is passed in as a predicate: the UI supplies a
 * `PackageManager`-backed set, the tests supply a lambda. That keeps `android.*` out of the
 * filtering rules entirely.
 */
object MonitorFilters {

    fun apply(
        records: List<ActivityRecord>,
        filter: MonitorFilter,
        now: Long,
        isSystemPackage: (String) -> Boolean = { false }
    ): List<ActivityRecord> {
        val since = filter.timeWindow.since(now)
        val query = filter.query.trim()
        val prefix = filter.activityPrefix.trim()

        if (since == null && query.isEmpty() && filter.packages.isEmpty() &&
            prefix.isEmpty() && !filter.excludeSystem
        ) {
            return records
        }

        return records.filter { record ->
            (since == null || record.timestamp >= since) &&
                (query.isEmpty() || matches(record, query)) &&
                (filter.packages.isEmpty() || record.packageName in filter.packages) &&
                (prefix.isEmpty() || matchesPrefix(record, prefix)) &&
                (!filter.excludeSystem || !isSystemPackage(record.packageName))
        }
    }

    /** Substring match on package or activity class, case-insensitive. */
    fun matches(record: ActivityRecord, query: String): Boolean =
        record.packageName.contains(query, ignoreCase = true) ||
            record.activityClass.contains(query, ignoreCase = true)

    /** Matches either the fully qualified class or its simple name. */
    fun matchesPrefix(record: ActivityRecord, prefix: String): Boolean =
        record.activityClass.startsWith(prefix, ignoreCase = true) ||
            record.activityClass.substringAfterLast('.').startsWith(prefix, ignoreCase = true)

    /**
     * Groups by package, preserving first-appearance order (the input is newest-first, so the
     * package with the most recent launch comes first) and keeping each group's order.
     */
    fun groupByPackage(records: List<ActivityRecord>): List<PackageGroup> {
        val grouped = LinkedHashMap<String, MutableList<ActivityRecord>>()
        records.forEach { record ->
            grouped.getOrPut(record.packageName) { mutableListOf() }.add(record)
        }
        return grouped.map { (packageName, items) -> PackageGroup(packageName, items) }
    }

    /** Distinct packages present in the records, for the package multi-select. */
    fun packagesOf(records: List<ActivityRecord>): List<String> =
        records.map { it.packageName }.distinct().sorted()

    /** Distinct Activity classes, used to promote a class into the prefix filter. */
    fun activitiesOf(records: List<ActivityRecord>): List<String> =
        records.map { it.activityClass }.distinct().sorted()
}
