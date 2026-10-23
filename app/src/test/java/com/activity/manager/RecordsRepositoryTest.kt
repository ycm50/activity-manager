package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RecordsRepositoryTest {

    // Timestamps are seconds apart *1000: the report formatter prints whole seconds, so a
    // fixture using raw 1/2/3 milliseconds rendered every row as 00:00:00.
    private fun record(id: Long, pkg: String, cls: String, ts: Long = id * 1_000L) =
        ActivityRecord(key = "raw-$id", packageName = pkg, activityClass = cls, timestamp = ts)

    private val sample = listOf(
        record(1, "com.android.settings", "com.android.settings.Settings"),
        record(2, "com.example.app", "com.example.app.MainActivity"),
        record(3, "org.other", "org.other.ui.DetailActivity")
    )

    @Test
    fun blankQueryReturnsEveryRecord() {
        assertEquals(sample, RecordsRepository.filterRecords(sample, ""))
        assertEquals(sample, RecordsRepository.filterRecords(sample, "   "))
    }

    @Test
    fun queryMatchesPackageName() {
        assertEquals(listOf(sample[1]), RecordsRepository.filterRecords(sample, "example"))
    }

    @Test
    fun queryMatchesActivityClass() {
        assertEquals(listOf(sample[2]), RecordsRepository.filterRecords(sample, "DetailActivity"))
    }

    @Test
    fun queryIsCaseInsensitiveAndTrimmed() {
        assertEquals(
            RecordsRepository.filterRecords(sample, "SETTINGS"),
            RecordsRepository.filterRecords(sample, "  settings  ")
        )
        assertFalse(RecordsRepository.filterRecords(sample, "settings").isEmpty())
    }

    @Test
    fun queryWithNoMatchReturnsEmptyList() {
        assertTrue(RecordsRepository.filterRecords(sample, "no-such-package").isEmpty())
    }

    @Test
    fun newestFirstSortsNewestFirst() {
        assertEquals(
            listOf("raw-3", "raw-2", "raw-1"),
            RecordsRepository.newestFirst(sample).map { it.key }
        )
    }

    @Test
    fun newestFirstIsIdempotent() {
        // The reader already returns newest-first; the export path used to reverse that a
        // second time and produce an oldest-first report.
        val once = RecordsRepository.newestFirst(sample)
        assertEquals(once.map { it.key }, RecordsRepository.newestFirst(once).map { it.key })
    }

    @Test
    fun newestFirstIgnoresInsertionOrder() {
        val shuffled = listOf(sample[1], sample[2], sample[0])
        assertEquals(
            listOf("raw-3", "raw-2", "raw-1"),
            RecordsRepository.newestFirst(shuffled).map { it.key }
        )
    }

    @Test
    fun newestFirstToleratesTinyLists() {
        assertEquals(emptyList<ActivityRecord>(), RecordsRepository.newestFirst(emptyList()))
        assertEquals(sample.take(1), RecordsRepository.newestFirst(sample.take(1)))
    }

    @Test
    fun reportRendersHeaderOrderingAndEachActivity() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            // The exporter renders an already newest-first list (MainActivity passes
            // newestFirst(...) into formatReport), so the fixture must be ordered the same
            // way. Rendering `sample` as-is used to assert the opposite order and fail.
            val text = RecordsRepository.formatReport(
                RecordsRepository.newestFirst(sample),
                generatedAt = 0L
            )
            val lines = text.lines()

            assertEquals("=== Activity Monitor Report ===", lines[0])
            assertEquals("Generated: 1970-01-01 00:00:00", lines[1])
            assertEquals("Total: 3 activities", lines[2])
            // 1-based numbering follows the given (newest-first) order.
            assertTrue(text.contains("1. [1970-01-01 00:00:03] org.other"))
            assertTrue(text.contains("   Activity: org.other.ui.DetailActivity"))
            assertTrue(text.contains("3. [1970-01-01 00:00:01] com.android.settings"))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun emptyReportStillHasAValidHeader() {
        val text = RecordsRepository.formatReport(emptyList(), generatedAt = 0L)
        assertTrue(text.contains("Total: 0 activities"))
    }
}
