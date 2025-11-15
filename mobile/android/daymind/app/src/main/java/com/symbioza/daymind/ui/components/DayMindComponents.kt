package com.symbioza.daymind.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.symbioza.daymind.ui.theme.DayMindPalette

@Composable
fun DMScaffold(
    snackbarHostState: SnackbarHostState,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = DayMindPalette.background,
        contentColor = DayMindPalette.textPrimary
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DayMindPalette.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(DayMindPalette.background, DayMindPalette.backdrop)
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = content
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(DayMindPalette.card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = {
            Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DayMindPalette.textPrimary)
            if (subtitle != null) {
                Text(text = subtitle, color = DayMindPalette.textSecondary, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    )
}

@Composable
fun StatusBadge(
    icon: ImageVector,
    title: String,
    value: String,
    caption: String,
    color: Color = DayMindPalette.accentMuted
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DayMindPalette.surfaceAlt)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title.uppercase(), color = DayMindPalette.textMuted, fontSize = 12.sp)
            Text(value, color = DayMindPalette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(caption, color = DayMindPalette.textSecondary, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f))
                .padding(10.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color)
        }
    }
}

@Composable
fun QueueBadge(pending: Int) {
    val bg = if (pending > 0) DayMindPalette.accentSoft else DayMindPalette.surfaceAlt
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg.copy(alpha = 0.35f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Queued chunks", color = DayMindPalette.textMuted, fontSize = 12.sp)
        Text(
            text = if (pending > 0) "$pending pending" else "Empty",
            color = DayMindPalette.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Text(
            text = if (pending > 0) "Ready to upload" else "Everything synced",
            color = DayMindPalette.textSecondary,
            fontSize = 12.sp
        )
    }
}

@Composable
fun InfoBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DayMindPalette.surfaceAlt)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = DayMindPalette.accentMuted
        )
        Text(
            text = message,
            color = DayMindPalette.textSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = DayMindPalette.accent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = text, color = DayMindPalette.textPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = DayMindPalette.surfaceAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = text, color = DayMindPalette.textPrimary)
    }
}

@Composable
fun RecordButton(isRecording: Boolean, onToggle: () -> Unit) {
    val color = if (isRecording) DayMindPalette.danger else DayMindPalette.accent
    Button(
        onClick = onToggle,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        Text(
            text = if (isRecording) "Stop capture" else "Start capture",
            color = DayMindPalette.textPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun VoiceProbabilityMeter(probability: Float) {
    val percent = (probability * 100).coerceIn(0f, 100f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Voice confidence",
            color = DayMindPalette.textSecondary,
            fontSize = 12.sp
        )
        LinearProgressIndicator(
            progress = percent / 100f,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(16.dp)),
            color = DayMindPalette.accent,
            trackColor = DayMindPalette.surfaceAlt
        )
        Text(
            text = "${percent.toInt()}%",
            color = DayMindPalette.textPrimary,
            fontSize = 12.sp
        )
    }
}
