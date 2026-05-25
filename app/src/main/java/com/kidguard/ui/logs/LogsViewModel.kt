package com.kidguard.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidguard.data.db.LockLogDao
import com.kidguard.data.model.LockLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val lockLogDao: LockLogDao
) : ViewModel() {

    val logs: StateFlow<List<LockLog>> = lockLogDao.getRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
