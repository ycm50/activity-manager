package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clear-confirmation toast lists these labels, so the shortening rules are pinned here:
 * showing the wrong label would tell the user the wrong directory was deleted.
 */
class HookDirectoryLabelTest {

    private val prefix = "/storage/emulated/0/Android/data/"

    @Test
    fun showsPackageAndDirectoryName() {
        assertEquals(
            "com.mxtech.videoplayer.ad/activity_monitor",
            HookDirectoryLabel.of(prefix + "com.mxtech.videoplayer.ad/files/activity_monitor")
        )
    }

    @Test
    fun dropsTheBoilerplatePrefix() {
        val label = HookDirectoryLabel.of(
            prefix + "com.example.app/files/activity_monitor"
        )
        assertEquals("com.example.app/activity_monitor", label)
        assertEquals(false, label.contains("storage"))
        assertEquals(false, label.contains("Android"))
    }

    @Test
    fun handlesDeepPackageNames() {
        assertEquals(
            "idm.internet.download.manager/activity_monitor",
            HookDirectoryLabel.of(prefix + "idm.internet.download.manager/files/activity_monitor")
        )
    }

    @Test
    fun unexpectedShapeFallsBackToLastTwoSegments() {
        assertEquals("b/c", HookDirectoryLabel.of("/a/b/c"))
        assertEquals("a/b", HookDirectoryLabel.of("a/b"))
    }

    @Test
    fun shortPathDoesNotReturnBlank() {
        assertEquals("only", HookDirectoryLabel.of("/only/"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(
            "com.example.app/activity_monitor",
            HookDirectoryLabel.of("  " + prefix + "com.example.app/files/activity_monitor  ")
        )
    }
}
