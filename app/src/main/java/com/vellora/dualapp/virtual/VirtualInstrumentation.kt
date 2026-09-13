package com.vellora.dualapp.virtual

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import java.lang.reflect.Method

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
 * 3. [execStartActivity] — a cloned app's OWN internal startActivity() calls
 *    (e.g. a splash screen navigating to its main screen) are redirected
 *    the same way the initial launch was, using the signature-matching
 *    trick documented on that method (no `override` keyword needed/possible
 *    for a hidden method, but the JVM dispatches to it anyway).
 */
class VirtualInstrumentation(
    private val original: Instrumentation,
    private val appContext: Context
) : Instrumentation() {

    // execStartActivity is a HIDDEN framework method — invisible to the
    // compile-time Android SDK stubs, so Kotlin's `override` keyword can't
    // be used on it (the compiler can't see any such method to override).
    // BUT: the JVM/ART's actual virtual-method dispatch is based purely on
    // method name + parameter/return type descriptors, not on the `override`
    // keyword (that's a source-level/compiler-only check). So defining a
    // plain method here with the EXACT same signature the real framework
    // method has is enough for ActivityThread's call to
    // `mInstrumentation.execStartActivity(...)` to land on THIS method at
    // runtime, even though Kotlin itself doesn't know it's "overriding"
    // anything. This is the standard technique plugin/virtualization
    // frameworks use for exactly this class of hidden API.
    private val originalExecStartActivity: Method? by lazy {
        try {
            Instrumentation::class.java.getDeclaredMethod(
                "execStartActivity",
                Context::class.java, IBinder::class.java, IBinder::class.java, Activity::class.java,
                Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java
            ).apply { isAccessible = true }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "execStartActivity reflection setup FAILED", e)
            null
        }
    }

    @Suppress("unused") // matched by JVM signature at runtime, not a compile-time override
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): Instrumentation.ActivityResult? {
        try {
            // If the CALLER is an Activity we already launched as a clone
            // (it carries our EXTRA_TARGET_PACKAGE), treat this as that same
            // app navigating to one of its own other screens — redirect it
            // through VirtualStubActivity exactly like the initial launch.
            // NOTE: don't gate this on the new Intent's component package —
            // in testing it sometimes came back as OUR host package even
            // for the target's own internal navigation, so the only
            // reliable signal is "am I already pointed at VirtualStubActivity
            // (already redirected, don't redirect again)".
            val ambientTargetPackage = (who as? Activity)?.intent
                ?.getStringExtra(VirtualConstants.EXTRA_TARGET_PACKAGE)
            val realComponent = intent.component
            if (ambientTargetPackage != null && realComponent != null &&
                realComponent.className != VirtualStubActivity::class.java.name
            ) {
                intent.putExtra(VirtualConstants.EXTRA_TARGET_PACKAGE, ambientTargetPackage)
                intent.putExtra(VirtualConstants.EXTRA_TARGET_CLASS, realComponent.className)
                intent.component = ComponentName(appContext.packageName, VirtualStubActivity::class.java.name)
                AppLogger.i(TAG, "execStartActivity: redirected in-app navigation to ${realComponent.className}")
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "execStartActivity: redirect logic FAILED", e)
        }

        val method = originalExecStartActivity
            ?: throw IllegalStateException("execStartActivity reflection unavailable")
        // The real framework method legitimately returns null when no
        // result is expected (requestCode < 0) — must NOT force a non-null
        // cast here, that itself was crashing every single launch.
        return method.invoke(
            original, who, contextThread, token, target, intent, requestCode, options
        ) as Instrumentation.ActivityResult?
    }

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
                        // attach() already created a Theme object (mTheme)
                        // bound to the OLD (host) Resources/AssetManager.
                        // setTheme() alone re-applies styles onto that SAME
                        // stale object instead of building a fresh one — so
                        // the underlying asset lookups still hit the wrong
                        // app's resource table. Nulling mTheme first forces
                        // Android to build a brand new Theme against the
                        // Resources we already swapped in above.
                        try {
                            val themeField = android.view.ContextThemeWrapper::class.java.getDeclaredField("mTheme")
                            themeField.isAccessible = true
                            themeField.set(activity, null)
                        } catch (_: Throwable) {
                        }
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
            // Force a completely fresh start for cloned apps — don't hand
            // them a savedInstanceState bundle from a previous run/rotation.
            // Some apps (WebView-based ones especially) try to restore
            // navigation/cache state from that bundle, and since our
            // sandboxed cache directory is fresh each time, the referenced
            // cache entry no longer exists — this is exactly what caused
            // InvestAndEarn's WebView to show "net::ERR_CACHE_MISS" instead
            // of just loading the page normally.
            val freshIcicle: Bundle? = null

            // The target Activity's own onCreate() runs inside this super
            // call. If IT throws (missing target Application init, a
            // resource it expects that we didn't wire up, etc.), we catch
            // it here so the WHOLE host app doesn't crash — instead we log
            // the real exception (visible in View Logs) and close just this
            // one broken clone launch.
            try {
                super.callActivityOnCreate(activity, freshIcicle)
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

    // ---------- Full lifecycle logging (the "log checker" the user wants) ----------
    // These are all normal public Instrumentation methods (not hidden APIs),
    // so plain `override` works fine — no signature-matching trick needed
    // here. Wrapping each in try/catch means a crash at ANY lifecycle stage
    // (not just onCreate) gets a clear, labeled entry in View Logs instead
    // of just a generic uncaught-exception dump, and doesn't take the whole
    // host app down with it.

    override fun callActivityOnStart(activity: Activity) {
        logLifecycle(activity, "onStart")
        runTargetLifecycle(activity, "onStart") { super.callActivityOnStart(activity) }
    }

    override fun callActivityOnResume(activity: Activity) {
        logLifecycle(activity, "onResume")
        runTargetLifecycle(activity, "onResume") { super.callActivityOnResume(activity) }
    }

    override fun callActivityOnPause(activity: Activity) {
        logLifecycle(activity, "onPause")
        runTargetLifecycle(activity, "onPause") { super.callActivityOnPause(activity) }
    }

    override fun callActivityOnStop(activity: Activity) {
        logLifecycle(activity, "onStop")
        runTargetLifecycle(activity, "onStop") { super.callActivityOnStop(activity) }
    }

    override fun callActivityOnDestroy(activity: Activity) {
        logLifecycle(activity, "onDestroy")
        runTargetLifecycle(activity, "onDestroy") { super.callActivityOnDestroy(activity) }
    }

    private fun isOurClone(activity: Activity): Boolean =
        activity.intent?.getStringExtra(VirtualConstants.EXTRA_TARGET_PACKAGE) != null

    private fun logLifecycle(activity: Activity, event: String) {
        val targetPackage = activity.intent?.getStringExtra(VirtualConstants.EXTRA_TARGET_PACKAGE)
        if (targetPackage != null) {
            AppLogger.i(TAG, "lifecycle: $event → $targetPackage (${activity.javaClass.name})")
        }
    }

    /** Only intercepts (try/catch's) lifecycle calls for OUR cloned activities — everything else (our own MainActivity etc.) runs completely untouched. */
    private inline fun runTargetLifecycle(activity: Activity, event: String, block: () -> Unit) {
        if (!isOurClone(activity)) {
            block()
            return
        }
        try {
            block()
        } catch (e: Throwable) {
            AppLogger.e(TAG, "lifecycle: $event THREW for cloned activity ${activity.javaClass.name}", e)
        }
    }
}
