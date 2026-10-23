package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clearing hook logs is collect → delete → verify, and the three `find` outputs are read by
 * [ClearOutcome]. Those rules are pinned here because a wrong answer is invisible on the
 * device: the previous implementation reported whatever `find -print` listed as "已删除",
 * including directories the following `rm` had failed to remove.
 */
class ClearOutcomeTest {

    private val dir = HookEventCodec.DIRECTORY_NAME

    @Test
    fun pathsKeepsOnlyHookDirectories() {
        val stdout = """
            /storage/emulated/0/Android/data/com.a/files/$dir
            /storage/emulated/0/Android/data/com.b/files/other
            /storage/emulated/0/Android/data/com.c/files/$dir
        """.trimIndent()

        assertEquals(
            listOf(
                "/storage/emulated/0/Android/data/com.a/files/$dir",
                "/storage/emulated/0/Android/data/com.c/files/$dir"
            ),
            ClearOutcome.paths(stdout)
        )
    }

    @Test
    fun pathsAreDeduplicatedAndTrimmed() {
        val line = "  /storage/emulated/0/Android/data/com.a/files/$dir  "
        assertEquals(
            listOf("/storage/emulated/0/Android/data/com.a/files/$dir"),
            ClearOutcome.paths("$line\n$line\n")
        )
    }

    @Test
    fun emptyOutputMeansNothingWasFound() {
        assertTrue(ClearOutcome.paths("").isEmpty())
        assertTrue(ClearOutcome.paths("\n\n  \n").isEmpty())
    }

    @Test
    fun traversalNoiseIsNotAnError() {
        val stderr = """
            find: /storage/emulated/0/Android/data/com.other: Permission denied
            find: /storage/emulated/0/Android/data/com.missing/files: No such file or directory
        """.trimIndent()

        assertNull(ClearOutcome.errorOf(stderr))
    }

    @Test
    fun realErrorsSurvive() {
        val stderr = "find: /storage/emulated/0/Android/data: No such file or directory\n" +
            "rm: /storage/emulated/0/Android/data/com.a/files/$dir: Operation not permitted"

        val error = ClearOutcome.errorOf(stderr)
        assertTrue(error != null && error.contains("Operation not permitted"))
    }

    @Test
    fun clearResultIsIncompleteWhileAnythingRemains() {
        val removed = listOf("/storage/emulated/0/Android/data/com.a/files/$dir")
        assertTrue(HookEventReader.ClearResult(removed).isComplete)

        val partial = HookEventReader.ClearResult(
            removed,
            remaining = listOf("/storage/emulated/0/Android/data/com.b/files/$dir")
        )
        assertTrue(!partial.isComplete)
        assertEquals(1, partial.count)
    }
}
