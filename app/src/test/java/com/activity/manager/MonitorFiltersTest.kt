package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * Filtering happens in the UI layer but its rules live here, so they are pinned without a
 * device. The old screen only supported a substring search; windows, package sets and the
 * system-app exclusion are new and each has an edge worth a test.
 */
class MonitorFiltersTest {

    private fun record(
        pkg: String,
        cls: String,
        ts: Long,
        key: String = "$pkg@$ts"
    ) = ActivityRecord(key = key, packageName = pkg, activityClass = cls, timestamp = ts)

    private val now = Instant.parse("2026-10-23T10:00:00Z").toEpochMilli()

    private fun hoursAgo(h: Long) = now - h * 3_600_000L

    private val records = listOf(
        record("com.android.settings", "com.android.settings.Settings", hoursAgo(0)),
        record("com.example.app", "com.example.app.MainActivity", hoursAgo(0)),
        record("com.example.app", "com.example.app.DetailActivity", hoursAgo(2)),
        record("org.other", "org.other.ui.LaunchActivity", hoursAgo(30))
    )

    @Test
    fun emptyFilterKeepsEveryRecordAndSkipsWork() {
        // The identity path matters: this runs on every recomposition of a long list.
        assertSame(records, MonitorFilters.apply(records, MonitorFilter(), now))
    }

    @Test
    fun blankQueryIsIgnored() {
        assertEquals(records, MonitorFilters.apply(records, MonitorFilter(query = "   "), now))
    }

    @Test
    fun queryMatchesPackageAndClass() {
        assertEquals(
            listOf("com.example.app@${hoursAgo(0)}", "com.example.app@${hoursAgo(2)}"),
            MonitorFilters.apply(records, MonitorFilter(query = "example"), now).map { it.key }
        )
        assertEquals(
            listOf("com.example.app@${hoursAgo(2)}"),
            MonitorFilters.apply(records, MonitorFilter(query = "DetailActivity"), now).map { it.key }
        )
    }

    @Test
    fun fiveMinuteWindowDropsOlderEvents() {
        val visible = MonitorFilters.apply(records, MonitorFilter(timeWindow = TimeWindow.FIVE_MINUTES), now)
        assertEquals(2, visible.size)
        assertTrue(visible.all { it.timestamp >= now - 300_000L })
    }

    @Test
    fun oneHourWindowIsInclusiveOfTheBoundary() {
        val boundary = record("a.b", "a.b.C", ts = now - 3_600_000L, key = "boundary")
        val visible = MonitorFilters.apply(
            records + boundary,
            MonitorFilter(timeWindow = TimeWindow.ONE_HOUR),
            now
        )
        assertTrue(visible.any { it.key == "boundary" })
    }

    @Test
    fun todayUsesTheCalendarDayNotTheLast24Hours() {
        // Derived from the device zone so the assertion holds in any time zone: one millisecond
        // after the local midnight is "today", one millisecond before it is not.
        val start = TimeWindow.startOfDay(now, TimeZone.getDefault())
        val today = record("a.b", "a.b.C", ts = start + 1, key = "today")
        val yesterday = record("a.b", "a.b.C", ts = start - 1, key = "yesterday")

        val visible = MonitorFilters.apply(
            listOf(today, yesterday),
            MonitorFilter(timeWindow = TimeWindow.TODAY),
            now
        )
        assertEquals(listOf("today"), visible.map { it.key })
    }

    @Test
    fun packageSetRestrictsToThosePackages() {
        val visible = MonitorFilters.apply(
            records,
            MonitorFilter(packages = setOf("org.other")),
            now
        )
        assertEquals(listOf("org.other"), visible.map { it.packageName })
    }

    @Test
    fun activityPrefixMatchesQualifiedAndSimpleNames() {
        assertEquals(
            2,
            MonitorFilters.apply(records, MonitorFilter(activityPrefix = "com.example"), now).size
        )
        assertEquals(
            1,
            MonitorFilters.apply(records, MonitorFilter(activityPrefix = "Settings"), now).size
        )
    }

    @Test
    fun excludeSystemUsesTheSuppliedPredicate() {
        val visible = MonitorFilters.apply(
            records,
            MonitorFilter(excludeSystem = true),
            now,
            isSystemPackage = { it.startsWith("com.android.") }
        )
        assertEquals(listOf("com.example.app", "com.example.app", "org.other"), visible.map { it.packageName })
    }

    @Test
    fun groupingPreservesFirstAppearanceOrderAndInnerOrder() {
        val groups = MonitorFilters.groupByPackage(records)

        assertEquals(
            listOf("com.android.settings", "com.example.app", "org.other"),
            groups.map { it.packageName }
        )
        assertEquals(2, groups[1].size)
        assertEquals(
            listOf("com.example.app.MainActivity", "com.example.app.DetailActivity"),
            groups[1].records.map { it.activityClass }
        )
    }

    @Test
    fun packagesOfIsDeduplicatedAndSorted() {
        assertEquals(
            listOf("com.android.settings", "com.example.app", "org.other"),
            MonitorFilters.packagesOf(records)
        )
    }
}
