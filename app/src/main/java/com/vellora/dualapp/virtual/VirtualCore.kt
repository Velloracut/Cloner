package com.vellora.dualapp.virtual

import android.content.Context
import android.content.pm.PackageManager

/**
 * Single entry point for the whole virtualization engine. Everything the
 * rest of the app needs (clone / launch / uninstall / query) goes through
 * here, so MainActivity never has to know how cloning is actually done.
 *
 * PHASE 1 (current): stub implementation. "Cloning" an app just records the
 * package name — nothing is actually sandboxed or launched in isolation
 * yet. This keeps the app buildable and the UI functional while the real
 * engine is built underneath it in later phases.
 *
 * PHASE 2: HookManager will intercept ActivityThread / Instrumentation so a
 * cloned app's real Activity can be started inside VirtualStubActivity.
 *
 * PHASE 3: VirtualPackageManager + per-clone sandbox storage (each clone
 * gets its own data/, files/, databases/ directory under this app's
 * private storage — no separate Android user/profile needed).
 *
 * PHASE 4: MainActivity wired directly to this class (already mostly true
 * by the end of Phase 1).
 */
object VirtualCore {

    private const val PREFS_NAME = "virtual_core_registry"
    private lateinit var appContext: Context

    /** Must be called once, e.g. from Application.onCreate() or MainActivity. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * PHASE 1 stub: just marks [packageName] as "cloned" in local prefs.
     * PHASE 3 will replace this body with: copy the APK into a private
     * sandbox dir, create an isolated data directory, and register it with
     * VirtualPackageManager.
     */
    fun cloneApp(packageName: String): Boolean {
        return try {
            registry().edit().putBoolean(packageName, true).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isCloned(packageName: String): Boolean =
        registry().getBoolean(packageName, false)

    fun removeClone(packageName: String) {
        registry().edit().remove(packageName).apply()
    }

    fun clonedPackages(): Set<String> =
        registry().all.keys.filter { registry().getBoolean(it, false) }.toSet()

    /**
     * PHASE 1 stub: returns false always (nothing to launch yet — there is
     * no isolated instance to start). PHASE 2 will replace this with
     * HookManager.launch(packageName), which starts the target app's real
     * launcher Activity through VirtualStubActivity using a hooked
     * Instrumentation so it believes it's running as itself, in its own
     * sandboxed data directory.
     */
    fun launchClonedApp(packageName: String): Boolean {
        // TODO(Phase 2): HookManager.launch(appContext, packageName)
        return false
    }

    private fun registry() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
