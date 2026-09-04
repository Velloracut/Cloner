package com.vellora.dualapp.virtual

import android.content.Context

/**
 * PHASE 2 WORK GOES HERE (not yet implemented).
 *
 * This is where the actual reflection-based hooking lives:
 *
 *  - Hook `Instrumentation` on the current `ActivityThread` (via reflection
 *    on `ActivityThread.currentActivityThread()` -> field `mInstrumentation`)
 *    with our own subclass, so that when a cloned app's Activity is asked
 *    to start, we intercept `execStartActivity` / `newActivity` and swap in
 *    the target app's real Activity class loaded through our own
 *    ClassLoader instead of the system's.
 *
 *  - Hook `ActivityManager` (via `IActivityManager` / `IActivityTaskManager`
 *    proxies depending on Android version) so `startActivity` calls
 *    targeting a "not really installed" package are redirected to
 *    VirtualStubActivity, which the system DOES know about.
 *
 *  - This is the single most fragile part of the whole project: the exact
 *    field/method names on ActivityThread, IActivityManager etc. change
 *    across Android versions (sometimes even between minor releases), so
 *    this needs version-gated reflection (`Build.VERSION.SDK_INT` checks)
 *    with a fallback path per major Android version we want to support.
 *
 *  - Reference implementations to study: VirtualApp (asLody/VirtualApp on
 *    GitHub) and DroidPlugin — both open-source, both solve this exact
 *    problem. We will port/adapt their hooking approach rather than
 *    reinventing it from scratch.
 */
object HookManager {

    private var installed = false

    /** PHASE 2: install the Instrumentation + AMS hooks. Currently a no-op. */
    fun ensureHooksInstalled(context: Context) {
        if (installed) return
        // TODO(Phase 2): reflective hook installation goes here.
        installed = true
    }

    /** PHASE 2: actually start [packageName]'s launcher activity virtually. */
    fun launch(context: Context, packageName: String): Boolean {
        ensureHooksInstalled(context)
        // TODO(Phase 2): resolve target app's launch intent via
        // VirtualPackageManager, then route it through VirtualStubActivity.
        return false
    }
}
