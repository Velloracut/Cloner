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

/**
 * Storage-only counterpart to [VirtualContext], used when
 * [ActivityLaunchHook] already made [base] a REAL, correctly-identified
 * target Context (real package name, real resources, real classloader —
 * all handled natively by Android's own attach() machinery). Package
 * identity/resources/classloader are already right, so this wrapper does
 * NOT override getPackageName()/getResources()/getClassLoader() at all —
 * those simply fall through to [base] via normal ContextWrapper delegation.
 * It only redirects storage, which is the one thing a real target Context
 * can't safely give us (its real system data dir belongs to a different
 * UID than our actual running process).
 */
class VirtualStorageContext(
    base: Context,
    private val hostContext: Context,
    private val targetPackage: String
) : ContextWrapper(base) {

    // IMPORTANT: sandboxRoot must be anchored to the HOST app's own real
    // filesDir (a directory our actual process UID can write to) — NOT
    // base.filesDir, which now resolves through the target's REAL
    // ContextImpl to the target package's own system data dir (a
    // different UID's private storage our process has no permission to
    // touch). This is exactly the bug this class exists to avoid.
    private val sandboxDir: File by lazy {
        VirtualPackageManager.sandboxRoot(hostContext, targetPackage)
    }

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

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("virtual_${targetPackage}_$name", mode)
}
