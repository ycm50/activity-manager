package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `find` prints per-path noise for every directory it cannot enter while walking
 * `Android/data`. Showing that to the user made a fully successful operation look like a
 * failure, so the filtering rules are pinned here.
 */
class ShellErrorTextTest {

    @Test
    fun noSuchFileLinesAreNoise() {
        assertTrue(
            ShellErrorText.isTraversalNoise(
                "find: /storage/emulated/0/Android/data/com.x/files: No such file or directory"
            )
        )
    }

    @Test
    fun permissionDeniedLinesAreNoise() {
        assertTrue(
            ShellErrorText.isTraversalNoise(
                "find: /storage/emulated/0/Android/data/com.kylin.read/cache: Permission denied"
            )
        )
    }

    @Test
    fun genuineFindErrorsSurvive() {
        // A bad predicate is a real mistake and must still be reported.
        assertFalse(ShellErrorText.isTraversalNoise("find: unknown predicate `-bogus'"))
        assertTrue(ShellErrorText.isTraversalNoise("find: '/x': No such file or directory"))
    }

    @Test
    fun nonFindMessagesAreNeverTreatedAsNoise() {
        assertFalse(ShellErrorText.isTraversalNoise("dumpsys: not found"))
        assertFalse(ShellErrorText.isTraversalNoise("Permission denied"))
    }

    @Test
    fun blankLinesAreNotNoiseButAreDropped() {
        assertFalse(ShellErrorText.isTraversalNoise(""))
        assertFalse(ShellErrorText.isTraversalNoise("   "))
        assertEquals("", ShellErrorText.meaningful("   \n  \n"))
    }

    @Test
    fun allNoiseCollapsesToEmpty() {
        val stderr = listOf(
            "find: /a: Permission denied",
            "find: /b: No such file or directory",
            "find: /c: Permission denied"
        ).joinToString("\n")

        assertEquals("", ShellErrorText.meaningful(stderr))
    }

    @Test
    fun realErrorIsPreservedAlongsideNoise() {
        val stderr = listOf(
            "find: /a: No such file or directory",
            "find: unknown predicate `-bogus'"
        ).joinToString("\n")

        assertEquals("find: unknown predicate `-bogus'", ShellErrorText.meaningful(stderr))
    }

    @Test
    fun successWithNoStderrStaysEmpty() {
        assertEquals("", ShellErrorText.meaningful(""))
    }
}
