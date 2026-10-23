package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intent extras are written to a log that shell/Shizuku can read and that survives until the
 * user clears it, so credentials must be masked before they are ever written.
 */
class SensitiveKeysTest {

    @Test
    fun credentialShapedKeysAreMasked() {
        listOf(
            "token",
            "access_token",
            "userToken",
            "password",
            "passwd",
            "pwd",
            "session_id",
            "Cookie",
            "auth_code",
            "client_secret",
            "api_key",
            "apikey",
            "signature"
        ).forEach { key ->
            assertTrue("expected masked: $key", SensitiveKeys.isSensitive(key))
            assertEquals("***", SensitiveKeys.display(key, "super-secret"))
        }
    }

    @Test
    fun ordinaryKeysAreKept() {
        listOf("id", "uid", "tab", "type", "from", "keyword", "song_id").forEach { key ->
            assertFalse("unexpected mask: $key", SensitiveKeys.isSensitive(key))
            assertEquals("7", SensitiveKeys.display(key, "7"))
        }
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(SensitiveKeys.isSensitive("TOKEN"))
        assertTrue(SensitiveKeys.isSensitive("PassWord"))
    }

    @Test
    fun longValuesAreClampedWithAnEllipsis() {
        val long = "x".repeat(SensitiveKeys.MAX_VALUE_LENGTH + 10)
        val shown = SensitiveKeys.display("comment", long)

        assertEquals(SensitiveKeys.MAX_VALUE_LENGTH + 1, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun shortValuesAreNotClamped() {
        assertEquals("short", SensitiveKeys.display("comment", "short"))
    }

    @Test
    fun aMaskedValueNeverLeaksItsLength() {
        val shown = SensitiveKeys.display("token", "x".repeat(500))
        assertEquals(SensitiveKeys.MASK, shown)
    }
}
