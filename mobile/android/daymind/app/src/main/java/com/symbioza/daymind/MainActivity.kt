package com.symbioza.daymind

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.symbioza.daymind.state.MainViewModel
import com.symbioza.daymind.ui.DayMindScreen
import com.symbioza.daymind.ui.theme.DayMindTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    viewModel.toggleRecording()
                }
            }
            val storagePermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {}
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                viewModel.snackbarFlow.collect { message ->
                    snackbarHostState.showSnackbar(message)
                }
            }

            DayMindTheme {
                DayMindScreen(
                    state = uiState,
                    snackbarHostState = snackbarHostState,
                    onToggleRecording = {
                        val granted = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        val storageGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        } else true

                        when {
                            !granted -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            !storageGranted -> storagePermissionLauncher.launch(
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                            else -> viewModel.toggleRecording()
                        }
                    },
                    onSync = viewModel::syncNow,
                    onShareLatestChunk = viewModel::shareLastChunk,
                    onPlayLastChunk = viewModel::playLatestChunk,
                    onStopPlayback = viewModel::stopPlayback,
                    onShareArchive = viewModel::shareArchive,
                    onShareChunk = viewModel::shareChunk,
                    onShareTranscript = viewModel::shareTranscript,
                    onThresholdChange = viewModel::updateVadThreshold,
                    onAggressivenessChange = viewModel::updateVadAggressiveness,
                    onNoiseGateChange = viewModel::updateNoiseGate,
                    onRefreshSummary = { viewModel.refreshSummary(force = true) }
                )
            }
        }
    }
}
