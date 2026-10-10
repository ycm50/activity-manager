package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history is persisted as plain text, so the codec must round-trip exactly and must
 * ignore corrupted lines instead of throwing (a bad preferences file should never brick
 * the restore screen).
 */
class DisableHistoryCodecTest {

    private val packageEntry = DisableHistoryEntry(
        id = 1L,
        target = DisableTarget.PACKAGE,
        packageName = "com.example.app",
        activityClass = null,
        disabledAt = 1_700_000_000_000L,
        restored = false
    )

    private val activityEntry = DisableHistoryEntry(
        id = 2L,
        target = DisableTarget.ACTIVITY,
        packageName = "com.example.app",
        activityClass = "com.example.app.SplashActivity",
        disabledAt = 1_700_000_001_000L,
        restored = true
    )

    @Test
    fun roundTripsBothTargetTypes() {
        val input = listOf(packageEntry, activityEntry)
        assertEquals(input, DisableHistoryCodec.decode(DisableHistoryCodec.encode(input)))
    }

    @Test
    fun emptyInputsProduceEmptyOutput() {
        assertEquals(emptyList<DisableHistoryEntry>(), DisableHistoryCodec.decode(null))
        assertEquals(emptyList<DisableHistoryEntry>(), DisableHistoryCodec.decode(""))
        assertEquals("", DisableHistoryCodec.encode(emptyList()))
    }

    @Test
    fun malformedLinesAreSkippedNotFatal() {
        val good = DisableHistoryCodec.encode(listOf(packageEntry))
        val raw = listOf(
            "garbage",
            "",
            "1\tp\tcom.a",                       // too few fields
            "x\tp\tcom.a\t\t1\t0",              // non-numeric id
            "3\tZ\tcom.a\t\t1700000000000\t0",  // unknown target
            "4\ta\tcom.a\t\t1700000000000\t0",  // ACTIVITY without class
            good
        ).joinToString("\n")

        val decoded = DisableHistoryCodec.decode(raw)
        assertEquals(listOf(packageEntry), decoded)
    }

    @Test
    fun componentRenderingFollowsPmSyntax() {
        assertEquals("com.example.app", packageEntry.component)
        assertEquals(
            "com.example.app/com.example.app.SplashActivity",
            activityEntry.component
        )
    }

    @Test
    fun shortLabelFallsBackSensibly() {
        assertEquals("app", packageEntry.shortLabel)
        assertEquals("SplashActivity", activityEntry.shortLabel)

        // Defensive: an activity entry with a blank class still renders the package.
        val blank = activityEntry.copy(activityClass = null)
        assertEquals("com.example.app", blank.shortLabel)
        assertEquals("com.example.app", blank.component)
    }

    @Test
    fun restoredFlagSurvivesRoundTrip() {
        val decoded = DisableHistoryCodec.decode(DisableHistoryCodec.encode(listOf(activityEntry)))
        assertTrue(decoded.single().restored)
        assertNull(decoded.singleOrNull { !it.restored })
    }
}
