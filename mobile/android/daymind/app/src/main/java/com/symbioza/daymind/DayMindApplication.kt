package com.symbioza.daymind

import android.app.Application
import com.symbioza.daymind.audio.ChunkPlaybackManager
import com.symbioza.daymind.config.ConfigRepository
import com.symbioza.daymind.data.ChunkRepository
import com.symbioza.daymind.data.TranscriptStore
import com.symbioza.daymind.data.LogStore
import com.symbioza.daymind.state.RecordingStateStore
import com.symbioza.daymind.upload.ArchiveSyncWorker
import com.symbioza.daymind.upload.SyncStatusStore

class DayMindApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(private val application: Application) {
    val configRepository = ConfigRepository(application)
    val chunkRepository = ChunkRepository(application)
    val transcriptStore = TranscriptStore(application)
    val logStore = LogStore()
    val syncStatusStore = SyncStatusStore()
    val recordingStateStore = RecordingStateStore()
    val chunkPlaybackManager = ChunkPlaybackManager(application)

    fun enqueueManualSync() {
        ArchiveSyncWorker.enqueue(application)
    }
}
