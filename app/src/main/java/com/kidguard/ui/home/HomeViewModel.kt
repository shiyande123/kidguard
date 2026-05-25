package com.kidguard.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidguard.data.db.ChildDao
import com.kidguard.data.db.LockLogDao
import com.kidguard.data.db.SettingsDao
import com.kidguard.data.model.Child
import com.kidguard.data.model.LockLog
import com.kidguard.service.MonitoringService
import com.kidguard.util.DebugLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val childDao: ChildDao,
    private val lockLogDao: LockLogDao,
    private val settingsDao: SettingsDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    init {
        DebugLog.init(context)
        DebugLog.log("HomeVM", "=== ViewModel created ===")
    }

    // Navigation events: one-shot events for UI navigation
    private val _navigationEvents = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<NavigationEvent> = _navigationEvents

    sealed class NavigationEvent {
        data class RequestBatteryOptimization(val intent: Intent) : NavigationEvent()
    }

    val children: StateFlow<List<Child>> = childDao.getAllActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val childrenCount: StateFlow<Int> = childDao.getAllActive()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recentLogs: StateFlow<List<LockLog>> = lockLogDao.getRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isMonitoring: StateFlow<Boolean> = settingsDao.get()
        .map { it?.isEnabled ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleMonitoring() {
        val currentState = isMonitoring.value
        val newState = !currentState
        DebugLog.log("HomeVM", "toggle: $currentState -> $newState")

        // 开启监控时，检查电池优化白名单
        if (newState) {
            checkBatteryOptimization()
        }

        viewModelScope.launch {
            try {
                val current = settingsDao.get().firstOrNull()
                DebugLog.log("HomeVM", "current settings: $current")
                if (current != null) {
                    settingsDao.upsert(current.copy(isEnabled = newState))
                    DebugLog.log("HomeVM", "settings saved")
                } else {
                    DebugLog.log("HomeVM", "WARNING: settings is null!")
                }

                context.getSharedPreferences("kidguard_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("monitoring_enabled", newState).apply()

                if (newState) {
                    DebugLog.log("HomeVM", "Starting service...")
                    val intent = Intent(context, MonitoringService::class.java)
                    try {
                        ContextCompat.startForegroundService(context, intent)
                        DebugLog.log("HomeVM", "Service start intent SENT OK")
                    } catch (e: Exception) {
                        DebugLog.log("HomeVM", "Service start FAILED: ${e.javaClass.simpleName}: ${e.message}")
                        Log.e("HomeVM", "startForegroundService failed", e)
                    }
                } else {
                    DebugLog.log("HomeVM", "Stopping service...")
                    context.stopService(Intent(context, MonitoringService::class.java))
                    DebugLog.log("HomeVM", "Service stop intent sent")
                }
            } catch (e: Exception) {
                DebugLog.log("HomeVM", "ERROR: ${e.javaClass.simpleName}: ${e.message}")
                Log.e("HomeVM", "toggleMonitoring failed", e)
            }
        }
    }

    /**
     * 检查电池优化白名单，不在白名单则提示用户
     */
    private fun checkBatteryOptimization() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                DebugLog.log("HomeVM", "Not in battery whitelist, requesting...")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                _navigationEvents.tryEmit(NavigationEvent.RequestBatteryOptimization(intent))
            } else {
                DebugLog.log("HomeVM", "Already in battery whitelist")
            }
        } catch (e: Exception) {
            DebugLog.log("HomeVM", "Battery check error: ${e.message}")
        }
    }
}
