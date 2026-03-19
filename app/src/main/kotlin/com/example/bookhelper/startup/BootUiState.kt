package com.example.bookhelper.startup

import com.example.bookhelper.ui.ReaderUiState

data class ReaderStartupPayload(
    val initialUiState: ReaderUiState,
    val ttsManagerHolder: StartupTtsManagerHolder,
    val vocabularyDaoHolder: StartupVocabularyDaoHolder,
    val dictionaryLookupServiceHolder: StartupDictionaryLookupServiceHolder,
)

data class BootUiState(
    val snapshot: BootStatusSnapshot = BootStatusSnapshot(sessionId = "pending", stages = emptyList()),
    val latestSummary: String = "앱 시작 준비 중",
    val detailedLogs: List<BootLogEvent> = emptyList(),
    val startupPayload: ReaderStartupPayload? = null,
    val failureMessage: String? = null,
)

class StartupTtsManagerHolder(var manager: com.example.bookhelper.tts.AndroidTtsManager? = null)

class StartupVocabularyDaoHolder(var dao: com.example.bookhelper.data.local.VocabularyDao? = null)

class StartupDictionaryLookupServiceHolder(var lookupService: com.example.bookhelper.dictionary.DictionaryLookupService? = null)
