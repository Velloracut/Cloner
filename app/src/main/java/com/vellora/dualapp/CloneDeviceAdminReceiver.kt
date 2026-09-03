package com.vellora.dualapp

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Required boilerplate receiver for becoming a Profile Owner.
 * We don't enforce any device policies here — this only exists so
 * Android will let us provision a Work Profile via
 * ACTION_PROVISION_MANAGED_PROFILE.
 */
class CloneDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        // Called once, inside the newly-created work profile, right after
        // provisioning finishes. We just enable the profile so it becomes
        // active/visible to the user.
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(context, CloneDeviceAdminReceiver::class.java)
        dpm.setProfileEnabled(admin)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "Work profile ready for cloning", Toast.LENGTH_SHORT).show()
    }
}
