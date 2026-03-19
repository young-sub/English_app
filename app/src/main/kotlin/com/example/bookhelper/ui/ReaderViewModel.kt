package com.example.bookhelper.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.bookhelper.contracts.OcrLine
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrWord
import com.example.bookhelper.data.local.DictionaryDatabase
import com.example.bookhelper.data.local.UserDatabase
import com.example.bookhelper.data.local.VocabularyDao
import com.example.bookhelper.data.local.buildVocabularyStorageKey
import com.example.bookhelper.data.local.mergeVocabularyEntity
import com.example.bookhelper.dictionary.DictionaryLookupService
import com.example.bookhelper.dictionary.Lemmatizer
import com.example.bookhelper.dictionary.RoomDictionaryLookupDataSource
import com.example.bookhelper.ocr.OcrFrameResult
import com.example.bookhelper.provisioning.ProvisioningReadiness
import com.example.bookhelper.startup.ReaderStartupPayload
import com.example.bookhelper.text.SelectionResolver
import com.example.bookhelper.text.SentenceSegmenter
import com.example.bookhelper.text.TimedTapSelectionEngine
import com.example.bookhelper.text.TextPostProcessor
import com.example.bookhelper.text.WordHit
import com.example.bookhelper.text.wordsToReadableText
import com.example.bookhelper.tts.AndroidTtsManager
import com.example.bookhelper.tts.BundledTtsModel
import com.example.bookhelper.tts.BundledTtsModelInstaller
import com.example.bookhelper.tts.BundledTtsModels
import com.example.bookhelper.tts.TtsEnginePreference
import com.example.bookhelper.tts.defaultSpeakerId
import com.example.bookhelper.tts.isDownloadedModel
import com.example.bookhelper.tts.normalizeSpeakerId
import com.example.bookhelper.tts.requestsOnDeviceTts
import com.example.bookhelper.tts.resolveLocalTtsEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReaderViewModel(
    private val appContext: Context,
    startupPayload: ReaderStartupPayload,
) : ViewModel() {
    private val settingsStore = ReaderSettingsStore(appContext)
    private val lemmatizer = Lemmatizer()
    private val postProcessor = TextPostProcessor()
    private val selectionResolver = SelectionResolver()
    private val sentenceSegmenter = SentenceSegmenter()
    private val timedTapSelectionEngine = TimedTapSelectionEngine()
    private val bundledModelInstaller = BundledTtsModelInstaller(appContext)
    private val ttsManager = requireNotNull(startupPayload.ttsManagerHolder.manager)
    private val localModels = startupPayload.initialUiState.availableLocalModels
    private val installedLocalModelPaths = mutableMapOf<String, String>()
    private val vocabularyDaoMutex = Mutex()
    private val dictionaryRuntimeMutex = Mutex()
    @Volatile
    private var vocabularyDao: VocabularyDao? = startupPayload.vocabularyDaoHolder.dao
    @Volatile
    private var dictionaryRuntime: DictionaryRuntime? = startupPayload.dictionaryLookupServiceHolder.lookupService?.let { DictionaryRuntime(it) }
    private var pendingDictionaryLookupJob: Job? = null
    private var pendingTapDictionaryLookupJob: Job? = null

    private val _uiState = MutableStateFlow(startupPayload.initialUiState)
    val uiState: ReaderUiState
        get() = _uiState.value
    val uiStateFlow: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        timedTapSelectionEngine.setMaxIntervalMs(startupPayload.initialUiState.tapSelectionWindowMs)
    }

    private suspend fun ensureVocabularyDao(): VocabularyDao {
        vocabularyDao?.let { return it }
        return vocabularyDaoMutex.withLock {
            vocabularyDao?.let { return it }

            val dao = UserDatabase.get(appContext).vocabularyDao()
            vocabularyDao = dao
            dao
        }
    }

    private suspend fun ensureDictionaryRuntime(): DictionaryRuntime {
        dictionaryRuntime?.let { return it }
        return dictionaryRuntimeMutex.withLock {
            dictionaryRuntime?.let { return it }

            val dictionaryDao = DictionaryDatabase.get(appContext).dictionaryDao()
            val runtime = DictionaryRuntime(
                lookupService = DictionaryLookupService(RoomDictionaryLookupDataSource(dictionaryDao)),
            )
            dictionaryRuntime = runtime
            runtime
        }
    }

    fun onOcrPage(frame: OcrFrameResult) {
        _uiState.update {
            it.copy(
                ocrPage = frame.page,
                sourceWidth = frame.sourceWidth,
                sourceHeight = frame.sourceHeight,
                isAnalyzing = false,
            )
        }
    }

    fun onTap(x: Float, y: Float) {
        if (!_uiState.value.isDictionaryReady) {
            _uiState.update { it.copy(message = "사전 데이터 준비 중입니다. 잠시 후 다시 시도해 주세요.") }
            return
        }

        val page = _uiState.value.ocrPage
        val hit = selectionResolver.resolveWordHit(x, y, page)
        if (hit != null) {
            val timedSelection = timedTapSelectionEngine.onTap(hit, SystemClock.elapsedRealtime())
            if (timedSelection != null) {
                cancelPendingDictionaryLookup()
                cancelPendingTapDictionaryLookup()
                val remappedStart = remapHitToPage(timedSelection.startHit, page)
                val remappedEnd = remapHitToPage(timedSelection.endHit, page)
                val rangeWords = if (remappedStart != null && remappedEnd != null) {
                    selectionResolver.resolveWordsBetweenHits(
                        firstHit = remappedStart,
                        secondHit = remappedEnd,
                        page = page,
                    )
                } else {
                    timedSelection.words
                }
                val wordsForRead = rangeWords.ifEmpty { timedSelection.words }
                val textToRead = wordsToReadableText(wordsForRead)
                speakWithCurrentTts(textToRead, utteranceId = "timed-selection")
                _uiState.update {
                    it.copy(
                        selectedWord = wordsForRead.firstOrNull() ?: hit.word,
                        selectedWords = wordsForRead,
                        selectedSentence = textToRead,
                        isDictionaryDialogVisible = false,
                        message = "선택 범위 읽기",
                    )
                }
                return
            }

            val sentence = resolveSentenceAt(y, page)
            scheduleTapDictionaryLookup(word = hit.word, sentence = sentence)
            return
        }

        val word = selectionResolver.resolveWord(x, y, page)
        if (word == null) {
            cancelPendingDictionaryLookup()
            cancelPendingTapDictionaryLookup()
            timedTapSelectionEngine.reset()
            _uiState.update { it.copy(selectedWords = emptyList(), message = "No word at tap point") }
            return
        }

        val sentence = resolveSentenceAt(y, page)
        scheduleTapDictionaryLookup(word = word, sentence = sentence)
    }

    private fun remapHitToPage(hit: WordHit, page: OcrPage): WordHit? {
        val box = hit.word.boundingBox
        if (box != null) {
            val centerX = (box.left + box.right) / 2f
            val centerY = (box.top + box.bottom) / 2f
            selectionResolver.resolveWordHit(centerX, centerY, page)?.let { return it }
        }

        val lines = page.blocks.flatMap { it.lines }
        val line = lines.getOrNull(hit.lineIndex) ?: return null
        val word = line.words.getOrNull(hit.wordIndex) ?: return null
        return WordHit(
            word = word,
            line = line,
            lineIndex = hit.lineIndex,
            wordIndex = hit.wordIndex,
        )
    }

    private fun scheduleTapDictionaryLookup(word: OcrWord, sentence: String?) {
        cancelPendingTapDictionaryLookup()
        pendingTapDictionaryLookupJob = viewModelScope.launch {
            delay(_uiState.value.tapSelectionWindowMs)
            scheduleDictionaryLookup(word = word, sentence = sentence)
        }
    }

    private fun scheduleDictionaryLookup(word: OcrWord, sentence: String?) {
        cancelPendingTapDictionaryLookup()
        cancelPendingDictionaryLookup()
        pendingDictionaryLookupJob = viewModelScope.launch(Dispatchers.IO) {
            val runtime = ensureDictionaryRuntime()
            delay(DictionaryLookupDebounceMs)
            val normalized = postProcessor.normalizeToken(word.text)
            val lemmas = lemmatizer.candidates(normalized)
            val entries = runtime.lookupService.lookup(
                normalizedToken = normalized,
                lemmaCandidates = lemmas,
                sentenceContext = sentence,
            )
            maybeAutoSpeak(word = word, sentence = sentence)
            if (entries.isEmpty()) {
                _uiState.update {
                    it.copy(
                        selectedWord = null,
                        selectedWords = emptyList(),
                        selectedSentence = null,
                        dictionaryEntries = emptyList(),
                        isDictionaryDialogVisible = false,
                        isKoreanDefinitionVisible = false,
                        message = "No dictionary match",
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    selectedWord = word,
                    selectedWords = listOf(word),
                    selectedSentence = sentence,
                    dictionaryEntries = entries,
                    isDictionaryDialogVisible = true,
                    isKoreanDefinitionVisible = false,
                    message = "${entries.size} entries",
                )
            }
        }
    }

    private fun cancelPendingDictionaryLookup() {
        pendingDictionaryLookupJob?.cancel()
        pendingDictionaryLookupJob = null
    }

    private fun cancelPendingTapDictionaryLookup() {
        pendingTapDictionaryLookupJob?.cancel()
        pendingTapDictionaryLookupJob = null
    }

    private fun maybeAutoSpeak(word: OcrWord, sentence: String?) {
        val state = _uiState.value
        if (!state.autoSpeakEnabled) {
            return
        }

        when (state.speechTarget) {
            SpeechTarget.WORD -> speakWithCurrentTts(word.text, utteranceId = "word")
            SpeechTarget.SENTENCE -> {
                if (!sentence.isNullOrBlank()) {
                    speakWithCurrentTts(sentence, utteranceId = "sentence")
                } else {
                    speakWithCurrentTts(word.text, utteranceId = "word-fallback")
                }
            }
        }
    }

    fun setSpeechRate(rate: Float) {
        ttsManager.setRate(rate)
        settingsStore.saveSpeechRate(rate)
        _uiState.update { it.copy(speechRate = rate.coerceIn(0.85f, 1.15f)) }
    }

    fun setAutoSpeakEnabled(enabled: Boolean) {
        settingsStore.saveAutoSpeakEnabled(enabled)
        _uiState.update { it.copy(autoSpeakEnabled = enabled) }
    }

    fun setSpeechTarget(target: SpeechTarget) {
        settingsStore.saveSpeechTarget(target)
        _uiState.update { it.copy(speechTarget = target) }
    }

    fun setTapSelectionWindowMs(value: Long) {
        val normalized = value.coerceIn(300L, 3000L)
        timedTapSelectionEngine.setMaxIntervalMs(normalized)
        cancelPendingTapDictionaryLookup()
        settingsStore.saveTapSelectionWindowMs(normalized)
        _uiState.update { it.copy(tapSelectionWindowMs = normalized) }
    }

    fun setDragSelectionMinDistancePx(value: Float) {
        val normalized = value.coerceIn(4f, 200f)
        settingsStore.saveDragSelectionMinDistancePx(normalized)
        _uiState.update { it.copy(dragSelectionMinDistancePx = normalized) }
    }

    fun setTtsEnginePreference(value: TtsEnginePreference) {
        val normalizedPreference = value
        settingsStore.saveTtsEnginePreference(normalizedPreference)
        ttsManager.setEnginePreference(normalizedPreference)
        _uiState.update {
            it.copy(
                ttsEnginePreference = normalizedPreference,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val shouldRequestLocal = resolveLocalTtsEnabled(
                preference = normalizedPreference,
                modelReady = state.bundledModelReady,
            )
            ttsManager.setLocalModelEnabled(shouldRequestLocal)
            val runtimeStatus = if (shouldRequestLocal) {
                ttsManager.refreshLocalRuntime()
            } else {
                ttsManager.currentRuntimeStatus()
            }
            val effectiveLocal = runtimeStatus.effectiveRoute == com.example.bookhelper.tts.TtsRoute.LOCAL_KOKORO

            _uiState.update {
                it.copy(
                    effectiveLocalModelEnabled = effectiveLocal,
                    localRuntimeReady = runtimeStatus.runtimeReady,
                    localRuntimeChecking = runtimeStatus.runtimeChecking,
                    localRuntimeLastError = runtimeStatus.lastFailureReason,
                    effectiveTtsRoute = runtimeStatus.effectiveRoute,
                    message = if (runtimeStatus.runtimeChecking) {
                        "TTS 엔진 변경: 온디바이스 음성 점검 중"
                    } else if (effectiveLocal) {
                        "TTS 엔진 변경 완료: 온디바이스 음성 사용"
                    } else {
                        "TTS 엔진 변경 완료: 시스템 TTS 사용"
                    },
                )
            }
        }
    }

    fun setLocalModel(modelId: String) {
        val selectedModel = resolveLocalModel(modelId)
        viewModelScope.launch(Dispatchers.IO) {
            val activation = activateLocalModel(
                model = selectedModel,
                requestedSpeakerId = selectedModel.defaultSpeakerId,
                preference = _uiState.value.ttsEnginePreference,
            )

            settingsStore.saveLocalModelId(selectedModel.id)
            settingsStore.saveLocalSpeakerId(activation.speakerId)

            _uiState.update {
                it.copy(
                    selectedLocalModelId = selectedModel.id,
                    availableSpeakers = selectedModel.speakers,
                    selectedSpeakerId = activation.speakerId,
                    bundledModelName = selectedModel.displayName,
                    bundledModelReady = activation.modelReady,
                    bundledModelPath = activation.modelPath,
                    effectiveLocalModelEnabled = activation.effectiveLocalModelEnabled,
                    localRuntimeReady = activation.runtimeReady,
                    localRuntimeChecking = activation.runtimeChecking,
                    localRuntimeLastError = activation.lastFailureReason,
                    effectiveTtsRoute = activation.effectiveRoute,
                    message = if (activation.runtimeChecking) {
                        "로컬 모델 변경: ${selectedModel.shortLabel} (런타임 점검 중)"
                    } else if (activation.effectiveLocalModelEnabled) {
                        "로컬 모델 변경: ${selectedModel.shortLabel} (온디바이스 음성 활성화)"
                    } else if (activation.onDeviceRequested && activation.modelReady) {
                        "${selectedModel.shortLabel} 런타임 점검 실패, 시스템 TTS 사용"
                    } else if (activation.modelReady) {
                        "로컬 모델 변경: ${selectedModel.shortLabel} (현재 시스템 TTS)"
                    } else {
                        "${selectedModel.shortLabel} 설치/검증 실패, 시스템 TTS 사용"
                    },
                )
            }
        }
    }

    fun setLocalSpeaker(speakerId: Int) {
        val model = resolveLocalModel(_uiState.value.selectedLocalModelId)
        val normalizedSpeakerId = model.normalizeSpeakerId(speakerId)
        settingsStore.saveLocalSpeakerId(normalizedSpeakerId)
        ttsManager.setLocalSpeakerId(normalizedSpeakerId)

        val speakerName = model.speakers.firstOrNull { it.id == normalizedSpeakerId }?.displayLabel
            ?: normalizedSpeakerId.toString()
        _uiState.update {
            it.copy(
                selectedSpeakerId = normalizedSpeakerId,
                message = "화자 변경: $speakerName",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val shouldRequestLocal = resolveLocalTtsEnabled(
                preference = state.ttsEnginePreference,
                modelReady = state.bundledModelReady,
            )
            ttsManager.setLocalModelEnabled(shouldRequestLocal)
            val runtimeStatus = if (shouldRequestLocal) {
                ttsManager.refreshLocalRuntime()
            } else {
                ttsManager.currentRuntimeStatus()
            }
            val effectiveLocal = runtimeStatus.effectiveRoute == com.example.bookhelper.tts.TtsRoute.LOCAL_KOKORO

            _uiState.update {
                it.copy(
                    effectiveLocalModelEnabled = effectiveLocal,
                    localRuntimeReady = runtimeStatus.runtimeReady,
                    localRuntimeChecking = runtimeStatus.runtimeChecking,
                    localRuntimeLastError = runtimeStatus.lastFailureReason,
                    effectiveTtsRoute = runtimeStatus.effectiveRoute,
                    message = if (runtimeStatus.runtimeChecking) {
                        "화자 변경 완료: $speakerName (런타임 점검 중)"
                    } else if (effectiveLocal) {
                        "화자 변경 완료: $speakerName (온디바이스 음성 활성)"
                    } else {
                        "화자 변경 완료: $speakerName (시스템 TTS 사용)"
                    },
                )
            }
        }
    }

    fun onSettingsDialogClosed() {
        val runtimeStatus = ttsManager.currentRuntimeStatus()

        _uiState.update {
            it.copy(
                localRuntimeChecking = runtimeStatus.runtimeChecking,
                localRuntimeLastError = runtimeStatus.lastFailureReason,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val effectiveLocal = runtimeStatus.effectiveRoute == com.example.bookhelper.tts.TtsRoute.LOCAL_KOKORO

            _uiState.update {
                it.copy(
                    effectiveLocalModelEnabled = effectiveLocal,
                    localRuntimeReady = runtimeStatus.runtimeReady,
                    localRuntimeChecking = runtimeStatus.runtimeChecking,
                    localRuntimeLastError = runtimeStatus.lastFailureReason,
                    effectiveTtsRoute = runtimeStatus.effectiveRoute,
                    message = if (runtimeStatus.runtimeChecking) {
                        "온디바이스 음성 런타임 점검 중"
                    } else if (effectiveLocal) {
                        "온디바이스 음성 활성화 완료"
                    } else if (it.ttsEnginePreference.requestsOnDeviceTts() && !it.bundledModelReady) {
                        "로컬 모델이 준비되지 않아 시스템 TTS 사용"
                    } else if (it.ttsEnginePreference.requestsOnDeviceTts()) {
                        "로컬 런타임 점검 실패로 시스템 TTS 사용"
                    } else {
                        "시스템 TTS 사용"
                    },
                )
            }
        }
    }

    fun dismissDictionaryDialog() {
        _uiState.update { it.copy(isDictionaryDialogVisible = false) }
    }

    fun openSavedWordsDialog() {
        _uiState.update { it.copy(isSavedWordsDialogVisible = true) }
    }

    fun dismissSavedWordsDialog() {
        _uiState.update { it.copy(isSavedWordsDialogVisible = false) }
    }

    fun openSavedWord(item: SavedWordItem) {
        if (!_uiState.value.isDictionaryReady) {
            _uiState.update {
                it.copy(message = "사전 데이터 준비 중입니다. 잠시 후 다시 시도해 주세요.")
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val runtime = ensureDictionaryRuntime()
            val normalized = postProcessor.normalizeToken(item.word)
            val lemmas = linkedSetOf(item.lemma, normalized)
                .filter { it.isNotBlank() }
                .toList()
                .ifEmpty { lemmatizer.candidates(normalized) }
            val entries = runtime.lookupService.lookup(
                normalizedToken = normalized,
                lemmaCandidates = lemmas,
                sentenceContext = item.sentence,
            )
            if (entries.isEmpty()) {
                _uiState.update {
                    it.copy(
                        selectedWord = null,
                        selectedWords = emptyList(),
                        selectedSentence = null,
                        dictionaryEntries = emptyList(),
                        isDictionaryDialogVisible = false,
                        isKoreanDefinitionVisible = false,
                        isSavedWordsDialogVisible = false,
                        message = "No dictionary match",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    selectedWord = OcrWord(text = item.word, boundingBox = null),
                    selectedWords = emptyList(),
                    selectedSentence = item.sentence,
                    dictionaryEntries = entries,
                    isDictionaryDialogVisible = true,
                    isKoreanDefinitionVisible = false,
                    isSavedWordsDialogVisible = false,
                    message = "${entries.size} entries",
                )
            }
        }
    }

    fun deleteSavedWord(item: SavedWordItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val vocabularyDao = ensureVocabularyDao()
            vocabularyDao.deleteByKey(item.key)
            val saved = loadSavedWords(vocabularyDao)
            _uiState.update {
                it.copy(
                    savedWords = saved,
                    message = "Deleted: ${item.word}",
                )
            }
        }
    }

    fun revealKoreanDefinition() {
        _uiState.update { it.copy(isKoreanDefinitionVisible = true) }
    }

    fun saveSelectedWord() {
        if (!_uiState.value.isDictionaryReady) {
            _uiState.update { it.copy(message = "사전 데이터 준비 중에는 저장을 지원하지 않습니다.") }
            return
        }

        val word = _uiState.value.selectedWord ?: return
        if (_uiState.value.dictionaryEntries.isEmpty()) {
            _uiState.update { it.copy(message = "No dictionary match") }
            return
        }
        val sentence = _uiState.value.selectedSentence
        viewModelScope.launch(Dispatchers.IO) {
            val vocabularyDao = ensureVocabularyDao()
            val normalized = postProcessor.normalizeToken(word.text)
            val lemma = lemmatizer.candidates(normalized).firstOrNull().orEmpty()
            val key = buildVocabularyKey(word.text, lemma)
            val now = System.currentTimeMillis()
            val existing = vocabularyDao.findByKey(key)
            val merged = mergeVocabularyEntity(
                existing = existing,
                key = key,
                word = word.text,
                lemma = lemma,
                sentence = sentence,
                now = now,
            )

            vocabularyDao.upsert(merged)

            val saved = loadSavedWords(vocabularyDao)
            _uiState.update {
                it.copy(
                    message = "Saved: ${word.text} (${merged.saveCount})",
                    savedWords = saved,
                )
            }
        }
    }

    fun onDragSelect(startX: Float, startY: Float, endX: Float, endY: Float) {
        cancelPendingDictionaryLookup()
        cancelPendingTapDictionaryLookup()
        timedTapSelectionEngine.reset()
        val words = selectionResolver.resolveWordsInRegion(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            page = _uiState.value.ocrPage,
            minDragDistance = _uiState.value.dragSelectionMinDistancePx,
        )
        if (words.isEmpty()) {
            return
        }

        val text = wordsToReadableText(words)
        if (text.isBlank()) {
            return
        }

        speakWithCurrentTts(text, utteranceId = "drag-selection")
        _uiState.update {
            it.copy(
                selectedWord = words.first(),
                selectedWords = words,
                selectedSentence = text,
                isDictionaryDialogVisible = false,
                message = "드래그 구간 읽기",
            )
        }
    }

    private suspend fun loadSavedWords(vocabularyDao: VocabularyDao): List<SavedWordItem> {
        return vocabularyDao.findAll().map {
            SavedWordItem(
                key = it.key,
                word = it.word,
                lemma = it.lemma,
                sentence = it.sentence,
                saveCount = it.saveCount,
                createdAt = it.createdAt,
            )
        }
    }

    private fun buildVocabularyKey(word: String, lemma: String): String {
        return buildVocabularyStorageKey(
            word = word,
            lemma = lemma,
            normalize = { value -> postProcessor.normalizeToken(value).lowercase() },
        )
    }

    private fun speakWithCurrentTts(text: String, utteranceId: String) {
        ttsManager.speak(
            text = text,
            utteranceId = utteranceId,
            onResult = { result ->
                applySpeakResult(result)
            },
        )
    }

    private fun applySpeakResult(result: com.example.bookhelper.tts.TtsSpeakResult) {
        val runtimeStatus = ttsManager.currentRuntimeStatus()
        _uiState.update {
            val usedLocal = result.route == com.example.bookhelper.tts.TtsRoute.LOCAL_KOKORO
            val routeText = when (result.route) {
                com.example.bookhelper.tts.TtsRoute.LOCAL_KOKORO -> "온디바이스 음성"
                com.example.bookhelper.tts.TtsRoute.SYSTEM_TTS -> "시스템 TTS"
                com.example.bookhelper.tts.TtsRoute.NONE -> "재생 실패"
            }
            it.copy(
                effectiveTtsRoute = result.route,
                effectiveLocalModelEnabled = usedLocal,
                localRuntimeReady = runtimeStatus.runtimeReady,
                localRuntimeChecking = runtimeStatus.runtimeChecking,
                localRuntimeLastError = runtimeStatus.lastFailureReason,
                message = if (result.accepted) {
                    "TTS 재생: $routeText"
                } else {
                    "TTS 재생 실패: ${result.reason ?: "unknown"}"
                },
            )
        }
    }

    private fun resolveSentenceAt(y: Float, page: OcrPage): String? {
        val nearestLine = page.blocks
            .flatMap { it.lines }
            .minByOrNull { lineDistance(it, y) }
            ?: return null

        return sentenceSegmenter
            .split(nearestLine.text)
            .firstOrNull()
    }

    private fun lineDistance(line: OcrLine, y: Float): Float {
        val box = line.boundingBox ?: return Float.MAX_VALUE
        val center = (box.top + box.bottom) / 2f
        return kotlin.math.abs(center - y)
    }

    private fun resolveLocalModel(modelId: String?): BundledTtsModel {
        return localModels.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
            ?: localModels.firstOrNull { it.id.equals(BundledTtsModels.DefaultEnglish.id, ignoreCase = true) }
            ?: localModels.firstOrNull()
            ?: BundledTtsModels.DefaultEnglish
    }

    private fun ensureBundledModelInstalled(model: BundledTtsModel): String? {
        installedLocalModelPaths[model.id]?.let { return it }
        if (model.isDownloadedModel) {
            return bundledModelInstaller.resolveInstalledModelPath(model)
                ?.also { installedLocalModelPaths[model.id] = it }
        }
        return bundledModelInstaller.ensureInstalled(model)
            .onFailure { throwable ->
                Log.e(
                    TAG,
                    "Bundled local TTS model install failed. modelId=${model.id}, assetDir=${model.assetDirectory}",
                    throwable,
                )
            }
            .onSuccess { path -> installedLocalModelPaths[model.id] = path }
            .getOrNull()
    }

    private fun activateLocalModel(
        model: BundledTtsModel,
        requestedSpeakerId: Int,
        preference: TtsEnginePreference,
    ): LocalModelActivation {
        val normalizedSpeakerId = model.normalizeSpeakerId(requestedSpeakerId)
        val modelPath = ensureBundledModelInstalled(model)
        val modelReady = !modelPath.isNullOrBlank()

        ttsManager.setLocalModelPath(modelPath)
        ttsManager.setLocalSpeakerId(normalizedSpeakerId)

        val shouldRequestLocal = resolveLocalTtsEnabled(
            preference = preference,
            modelReady = modelReady,
        )

        ttsManager.setLocalModelEnabled(shouldRequestLocal)
        val runtimeStatus = if (shouldRequestLocal) {
            ttsManager.refreshLocalRuntime()
        } else {
            ttsManager.currentRuntimeStatus()
        }

        val runtimeReady = runtimeStatus.runtimeReady
        val effectiveLocalModelEnabled = runtimeStatus.effectiveRoute == com.example.bookhelper.tts.TtsRoute.LOCAL_KOKORO

        return LocalModelActivation(
            speakerId = normalizedSpeakerId,
            modelPath = modelPath,
            modelReady = modelReady,
            runtimeReady = runtimeReady,
            runtimeChecking = runtimeStatus.runtimeChecking,
            lastFailureReason = runtimeStatus.lastFailureReason,
            onDeviceRequested = preference.requestsOnDeviceTts(),
            effectiveLocalModelEnabled = effectiveLocalModelEnabled,
            effectiveRoute = runtimeStatus.effectiveRoute,
        )
    }

    override fun onCleared() {
        cancelPendingDictionaryLookup()
        cancelPendingTapDictionaryLookup()
        ttsManager.stop()
        super.onCleared()
    }

    private data class DictionaryRuntime(
        val lookupService: DictionaryLookupService,
    )

    private data class LocalModelActivation(
        val speakerId: Int,
        val modelPath: String?,
        val modelReady: Boolean,
        val runtimeReady: Boolean,
        val runtimeChecking: Boolean,
        val lastFailureReason: String?,
        val onDeviceRequested: Boolean,
        val effectiveLocalModelEnabled: Boolean,
        val effectiveRoute: com.example.bookhelper.tts.TtsRoute,
    )

    companion object {
        private const val TAG = "ReaderViewModel"
        private const val DictionaryLookupDebounceMs = 120L

        fun factory(context: Context, startupPayload: ReaderStartupPayload): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
                        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                    }
                    val viewModel = ReaderViewModel(context.applicationContext, startupPayload)
                    return requireNotNull(modelClass.cast(viewModel))
                }
            }
        }
    }
}
