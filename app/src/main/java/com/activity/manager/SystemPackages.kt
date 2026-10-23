package com.activity.manager

/**
 * Decides whether a package is a system app, without requiring package visibility.
 *
 * The module deliberately does **not** declare `QUERY_ALL_PACKAGES`: the user asked for a
 * curated `<queries>` list instead. That means `PackageManager.getApplicationInfo` throws for
 * most packages, so `excludeSystem` and the risk heuristic cannot rely on `ApplicationInfo`
 * flags alone. The flags are used when they are available; this heuristic covers the rest.
 *
 * Pure, so the rules are unit-tested.
 */
object SystemPackages {

    /** Package-name prefixes used by AOSP and the OEM builds this app targets. */
    private val PREFIXES = listOf(
        "android.",
        "com.android.",
        "com.google.android.",
        "com.oplus.",
        "com.coloros.",
        "com.miui.",
        "com.huawei.",
        "com.hihonor.",
        "com.samsung.android.",
        "com.oneplus."
    )

    /** Exact names that are system packages but do not match a prefix. */
    private val EXACT = setOf("android", "com.android", "com.google.android")

    /**
     * A package looks like part of the system.
     *
     * @param knownSystem packages whose `FLAG_SYSTEM` flag was readable from `PackageManager`.
     */
    fun isLikelySystem(packageName: String, knownSystem: Set<String> = emptySet()): Boolean {
        if (packageName in knownSystem) return true
        if (DangerousPackages.entryOf(packageName) != null) return true
        if (packageName in EXACT) return true
        return PREFIXES.any { packageName.startsWith(it) }
    }
}
