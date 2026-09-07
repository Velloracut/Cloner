package com.vellora.dualapp.virtual

/**
 * Android 9 (P) se upar, framework ke "hidden"/internal fields aur methods
 * tak seedha reflection access block hota hai (hidden-api-enforcement).
 * HookManager ko ActivityThread.mInstrumentation jaisi internal fields tak
 * pahunchna zaroori hai, isliye pehle yeh restriction hatani parti hai.
 *
 * Delegates to LSPosed's maintained `AndroidHiddenApiBypass` library
 * (Unsafe-based, stable across ART versions) instead of a hand-rolled
 * meta-reflection trick — some newer Android versions have started
 * force-blacklisting the exact VMRuntime method a manual trick would call
 * directly, so relying on an actively-updated library is safer long term.
 */
object HiddenApiBypass {
    private var exempted = false

    fun exemptAll() {
        if (exempted) return
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.setHiddenApiExemptions("L")
            exempted = true
        } catch (e: Throwable) {
            // Device/version-specific block possible — safe to continue;
            // downstream reflection calls will simply throw and
            // HookManager will report itself as not installed.
        }
    }
}
