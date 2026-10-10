package com.activity.manager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the command-injection fix: the arguments must stay a plain argv array that no
 * shell ever parses. Also pins the restore command, which must be `default-state` (the
 * inverse of `disable`) rather than `enable`.
 */
class ComponentDisablerTest {

    @Test
    fun packageDisablePassesPackageAsOwnArgument() {
        assertArrayEquals(
            arrayOf("pm", "disable", "--user", "0", "com.example.app"),
            ComponentDisabler.disablePackageArgs("com.example.app")
        )
    }

    @Test
    fun activityDisableJoinsPackageAndClassWithSlash() {
        assertArrayEquals(
            arrayOf("pm", "disable", "--user", "0", "com.example.app/.MainActivity"),
            ComponentDisabler.disableActivityArgs("com.example.app", ".MainActivity")
        )
    }

    @Test
    fun restoreUsesDefaultStateNotEnable() {
        val args = ComponentDisabler.restoreArgs("com.example.app/.MainActivity")

        assertArrayEquals(
            arrayOf("pm", "default-state", "--user", "0", "com.example.app/.MainActivity"),
            args
        )
        // 'enable' would force-enable components that were legitimately disabled.
        assertFalse(args.contains("enable"))
        assertEquals("default-state", args[1])
    }

    @Test
    fun shellMetacharactersAreNeverInterpreted() {
        val payload = "com.example.app; rm -rf /data"

        val args = ComponentDisabler.disablePackageArgs(payload)

        // The payload stays inside a single argv entry instead of splitting the command.
        assertEquals(5, args.size)
        assertEquals(payload, args[4])
    }

    @Test
    fun restoreArgumentsAreAlsoASingleArgvEntry() {
        val payload = "com.example.app/.Act; reboot"
        val args = ComponentDisabler.restoreArgs(payload)

        assertEquals(5, args.size)
        assertEquals(payload, args[4])
    }

    @Test
    fun noArgumentIsAShellInterpreter() {
        val args = ComponentDisabler.disablePackageArgs("com.example.app")
        assertFalse(args.any { it.endsWith("sh") || it == "-c" })
        assertTrue(ComponentDisabler.restoreArgs("p").none { it.endsWith("sh") || it == "-c" })
    }
}
