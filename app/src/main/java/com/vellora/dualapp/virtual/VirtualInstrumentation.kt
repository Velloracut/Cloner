package com.vellora.dualapp.virtual

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle

private const val TAG = "VirtualEngine"

/** Extra keys used to smuggle the real target Activity through AMS. */
object VirtualConstants {
    const val EXTRA_TARGET_PACKAGE = "com.vellora.dualapp.virtual.EXTRA_TARGET_PACKAGE"
    const val EXTRA_TARGET_CLASS = "com.vellora.dualapp.virtual.EXTRA_TARGET_CLASS"
}

/**
 * PHASE 2 CORE: wraps the app's real system Instrumentation. Two jobs:
 *
 * 1. [newActivity] — when ActivityThread asks us to instantiate
 *    VirtualStubActivity (the component HookManager.launch() actually
 *    points the initial launch Intent at — see there for why), we read the
 *    real target package/class back from the Intent's extras and instantiate
 *    the REAL target Activity class instead, loaded through that package's
 *    own ClassLoader (see VirtualPackageManager). The stub declared in the
 *    manifest is only ever a formality for AMS's bookkeeping — the object
 *    that actually runs is the target app's real Activity subclass.
 *
 * 2. [callActivityOnCreate] — right before onCreate() runs, swap the
 *    Activity's base Context for a VirtualContext so the target app's code
 *    sees its own package name, resources, and sandboxed storage.
 *
 * NOT done yet: intercepting a cloned app's OWN internal startActivity()
 * calls (e.g. it navigating from its home screen to a settings screen).
 * That needs hooking `execStartActivity`, which is a HIDDEN framework
 * method — invisible to the compile-time Android SDK stubs, so it can't be
 * `override`n here without swapping in a full/hidden-API android.jar as
 * compileOnly (not set up yet). Today, only the app's initial launch (from
 * HookManager.launch()) is redirected; multi-screen in-app navigation
 * inside a clone is a known Phase 2 limitation to close next.
 */
class VirtualInstrumentation(
    private val original: Instrumentation,
    private val appContext: Context
) : Instrumentation() {

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent?): Activity {
        val targetPackage = intent?.getStringExtra(VirtualConstants.EXTRA_TARGET_PACKAGE)
        val targetClass = intent?.getStringExtra(VirtualConstants.EXTRA_TARGET_CLASS)
        AppLogger.i(TAG, "newActivity: className=$className targetPackage=$targetPackage targetClass=$targetClass")
        if (targetPackage != null && targetClass != null) {
            val targetLoader = VirtualPackageManager.classLoaderFor(appContext, targetPackage)
            if (targetLoader != null) {
                try {
                    val activity = super.newActivity(targetLoader, targetClass, intent)
                    AppLogger.i(TAG, "newActivity: REAL target Activity instantiated OK ($targetClass)")
                    return activity
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "newActivity: failed to instantiate $targetClass — falling back to stub", e)
                }
            } else {
                AppLogger.e(TAG, "newActivity: classLoaderFor($targetPackage) returned null — falling back to stub")
            }
        }
        return super.newActivity(cl, className, intent)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        val targetPackage = activity.intent?.getStringExtra(VirtualConstants.EXTRA_TARGET_PACKAGE)
        if (targetPackage != null) {
            try {
                val contextWrapperClass = Class.forName("android.content.ContextWrapper")
                val baseField = contextWrapperClass.getDeclaredField("mBase")
                baseField.isAccessible = true
                val realBase = baseField.get(activity) as Context
                baseField.set(activity, VirtualContext(realBase, targetPackage))
                AppLogger.i(TAG, "callActivityOnCreate: base context swapped OK for $targetPackage")
            } catch (e: Throwable) {
                AppLogger.e(TAG, "callActivityOnCreate: base context swap FAILED for $targetPackage", e)
            }
        }
        super.callActivityOnCreate(activity, icicle)
    }
}
