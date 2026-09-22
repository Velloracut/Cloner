package com.vellora.dualapp.virtual

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.IOException

private const val TAG = "VirtualEngine"

/**
 * Uses Pine (real ART-level method hooking, Xposed-API compatible) to
 * intercept ONE specific, confirmed-problematic call:
 * `android.content.res.ApkAssets.loadOverlayFromPath(String, boolean)`.
 *
 * Why this exact method: it's how Android loads Runtime Resource Overlays
 * (RRO/idmap/.frro files) — OEM theme/customization overlays that get
 * layered onto an app's resources at runtime. Our virtualized AssetManager
 * (built via addAssetPath/ApkAssets.loadFromPath) never registers properly
 * with the system's overlay-manager bookkeeping, so when framework code
 * later tries to apply an overlay against it, the specific overlaid
 * resource ID doesn't resolve — this is the confirmed cause (cross-checked
 * against a real open-source engine's source, BlackBox) of the
 * Resources.NotFoundException crashes seen across THREE different cloned
 * apps (Asaloun, Opera, Gallery) at three different resource IDs, always
 * through `android.content.res.OverrideResources`.
 *
 * This method is `static`, so it CANNOT be intercepted by our normal
 * subclass/signature-matching trick (that only works for virtual/instance
 * methods on a class we actually instantiate, like Instrumentation). Static
 * calls from unrelated framework code need real ART-level hooking — that's
 * what Pine provides, without us writing any native code ourselves.
 *
 * Scope: intentionally narrow. This does not replace or touch any other
 * part of the engine — if Pine itself fails to install on some device,
 * [install] just logs and does nothing further; existing behavior is
 * unchanged.
 */
object PineOverlayHook {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        try {
            val pineClass = Class.forName("top.canyie.pine.Pine")
            AppLogger.i(TAG, "PineOverlayHook: Pine class found, attempting hook")

            XposedHelpers.findAndHookMethod(
                "android.content.res.ApkAssets",
                javaClass.classLoader,
                "loadOverlayFromPath",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = param.args.getOrNull(0) as? String
                        AppLogger.i(TAG, "PineOverlayHook: blocked overlay load for path=$path")
                        // Make the call fail the same way it would if the
                        // overlay genuinely didn't exist — the framework
                        // code calling this already expects/handles an
                        // IOException here and just skips that overlay.
                        param.throwable = IOException("Blocked by Cloner: virtualized overlay unsupported")
                    }
                }
            )
            installed = true
            AppLogger.i(TAG, "PineOverlayHook: installed OK")
        } catch (e: Throwable) {
            AppLogger.e(TAG, "PineOverlayHook: install FAILED (Pine unavailable or hook target changed)", e)
        }
    }
}
