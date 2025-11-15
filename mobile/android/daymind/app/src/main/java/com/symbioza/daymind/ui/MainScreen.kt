package com.symbioza.daymind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.symbioza.daymind.state.UiState

@Composable
fun DayMindScreen(
    state: UiState,
    onToggleRecording: () -> Unit,
    onSync: () -> Unit,
    onPlayLastChunk: () -> Unit,
    onStopPlayback: () -> Unit,
    onShareArchive: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Android Audio Bridge",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Button(onClick = onToggleRecording) {
                Text(if (state.isRecording) "Stop Recording" else "Start Recording")
            }

            Button(
                onClick = { if (state.isPlayingBack) onStopPlayback() else onPlayLastChunk() },
                enabled = state.canPlayChunk || state.isPlayingBack
            ) {
                Text(if (state.isPlayingBack) "Stop Playback" else "Play Last Chunk")
            }

            Button(
                onClick = onSync,
                enabled = !state.isSyncing && state.pendingChunks > 0
            ) {
                Text(if (state.isSyncing) "Syncing..." else "Sync Now")
            }

            Button(
                onClick = onShareArchive,
                enabled = state.hasArchiveToShare
            ) {
                Text("Share Archive")
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Pending chunks: ${state.pendingChunks}")
                Text(text = state.syncMessage)
            }
        }
    }
}
