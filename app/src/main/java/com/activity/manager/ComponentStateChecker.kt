package com.activity.manager

/**
 * Reads the *actual* enabled state of a component back from the package manager.
 *
 * The history records what we asked `pm` to do, which can drift from reality: the user may
 * re-enable an app by hand, another tool may override it, or a restore may have been issued
 * while the component was already back to its default. 更新 re-checks every entry so the
 * list reflects the device rather than our intent.
 *
 * Implemented with `dumpsys package <pkg>` because it reports the resolved `enabled=`
 * value for both a whole package and an individual component, whereas
 * `pm list packages -d` only covers package-level state.
 */
class ComponentStateChecker(private val shell: ShizukuShell) {

    /**
     * Outcome of one check.
     *
     * [reason] explains a non-definitive answer (command failed, output unparsable) so the UI
     * can name what went wrong instead of only counting it.
     */
    data class CheckResult(val state: State, val reason: String?) {
        val isUnknown: Boolean get() = state == State.UNKNOWN
    }

    /** What the device currently says about a component. */
    enum class State {
        /** Explicitly disabled by the user. */
        DISABLED,

        /** Enabled explicitly, or back at its default. */
        ENABLED,

        /** The package is not installed, or the component no longer exists. */
        MISSING,

        /** Could not determine (command failed, unexpected output). */
        UNKNOWN
    }

    /**
     * Fast path: the set of packages currently disabled, in ONE command.
     *
     * `dumpsys package <pkg>` is heavy (thousands of lines, seconds each), so running it per
     * entry made 更新 appear to hang. `pm list packages -d` answers the package-level
     * question for the whole device in a single call, so only activity entries — which need
     * per-component state — still pay for a `dumpsys`.
     *
     * @return disabled package names, or null when the command failed (fall back to dumpsys).
     */
    suspend fun disabledPackages(): Set<String>? {
        val result = shell.run(arrayOf(ComponentDisabler.PM, "list", "packages", "-d"))
        if (!result.hasOutput) return null
        return parseDisabledPackages(result.stdout)
    }

    /**
     * Checks one history entry, preferring the cheap package list when available.
     *
     * Never throws: a failed command is reported as [State.UNKNOWN] together with the shell's
     * own message, which is what the UI shows the user.
     */
    suspend fun check(entry: DisableHistoryEntry, disabledPackages: Set<String>?): CheckResult {
        if (!entry.isActivity && disabledPackages != null) {
            val disabled = entry.packageName in disabledPackages
            return CheckResult(if (disabled) State.DISABLED else State.ENABLED, null)
        }

        val result = shell.run(arrayOf(ShizukuShell.DUMPSYS, "package", entry.packageName))
        if (!result.hasOutput) {
            // dumpsys failures are real errors, but filter traversal noise defensively so a
            // wrapper's chatter is never shown as the reason.
            val reason = ShellErrorText.meaningful(result.stderr).ifEmpty {
                "dumpsys 无输出（exit=" + result.exitCode + "）"
            }
            return CheckResult(State.UNKNOWN, reason)
        }

        // `pm list packages -d` is authoritative for the package itself, so an activity in a
        // disabled package is disabled even when its own line says otherwise.
        if (disabledPackages != null && entry.packageName in disabledPackages) {
            return CheckResult(State.DISABLED, null)
        }

        val state = parse(result.stdout, entry)
        val reason = if (state == State.UNKNOWN) {
            "无法从 dumpsys 输出解析该组件的 enabled 状态"
        } else {
            null
        }
        return CheckResult(state, reason)
    }

    companion object {
        private const val PACKAGE_PREFIX = "package:"

        /**
         * Section headers that carry per-component overrides. They only appear when the list
         * is non-empty.
         */
        private const val DISABLED_COMPONENTS_SETTING = "disabledComponents:"
        private const val ENABLED_COMPONENTS_SETTING = "enabledComponents:"

        /** Values `dumpsys` uses to mean "off". */
        private val DISABLED_VALUES = setOf(
            "disabled",
            "disabled-user",
            "disabled-until-used",
            "disabled-user-until-used"
        )

        /**
         * Extracts package names from `pm list packages -d` output (`package:com.foo` lines).
         *
         * Pure, so the parsing is unit-tested rather than trusted.
         */
        fun parseDisabledPackages(stdout: String): Set<String> =
            stdout.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith(PACKAGE_PREFIX) }
                .map { it.removePrefix(PACKAGE_PREFIX).trim() }
                .filter { it.isNotEmpty() }
                .toSet()

