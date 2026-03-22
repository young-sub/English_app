package com.example.bookhelper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bookhelper.startup.BootScreen
import com.example.bookhelper.startup.BootViewModel
import com.example.bookhelper.ui.ReaderScreen
import com.example.bookhelper.ui.ReaderViewModel

class MainActivity : ComponentActivity() {
    private var permissionGranted by mutableStateOf(false)

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionGranted = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        permissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            val bootVm: BootViewModel = viewModel(factory = BootViewModel.factory(applicationContext))
            val bootUiState by bootVm.uiStateFlow.collectAsState()
            MaterialTheme {
                Surface {
                    val startupPayload = bootUiState.startupPayload
                    if (startupPayload == null) {
                        BootScreen(
                            uiState = bootUiState,
                            onRetry = bootVm::retry,
                        )
                    } else {
                        val vm: ReaderViewModel = viewModel(
                            key = bootUiState.snapshot.sessionId,
                            factory = ReaderViewModel.factory(applicationContext, startupPayload),
                        )
                        val uiState by vm.uiStateFlow.collectAsState()
                        ReaderScreen(
                            uiState = uiState,
                            cameraPermissionGranted = permissionGranted,
                            onFrameOcr = vm::onOcrPage,
                            onTap = vm::onTap,
                            onDragSelect = vm::onDragSelect,
                            onSaveWord = vm::saveSelectedWord,
                            onSetSpeechRate = vm::setSpeechRate,
                            onSetTapSelectionWindowMs = vm::setTapSelectionWindowMs,
                            onSetDragSelectionMinDistancePx = vm::setDragSelectionMinDistancePx,
                            onSetTtsEnginePreference = vm::setTtsEnginePreference,
                            onSetLocalModel = vm::setLocalModel,
                            onSetLocalSpeaker = vm::setLocalSpeaker,
                            onSetAutoSpeakEnabled = vm::setAutoSpeakEnabled,
                            onSetSpeechTarget = vm::setSpeechTarget,
                            onCloseSettingsDialog = vm::onSettingsDialogClosed,
                            onDismissDictionaryDialog = vm::dismissDictionaryDialog,
                            onRevealKoreanDefinition = vm::revealKoreanDefinition,
                            onOpenSavedWordsDialog = vm::openSavedWordsDialog,
                            onDismissSavedWordsDialog = vm::dismissSavedWordsDialog,
                            onOpenSavedWord = vm::openSavedWord,
                            onDeleteSavedWord = vm::deleteSavedWord,
                            onSpeakWordFromDictionary = vm::speakWordFromDictionary,
                            onStopSpeaking = vm::stopSpeaking,
                        )
                    }
                }
            }
        }
    }
}
