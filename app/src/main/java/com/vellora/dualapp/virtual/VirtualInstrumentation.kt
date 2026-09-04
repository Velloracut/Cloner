package com.vellora.dualapp.virtual

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/** Extra keys used to smuggle the real target Activity through AMS. */
object VirtualConstants {
    const val EXTRA_TARGET_PACKAGE = "com.vellora.dualapp.virtual.EXTRA_TARGET_PACKAGE"
    const val EXTRA_TARGET_CLASS = "com.vellora.dualapp.virtual.EXTRA_TARGET_CLASS"
}

/**
 * PHASE 2 CORE: wraps the app's real system Instrumentation. Three jobs:
 *
 * 1. [execStartActivity] — every startActivity() call passes through here
 *    before reaching AMS. If the target is a cloned package, we can't let
 *    AMS see it directly (it isn't "installed" as far as the system is
 *    concerned as a launchable component of OUR app) — so we swap the
 *    Intent's component to VirtualStubActivity (which IS declared in the
 *    manifest) and stash the real target package/class in extras.
 *
 * 2. [newActivity] — when ActivityThread asks us to instantiate
 *    VirtualStubActivity, we read those extras back and instantiate the
 *    REAL target Activity class instead, loaded through that package's own
 *    ClassLoader (see VirtualPackageManager). The stub declared in the
 *    manifest is only ever a formality for AMS's bookkeeping — the object
 *    that actually runs is the target app's real Activity subclass.
 *
 * 3. [callActivityOnCreate] — right before onCreate() runs, swap the
 *    Activity's base Context for a VirtualContext so the target app's code
 *    sees its own package name, resources, and sandboxed storage.
 */
class VirtualInstrumentation(
    private val original: Instrumentation,
    private val appContext: Context
) : Instrumentation() {

    override fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult {
        val realComponent = intent.component
        if (realComponent != null &&
            realComponent.packageName != who.packageName &&
            VirtualCore.isCloned(realComponent.packageName)
        ) {
            intent.putExtra(VirtualConstants.EXTRA_TARGET_PACKAGE, realComponent.packageName)
            intent.putExtra(VirtualConstants.EXTRA_TARGET_CLASS, realComponent.className)
            intent.component = ComponentName(who.packageName, VirtualStubActivity::class.java.name)
        }
        return super.execStartActivity(who, contextThread, token, target, intent, requestCode, options)
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent?): Activity {
        val targetPackage = intent?.getStringExtra(VirtualConstants.EXTRA_TARGET_PACKAGE)
        val targetClass = intent?.getStringExtra(VirtualConstants.EXTRA_TARGET_CLASS)
        if (targetPackage != null && targetClass != null) {
            val targetLoader = VirtualPackageManager.classLoaderFor(appContext, targetPackage)
            if (targetLoader != null) {
                try {
                    return super.newActivity(targetLoader, targetClass, intent)
                } catch (e: Throwable) {
                    // Target class load failed (missing dependency, weird
                    // multi-dex layout, etc.) — fall back to the stub below
                    // rather than crashing the whole launch.
                }
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
            } catch (e: Throwable) {
                // Base-context swap failed — the activity still launches,
                // just under the host's package identity/resources instead
                // of the cloned app's own (layouts may render incorrectly).
            }
        }
        super.callActivityOnCreate(activity, icicle)
    }
}
