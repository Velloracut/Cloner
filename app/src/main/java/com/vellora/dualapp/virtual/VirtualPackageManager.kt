package com.vellora.dualapp.virtual

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import dalvik.system.DexClassLoader
import java.io.File

private const val TAG = "VirtualEngine"

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

    /** All APK files that together make up a package (base + splits). */
    private fun apkPathsFor(context: Context, packageName: String): List<String> {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        return buildList {
            add(appInfo.sourceDir)
            appInfo.splitSourceDirs?.let { addAll(it) }
        }
    }

    /**
     * Loads the target app's real APK classes into our process via
     * DexClassLoader, parented to our own ClassLoader (so shared framework/
     * Kotlin-stdlib classes still resolve normally).
     *
     * Modern apps (especially anything updated via Play Store, like Google's
     * own apps) ship as SPLIT APKs — base.apk holds just a bootstrap, and
     * the real Activity/class code often lives in one of several
     * split_*.apk files installed alongside it. Passing only sourceDir here
     * caused ClassNotFoundException for exactly this reason — all split
     * paths need to go into the DexClassLoader's classpath together.
     */
    fun classLoaderFor(context: Context, packageName: String): ClassLoader? {
        classLoaderCache[packageName]?.let { return it }
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val apkPaths = apkPathsFor(context, packageName)
            val dexPath = apkPaths.joinToString(File.pathSeparator)
            val optimizedDir = File(sandboxRoot(context, packageName), "dex").apply { mkdirs() }
            val loader = DexClassLoader(
                dexPath,
                optimizedDir.absolutePath,
                appInfo.nativeLibraryDir,
                context.classLoader
            )
            classLoaderCache[packageName] = loader
            AppLogger.i(TAG, "classLoaderFor($packageName) OK — ${apkPaths.size} apk(s): $apkPaths")
            loader
        } catch (e: Exception) {
            AppLogger.e(TAG, "classLoaderFor($packageName) FAILED", e)
            null
        }
    }

    /**
     * Builds a Resources object backed by the target APK's own asset table
     * (via AssetManager.addAssetPath — a hidden method, needs
     * HiddenApiBypass to have run first). Without this, the cloned app's
     * XML layouts/strings/drawables would resolve against OUR resource IDs
     * and crash or render wrong.
     *
     * Same split-APK reasoning as classLoaderFor(): resources for
     * density/language/feature splits live in their own split_*.apk, not
     * base.apk, so every split's asset path needs adding too.
     */
    @Suppress("DEPRECATION")
    fun resourcesFor(context: Context, packageName: String): Resources? {
        resourcesCache[packageName]?.let { return it }
        return try {
            val apkPaths = apkPathsFor(context, packageName)
            val assetManager = AssetManager::class.java.newInstance()
            val addAssetPath = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath", String::class.java
            )
            addAssetPath.isAccessible = true
            apkPaths.forEach { path -> addAssetPath.invoke(assetManager, path) }

            val hostRes = context.resources
            val resources = Resources(assetManager, hostRes.displayMetrics, hostRes.configuration)
            resourcesCache[packageName] = resources
            AppLogger.i(TAG, "resourcesFor($packageName) OK — ${apkPaths.size} apk(s)")
            resources
        } catch (e: Exception) {
            AppLogger.e(TAG, "resourcesFor($packageName) FAILED", e)
            null
        }
    }
}
