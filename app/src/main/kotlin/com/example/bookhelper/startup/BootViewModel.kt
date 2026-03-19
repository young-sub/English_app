package com.example.bookhelper.startup

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.bookhelper.data.local.DictionaryDatabase
import com.example.bookhelper.data.local.UserDatabase
import com.example.bookhelper.dictionary.DictionaryLookupService
import com.example.bookhelper.dictionary.RoomDictionaryLookupDataSource
import com.example.bookhelper.provisioning.ProvisioningReadiness
import com.example.bookhelper.provisioning.ProvisioningService
import com.example.bookhelper.tts.AndroidTtsManager
import com.example.bookhelper.tts.BundledTtsModel
import com.example.bookhelper.tts.BundledTtsModelInstaller
import com.example.bookhelper.tts.BundledTtsModels
import com.example.bookhelper.tts.DownloadedLocalModelRegistry
import com.example.bookhelper.tts.TtsRoute
import com.example.bookhelper.tts.isDownloadedModel
import com.example.bookhelper.tts.requestsOnDeviceTts
import com.example.bookhelper.tts.normalizeSpeakerId
import com.example.bookhelper.tts.resolveLocalTtsEnabled
import com.example.bookhelper.ui.ReaderSettings
import com.example.bookhelper.ui.ReaderSettingsStore
import com.example.bookhelper.ui.ReaderUiState
import com.example.bookhelper.ui.SavedWordItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class BootViewModel(
    private val appContext: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BootUiState())
    val uiStateFlow: StateFlow<BootUiState> = _uiState.asStateFlow()
    private var activeTtsHolder: StartupTtsManagerHolder? = null

    init {
        runBoot()
    }

    fun retry() {
        runBoot()
    }

    private fun runBoot() {
        val recorder = InMemoryBootDiagnosticsRecorder()
        _uiState.value = BootUiState()

        viewModelScope.launch(Dispatchers.IO) {
            val settingsStore = ReaderSettingsStore(appContext)
            val provisioningService = ProvisioningService()
            val bundledModelInstaller = BundledTtsModelInstaller(appContext)
            val ttsHolder = StartupTtsManagerHolder()
            val vocabularyDaoHolder = StartupVocabularyDaoHolder()
            val dictionaryLookupServiceHolder = StartupDictionaryLookupServiceHolder()
            activeTtsHolder = ttsHolder

            lateinit var settings: ReaderSettings
            lateinit var localModels: List<BundledTtsModel>
            lateinit var selectedModel: BundledTtsModel
            lateinit var localActivation: BootLocalModelActivation
            lateinit var savedWords: List<SavedWordItem>
            lateinit var provisioningSummary: String

            val coordinator = BootCoordinator(
                steps = listOf(
                    BootStep(BootStage.SETTINGS) {
                        settings = settingsStore.load()
                        BootStepResult("설정 로드 완료")
                    },
                    BootStep(BootStage.BUNDLED_ASSETS) {
                        localModels = (
                            bundledModelInstaller.discoverBundledModels() +
                                DownloadedLocalModelRegistry.discoverInstalledModels(File(appContext.filesDir, "tts-models"))
                            )
                            .distinctBy { it.id }
                        selectedModel = resolveLocalModel(localModels, settings.localModelId)
                        if (!selectedModel.id.equals(settings.localModelId, ignoreCase = true)) {
                            settingsStore.saveLocalModelId(selectedModel.id)
                        }
                        BootStepResult("번들 모델 ${localModels.size}개 확인")
                    },
                    BootStep(BootStage.SYSTEM_TTS) {
                        val manager = AndroidTtsManager(appContext)
                        manager.setRate(settings.speechRate)
                        manager.setEnginePreference(settings.ttsEnginePreference)
                        val ready = manager.awaitReady(4_000L)
                        ttsHolder.manager = manager
                        if (!ready) error("system TTS handshake timed out")
                        BootStepResult("시스템 TTS 준비 완료")
                    },
                    BootStep(BootStage.LOCAL_TTS_RUNTIME) {
                        val manager = requireNotNull(ttsHolder.manager)
                        val shouldPrepareLocalRuntime = settings.ttsEnginePreference.requestsOnDeviceTts()
                        localActivation = if (shouldPrepareLocalRuntime) {
                            activateLocalModel(
                                manager = manager,
                                installer = bundledModelInstaller,
                                model = selectedModel,
                                requestedSpeakerId = settings.localSpeakerId,
                                preference = settings.ttsEnginePreference,
                            )
                        } else {
                            skipLocalModelActivation(
                                manager = manager,
                                model = selectedModel,
                                requestedSpeakerId = settings.localSpeakerId,
                            )
                        }
                        if (shouldPrepareLocalRuntime && !localActivation.effectiveLocalModelEnabled) {
                            error(localActivation.lastFailureReason ?: "local TTS runtime is not ready")
                        }
                        BootStepResult("로컬 TTS 구동 준비 완료")
                    },
                    BootStep(BootStage.DICTIONARY) {
                        val vocabularyDao = UserDatabase.get(appContext).vocabularyDao()
                        val dictionaryDao = DictionaryDatabase.get(appContext).dictionaryDao()
                        val lookupService = DictionaryLookupService(RoomDictionaryLookupDataSource(dictionaryDao))
                        vocabularyDaoHolder.dao = vocabularyDao
                        dictionaryLookupServiceHolder.lookupService = lookupService
                        savedWords = vocabularyDao.findAll().map {
                            SavedWordItem(
                                key = it.key,
                                word = it.word,
                                lemma = it.lemma,
                                sentence = it.sentence,
                                saveCount = it.saveCount,
                                createdAt = it.createdAt,
                            )
                        }
                        BootStepResult("사전/단어장 준비 완료")
                    },
                    BootStep(BootStage.PROVISIONING) {
                        val status = provisioningService.prepare(
                            ProvisioningReadiness(
                                ocrModelReady = true,
                                ttsVoiceReady = ttsHolder.manager != null,
                            ),
                        )
                        provisioningSummary = "Provisioning: $status"
                        BootStepResult(provisioningSummary)
                    },
                    BootStep(BootStage.LOCAL_TTS_BENCHMARK) {
                        if (settings.ttsEnginePreference.requestsOnDeviceTts()) {
                            val manager = requireNotNull(ttsHolder.manager)
                            manager.refreshLocalRuntime()
                            BootStepResult("로컬 TTS 벤치마크/재확인 완료")
                        } else {
                            BootStepResult("시스템 음성 사용 중")
                        }
                    },
                ),
                recorder = object : BootDiagnosticsRecorder {
                    override fun record(event: BootLogEvent) {
                        recorder.record(event)
                        val priority = when (event.level) {
                            BootLogLevel.INFO -> Log.INFO
                            BootLogLevel.WARN -> Log.WARN
                            BootLogLevel.ERROR -> Log.ERROR
                        }
                        Log.println(priority, TAG, "${event.stage.name}: ${event.message}")
                        _uiState.update { state ->
                            state.copy(detailedLogs = recorder.events.takeLast(100))
                        }
                    }
                },
            )

            val result = coordinator.run { snapshot ->
                _uiState.update {
                    it.copy(
                        snapshot = snapshot,
                        latestSummary = snapshot.currentStage?.displayName ?: "앱 시작 준비 중",
                    )
                }
            }

            if (!result.finalSnapshot.canEnterMain) {
                _uiState.update {
                    it.copy(
                        snapshot = result.finalSnapshot,
                        failureMessage = result.finalSnapshot.stages.firstOrNull { stage ->
                            stage.status == BootStageStatus.FAILED
                        }?.summary ?: "startup failed",
                    )
                }
                return@launch
            }

            val initialUiState = ReaderUiState(
                savedWords = savedWords,
                speechRate = settings.speechRate,
                autoSpeakEnabled = settings.autoSpeakEnabled,
                speechTarget = settings.speechTarget,
                tapSelectionWindowMs = settings.tapSelectionWindowMs,
                dragSelectionMinDistancePx = settings.dragSelectionMinDistancePx,
                ttsEnginePreference = settings.ttsEnginePreference,
                effectiveLocalModelEnabled = localActivation.effectiveLocalModelEnabled,
                localRuntimeReady = localActivation.runtimeReady,
                localRuntimeChecking = localActivation.runtimeChecking,
                localRuntimeLastError = localActivation.lastFailureReason,
                effectiveTtsRoute = localActivation.effectiveRoute,
                availableLocalModels = localModels,
                selectedLocalModelId = selectedModel.id,
                availableSpeakers = selectedModel.speakers,
                selectedSpeakerId = localActivation.speakerId,
                bundledModelName = selectedModel.displayName,
                bundledModelReady = localActivation.modelReady,
                bundledModelPath = localActivation.modelPath,
                isDictionaryReady = true,
                message = "$provisioningSummary, 앱 진입 준비 완료",
            )

            _uiState.update {
                it.copy(
                    snapshot = result.finalSnapshot,
                    latestSummary = "준비 완료",
                    startupPayload = ReaderStartupPayload(
                        initialUiState = initialUiState,
                        ttsManagerHolder = ttsHolder,
                        vocabularyDaoHolder = vocabularyDaoHolder,
                        dictionaryLookupServiceHolder = dictionaryLookupServiceHolder,
                    ),
                    failureMessage = null,
                )
            }
        }
    }

    override fun onCleared() {
        activeTtsHolder?.manager?.shutdown()
        activeTtsHolder?.manager = null
        activeTtsHolder = null
        super.onCleared()
    }

    private fun resolveLocalModel(localModels: List<BundledTtsModel>, modelId: String?): BundledTtsModel {
        return localModels.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
            ?: localModels.firstOrNull { it.id.equals(BundledTtsModels.DefaultEnglish.id, ignoreCase = true) }
            ?: localModels.firstOrNull()
            ?: BundledTtsModels.DefaultEnglish
    }

    private fun activateLocalModel(
        manager: AndroidTtsManager,
        installer: BundledTtsModelInstaller,
        model: BundledTtsModel,
        requestedSpeakerId: Int,
        preference: com.example.bookhelper.tts.TtsEnginePreference,
    ): BootLocalModelActivation {
        val normalizedSpeakerId = model.normalizeSpeakerId(requestedSpeakerId)
        val modelPath = if (model.isDownloadedModel) {
            installer.resolveInstalledModelPath(model)
        } else {
            installer.ensureInstalled(model).getOrNull()
        }
        val modelReady = !modelPath.isNullOrBlank()

        manager.setLocalModelPath(modelPath)
        manager.setLocalSpeakerId(normalizedSpeakerId)
        val shouldRequestLocal = resolveLocalTtsEnabled(
            preference = preference,
            modelReady = modelReady,
        )
        manager.setLocalModelEnabled(shouldRequestLocal)
        val runtimeStatus = if (shouldRequestLocal) manager.refreshLocalRuntime() else manager.currentRuntimeStatus()

        return BootLocalModelActivation(
            speakerId = normalizedSpeakerId,
            modelPath = modelPath,
            modelReady = modelReady,
            runtimeReady = runtimeStatus.runtimeReady,
            runtimeChecking = runtimeStatus.runtimeChecking,
            lastFailureReason = runtimeStatus.lastFailureReason,
            effectiveLocalModelEnabled = runtimeStatus.effectiveRoute == TtsRoute.LOCAL_KOKORO,
            effectiveRoute = runtimeStatus.effectiveRoute,
        )
    }

    private fun skipLocalModelActivation(
        manager: AndroidTtsManager,
        model: BundledTtsModel,
        requestedSpeakerId: Int,
    ): BootLocalModelActivation {
        val normalizedSpeakerId = model.normalizeSpeakerId(requestedSpeakerId)
        manager.setLocalSpeakerId(normalizedSpeakerId)
        manager.setLocalModelPath(null)
        manager.setLocalModelEnabled(false)
        val runtimeStatus = manager.currentRuntimeStatus()
        return BootLocalModelActivation(
            speakerId = normalizedSpeakerId,
            modelPath = null,
            modelReady = false,
            runtimeReady = runtimeStatus.runtimeReady,
            runtimeChecking = runtimeStatus.runtimeChecking,
            lastFailureReason = runtimeStatus.lastFailureReason,
            effectiveLocalModelEnabled = false,
            effectiveRoute = runtimeStatus.effectiveRoute,
        )
    }

    private data class BootLocalModelActivation(
        val speakerId: Int,
        val modelPath: String?,
        val modelReady: Boolean,
        val runtimeReady: Boolean,
        val runtimeChecking: Boolean,
        val lastFailureReason: String?,
        val effectiveLocalModelEnabled: Boolean,
        val effectiveRoute: TtsRoute,
    )

    companion object {
        private const val TAG = "BootViewModel"

        fun factory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(BootViewModel::class.java)) {
                        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                    }
                    return requireNotNull(modelClass.cast(BootViewModel(context.applicationContext)))
                }
            }
        }
    }
}
