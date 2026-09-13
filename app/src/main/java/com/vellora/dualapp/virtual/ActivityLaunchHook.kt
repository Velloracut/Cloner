package com.vellora.dualapp.virtual

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Message

private const val TAG = "VirtualEngine"

/**
 * ROOT-LEVEL FIX for the attach()-timing bug traced from the Alibaba/Quran
 * crash logs.
 *
 * THE BUG: VirtualInstrumentation's old context/resources/Application swap
 * happened in callActivityOnCreate() — but a target Activity's OWN
 * attachBaseContext() (and its real Application's attach/onCreate) already
 * run INSIDE Activity.attach(), which ActivityThread calls BEFORE
 * callActivityOnCreate(). So the target's own code always got one full
 * pass against the WRONG (host) context/resources/Application before we
 * ever swapped anything — that's the NullPointerException inside
 * Alibaba's attachBaseContext() and the Resources.NotFoundException inside
 * Quran's onCreate() (stale AssetManager/theme state left over from that
 * earlier wrong-context pass).
 *
 * THE FIX: intervene BEFORE ActivityThread builds that first context at
 * all. performLaunchActivity()/handleLaunchActivity() are private
 * ActivityThread methods only ever called from its own message handler
 * (H), so we hook H's Handler.Callback (mCallback) to intercept the launch
 * message BEFORE Android's real handleMessage() runs. There we rewrite the
 * pending launch's Intent component + ActivityInfo to point at the TARGET
 * package instead of our VirtualStubActivity host stub. Once that's done,
 * Android's OWN machinery —
 *   ActivityThread.getPackageInfoNoCheck(r.activityInfo.applicationInfo, …)
 *   → a REAL LoadedApk for the target (real AssetManager over the
 *     target's actual resources.arsc, real PathClassLoader over its real
 *     dex/split APKs — correctly resolves resource IDs like 0x7f110077,
 *     which is exactly what the hand-rolled resourcesFor()/classLoaderFor()
 *     in VirtualPackageManager could only approximate)
 *   ContextImpl.createActivityContext(…, r.packageInfo, r.activityInfo, …)
 *   → a REAL target-scoped Context
 *   r.packageInfo.makeApplication(false, mInstrumentation)
 *   → the target's REAL Application class, attached/onCreate'd BEFORE
 *     attach() even runs — precisely what Alibaba's attachBaseContext()
 *     needed and didn't have before
 * — build everything correctly, natively, exactly as if the target app had
 * genuinely been launched by the system. VirtualInstrumentation still
 * layers a thin storage-only wrapper afterward (see
 * VirtualStorageContext) since a real target Context's data dir belongs to
 * a UID our process can't write to — that's the one thing this hook
 * deliberately leaves for VirtualInstrumentation to handle.
 *
 * TWO MESSAGE SHAPES depending on API level:
 *  - API < 28: msg.what == LAUNCH_ACTIVITY (100). msg.obj IS the
 *    ActivityClientRecord directly — patch its `intent` and `activityInfo`
 *    fields.
 *  - API >= 28: msg.what == EXECUTE_TRANSACTION (159). msg.obj is a
 *    ClientTransaction carrying a list of ClientTransactionItem; find the
 *    LaunchActivityItem inside and patch ITS internal Intent/ActivityInfo
 *    fields — found BY TYPE via reflection (not by field name), since
 *    LaunchActivityItem's private field names have shifted across
 *    releases but there is reliably exactly one Intent field and one
 *    ActivityInfo field on it.
 *
 * EXTREMELY reflection-heavy and version/OEM-fragile by nature, same as
 * every other hook in this engine — every step is wrapped so a failure
 * anywhere just aborts THIS launch's patch attempt (no
 * EXTRA_REAL_HOOK_APPLIED gets set, so VirtualInstrumentation transparently
 * falls back to its old manual-swap path) instead of crashing the host.
 */
object ActivityLaunchHook {

    private const val LAUNCH_ACTIVITY = 100
    private const val EXECUTE_TRANSACTION = 159

