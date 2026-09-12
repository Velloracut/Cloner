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
        val targetClass = activity.intent?.getStringExtra(VirtualConstants.EXTRA_TARGET_CLASS)
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

            // Activity caches its OWN Resources reference in a private
            // field (set once during attach(), from the manifest's
            // VirtualStubActivity — i.e. OUR host resources) rather than
            // reading dynamically through mBase.getResources() each time.
            // Swapping mBase alone does NOT change what activity.getResources()
            // returns — this field has to be patched too, or every resource
            // lookup (drawables, AppCompat's internal checks, etc.) resolves
            // against the WRONG app's resource table and throws
            // Resources.NotFoundException, exactly as seen in testing.
            try {
                val resources = VirtualPackageManager.resourcesFor(appContext, targetPackage)
                if (resources != null) {
                    // mResources lives on ContextThemeWrapper (Activity's
                    // parent class), not on Activity itself — getDeclaredField
                    // only checks the exact class given, so this has to
                    // target ContextThemeWrapper specifically.
                    val resField = android.view.ContextThemeWrapper::class.java.getDeclaredField("mResources")
                    resField.isAccessible = true
                    resField.set(activity, resources)
                    AppLogger.i(TAG, "callActivityOnCreate: mResources swapped OK for $targetPackage")
                }
            } catch (e: Throwable) {
                AppLogger.e(TAG, "callActivityOnCreate: mResources swap FAILED for $targetPackage", e)
            }

            // The Activity's theme was also set up during attach() using
            // VirtualStubActivity's manifest theme id — a number that's
            // meaningless (or wrong) against the target's own resource
            // table now that mResources has changed. Re-apply the TARGET's
            // own declared theme (falling back to its Application-level
            // theme) so styled-attribute lookups resolve correctly.
            if (targetClass != null) {
                try {
                    val pm = appContext.packageManager
                    val activityInfo = pm.getActivityInfo(
                        android.content.ComponentName(targetPackage, targetClass), 0
                    )
                    val themeResId = if (activityInfo.theme != 0) activityInfo.theme
                    else pm.getApplicationInfo(targetPackage, 0).theme
                    if (themeResId != 0) {
                        activity.setTheme(themeResId)
                        AppLogger.i(TAG, "callActivityOnCreate: theme re-applied OK for $targetPackage")
                    }
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "callActivityOnCreate: theme re-apply FAILED for $targetPackage", e)
                }
            }

            // Give the Activity the target app's own (real) Application
            // instance — already initialized via its own onCreate() — so
            // getApplication() returns something the target's code can
            // actually rely on, instead of our host app's Application.
            try {
                val app = VirtualPackageManager.applicationFor(appContext, targetPackage)
                if (app != null) {
                    val appField = Activity::class.java.getDeclaredField("mApplication")
                    appField.isAccessible = true
                    appField.set(activity, app)
                    AppLogger.i(TAG, "callActivityOnCreate: fake Application attached for $targetPackage")
                }
            } catch (e: Throwable) {
                AppLogger.e(TAG, "callActivityOnCreate: attaching fake Application FAILED for $targetPackage", e)
            }
        }

        if (targetPackage != null) {
            // The target Activity's own onCreate() runs inside this super
            // call. If IT throws (missing target Application init, a
            // resource it expects that we didn't wire up, etc.), we catch
            // it here so the WHOLE host app doesn't crash — instead we log
            // the real exception (visible in View Logs) and close just this
            // one broken clone launch.
            try {
                super.callActivityOnCreate(activity, icicle)
            } catch (e: Throwable) {
                AppLogger.e(TAG, "callActivityOnCreate: target onCreate() THREW for $targetPackage", e)
                try {
                    activity.finish()
                } catch (_: Throwable) {
                }
            }
        } else {
            super.callActivityOnCreate(activity, icicle)
        }
    }
}
