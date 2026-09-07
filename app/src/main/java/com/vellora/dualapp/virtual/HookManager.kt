package com.vellora.dualapp.virtual

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * PHASE 2: installs the ActivityThread.mInstrumentation hook (see
 * VirtualInstrumentation) and exposes launch() to actually start a cloned
 * app's real Activity.
 *
 * Fragile-by-nature: field/method names on ActivityThread are internal and
 * can differ slightly across Android versions/OEM skins. If reflection
 * fails here, [installed] stays false and launch() returns false instead of
 * crashing — MainActivity already shows a fallback Toast for that case.
 */
object HookManager {

    var installed = false
        private set

    fun ensureHooksInstalled(context: Context) {
        if (installed) return
        HiddenApiBypass.exemptAll()
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
            currentActivityThreadMethod.isAccessible = true
            val activityThread = currentActivityThreadMethod.invoke(null)

            val instrumentationField = activityThreadClass.getDeclaredField("mInstrumentation")
            instrumentationField.isAccessible = true
            val original = instrumentationField.get(activityThread) as Instrumentation

            val hooked = VirtualInstrumentation(original, context.applicationContext)
            instrumentationField.set(activityThread, hooked)
            installed = true
        } catch (e: Throwable) {
            installed = false
        }
    }

    /**
     * Resolves [packageName]'s own launcher Activity, then points the
     * Intent at VirtualStubActivity instead (which IS declared in our
     * manifest, so AMS accepts it) while stashing the real target
     * package/class in extras. VirtualInstrumentation.newActivity() reads
     * those back and instantiates the real target Activity in its place.
     */
    fun launch(context: Context, packageName: String): Boolean {
        ensureHooksInstalled(context)
        if (!installed) return false

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        val realComponent = launchIntent.component ?: return false

        launchIntent.putExtra(VirtualConstants.EXTRA_TARGET_PACKAGE, realComponent.packageName)
        launchIntent.putExtra(VirtualConstants.EXTRA_TARGET_CLASS, realComponent.className)
        launchIntent.component = ComponentName(context.packageName, VirtualStubActivity::class.java.name)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        return try {
            context.startActivity(launchIntent)
            true
        } catch (e: Throwable) {
            false
        }
    }
}