    /**
     * Quick kill-switch for testing — flip to false (and rebuild) to
     * instantly go back to the old manual-swap-only behavior without
     * touching HookManager or reverting any commit. Useful while this
     * hook is still being validated against real devices/Android
     * versions: if a regression shows up, set this false, rebuild, and
     * confirm the OLD behavior is restored — that isolates whether THIS
     * hook is the cause before digging into field-shape mismatches.
     */
    var enabled = true

    private var installed = false

    fun ensureInstalled(context: Context) {
        if (!enabled) {
            AppLogger.i(TAG, "ActivityLaunchHook: disabled via kill-switch — skipping install")
            return
        }
        if (installed) return
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
            currentActivityThreadMethod.isAccessible = true
            val activityThread = currentActivityThreadMethod.invoke(null)

            val hField = activityThreadClass.getDeclaredField("mH")
            hField.isAccessible = true
            val h = hField.get(activityThread) as Handler

            val callbackField = Handler::class.java.getDeclaredField("mCallback")
            callbackField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val originalCallback = callbackField.get(h) as? Handler.Callback

            val appContext = context.applicationContext
            callbackField.set(
                h,
                Handler.Callback { msg ->
                    try {
                        when (msg.what) {
                            LAUNCH_ACTIVITY -> patchLegacyLaunch(appContext, msg)
                            EXECUTE_TRANSACTION -> patchClientTransaction(appContext, msg)
                        }
                    } catch (e: Throwable) {
                        AppLogger.e(
                            TAG,
                            "ActivityLaunchHook: patch attempt threw (non-fatal — falling back to manual swap)",
                            e
                        )
                    }
                    // We only ever mutate the message payload IN PLACE and
                    // never consume it — always let the real handler run
                    // afterward exactly as it normally would.
                    originalCallback?.handleMessage(msg) ?: false
                }
            )
            installed = true
            AppLogger.i(TAG, "ActivityLaunchHook: H.mCallback installed OK")
        } catch (e: Throwable) {
            installed = false
            AppLogger.e(
                TAG,
                "ActivityLaunchHook: install FAILED — every launch will use the old manual context/resources swap instead",
                e
            )
        }
    }

    private fun patchLegacyLaunch(context: Context, msg: Message) {
        val record = msg.obj ?: return
        val recordClass = record.javaClass
        // Defensive: only touch messages whose payload really is an
        // ActivityClientRecord (simple-name check, not exact class
        // reference — this class lives inside ActivityThread and isn't
        // otherwise reachable without matching reflection on both sides).
        if (recordClass.simpleName != "ActivityClientRecord") return

        // Find BOTH fields first, before touching anything. Mutating the
        // Intent's component and only THEN discovering the ActivityInfo
        // field can't be found would leave component pointing at the
        // target while activityInfo still says host — that mismatch
        // ClassNotFoundExceptions EVERY launch, not just this one.
        val intentField = findField(recordClass, Intent::class.java) ?: run {
            AppLogger.e(TAG, "ActivityLaunchHook: no Intent field on ActivityClientRecord — API shape changed?")
            return
        }
        val infoField = findField(recordClass, ActivityInfo::class.java) ?: run {
            AppLogger.e(
                TAG,
                "ActivityLaunchHook: no ActivityInfo field on ActivityClientRecord — API shape changed? " +
                    "Aborting patch, leaving this launch untouched (manual-swap fallback will handle it)."
            )
            return
        }

        val intent = intentField.get(record) as? Intent ?: return
        val realComponent = extractRedirectTarget(intent) ?: return
        val realActivityInfo = resolveActivityInfo(context, realComponent) ?: return

        val originalComponent = intent.component
        try {
            intent.component = realComponent
            infoField.set(record, realActivityInfo)
            intent.putExtra(VirtualConstants.EXTRA_REAL_HOOK_APPLIED, true)
            AppLogger.i(TAG, "ActivityLaunchHook: legacy LAUNCH_ACTIVITY patched OK → $realComponent")
        } catch (e: Throwable) {
            // Roll back the Intent so this launch falls through to the
            // OLD manual-swap path instead of a half-patched, guaranteed-
            // to-crash state.
            intent.component = originalComponent
            intent.removeExtra(VirtualConstants.EXTRA_REAL_HOOK_APPLIED)
            AppLogger.e(TAG, "ActivityLaunchHook: mutation FAILED mid-patch — rolled back to unpatched state", e)
        }
    }

    private fun patchClientTransaction(context: Context, msg: Message) {
        val transaction = msg.obj ?: return
        val getCallbacks = try {
            transaction.javaClass.getMethod("getCallbacks")
        } catch (e: Throwable) {
            AppLogger.e(TAG, "ActivityLaunchHook: ClientTransaction.getCallbacks() not found — API shape changed?", e)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val callbacks = getCallbacks.invoke(transaction) as? List<Any> ?: return

        for (item in callbacks) {
            if (item.javaClass.simpleName != "LaunchActivityItem") continue
            patchLaunchActivityItem(context, item)
        }
    }

    private fun patchLaunchActivityItem(context: Context, item: Any) {
        val itemClass = item.javaClass

        // Same ordering fix as patchLegacyLaunch: find BOTH fields before
        // mutating anything, so a lookup failure aborts cleanly instead of
        // leaving the Intent's component pointed at the target while
        // ActivityInfo still says host — that mismatch is what was
        // crashing EVERY clone launch (including previously-working ones)
        // after this hook was first added.
        val intentField = findField(itemClass, Intent::class.java) ?: run {
            AppLogger.e(TAG, "ActivityLaunchHook: no Intent field on LaunchActivityItem — API shape changed?")
            return
        }
        val infoField = findField(itemClass, ActivityInfo::class.java) ?: run {
            AppLogger.e(
                TAG,
                "ActivityLaunchHook: no ActivityInfo field on LaunchActivityItem — API shape changed? " +
                    "Aborting patch, leaving this launch untouched (manual-swap fallback will handle it)."
            )
            return
        }

        val intent = intentField.get(item) as? Intent ?: return
        val realComponent = extractRedirectTarget(intent) ?: return
        val realActivityInfo = resolveActivityInfo(context, realComponent) ?: return

        val originalComponent = intent.component
        try {
            intent.component = realComponent
            infoField.set(item, realActivityInfo)
            intent.putExtra(VirtualConstants.EXTRA_REAL_HOOK_APPLIED, true)
            AppLogger.i(TAG, "ActivityLaunchHook: ClientTransaction/LaunchActivityItem patched OK → $realComponent")
        } catch (e: Throwable) {
            intent.component = originalComponent
            intent.removeExtra(VirtualConstants.EXTRA_REAL_HOOK_APPLIED)
            AppLogger.e(TAG, "ActivityLaunchHook: mutation FAILED mid-patch — rolled back to unpatched state", e)
        }
    }

    /** Only redirect launches WE created (see HookManager.launch) — recognized by our own extras. */
    private fun extractRedirectTarget(intent: Intent): ComponentName? {
        val targetPackage = intent.getStringExtra(VirtualConstants.EXTRA_TARGET_PACKAGE) ?: return null
        val targetClass = intent.getStringExtra(VirtualConstants.EXTRA_TARGET_CLASS) ?: return null
        // Already patched (e.g. execStartActivity's in-app-navigation
        // redirect building a fresh VirtualStubActivity-pointed Intent
        // for the SAME clone) — nothing new to do here.
        if (intent.getBooleanExtra(VirtualConstants.EXTRA_REAL_HOOK_APPLIED, false)) return null
        return ComponentName(targetPackage, targetClass)
    }

    private fun resolveActivityInfo(context: Context, component: ComponentName): ActivityInfo? {
        return try {
            context.packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "ActivityLaunchHook: getActivityInfo($component) FAILED", e)
            null
        }
    }

    /** Finds the first declared field on [cls] whose type is [type] — robust across API-level field-name churn. */
    private fun findField(cls: Class<*>, type: Class<*>): java.lang.reflect.Field? =
        cls.declaredFields.firstOrNull { type.isAssignableFrom(it.type) }?.apply { isAccessible = true }
}
