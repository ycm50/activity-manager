package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event log is plain text appended from third-party processes, so parsing must be
 * strict enough to reject junk yet tolerant of a half-written trailing line.
 */
class HookEventCodecTest {

    private fun event(
        ts: Long = 1_700_000_000_000L,
        pkg: String = "com.example.app",
        cls: String = "com.example.app.MainActivity"
    ) = HookEvent(ts, pkg, cls)

    @Test
    fun encodeUsesPipeSeparatedTriple() {
        assertEquals(
            "1700000000000|com.example.app|com.example.app.MainActivity",
            HookEventCodec.encode(event())
        )
    }

    @Test
    fun roundTripsThroughEncodeAndParse() {
        val original = event()
        val parsed = HookEventCodec.parse(HookEventCodec.encode(original) + "\n")

        assertEquals(1, parsed.size)
        assertEquals(original, parsed.single().event)
    }

    @Test
    fun parsesMultipleLinesNewestDataIntact() {
        val text = buildString {
            append(HookEventCodec.encode(event(ts = 2L, cls = "A")))
            append('\n')
            append(HookEventCodec.encode(event(ts = 1L, cls = "B")))
            append('\n')
        }
        val parsed = HookEventCodec.parse(text)

        assertEquals(2, parsed.size)
        assertEquals(listOf(2L, 1L), parsed.map { it.event.timestamp })
    }

    @Test
    fun emptyInputYieldsNoEvents() {
        assertTrue(HookEventCodec.parse("").isEmpty())
        assertTrue(HookEventCodec.parse("\n\n").isEmpty())
    }

    @Test
    fun malformedLinesAreSkippedNotFatal() {
        val good = HookEventCodec.encode(event())
        val text = listOf(
            "garbage",
            "notanumber|com.a|.B",
            "123|com.a",                      // only two fields
            "123||.B",                        // empty package
            "123|com.a|",                     // empty class
            "123|com.a|.B|extra",             // class keeps the remainder, still valid
            good
        ).joinToString("\n")

        val parsed = HookEventCodec.parse(text)
        assertEquals(2, parsed.size) // the 4-field line is valid, class = ".B|extra"
        assertEquals(good, parsed.last().raw)
    }

    @Test
    fun separatorsInInputAreSanitisedSoLinesCannotBreak() {
        val encoded = HookEventCodec.encode(
            event(pkg = "com.a|b", cls = "com.a\nB")
        )
        // Exactly two separators survive, so the line stays parseable.
        assertEquals(2, encoded.count { it == HookEventCodec.SEPARATOR })

        val parsed = HookEventCodec.parse(encoded)
        assertEquals(1, parsed.size)
        assertEquals("com.a_b", parsed.single().event.packageName)
    }

    @Test
    fun partialTrailingLineIsIgnored() {
        val complete = HookEventCodec.encode(event())
        val parsed = HookEventCodec.parse(complete + "\n17000000000")

        assertEquals(1, parsed.size)
        assertEquals(complete, parsed.single().raw)
    }

    @Test
    fun constantsMatchTheOnDeviceLayout() {
        assertEquals("activity_monitor", HookEventCodec.DIRECTORY_NAME)
        assertEquals("events.log", HookEventCodec.FILE_NAME)
    }
}
