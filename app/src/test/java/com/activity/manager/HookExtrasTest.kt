package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 4th log column is written from inside third-party app processes, so it must be
 * unambiguous, bounded, and tolerant of junk. Each of those properties is pinned here.
 */
class HookExtrasTest {

    private fun event(
        kind: HookEventKind = HookEventKind.CREATED,
        extras: String? = null
    ) = HookEvent(
        timestamp = 1L,
        packageName = "com.example.app",
        activityClass = "com.example.app.MainActivity",
        kind = kind,
        extrasSummary = extras
    )

    @Test
    fun plainCreateEventNeedsNoExtraColumn() {
        // Keeping the 3-column shape for the common case means older readers stay compatible
        // and the log stays as small as it was before extras existed.
        assertEquals("", HookExtras.encode(event()))
    }

    @Test
    fun newIntentIsRecordedEvenWithoutOtherData() {
        assertEquals("et=n", HookExtras.encode(event(kind = HookEventKind.NEW_INTENT)))
    }

    @Test
    fun everyFieldRoundTrips() {
        val original = HookEvent(
            timestamp = 5L,
            packageName = "com.example.app",
            activityClass = "com.example.app.MainActivity",
            kind = HookEventKind.NEW_INTENT,
            intentAction = "android.intent.action.SEND",
            intentData = "content://media/1?a=b|c;d",
            referrer = "com.caller",
            launchedFromPackage = "com.caller",
            taskId = 7,
            processName = "com.example.app:remote",
            extrasSummary = "id:7,token:***"
        )

        val restored = HookExtras.apply(
            HookEvent(5L, "com.example.app", "com.example.app.MainActivity"),
            HookExtras.encode(original)
        )

        assertEquals(original, restored)
    }

    @Test
    fun unknownKeysAndMalformedPairsAreIgnored() {
        val restored = HookExtras.apply(event(), "zz=1;noseparator;=empty;a=ok")

        assertNull(restored.taskId)
        assertEquals("ok", restored.intentAction)
    }

    @Test
    fun taskIdMustBeNumeric() {
        assertEquals(3, HookExtras.apply(event(), "t=3").taskId)
        assertNull(HookExtras.apply(event(), "t=abc").taskId)
    }

    @Test
    fun oversizedPayloadIsDroppedInsteadOfTruncatedMidValue() {
        val huge = HookExtras.encodeSummary(List(80) { "key$it" to "v".repeat(20) })
        val encoded = HookEventCodec.encode(event(extras = huge))

        assertTrue(encoded.length < 600)
        // Whatever survived must still be a valid line with an intact class name.
        val parsed = HookEventCodec.parse(encoded)
        assertEquals(1, parsed.size)
        assertEquals("com.example.app.MainActivity", parsed.single().event.activityClass)
    }

    @Test
    fun escapeRoundTripsAmbiguousCharacters() {
        val raw = "a|b;c=d\ne\rf,g:h%i"
        val escaped = HookExtras.escape(raw)

        assertTrue(escaped.none { it == '|' || it == ';' || it == '=' || it == '\n' || it == '\r' || it == ',' || it == ':' })
        assertEquals(raw, HookExtras.unescape(escaped))
    }

    @Test
    fun escapeLeavesOrdinaryTextUntouched() {
        assertEquals("android.intent.action.MAIN", HookExtras.escape("android.intent.action.MAIN"))
    }

    @Test
    fun unescapeToleratesPartialPercentSequences() {
        assertEquals("50%", HookExtras.unescape("50%"))
        assertEquals("ok%zz", HookExtras.unescape("ok%zz"))
        assertEquals("a", HookExtras.unescape("%61"))
    }

    @Test
    fun summaryRoundTripsSeparatorsInsideKeysAndValues() {
        val pairs = listOf("a,b" to "c:d", "plain" to "value,with,commas")
        val decoded = HookExtras.decodeSummary(HookExtras.encodeSummary(pairs))

        assertEquals(pairs, decoded)
    }

    @Test
    fun summarySkipsMalformedEntries() {
        assertEquals(
            listOf("k" to "v"),
            HookExtras.decodeSummary("junk,,k:v,:novalue")
        )
        assertTrue(HookExtras.decodeSummary(null).isEmpty())
        assertTrue(HookExtras.decodeSummary("").isEmpty())
    }
}
