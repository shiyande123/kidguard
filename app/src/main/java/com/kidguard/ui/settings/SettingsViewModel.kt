package com.kidguard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidguard.data.db.SettingsDao
import com.kidguard.data.model.Settings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDao: SettingsDao
) : ViewModel() {

    val settings: StateFlow<Settings?> = settingsDao.get()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            settingsDao.upsert(newSettings)
        }
    }
}
