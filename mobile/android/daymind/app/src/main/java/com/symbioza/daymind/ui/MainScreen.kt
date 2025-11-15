package com.symbioza.daymind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.symbioza.daymind.state.ChunkSummary
import com.symbioza.daymind.state.UiState
import com.symbioza.daymind.state.TranscriptSummary
import com.symbioza.daymind.ui.components.DMScaffold
import com.symbioza.daymind.ui.components.InfoBanner
import com.symbioza.daymind.ui.components.PrimaryButton
import com.symbioza.daymind.ui.components.QueueBadge
import com.symbioza.daymind.ui.components.RecordButton
import com.symbioza.daymind.ui.components.SecondaryButton
import com.symbioza.daymind.ui.components.SectionCard
import com.symbioza.daymind.ui.components.StatusBadge
import com.symbioza.daymind.ui.theme.DayMindPalette
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
    onShareChunk: (ChunkSummary) -> Unit,
    onShareTranscript: (TranscriptSummary) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onAggressivenessChange: (Float) -> Unit,
    onNoiseGateChange: (Float) -> Unit
) {
    DMScaffold {
        Text(
            text = "DayMind Live",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = DayMindPalette.textPrimary
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                RecordSection(
                    state = state,
                    onToggleRecording = onToggleRecording,
                    onSync = onSync,
                    onShareLatestChunk = onShareLatestChunk,
                    onShareArchive = onShareArchive,
                    onPlayLastChunk = onPlayLastChunk,
                    onStopPlayback = onStopPlayback
                )
            }
            item {
                SummarySection(state = state)
            }
            item {
                ChunkVaultSection(state = state, onShareChunk = onShareChunk)
            }
            item {
                TranscriptSection(state = state, onShareTranscript = onShareTranscript)
            }
            item {
                ActivityLogSection(entries = state.logEntries)
            }
            item {
                SettingsSection(
                    vadThreshold = state.vadThreshold,
                    vadAggressiveness = state.vadAggressiveness,
                    noiseGate = state.noiseGate,
                    onThresholdChange = onThresholdChange,
                    onAggressivenessChange = onAggressivenessChange,
                    onNoiseGateChange = onNoiseGateChange
                )
            }
        }
    }
}

@Composable
private fun RecordSection(
    state: UiState,
    onToggleRecording: () -> Unit,
    onSync: () -> Unit,
    onShareLatestChunk: () -> Unit,
    onShareArchive: () -> Unit,
    onPlayLastChunk: () -> Unit,
    onStopPlayback: () -> Unit
) {
    SectionCard(
        title = "Ambient capture",
        subtitle = "Single tap capture with Whisper + VAD energy feedback."
    ) {
        RecordButton(isRecording = state.isRecording, onToggle = onToggleRecording)
        QueueBadge(pending = state.pendingChunks)
        StatusBadge(
            icon = Icons.Outlined.Mic,
            title = "Recorder",
            value = if (state.isRecording) "Recording" else "Idle",
            caption = "Tap to ${if (state.isRecording) "stop" else "start"} capture"
        )
        StatusBadge(
            icon = Icons.Outlined.CloudUpload,
            title = "Uploader",
            value = if (state.isSyncing) "Uploading" else "Idle",
            caption = if (state.isSyncing) "Keeping queue empty" else "Ready when you sync"
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            SecondaryButton(
                text = if (state.isPlayingBack) "Stop playback" else "Play last chunk",
                onClick = { if (state.isPlayingBack) onStopPlayback() else onPlayLastChunk() }
            )
            SecondaryButton(text = "Share latest", onClick = onShareLatestChunk)
        }
        PrimaryButton(
            text = if (state.isSyncing) "Syncing…" else "Sync now",
            onClick = onSync
        )
        SecondaryButton(text = "Share archive", onClick = onShareArchive)
    }
}

@Composable
private fun SummarySection(state: UiState) {
    SectionCard(
        title = "Session insights",
        subtitle = "Uploader heartbeat, Text-First policy, and quick QA hints."
    ) {
        StatusBadge(
            icon = Icons.Outlined.NotificationsActive,
            title = "Sync status",
            value = state.syncMessage,
            caption = "Queue ${state.pendingChunks} chunk(s)"
        )
        InfoBanner(
            message = "Voice chunks stay on-device until you tap sync. JSONL metadata + FLAC archives follow the Text-First Storage policy."
        )
    }
}

