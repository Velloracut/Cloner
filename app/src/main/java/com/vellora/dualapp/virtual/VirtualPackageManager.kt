package com.vellora.dualapp.virtual

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import dalvik.system.DexClassLoader
import java.io.File

/**
 * PHASE 2/3: makes a cloned app's real, already-installed APK usable inside
 * OUR process — loading its classes and its resources (layouts, strings,
 * drawables) so it behaves like itself instead of falling back to our
 * (mismatched) resource IDs and classes.
 *
 * Sandbox layout per cloned package:
 *   /data/data/com.vellora.dualapp/files/virtual/<packageName>/
 *       ├─ files/        (getFilesDir() redirect)
 *       ├─ cache/        (getCacheDir() redirect)
 *       ├─ databases/    (getDatabasePath() redirect)
 *       └─ dex/          (ART's optimized-dex cache for DexClassLoader)
 */
object VirtualPackageManager {

    private val classLoaderCache = mutableMapOf<String, ClassLoader>()
    private val resourcesCache = mutableMapOf<String, Resources>()

    fun sandboxRoot(context: Context, packageName: String): File =
        File(context.filesDir, "virtual/$packageName").apply { mkdirs() }

    /**
     * Loads the target app's real APK classes into our process via
     * DexClassLoader, parented to our own ClassLoader (so shared framework/
     * Kotlin-stdlib classes still resolve normally).
     */
    fun classLoaderFor(context: Context, packageName: String): ClassLoader? {
        classLoaderCache[packageName]?.let { return it }
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val optimizedDir = File(sandboxRoot(context, packageName), "dex").apply { mkdirs() }
            val loader = DexClassLoader(
                appInfo.sourceDir,
                optimizedDir.absolutePath,
                appInfo.nativeLibraryDir,
                context.classLoader
            )
            classLoaderCache[packageName] = loader
            loader
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Builds a Resources object backed by the target APK's own asset table
     * (via AssetManager.addAssetPath — a hidden method, needs
     * HiddenApiBypass to have run first). Without this, the cloned app's
     * XML layouts/strings/drawables would resolve against OUR resource IDs
     * and crash or render wrong.
     */
    fun resourcesFor(context: Context, packageName: String): Resources? {
        resourcesCache[packageName]?.let { return it }
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val assetManager = AssetManager::class.java.newInstance()
            val addAssetPath = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath", String::class.java
            )
            addAssetPath.isAccessible = true
            addAssetPath.invoke(assetManager, appInfo.sourceDir)

            val hostRes = context.resources
            val resources = Resources(assetManager, hostRes.displayMetrics, hostRes.configuration)
            resourcesCache[packageName] = resources
            resources
        } catch (e: Exception) {
            null
        }
    }
}
