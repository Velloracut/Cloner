package com.vellora.dualapp.virtual

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * PHASE 2/3: wraps the real (host-app) base Context so a cloned app's code
 * believes it IS itself — own package name, own resources, own storage —
 * while actually still executing inside this app's process. Installed by
 * VirtualInstrumentation.callActivityOnCreate() just before the cloned
 * Activity's onCreate() runs.
 *
 * This is what makes two clones of the same app keep separate data: every
 * storage call is redirected into that package's own sandbox folder instead
 * of the host app's normal private storage.
 */
class VirtualContext(
    base: Context,
    private val targetPackage: String
) : ContextWrapper(base) {

    private val sandboxDir: File by lazy {
        VirtualPackageManager.sandboxRoot(base, targetPackage)
    }

    override fun getPackageName(): String = targetPackage

    override fun getClassLoader(): ClassLoader =
        VirtualPackageManager.classLoaderFor(baseContext, targetPackage) ?: super.getClassLoader()

    override fun getResources(): Resources =
        VirtualPackageManager.resourcesFor(baseContext, targetPackage) ?: super.getResources()

    override fun getFilesDir(): File =
        File(sandboxDir, "files").apply { mkdirs() }

    override fun getCacheDir(): File =
        File(sandboxDir, "cache").apply { mkdirs() }

    override fun getDatabasePath(name: String): File {
        val dbDir = File(sandboxDir, "databases").apply { mkdirs() }
        return File(dbDir, name)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?
    ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), null)

    /**
     * Phase-2 simplification: isolated by giving each clone a uniquely
     * prefixed prefs name rather than a fully separate file path. Good
     * enough to guarantee no collisions with the host app's own prefs;
     * Phase 3 can move this to a real per-sandbox XML file if needed.
     */
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("virtual_${targetPackage}_$name", mode)
}
