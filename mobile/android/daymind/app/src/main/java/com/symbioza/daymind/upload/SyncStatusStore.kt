package com.symbioza.daymind.upload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncStatus(
    val message: String = "Waiting",
    val lastError: String? = null,
    val isSyncing: Boolean = false,
    val latestArchivePath: String? = null
)

class SyncStatusStore {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    fun markInProgress(message: String = "Synchronizing...") {
        _status.value = _status.value.copy(message = message, isSyncing = true, lastError = null)
    }

    fun markSuccess(message: String, archivePath: String? = null) {
        _status.value = _status.value.copy(
            message = message,
            isSyncing = false,
            lastError = null,
            latestArchivePath = archivePath ?: _status.value.latestArchivePath
        )
    }

    fun markError(message: String) {
        _status.value = _status.value.copy(
            message = message,
            isSyncing = false,
            lastError = message
        )
    }

    fun setArchivePath(path: String) {
        _status.value = _status.value.copy(latestArchivePath = path)
    }
}