@Composable
private fun ChunkVaultSection(state: UiState, onShareChunk: (ChunkSummary) -> Unit) {
    SectionCard(
        title = "Chunk vault log",
        subtitle = "Peek into recent captures for QA/export."
    ) {
        if (state.chunks.isEmpty()) {
            Text("No chunks recorded yet.", color = DayMindPalette.textSecondary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.chunks.take(6).forEach { chunk ->
                    ChunkRow(chunk = chunk, onShare = onShareChunk)
                }
            }
        }
    }
}

@Composable
private fun TranscriptSection(state: UiState, onShareTranscript: (TranscriptSummary) -> Unit) {
    SectionCard(
        title = "Transcripts",
        subtitle = "Latest Whisper captures with timestamped SRT export."
    ) {
        if (state.transcripts.isEmpty()) {
            Text("No transcripts yet.", color = DayMindPalette.textSecondary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.transcripts.take(5).forEach { transcript ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = transcript.summary,
                            color = DayMindPalette.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Chunk ${transcript.chunkId.take(6)}",
                            color = DayMindPalette.textMuted,
                            fontSize = 12.sp
                        )
                        SecondaryButton(text = "Share transcript") { onShareTranscript(transcript) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityLogSection(entries: List<String>) {
    SectionCard(
        title = "Activity log",
        subtitle = "Live status messages from recorder, queue, and uploader."
    ) {
        if (entries.isEmpty()) {
            Text("No events yet.", color = DayMindPalette.textSecondary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.take(12).forEach { line ->
                    Text(text = line, color = DayMindPalette.textSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    vadThreshold: Int,
    vadAggressiveness: Int,
    noiseGate: Float,
    onThresholdChange: (Float) -> Unit,
    onAggressivenessChange: (Float) -> Unit,
    onNoiseGateChange: (Float) -> Unit
) {
    SectionCard(
        title = "Audio sensitivity",
        subtitle = "Tune thresholds per room/device. Values apply instantly."
    ) {
        AudioSensitivitySection(
            vadThreshold = vadThreshold,
            vadAggressiveness = vadAggressiveness,
            noiseGate = noiseGate,
            onThresholdChange = onThresholdChange,
            onAggressivenessChange = onAggressivenessChange,
            onNoiseGateChange = onNoiseGateChange
        )
    }
}

@Composable
private fun ChunkRow(chunk: ChunkSummary, onShare: (ChunkSummary) -> Unit) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Chunk ${chunk.id.take(6)}", fontWeight = FontWeight.Bold, color = DayMindPalette.textPrimary)
        Text(text = "Saved: ${formatter.format(Date(chunk.createdAt))}", color = DayMindPalette.textSecondary)
        Text(text = if (chunk.uploaded) "Uploaded" else "Pending", color = DayMindPalette.textMuted)
        Spacer(modifier = Modifier.height(4.dp))
        SecondaryButton(text = "Share") { onShare(chunk) }
    }
}

@Composable
private fun AudioSensitivitySection(
    vadThreshold: Int,
    vadAggressiveness: Int,
    noiseGate: Float,
    onThresholdChange: (Float) -> Unit,
    onAggressivenessChange: (Float) -> Unit,
    onNoiseGateChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text("VAD Threshold: $vadThreshold", color = DayMindPalette.textSecondary)
            androidx.compose.material3.Slider(
                value = vadThreshold.toFloat(),
                onValueChange = onThresholdChange,
                valueRange = 1500f..9000f
            )
        }
        Column {
            Text("Aggressiveness: $vadAggressiveness", color = DayMindPalette.textSecondary)
            androidx.compose.material3.Slider(
                value = vadAggressiveness.toFloat(),
                onValueChange = onAggressivenessChange,
                steps = 2,
                valueRange = 0f..3f
            )
        }
        Column {
            Text("Noise gate: ${"%.0f".format(noiseGate * 100)}%", color = DayMindPalette.textSecondary)
            androidx.compose.material3.Slider(
                value = noiseGate,
                onValueChange = onNoiseGateChange,
                valueRange = 0f..0.6f
            )
        }
    }
}
