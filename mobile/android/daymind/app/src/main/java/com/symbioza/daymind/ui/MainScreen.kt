package com.symbioza.daymind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.symbioza.daymind.state.ChunkSummary
import com.symbioza.daymind.state.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DayMindScreen(
    state: UiState,
    onToggleRecording: () -> Unit,
    onSync: () -> Unit,
    onShareLatestChunk: () -> Unit,
    onPlayLastChunk: () -> Unit,
    onStopPlayback: () -> Unit,
    onShareArchive: () -> Unit,
    onShareChunk: (ChunkSummary) -> Unit
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
                onClick = onShareLatestChunk,
                enabled = state.canShareChunk
            ) {
                Text("Share Last Chunk")
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

            if (state.chunks.isNotEmpty()) {
                Text("Chunk Vault", style = MaterialTheme.typography.titleMedium)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.chunks.take(10)) { chunk ->
                        ChunkRow(chunk, onShareChunk)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChunkRow(chunk: ChunkSummary, onShare: (ChunkSummary) -> Unit) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = "Chunk ${chunk.id.take(6)}", fontWeight = FontWeight.Bold)
        Text(text = "Saved: ${formatter.format(Date(chunk.createdAt))}")
        Text(text = if (chunk.uploaded) "Uploaded" else "Pending")
        Button(onClick = { onShare(chunk) }) {
            Text("Share")
        }
    }
}
