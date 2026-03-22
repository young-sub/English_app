package com.example.bookhelper.ui

import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrWord
import com.example.bookhelper.dictionary.DictionaryEntry
import com.example.bookhelper.tts.BundledTtsModel
import com.example.bookhelper.tts.LocalSpeakerProfile
import com.example.bookhelper.tts.TtsRoute
import com.example.bookhelper.tts.TtsEnginePreference

enum class SpeechTarget {
    WORD,
    SENTENCE,
}

data class SavedWordItem(
    val key: String,
    val word: String,
    val lemma: String,
    val sentence: String?,
    val saveCount: Int,
    val createdAt: Long,
)

data class ReaderUiState(
    val ocrPage: OcrPage = OcrPage.EMPTY,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val selectedWord: OcrWord? = null,
    val selectedWords: List<OcrWord> = emptyList(),
    val selectedSentence: String? = null,
    val dictionaryEntries: List<DictionaryEntry> = emptyList(),
    val autoSpeakEnabled: Boolean = true,
    val speechTarget: SpeechTarget = SpeechTarget.SENTENCE,
    val isDictionaryDialogVisible: Boolean = false,
    val isSavedWordsDialogVisible: Boolean = false,
    val savedWords: List<SavedWordItem> = emptyList(),
    val isKoreanDefinitionVisible: Boolean = false,
    val isDictionaryReady: Boolean = false,
    val isAnalyzing: Boolean = false,
    val isSpeaking: Boolean = false,
    val message: String? = null,
    val speechRate: Float = 1.0f,
    val tapSelectionWindowMs: Long = 1200L,
    val dragSelectionMinDistancePx: Float = 24f,
    val ttsEnginePreference: TtsEnginePreference = TtsEnginePreference.SYSTEM_DEFAULT,
    val effectiveLocalModelEnabled: Boolean = false,
    val localRuntimeReady: Boolean = false,
    val localRuntimeChecking: Boolean = false,
    val localRuntimeLastError: String? = null,
    val effectiveTtsRoute: TtsRoute = TtsRoute.SYSTEM_TTS,
    val availableLocalModels: List<BundledTtsModel> = emptyList(),
    val selectedLocalModelId: String = "",
    val availableSpeakers: List<LocalSpeakerProfile> = emptyList(),
    val selectedSpeakerId: Int = 0,
    val bundledModelName: String = "",
    val bundledModelReady: Boolean = false,
    val bundledModelPath: String? = null,
)
