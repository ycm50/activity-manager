package com.activity.manager

/**
 * Decides whether a disable request must be refused, confirmed or allowed.
 *
 * The decision is a pure function of the inputs so it can be unit-tested exhaustively; the
 * privileged lookups (roles, package flags) happen in the caller and are passed in.
 *
 * Precedence, strongest first:
 *   1. the maintained [DangerousPackages] table (BLOCK beats everything),
 *   2. the current home app — disabling it leaves no home screen,
 *   3. the current installer — a third-party installer is not in the table but disabling it
 *      still removes the ability to install APKs,
 *   4. default input method / SMS / dialer — usable, but broken in obvious ways,
 *   5. the table's WARN entries,
 *   6. a generic system/persistent app.
 *
 * Activity-level and package-level requests are treated the same: disabling a single activity
 * of the installer is exactly as harmless/ harmful as disabling the package, and the user's
 * rule is that these components must not be disabled at all.
 */
object DangerousComponentPolicy {

    /** Everything the classification needs, gathered by the caller. */
    data class Input(
        val packageName: String,
        val activityClass: String? = null,
        val isSystemApp: Boolean = false,
        val isPersistent: Boolean = false,
        val roles: RoleResolver.Roles = RoleResolver.Roles()
    )

    /** The classification plus the sentence shown to the user (null when SAFE). */
    data class Verdict(val level: RiskLevel, val reason: String?) {
        val isBlocked: Boolean get() = level == RiskLevel.BLOCK
        val needsConfirmation: Boolean get() = level == RiskLevel.WARN
    }

    fun classify(input: Input): Verdict {
        val pkg = input.packageName
        val roles = input.roles

        DangerousPackages.entryOf(pkg)?.let { entry ->
            if (entry.level == RiskLevel.BLOCK) {
                return Verdict(RiskLevel.BLOCK, entry.displayName + "：" + entry.reason)
            }
        }

        if (pkg in roles.home) {
            return Verdict(RiskLevel.BLOCK, "当前桌面应用：禁用后没有主屏幕，无法正常使用设备")
        }

        if (pkg in roles.installer) {
            return Verdict(
                RiskLevel.BLOCK,
                "当前安装器：禁用后无法安装或卸载应用"
            )
        }

        if (pkg in roles.ime) {
            return Verdict(RiskLevel.WARN, "当前输入法：禁用后无法输入文字")
        }
        if (pkg in roles.sms) {
            return Verdict(RiskLevel.WARN, "当前短信应用：禁用后无法收发短信")
        }
        if (pkg in roles.dialer) {
            return Verdict(RiskLevel.WARN, "当前拨号应用：禁用后无法通话")
        }

        DangerousPackages.entryOf(pkg)?.let { entry ->
            if (entry.level == RiskLevel.WARN) {
                return Verdict(RiskLevel.WARN, entry.displayName + "：" + entry.reason)
            }
        }

        if (input.isPersistent) {
            return Verdict(RiskLevel.WARN, "常驻系统进程：禁用可能影响系统稳定性")
        }
        if (input.isSystemApp) {
            return Verdict(RiskLevel.WARN, "系统应用：禁用可能影响系统功能")
        }

        return Verdict(RiskLevel.SAFE, null)
    }
}
