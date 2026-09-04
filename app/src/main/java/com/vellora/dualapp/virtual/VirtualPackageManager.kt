package com.vellora.dualapp.virtual

import android.content.Context
import java.io.File

/**
 * PHASE 3 WORK GOES HERE (not yet implemented).
 *
 * Responsible for making a cloned app believe it is normally installed,
 * without it actually being registered with the system PackageManager:
 *
 *  - Load the target app's real APK (copied from its existing install
 *    location into our sandbox) using a custom ClassLoader/DexClassLoader,
 *    so its classes run inside OUR process without a second install.
 *
 *  - Proxy `Context.getPackageManager()` calls for the cloned app's own
 *    package name so `getApplicationInfo()`, `getPackageInfo()`, etc.
 *    return believable data instead of throwing NameNotFoundException.
 *
 *  - Per-clone sandbox root, e.g.:
 *      /data/data/com.vellora.dualapp/virtual/<packageName>/
 *          ├─ data/       (SharedPreferences, databases — isolated per clone)
 *          ├─ files/
 *          └─ apk/        (copy of the target app's APK)
 *    This is what gives each clone its own separate storage without
 *    needing a second Android user profile.
 */
object VirtualPackageManager {

    fun sandboxRoot(context: Context, packageName: String): File =
        File(context.filesDir, "virtual/$packageName").apply { mkdirs() }

    // TODO(Phase 3): copyApkIntoSandbox(context, packageName)
    // TODO(Phase 3): loadClasses(packageName): ClassLoader
    // TODO(Phase 3): getApplicationInfoFor(packageName): ApplicationInfo
}
