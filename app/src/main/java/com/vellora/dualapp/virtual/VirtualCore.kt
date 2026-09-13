package com.vellora.dualapp.virtual

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

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

    /**
     * Best-effort "which clone is currently active" hint, updated by
     * VirtualInstrumentation's lifecycle logging. Used only to label
     * crash-log entries as belonging to the HOST engine vs a specific
     * CLONE — not a precise foreground tracker (with multiple tasks it can
     * lag behind reality), but good enough for diagnosis.
     */
    @Volatile
    var activeClonePackage: String? = null

    /** Must be called once, e.g. from Application.onCreate() or MainActivity. */
    fun init(context: Context) {
        appContext = context.applicationContext
        AppLogger.init(appContext)
        installGlobalCrashLogger()
        logPastProcessExits()
    }

    /**
     * Catches crashes that happen OUTSIDE the specific try/catch blocks in
     * VirtualInstrumentation (e.g. a cloned app's own internal in-app
     * navigation, or something going wrong after onCreate() has already
     * returned successfully) so they still get logged before the normal
     * Android crash dialog takes over — otherwise these show up as a silent
     * "app just closed" with nothing in View Logs to explain why.
     *
     * Labels each entry HOST or CLONE:<package> (see [activeClonePackage])
     * so it's clear at a glance whether the engine itself broke or a
     * specific cloned app did. Covers ANY thread, not just main — Java-level
     * exceptions from a clone's own background threads land here too.
     *
     * Does NOT catch: native (JNI/C++) crashes or ANRs — those aren't
     * thrown exceptions at all, so no UncaughtExceptionHandler can see them.
     * [logPastProcessExits] covers those instead, retrospectively.
     */
    private fun installGlobalCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val label = activeClonePackage?.let { "CLONE:$it" } ?: "HOST"
            AppLogger.e("VirtualEngine/$label", "UNCAUGHT crash on thread \"${thread.name}\"", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * ANRs and native crashes kill the process outright — nothing inside
     * the process can log them as they happen. Android keeps its own
     * record of why a process last exited (REASON_ANR, REASON_CRASH_NATIVE,
     * etc.) via ApplicationExitInfo, retrievable on the NEXT cold start.
     * This is the same mechanism Firebase Crashlytics/Play Vitals use for
     * exactly this class of crash — there's no way to observe it live from
     * inside the frozen/crashed process itself, only after the fact.
     */
    private fun logPastProcessExits() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 5)
            for (info in reasons) {
                val reasonName = when (info.reason) {
                    ApplicationExitInfo.REASON_ANR -> "ANR"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                    ApplicationExitInfo.REASON_CRASH -> "CRASH"
                    else -> "exit(reason=${info.reason})"
                }
                AppLogger.i(
                    "VirtualEngine/PastExit",
                    "Previous session ended: $reasonName — ${info.description ?: "no description"} " +
                        "(pid=${info.pid}, time=${java.util.Date(info.timestamp)})"
                )
                if (Build.VERSION.SDK_INT >= 31 &&
                    (info.reason == ApplicationExitInfo.REASON_ANR ||
                        info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
                ) {
                    try {
                        val trace = info.traceInputStream?.bufferedReader()?.readText()
                        if (!trace.isNullOrBlank()) {
                            AppLogger.i("VirtualEngine/PastExit", "Trace for above exit:\n$trace")
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("VirtualEngine", "logPastProcessExits FAILED", e)
        }
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
            AppLogger.e("VirtualEngine", "cloneApp($packageName) FAILED", e)
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
