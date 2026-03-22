package com.example.bookhelper.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.dictionary.DictionaryEntry
import com.example.bookhelper.dictionary.DictionarySense
import com.example.bookhelper.tts.BundledTtsModels
import com.example.bookhelper.tts.TtsEnginePreference
import com.example.bookhelper.tts.defaultSpeakerId
import org.junit.Rule
import org.junit.Test

class ReaderScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun stopButtonAppearsAndInvokesCallbackWhileSpeaking() {
        var stopCount = 0

        composeRule.setContent {
            ReaderScreen(
                uiState = ReaderUiState(
                    ocrPage = OcrPage.EMPTY,
                    isSpeaking = true,
                ),
                cameraPermissionGranted = true,
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
                onStopSpeaking = { stopCount += 1 },
            )
        }

        composeRule.onNodeWithContentDescription("읽기 중지").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle {
            check(stopCount == 1)
        }
    }

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
                onStopSpeaking = {},
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
                onStopSpeaking = {},
            )
        }

        composeRule.onNodeWithText("설정").performSemanticsAction(SemanticsActions.OnClick)
        check(composeRule.onAllNodesWithText("단일 모델").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("미국 여성").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("[단일 모델] 미국/여성").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun dictionaryDialogShowsTopThreeEntriesWithMatchedKoreanSenses() {
        composeRule.setContent {
            var uiState by remember {
                mutableStateOf(
                    ReaderUiState(
                        ocrPage = OcrPage.EMPTY,
                        isDictionaryDialogVisible = true,
                        dictionaryEntries = listOf(
                            DictionaryEntry(
                                headword = "say",
                                lemma = "say",
                                definitionEn = "to speak",
                                definitionKo = "말하다",
                                senses = listOf(
                                    DictionarySense("to speak words", "말을 하다"),
                                    DictionarySense("to express an opinion", "의견을 표현하다"),
                                    DictionarySense("to recite formally", "낭독하다"),
                                ),
                            ),
                            DictionaryEntry(
                                headword = "say",
                                lemma = "say",
                                definitionEn = "to state information",
                                definitionKo = "진술하다",
                                senses = listOf(
                                    DictionarySense("to state information clearly", "분명하게 진술하다"),
                                ),
                            ),
                            DictionaryEntry(
                                headword = "say",
                                lemma = "say",
                                definitionEn = "the right to influence a decision",
                                definitionKo = "발언권",
                            ),
                            DictionaryEntry(
                                headword = "say",
                                lemma = "say",
                                definitionEn = "for example",
                                definitionKo = "예를 들어",
                                senses = listOf(
                                    DictionarySense("used to introduce an example", "예를 들어서"),
                                ),
                            ),
                        ),
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
                onSetTtsEnginePreference = {},
                onSetLocalModel = {},
                onSetLocalSpeaker = {},
                onSetAutoSpeakEnabled = {},
                onSetSpeechTarget = {},
                onCloseSettingsDialog = {},
                onDismissDictionaryDialog = {},
                onRevealKoreanDefinition = {
                    uiState = uiState.copy(isKoreanDefinitionVisible = true)
                },
                onOpenSavedWordsDialog = {},
                onDismissSavedWordsDialog = {},
                onOpenSavedWord = {},
                onDeleteSavedWord = {},
                onSpeakWordFromDictionary = {},
                onStopSpeaking = {},
            )
        }

        check(composeRule.onAllNodesWithText("후보 1").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("후보 2").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("후보 3").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("후보 4").fetchSemanticsNodes().isEmpty())
        check(composeRule.onAllNodesWithText("1. to speak words").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("2. to express an opinion").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("3. to recite formally").fetchSemanticsNodes().isEmpty())
        check(composeRule.onAllNodesWithText("1. to state information clearly").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("1. the right to influence a decision").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("1. used to introduce an example").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithText("한글 뜻 보기").performSemanticsAction(SemanticsActions.OnClick)

        check(composeRule.onAllNodesWithText("뜻: 말을 하다").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("뜻: 의견을 표현하다").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("뜻: 분명하게 진술하다").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("뜻: 발언권").fetchSemanticsNodes().isNotEmpty())
        check(composeRule.onAllNodesWithText("뜻: 예를 들어서").fetchSemanticsNodes().isEmpty())
    }
}
