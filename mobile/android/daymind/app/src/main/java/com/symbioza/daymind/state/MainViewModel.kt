package com.symbioza.daymind.state

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.symbioza.daymind.DayMindApplication
import com.symbioza.daymind.audio.RecordingService
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class UiState(
    val isRecording: Boolean = false,
    val pendingChunks: Int = 0,
    val syncMessage: String = "Waiting",
    val isSyncing: Boolean = false,
    val canPlayChunk: Boolean = false,
    val isPlayingBack: Boolean = false,
    val hasArchiveToShare: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (getApplication() as DayMindApplication).container

    val uiState: StateFlow<UiState> = combine(
        container.recordingStateStore.isRecording,
        container.chunkRepository.pendingCount,
        container.syncStatusStore.status,
        container.chunkRepository.latestChunkPath,
        container.chunkPlaybackManager.isPlaying
    ) { recording, pending, syncStatus, latestChunkPath, isPlaying ->
        UiState(
            isRecording = recording,
            pendingChunks = pending,
            syncMessage = syncStatus.message,
            isSyncing = syncStatus.isSyncing,
            canPlayChunk = !latestChunkPath.isNullOrBlank(),
            isPlayingBack = isPlaying,
            hasArchiveToShare = !syncStatus.latestArchivePath.isNullOrBlank()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState()
    )

    fun toggleRecording() {
        if (uiState.value.isRecording) {
            RecordingService.stop(getApplication())
        } else {
            RecordingService.start(getApplication())
        }
    }

    fun playLatestChunk() {
        val path = container.chunkRepository.latestChunkPath.value ?: return
        container.chunkPlaybackManager.play(File(path))
    }

    fun stopPlayback() {
        container.chunkPlaybackManager.stop()
    }

    fun syncNow() {
        container.enqueueManualSync()
    }

    fun shareArchive() {
        val path = container.syncStatusStore.status.value.latestArchivePath ?: return
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/flac"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(
            Intent.createChooser(intent, "Share DayMind archive")
        )
    }
}
