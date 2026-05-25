package com.kidguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, checking settings...")

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = context.getSharedPreferences("kidguard_prefs", Context.MODE_PRIVATE)
                    val monitoringEnabled = prefs.getBoolean("monitoring_enabled", false)

                    if (monitoringEnabled) {
                        Log.d(TAG, "Monitoring enabled in prefs, starting service")
                        val serviceIntent = Intent(context, MonitoringService::class.java)
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } else {
                        Log.d(TAG, "Monitoring disabled, skipping service start")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check settings on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
