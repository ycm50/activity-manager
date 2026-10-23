package com.activity.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 更新 button decides "restored or not" purely from `dumpsys package` text, so the
 * parsing rules are pinned here: a wrong answer deletes or keeps the wrong history entry.
 */
class ComponentStateCheckerTest {

    private fun pkgEntry(pkg: String = "com.example.app") = DisableHistoryEntry(
        id = 1L,
        target = DisableTarget.PACKAGE,
        packageName = pkg,
        activityClass = null,
        disabledAt = 1L
    )

    private fun activityEntry(
        pkg: String = "com.example.app",
        cls: String = "com.example.app.MainActivity"
    ) = DisableHistoryEntry(
        id = 2L,
        target = DisableTarget.ACTIVITY,
        packageName = pkg,
        activityClass = cls,
        disabledAt = 1L
    )

    private fun dump(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun packageDisabledIsReportedDisabled() {
        val text = dump(
            "Packages:",
            "  Package [com.example.app] (1234):",
            "    userId=10123",
            "    enabled=2",
            "    User 0: ceDataInode=1",
            "      enabled=2"
        )
        assertEquals(
            ComponentStateChecker.State.DISABLED,
            ComponentStateChecker.parse(text, pkgEntry())
        )
    }

    @Test
    fun packageEnabledIsReportedEnabled() {
        val text = dump(
            "  Package [com.example.app] (1234):",
            "    enabled=0",
            "    User 0:",
            "      enabled=0"
        )
        assertEquals(
            ComponentStateChecker.State.ENABLED,
            ComponentStateChecker.parse(text, pkgEntry())
        )
    }

    @Test
    fun checkResultReportsUnknownWithReason() {
        // The UI names what failed, so a non-definitive answer must carry a reason.
        val unknown = ComponentStateChecker.CheckResult(
            ComponentStateChecker.State.UNKNOWN,
            "dumpsys 无输出（exit=-1）"
        )
        assertEquals(true, unknown.isUnknown)
        assertEquals("dumpsys 无输出（exit=-1）", unknown.reason)

        val ok = ComponentStateChecker.CheckResult(ComponentStateChecker.State.DISABLED, null)
        assertEquals(false, ok.isUnknown)
        assertNull(ok.reason)
    }

    @Test
    fun disabledPackageListIsParsed() {
        val stdout = listOf(
            "package:com.example.disabled1",
            "package:com.example.disabled2",
            "",
            "  package:com.example.spaced  ",
            "garbage line",
        ).joinToString("\n")

        val parsed = ComponentStateChecker.parseDisabledPackages(stdout)
        assertEquals(
            setOf("com.example.disabled1", "com.example.disabled2", "com.example.spaced"),
            parsed
        )
    }

    @Test
    fun emptyDisabledListYieldsEmptySet() {
        assertEquals(emptySet<String>(), ComponentStateChecker.parseDisabledPackages(""))
    }

    @Test
    fun numericEnabledStateIsUnderstood() {
        // Some builds print the raw COMPONENT_ENABLED_STATE_* integer instead of a name.
        // Getting this wrong would make 更新 silently report "unknown" for every entry.
        assertEquals(
            ComponentStateChecker.State.DISABLED,
            ComponentStateChecker.parse(dump("    enabled=2"), pkgEntry())
        )
        assertEquals(
            ComponentStateChecker.State.DISABLED,
            ComponentStateChecker.parse(dump("    enabled=3"), pkgEntry())
        )
        assertEquals(
            ComponentStateChecker.State.DISABLED,
            ComponentStateChecker.parse(dump("    enabled=4"), pkgEntry())
        )
        assertEquals(
            ComponentStateChecker.State.ENABLED,
            ComponentStateChecker.parse(dump("    enabled=0"), pkgEntry())
        )
        assertEquals(
            ComponentStateChecker.State.ENABLED,
            ComponentStateChecker.parse(dump("    enabled=1"), pkgEntry())
        )
    }

    @Test
    fun unknownNumericCodeIsUnknown() {
        assertEquals(
            ComponentStateChecker.State.UNKNOWN,
            ComponentStateChecker.parse(dump("    enabled=99"), pkgEntry())
        )
    }

    @Test
    fun namedDisabledStatesAreRecognised() {
        for (value in listOf("disabled-user", "disabled-until-used", "default")) {
            val mark = if (value == "default") {
                ComponentStateChecker.State.ENABLED
            } else {
                ComponentStateChecker.State.DISABLED
            }
            assertEquals(
                "value=$value",
                mark,
                ComponentStateChecker.parse(dump("    enabled=$value"), pkgEntry())
            )
        }
    }

    @Test
    fun missingPackageIsReportedMissing() {
        val text = "Unable to find package: com.example.app"
        assertEquals(
            ComponentStateChecker.State.MISSING,
            ComponentStateChecker.parse(text, pkgEntry())
        )
    }

    @Test
    fun blankDumpIsUnknown() {
        assertEquals(
            ComponentStateChecker.State.UNKNOWN,
            ComponentStateChecker.parse("", pkgEntry())
        )
    }

    @Test
    fun unparsableDumpIsUnknownRatherThanGuessed() {
        assertEquals(
            ComponentStateChecker.State.UNKNOWN,
            ComponentStateChecker.parse("something entirely unexpected", pkgEntry())
        )
    }

    @Test
    fun activityStateComesFromTheDisabledComponentsList() {
        // Real shape on Android 16 / ColorOS: the override is not printed next to the
        // component, it appears as its own block only when non-empty.
        val text = dump(
            "  Package [com.example.app] (1234):",
            "    User 0: installed=true enabled=0",
            "      disabledComponents:",
            "        com.example.app.MainActivity",
            ""
        )
        assertEquals(
            ComponentStateChecker.State.DISABLED,
            ComponentStateChecker.parse(text, activityEntry())
        )
    }

    @Test
    fun activityListedUnderEnabledComponentsIsEnabled() {
        val text = dump(
            "  Package [com.example.app] (1234):",
            "    User 0: installed=true enabled=0",
            "      enabledComponents:",
            "        com.example.app.MainActivity",
            ""
        )
        assertEquals(
            ComponentStateChecker.State.ENABLED,
            ComponentStateChecker.parse(text, activityEntry())
        )
    }

    @Test
    fun disabledPackageWinsOverAComponentOverride() {
        val text = dump(
            "    User 0: installed=true enabled=2",
            "      enabledComponents:",
            "        com.example.app.MainActivity",
            ""
        )
        assertEquals(
            ComponentStateChecker.State.DISABLED,
            ComponentStateChecker.parse(text, activityEntry())
        )
    }

    @Test
    fun aRelativeStoredClassStillMatches() {
        val text = dump(
            "    User 0: installed=true enabled=0",
            "      disabledComponents:",
            "        com.example.app.MainActivity",
            ""
        )
        assertEquals(
            ComponentStateChecker.State.DISABLED,
            ComponentStateChecker.parse(text, activityEntry(cls = ".MainActivity"))
        )
    }

    @Test
    fun anUnrelatedComponentDoesNotMatch() {
        val text = dump(
            "    User 0: installed=true enabled=0",
            "      disabledComponents:",
            "        com.example.app.OtherActivity",
            ""
        )
        assertEquals(
            ComponentStateChecker.State.ENABLED,
            ComponentStateChecker.parse(text, activityEntry())
        )
    }

    @Test
    fun activityFallsBackToPackageStateWhenBlockAbsent() {
        // The component line is gone, but a disabled package disables its components.
        val text = dump(
            "  Package [com.example.app] (1234):",
            "    enabled=2"
        )
        assertEquals(
            ComponentStateChecker.State.DISABLED,
            ComponentStateChecker.parse(text, activityEntry())
        )
    }

    @Test
    fun activityWithEnabledComponentIsEnabled() {
        val text = dump(
            "    User 0: installed=true enabled=0",
            "      enabledComponents:",
            "        com.example.app.MainActivity",
            ""
        )
        assertEquals(
            ComponentStateChecker.State.ENABLED,
            ComponentStateChecker.parse(text, activityEntry())
        )
    }

    @Test
    fun valueIsTrimmedOfTrailingPunctuation() {
        assertEquals(
            ComponentStateChecker.State.DISABLED,
            ComponentStateChecker.parse("    enabled=disabled-user,", pkgEntry())
        )
        assertEquals(
            ComponentStateChecker.State.ENABLED,
            ComponentStateChecker.parse("    enabled=default)", pkgEntry())
        )
    }
}
