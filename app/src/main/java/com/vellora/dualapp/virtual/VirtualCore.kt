package com.vellora.dualapp.virtual

import android.content.Context

/**
 * Single entry point for the whole virtualization engine. Everything the
 * rest of the app needs (clone / launch / uninstall / query) goes through
 * here, so MainActivity never has to know how cloning is actually done.
 *
 * PHASE 1 (done): registry of which packages are "cloned", backed by prefs.
 *
 * PHASE 2 (done): launchClonedApp() now routes through HookManager, which
 * hooks Instrumentation so a cloned app's real Activity actually starts,
 * with its own resources and sandboxed storage (VirtualContext).
 *
 * PHASE 3 (next): today, cloning a package only registers it — the real
 * APK/data isolation happens lazily at launch time via
 * VirtualPackageManager. Phase 3 will make clone-time itself pre-warm the
 * sandbox (copy APK metadata, pre-create dirs) and add proper
 * uninstall/cleanup handling.
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
     * PHASE 2: routes through HookManager, which hooks Instrumentation and
     * starts the target app's real launcher Activity through
     * VirtualStubActivity — the target believes it's running as itself, in
     * its own sandboxed data directory (VirtualContext).
     */
    fun launchClonedApp(packageName: String): Boolean =
        HookManager.launch(appContext, packageName)

    private fun registry() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
