package com.activity.manager

/**
 * Resolves which packages currently hold the roles that matter for a disable decision.
 *
 * The static table in [DangerousPackages] covers the AOSP and the common OEM names, but OEM
 * builds rename things (this device's launcher is `com.android.launcher`, not
 * `com.android.launcher3`), and the user may have chosen a third-party launcher or installer.
 * These checks are therefore authoritative where they can answer:
 *
 * - launcher, SMS and dialer through `cmd role get-role-holders`,
 * - the launcher and the installer through `cmd package resolve-activity`,
 * - the input method through `settings get secure default_input_method`.
 *
 * All three commands were verified on a real device (Android 16 / ColorOS). Two traps are
 * encoded here: `resolve-activity` only matches the APK handler when a **data URI** is
 * supplied (without it the answer is "No activity found"), and `settings get secure
 * sms_default_application` / `dialer_default_application` return `null` on that device, so the
 * role commands are preferred.
 */
class RoleResolver(private val shell: ShizukuShell) {

    /** Packages that hold a role we care about. Every set may be empty. */
    data class Roles(
        val home: Set<String> = emptySet(),
        val installer: Set<String> = emptySet(),
        val ime: Set<String> = emptySet(),
        val sms: Set<String> = emptySet(),
        val dialer: Set<String> = emptySet()
    )

    suspend fun resolve(): Roles = Roles(
        home = packages(roleArgs(ROLE_HOME)) + packages(launcherArgs()),
        installer = packages(installerArgs()),
        ime = packages(imeArgs()),
        sms = packages(roleArgs(ROLE_SMS)),
        dialer = packages(roleArgs(ROLE_DIALER))
    )

    private suspend fun packages(argv: Array<String>): Set<String> =
        parsePackageSet(shell.run(argv).stdout)

    companion object {
        const val CMD = "/system/bin/cmd"
        const val SETTINGS = "/system/bin/settings"

        const val ROLE_HOME = "android.app.role.HOME"
        const val ROLE_SMS = "android.app.role.SMS"
        const val ROLE_DIALER = "android.app.role.DIALER"

        fun roleArgs(role: String): Array<String> =
            arrayOf(CMD, "role", "get-role-holders", role)

        fun launcherArgs(): Array<String> = arrayOf(
            CMD, "package", "resolve-activity", "--brief", "--user", "0",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.HOME"
        )

        /**
         * The handler for an APK file.
         *
         * The `-d` argument is required: without a data URI the resolver matches nothing and
         * prints "No activity found", which made the installer look undetectable.
         */
        fun installerArgs(): Array<String> = arrayOf(
            CMD, "package", "resolve-activity", "--brief", "--user", "0",
            "-a", "android.intent.action.VIEW",
            "-d", "file:///storage/emulated/0/a.apk",
            "-t", "application/vnd.android.package-archive"
        )

        fun imeArgs(): Array<String> =
            arrayOf(SETTINGS, "get", "secure", "default_input_method")

        /**
         * Extracts package names from any of the three outputs.
         *
         * `resolve-activity --brief` prints metadata lines (`priority=0 …`) followed by one
         * `pkg/Component` line; `get-role-holders` prints bare package names; `settings get`
         * prints a single component or `null`. Dropping lines containing '=' removes the
         * metadata, and requiring a dot removes "No activity found" / "null".
         */
        fun parsePackageSet(stdout: String): Set<String> =
            stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains('=') }
                .map { it.substringBefore('/') }
                .filter { pkg -> pkg.contains('.') && pkg.none { it.isWhitespace() } }
                .toSet()
    }
}
