package com.vellora.dualapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.os.Build
import android.os.UserManager
import androidx.core.content.ContextCompat

/**
 * Wraps the official Android "Work Profile" APIs (android.app.admin.*
 * and android.content.pm.CrossProfileApps) that apps like Island/Shelter
 * use to clone apps. No hooking, no reverse-engineering, no reflection —
 * everything here is a public, documented Android API.
 */
object WorkProfileManager {

    private fun adminComponent(context: Context) =
        ComponentName(context, CloneDeviceAdminReceiver::class.java)

    private fun dpm(context: Context) =
        ContextCompat.getSystemService(context, DevicePolicyManager::class.java)!!

    /** True if THIS process is currently running inside the managed work profile. */
    fun isRunningInsideWorkProfile(context: Context): Boolean {
        val um = ContextCompat.getSystemService(context, UserManager::class.java) ?: return false
        return um.isManagedProfile
    }

    /** True if a work profile already exists and this app owns it. */
    fun isProfileOwner(context: Context): Boolean =
        dpm(context).isProfileOwnerApp(context.packageName)

    /**
     * Step 1 (runs in the PERSONAL profile).
     * Kicks off the official system flow that creates a Work Profile and
     * installs a copy of this same app into it. The user must confirm —
     * this cannot be done silently, by Android design.
     */
    fun buildProvisioningIntent(context: Context): Intent {
        return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                adminComponent(context)
            )
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION,
                true
            )
        }
    }

    /**
     * Step 2 (must run INSIDE the work-profile copy of this app, i.e. after
     * the user opens the badged "Cloner" icon that appears post-provisioning).
     *
     * Installs an already-on-device app's APK into the work profile. This
     * only works for apps whose APK is already present on the device for
     * another user — it does not download or modify anything.
     */
    fun cloneIntoWorkProfile(context: Context, packageName: String): Boolean {
        if (!isRunningInsideWorkProfile(context)) return false
        val admin = adminComponent(context)
        return try {
            dpm(context).installExistingPackage(admin, packageName)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Step 3 (runs in the PERSONAL profile).
     * Launches the work-profile copy of [packageName] directly — the user
     * never has to manually switch profiles.
     */
    fun launchClonedApp(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val crossProfileApps =
            ContextCompat.getSystemService(context, CrossProfileApps::class.java) ?: return false
        val targetUsers = crossProfileApps.targetUserProfiles
        if (targetUsers.isEmpty()) return false
        val mainActivity = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.component ?: return false
        return try {
            crossProfileApps.startMainActivity(mainActivity, targetUsers[0])
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
