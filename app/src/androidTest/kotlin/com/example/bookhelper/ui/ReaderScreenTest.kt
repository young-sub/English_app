package com.example.bookhelper.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.tts.BundledTtsModels
import com.example.bookhelper.tts.TtsEnginePreference
import com.example.bookhelper.tts.defaultSpeakerId
import org.junit.Rule
import org.junit.Test

class ReaderScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun closeButtonDismissesSettingsDialogWhileLocalTtsLoads() {
        var settingsClosed = 0
        var selectedPreference = TtsEnginePreference.SYSTEM_DEFAULT

        composeRule.setContent {
            var uiState by remember {
                mutableStateOf(
                    ReaderUiState(
                        ocrPage = OcrPage.EMPTY,
                    ),
                )
            }

            ReaderScreen(
                uiState = uiState,
                cameraPermissionGranted = false,
                onFrameOcr = {},
                onTap = { _, _ -> },
                onDragSelect = { _, _, _, _ -> },
                onSaveWord = {},
                onSetSpeechRate = {},
                onSetTapSelectionWindowMs = {},
                onSetDragSelectionMinDistancePx = {},
                onSetTtsEnginePreference = { preference ->
                    selectedPreference = preference
                    uiState = uiState.copy(ttsEnginePreference = preference)
                },
                onSetLocalModel = {},
                onSetLocalSpeaker = {},
                onSetAutoSpeakEnabled = {},
                onSetSpeechTarget = {},
                onCloseSettingsDialog = { settingsClosed += 1 },
                onDismissDictionaryDialog = {},
                onRevealKoreanDefinition = {},
                onOpenSavedWordsDialog = {},
                onDismissSavedWordsDialog = {},
                onOpenSavedWord = {},
                onDeleteSavedWord = {},
                onSpeakWordFromDictionary = {},
            )
        }

        composeRule.onNodeWithText("설정").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("음성 모델").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("닫기").performSemanticsAction(SemanticsActions.OnClick)
        check(composeRule.onAllNodesWithText("현재 모델:").fetchSemanticsNodes().isEmpty())
        composeRule.runOnIdle {
            check(settingsClosed == 1)
            check(selectedPreference == TtsEnginePreference.ON_DEVICE)
        }
    }

    @Test
    fun lessacModelShowsFixedSingleSpeakerLabel() {
        composeRule.setContent {
            ReaderScreen(
                uiState = ReaderUiState(
                    ocrPage = OcrPage.EMPTY,
                    ttsEnginePreference = TtsEnginePreference.ON_DEVICE,
                    availableLocalModels = BundledTtsModels.All,
                    selectedLocalModelId = BundledTtsModels.PiperEnUsLessacLow.id,
                    availableSpeakers = BundledTtsModels.PiperEnUsLessacLow.speakers,
                    selectedSpeakerId = BundledTtsModels.PiperEnUsLessacLow.defaultSpeakerId,
                    bundledModelName = BundledTtsModels.PiperEnUsLessacLow.displayName,
                    bundledModelReady = true,
                    localRuntimeReady = true,
                    effectiveLocalModelEnabled = true,
                    message = "음성 모델 변경: 단일 모델 활성화",
                ),
                cameraPermissionGranted = false,
                onFrameOcr = {},
                onTap = { _, _ -> },
                onDragSelect = { _, _, _, _ -> },
                onSaveWord = {},
                onSetSpeechRate = {},
                onSetTapSelectionWindowMs = {},
                onSetDragSelectionMinDistancePx = {},
                onSetTtsEnginePreference = {},
                onSetLocalModel = {},
                onSetLocalSpeaker = {},
                onSetAutoSpeakEnabled = {},
                onSetSpeechTarget = {},
                onCloseSettingsDialog = {},
                onDismissDictionaryDialog = {},
                onRevealKoreanDefinition = {},
                onOpenSavedWordsDialog = {},
                onDismissSavedWordsDialog = {},
                onOpenSavedWord = {},
                onDeleteSavedWord = {},
                onSpeakWordFromDictionary = {},
            )
        }

        composeRule.onNodeWithText("설정").performSemanticsAction(SemanticsActions.OnClick)
        check(composeRule.onAllNodesWithText("단일 모델").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("미국 여성").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("[단일 모델] 미국/여성").fetchSemanticsNodes().isNotEmpty())
    }
}
