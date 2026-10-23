package com.activity.manager

/** How dangerous it is to disable something. */
enum class RiskLevel {
    /** Nothing known against it. */
    SAFE,

    /** Allowed after an explicit confirmation. */
    WARN,

    /**
     * Refused outright.
     *
     * These are the components whose absence breaks the device or makes it impossible to
     * recover: the package installer (no APKs can be installed or removed), the permission
     * controller (no app can obtain a permission), SystemUI (no status bar / navigation /
     * lock screen), the launcher (no home screen) and the media/settings providers.
     *
     * There is deliberately **no override**: the list is maintained here, and a false positive
     * is fixed by editing the table, not by adding an escape hatch that can break a device.
     */
    BLOCK
}

/** One entry of the maintained table. */
data class DangerousEntry(
    val packageName: String,
    val level: RiskLevel,
    val displayName: String,
    val reason: String
)

/**
 * The maintained list of system-critical packages.
 *
 * Kept as Kotlin data rather than an asset on purpose: an asset load can fail (missing,
 * corrupt, stripped by a resource shrinker) and the failure mode would be silent — the
 * protection would simply stop existing. A referenced object literal cannot go missing, and
 * R8 keeps it because [levelOf] is reachable.
 *
 * The package names were checked against a real device (OnePlus PHB110 / ColorOS / Android
 * 16); OEM variants are listed alongside the AOSP ones because the dynamic role checks only
 * cover the launcher, the installer and the default apps.
 */
object DangerousPackages {

    private fun block(packageName: String, displayName: String, reason: String) =
        DangerousEntry(packageName, RiskLevel.BLOCK, displayName, reason)

    private fun warn(packageName: String, displayName: String, reason: String) =
        DangerousEntry(packageName, RiskLevel.WARN, displayName, reason)

    private val ENTRIES: List<DangerousEntry> = listOf(
        // ── 安装器：禁用后无法安装 / 卸载任何应用 ──────────────────────────────
        block(
            "com.android.packageinstaller", "安装器",
            "禁用后无法安装或卸载应用，也无法打开「安装未知应用」开关"
        ),
        block("com.google.android.packageinstaller", "安装器（GMS）", "禁用后无法安装或卸载应用"),
        block("com.miui.packageinstaller", "安装器（MIUI）", "禁用后无法安装或卸载应用"),
        block("com.oplus.packageinstaller", "安装器（ColorOS）", "禁用后无法安装或卸载应用"),
        block("com.huawei.packageinstaller", "安装器（EMUI）", "禁用后无法安装或卸载应用"),

        // ── 权限：禁用后应用拿不到任何权限 ────────────────────────────────────
        block(
            "com.android.permissioncontroller", "权限管理",
            "禁用后应用无法获取权限，运行时权限弹窗消失"
        ),
        block("com.google.android.permissioncontroller", "权限管理（GMS）", "禁用后应用无法获取权限"),

        // ── 系统界面与设置 ────────────────────────────────────────────────────
        block("com.android.systemui", "系统界面", "禁用后没有状态栏、导航栏、锁屏与通知"),
        block("com.android.settings", "设置", "禁用后无法进入系统设置"),
        block("com.android.providers.settings", "设置存储", "禁用后系统设置读写全部异常"),
        block("com.android.providers.media.module", "媒体存储", "禁用后媒体与文件访问异常"),
        block("com.android.providers.media", "媒体存储（旧版）", "禁用后媒体与文件访问异常"),
        block("com.android.providers.downloads", "下载管理", "禁用后下载与文件导入异常"),

        // ── 框架与 shell ──────────────────────────────────────────────────────
        block("android", "系统框架", "系统框架本体，禁用必然无法开机"),
        block("com.android.shell", "Shell", "禁用后 adb / 特权命令通道异常"),

        // ── 电话栈 ────────────────────────────────────────────────────────────
        block("com.android.phone", "电话", "禁用后无法通话、收发短信，部分 ROM 无法开机"),
        block("com.android.telecom", "通话服务", "禁用后通话功能异常"),
        block("com.android.server.telecom", "通话服务", "禁用后通话功能异常"),

        // ── WebView：禁用后大量应用崩溃 ───────────────────────────────────────
        block("com.google.android.webview", "WebView", "禁用后所有依赖 WebView 的应用崩溃"),
        block("com.android.webview", "WebView", "禁用后所有依赖 WebView 的应用崩溃"),

        // ── 桌面：禁用后没有主屏幕（另有动态角色检测兜底）─────────────────────
        block("com.android.launcher", "桌面", "禁用后没有主屏幕，开机后无法进入桌面"),
        block("com.android.launcher3", "桌面", "禁用后没有主屏幕，开机后无法进入桌面"),
        block("com.google.android.apps.nexuslauncher", "桌面（Pixel）", "禁用后没有主屏幕"),
        block("com.oplus.launcher", "桌面（ColorOS 旧版）", "禁用后没有主屏幕"),
        block("com.miui.home", "桌面（MIUI）", "禁用后没有主屏幕"),
        block("com.huawei.android.launcher", "桌面（EMUI）", "禁用后没有主屏幕"),

        // ── 警告级：可确认后执行 ──────────────────────────────────────────────
        warn("com.google.android.gms", "Google 服务", "依赖 GMS 的应用会崩溃或无法登录"),
        warn("com.google.android.gsf", "Google 服务框架", "依赖 GMS 的应用会崩溃"),
        warn("com.android.providers.contacts", "联系人存储", "联系人读写异常"),
        warn("com.android.contacts", "联系人", "无法查看或编辑联系人"),
        warn("com.android.mms", "短信", "无法收发短信"),
        warn("com.android.documentsui", "文件选择器", "应用无法选择文件"),
        warn("com.android.mtp", "MTP", "USB 文件传输异常"),
        warn("com.android.certinstaller", "证书安装器", "无法安装证书"),
        warn("com.android.vending", "应用商店", "商店应用无法更新或卸载")
    )

    private val BY_PACKAGE: Map<String, DangerousEntry> =
        ENTRIES.associateBy { it.packageName }

    /** The maintained table, in declaration order. */
    fun all(): List<DangerousEntry> = ENTRIES

    fun entryOf(packageName: String): DangerousEntry? = BY_PACKAGE[packageName]

    /** BLOCK for a protected package, otherwise SAFE (the policy adds the heuristics). */
    fun levelOf(packageName: String): RiskLevel = BY_PACKAGE[packageName]?.level ?: RiskLevel.SAFE

    /** Known Chinese display name, used when the package itself is not queryable. */
    fun displayNameOf(packageName: String): String? = BY_PACKAGE[packageName]?.displayName
}
