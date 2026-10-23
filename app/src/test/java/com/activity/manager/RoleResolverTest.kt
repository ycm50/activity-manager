package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Role detection is what makes the guard work on OEM builds whose package names differ from
 * AOSP. The outputs below are verbatim from a real device (Android 16 / ColorOS), including the
 * two traps that were found there: `resolve-activity` needs a data URI to match the installer,
 * and `settings get secure sms_default_application` returns `null`.
 */
class RoleResolverTest {

    @Test
    fun parsesLauncherResolutionWithItsMetadataLine() {
        val stdout = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.android.launcher/.Launcher
        """.trimIndent()

        assertEquals(setOf("com.android.launcher"), RoleResolver.parsePackageSet(stdout))
    }

    @Test
    fun parsesInstallerResolution() {
        val stdout = """
            priority=0 preferredOrder=0 match=0x608000 specificIndex=-1 isDefault=true
            com.rosan.installer.x.revived/com.rosan.installer.ui.activity.InstallerActivity
        """.trimIndent()

        assertEquals(setOf("com.rosan.installer.x.revived"), RoleResolver.parsePackageSet(stdout))
    }

    @Test
    fun parsesRoleHoldersWhichAreBarePackageNames() {
        assertEquals(
            setOf("com.android.mms"),
            RoleResolver.parsePackageSet("com.android.mms\n")
        )
    }

    @Test
    fun parsesInputMethodComponent() {
        assertEquals(
            setOf("com.baidu.input_oppo"),
            RoleResolver.parsePackageSet("com.baidu.input_oppo/.ImeService\n")
        )
    }

    @Test
    fun missingAnswersProduceNoPackages() {
        assertTrue(RoleResolver.parsePackageSet("No activity found").isEmpty())
        assertTrue(RoleResolver.parsePackageSet("No activities found").isEmpty())
        assertTrue(RoleResolver.parsePackageSet("null").isEmpty())
        assertTrue(RoleResolver.parsePackageSet("").isEmpty())
    }

    @Test
    fun installerProbeMustCarryTheDataUri() {
        // Without -d the resolver matches nothing on Android 16 and prints "No activity found",
        // which is why the installer looked undetectable during the device audit.
        val args = RoleResolver.installerArgs().toList()

        assertTrue(args.contains("-d"))
        assertTrue(args.contains("application/vnd.android.package-archive"))
        assertTrue(args.any { it.startsWith("file://") })
    }

    @Test
    fun roleProbeUsesTheRoleCommandNotSecureSettings() {
        val args = RoleResolver.roleArgs(RoleResolver.ROLE_SMS).toList()

        assertEquals(listOf(RoleResolver.CMD, "role", "get-role-holders", "android.app.role.SMS"), args)
    }

    @Test
    fun inputMethodProbeReadsSecureSettings() {
        val args = RoleResolver.imeArgs().toList()

        assertEquals(listOf(RoleResolver.SETTINGS, "get", "secure", "default_input_method"), args)
    }
}