        /**
         * Resolves the state of [entry] from `dumpsys package` output.
         *
         * For an activity it first checks whether the whole package is disabled, then reads the
         * per-component override list; otherwise it uses the package-level `enabled=` flag.
         * Pure, so it is unit-tested on the JVM.
         */
        fun parse(dump: String, entry: DisableHistoryEntry): State {
            if (dump.isBlank()) return State.UNKNOWN
            if (dump.contains("Unable to find package") || dump.contains("Unknown package")) {
                return State.MISSING
            }

            val lines = dump.lineSequence().map { it.trim() }.toList()

            if (entry.isActivity) {
                val packageState = packageState(lines)
                // A disabled package disables every component in it, whatever the component
                // override says.
                if (packageState == State.DISABLED) return State.DISABLED
                componentOverride(lines, entry)?.let { return it }
                return packageState ?: State.UNKNOWN
            }

            return packageState(lines) ?: State.UNKNOWN
        }

        /**
         * Reads the `disabledComponents:` / `enabledComponents:` override list.
         *
         * Component overrides are **not** printed next to the component. `dumpsys package`
         * emits one block of overridden class names inside the package's user section, and only
         * when the list is non-empty:
         *
         * ```
         *       disabledComponents:
         *         com.android.certinstaller.CertInstallerMain
         * ```
         *
         * The previous implementation looked for `enabled=` on the component line, found
         * nothing on Android 16 (verified on device), and silently fell back to the package
         * state — so a disabled activity was reported as "enabled" and 更新 deleted the history
         * entry the user still needed to restore.
         */
        private fun componentOverride(lines: List<String>, entry: DisableHistoryEntry): State? {
            val cls = entry.activityClass ?: return null
            var mode: State? = null

            for (line in lines) {
                when {
                    line.isEmpty() -> mode = null
                    line == DISABLED_COMPONENTS_SETTING ->
                        mode = State.DISABLED
                    line == ENABLED_COMPONENTS_SETTING ->
                        mode = State.ENABLED
                    // Any other section header ends the list.
                    line.endsWith(":") -> mode = null
                    mode != null && matchesComponent(line, entry.packageName, cls) -> return mode
                }
            }
            return null
        }

        /**
         * Compares one entry of the override list with the stored component.
         *
         * The list prints fully qualified names, but a stored class may be relative (a leading
         * dot) on some paths, so both forms are accepted. The simple name alone is never
         * enough — it is shared with unrelated packages.
         */
        private fun matchesComponent(line: String, packageName: String, cls: String): Boolean {
            val normalized = if (cls.startsWith(".")) packageName + cls else cls
            if (line == normalized) return true
            return line.substringAfterLast('.') == normalized.substringAfterLast('.') &&
                line.contains(packageName)
        }

        /** Package-level `enabled=`, preferring the per-user resolved value. */
        private fun packageState(lines: List<String>): State? {
            for (line in lines) {
                enabledValue(line)?.let { return stateOf(it) }
            }
            return null
        }

        private fun enabledValue(line: String): String? {
            val marker = "enabled="
            val at = line.indexOf(marker)
            if (at < 0) return null
            val rest = line.substring(at + marker.length)
            val value = rest
                .substringBefore(' ')
                .substringBefore(',')
                .substringBefore(')')
                .trim()
            return value.ifEmpty { null }
        }

        /**
         * Maps an `enabled=` value to a state.
         *
         * `dumpsys` is not consistent across versions/OEM builds: some print the symbolic
         * name (`default`, `enabled`, `disabled-user`, …) and others print the raw
         * `COMPONENT_ENABLED_STATE_*` integer. Both forms are accepted, because treating the
         * numeric form as "unknown" would make 更新 silently do nothing on those devices.
         *
         * Numeric values: 0 = DEFAULT, 1 = ENABLED, 2 = DISABLED, 3 = DISABLED_USER,
         * 4 = DISABLED_UNTIL_USED.
         */
        private fun stateOf(value: String): State {
            val v = value.lowercase()

            v.toIntOrNull()?.let { code ->
                return when (code) {
                    0, 1 -> State.ENABLED
                    2, 3, 4 -> State.DISABLED
                    else -> State.UNKNOWN
                }
            }

            return when {
                v in DISABLED_VALUES -> State.DISABLED
                v.startsWith("disabled") -> State.DISABLED
                v.startsWith("enabled") || v == "default" -> State.ENABLED
                else -> State.UNKNOWN
            }
        }
    }
}
