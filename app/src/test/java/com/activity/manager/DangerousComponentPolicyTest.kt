package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disable guard is the safety-critical part of this change: a wrong SAFE verdict can brick
 * a device, while a wrong BLOCK verdict is only an annoyance. These tests therefore cover both
 * the "must never be disabled" set and the "must stay usable" normal case.
 */
class DangerousComponentPolicyTest {

    private fun input(
        pkg: String,
        activityClass: String? = null,
        system: Boolean = false,
        persistent: Boolean = false,
        roles: RoleResolver.Roles = RoleResolver.Roles()
    ) = DangerousComponentPolicy.Input(pkg, activityClass, system, persistent, roles)

    @Test
    fun systemInstallerIsBlocked() {
        val verdict = DangerousComponentPolicy.classify(input("com.android.packageinstaller"))
        assertTrue(verdict.isBlocked)
        assertTrue(verdict.reason!!.contains("安装"))
    }

    @Test
    fun activityLevelRequestOnAProtectedPackageIsAlsoBlocked() {
        // Disabling one activity of the installer is exactly as harmful as the package.
        val verdict = DangerousComponentPolicy.classify(
            input("com.android.packageinstaller", "com.android.packageinstaller.InstallStart")
        )
        assertTrue(verdict.isBlocked)
    }

    @Test
    fun systemUiAndPermissionControllerAreBlocked() {
        listOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.android.settings",
            "android"
        ).forEach { pkg ->
            assertTrue("expected BLOCK for $pkg", DangerousComponentPolicy.classify(input(pkg)).isBlocked)
        }
    }

    @Test
    fun theCurrentLauncherIsBlockedEvenWhenNotInTheTable() {
        listOf("com.android.launcher", "com.android.launcher3", "com.thirdparty.launcher").forEach { pkg ->
            val verdict = DangerousComponentPolicy.classify(
                input(pkg, roles = RoleResolver.Roles(home = setOf(pkg)))
            )
            assertTrue("expected BLOCK for $pkg", verdict.isBlocked)
        }
    }

    @Test
    fun aThirdPartyInstallerIsBlockedToo() {
        // Not in the maintained table (so the static backstop cannot catch it), but disabling
        // the app that handles APKs removes the ability to install anything.
        val verdict = DangerousComponentPolicy.classify(
            input("com.rosan.installer.x.revived", roles = RoleResolver.Roles(installer = setOf("com.rosan.installer.x.revived")))
        )
        assertTrue(verdict.isBlocked)
        assertTrue(verdict.reason!!.contains("安装"))
    }

    @Test
    fun defaultInputMethodSmsAndDialerAreWarnings() {
        val ime = DangerousComponentPolicy.classify(
            input("com.baidu.input_oppo", roles = RoleResolver.Roles(ime = setOf("com.baidu.input_oppo")))
        )
        val sms = DangerousComponentPolicy.classify(
            input("com.android.mms", roles = RoleResolver.Roles(sms = setOf("com.android.mms")))
        )
        val dialer = DangerousComponentPolicy.classify(
            input("com.android.contacts", roles = RoleResolver.Roles(dialer = setOf("com.android.contacts")))
        )

        assertTrue(ime.needsConfirmation)
        assertTrue(sms.needsConfirmation)
        assertTrue(dialer.needsConfirmation)
        assertFalse(ime.isBlocked)
    }

    @Test
    fun tableWarnEntriesNeedConfirmation() {
        val verdict = DangerousComponentPolicy.classify(input("com.google.android.gms"))
        assertEquals(RiskLevel.WARN, verdict.level)
        assertTrue(verdict.reason!!.contains("GMS"))
    }

    @Test
    fun aGenericSystemAppOnlyWarns() {
        val verdict = DangerousComponentPolicy.classify(input("com.oem.something", system = true))
        assertEquals(RiskLevel.WARN, verdict.level)
    }

    @Test
    fun aPersistentProcessWarnsEvenWhenNotMarkedSystem() {
        val verdict = DangerousComponentPolicy.classify(input("com.oem.daemon", persistent = true))
        assertEquals(RiskLevel.WARN, verdict.level)
    }

    @Test
    fun anOrdinaryAppIsSafe() {
        val verdict = DangerousComponentPolicy.classify(input("com.example.app"))

        assertEquals(RiskLevel.SAFE, verdict.level)
        assertNull(verdict.reason)
    }

    @Test
    fun theMaintainedTableIsConsistent() {
        val names = DangerousPackages.all().map { it.packageName }

        assertEquals("duplicate package in the table", names.size, names.distinct().size)
        DangerousPackages.all().forEach { entry ->
            assertTrue("blank reason for ${entry.packageName}", entry.reason.isNotBlank())
            assertTrue("blank name for ${entry.packageName}", entry.displayName.isNotBlank())
        }
    }

    @Test
    fun unknownPackagesAreSafeByDefault() {
        assertEquals(RiskLevel.SAFE, DangerousPackages.levelOf("com.example.app"))
        assertNull(DangerousPackages.entryOf("com.example.app"))
    }
}
