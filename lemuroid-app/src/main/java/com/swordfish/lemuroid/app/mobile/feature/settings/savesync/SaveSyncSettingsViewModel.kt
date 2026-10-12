package com.swordfish.lemuroid.app.mobile.feature.settings.savesync

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SaveSyncSettingsViewModel(
    private val application: Application,
    private val saveSyncManager: SaveSyncManager,
) : ViewModel() {
    class Factory(
        private val application: Application,
        private val saveSyncManager: SaveSyncManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SaveSyncSettingsViewModel(application, saveSyncManager) as T
        }
    }

    val saveSyncInProgress = PendingOperationsMonitor(getContext()).anySaveOperationInProgress()

    data class State(
        val isConfigured: Boolean = false,
        val configInfo: String = "",
        val savesSpace: String = "",
        val lastSyncInfo: String = "",
        val coreNames: List<String> = emptyList(),
        val coreVisibleNames: List<String> = emptyList(),
        val provider: String = "",
        val settingsActivity: Class<out Activity>? = null,
    )

    private val mutableUiState = MutableStateFlow(State())

    val uiState: StateFlow<State> = mutableUiState

    /** The provider can be configured from another activity and syncs update the summaries. */
    fun refreshState() {
        viewModelScope.launch {
            mutableUiState.value = withContext(Dispatchers.IO) { buildState() }
        }
    }

    private fun buildState(): State {
        return State(
            saveSyncManager.isConfigured(),
            saveSyncManager.getConfigInfo(),
            saveSyncManager.computeSavesSpace(),
            saveSyncManager.getLastSyncInfo(),
            computeCoreNames(),
            computeCoreVisibleNames(),
            saveSyncManager.getProvider(),
            saveSyncManager.getSettingsActivity(),
        )
    }

    private fun computeCoreNames(): List<String> {
        return CoreID.values().map { it.coreName }
    }

    private fun computeCoreVisibleNames(): List<String> {
        val context = getContext()
        return CoreID.values().map { saveSyncManager.getDisplayNameForCore(context, it) }
    }

    private fun getContext(): Context {
        return application.applicationContext
    }
}
