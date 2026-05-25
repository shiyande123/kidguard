package com.kidguard.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceAdminManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent: ComponentName =
        ComponentName(context, KidGuardDeviceAdmin::class.java)

    fun isAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    fun lockNow() {
        if (isAdminActive()) {
            dpm.lockNow()
        }
    }

    fun getAdminComponent(): ComponentName = adminComponent
}
