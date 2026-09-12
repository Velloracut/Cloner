package com.vellora.dualapp.virtual

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
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
    private val applicationCache = mutableMapOf<String, Application>()

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
     * DexClassLoader, parented to the pure BOOT classloader (android.*,
     * java.* framework classes only) — NOT our own host app's classloader.
     *
     * Why: Java/ART classloading is parent-first by default. If we parented
     * to our own (host) classloader, any library BOTH our host app and a
     * cloned app happen to bundle (e.g. Compose Material3 — Cloner itself
     * uses it too) would resolve to OUR host's copy first, even when the
     * cloned app ships a different, incompatible version of that same
     * library. That's exactly what caused a NoSuchMethodError in testing
     * (BluePrint's own Compose call resolved against Cloner's own bundled
     * class instead of BluePrint's). Parenting to the boot classloader
     * means the target's own DexClassLoader is authoritative for its own
     * bundled libraries, while still sharing core android.*/java.* framework
     * classes normally (those always come from the boot classpath anyway,
     * regardless of which app classloader asks for them).
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
                ClassLoader.getSystemClassLoader()
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

    /**
     * Instantiates and initializes the target app's OWN Application class
     * (its `android:name` in its manifest) — VirtualApp does the same thing
     * for exactly this reason: most real apps set up singletons/global
     * state in their Application.onCreate() that their Activities depend
     * on. Without this, an Activity that assumes its Application already
     * ran init code crashes with a null-state exception the moment it
     * touches that state.
     */
    fun applicationFor(context: Context, packageName: String): Application? {
        applicationCache[packageName]?.let { return it }
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val loader = classLoaderFor(context, packageName) ?: return null
            val appClassName = appInfo.className ?: "android.app.Application"
            val appClass = loader.loadClass(appClassName)
            val app = appClass.getDeclaredConstructor().newInstance() as Application

            val virtualContext = VirtualContext(context, packageName)
            // attachBaseContext is `protected`, not hidden — plain
            // reflection + setAccessible is enough, no HiddenApiBypass needed.
            val attachMethod = ContextWrapper::class.java
                .getDeclaredMethod("attachBaseContext", Context::class.java)
            attachMethod.isAccessible = true
            attachMethod.invoke(app, virtualContext)

            app.onCreate()
            applicationCache[packageName] = app
            AppLogger.i(TAG, "applicationFor($packageName) OK — class=$appClassName")
            app
        } catch (e: Throwable) {
            AppLogger.e(TAG, "applicationFor($packageName) FAILED", e)
            null
        }
    }
}
