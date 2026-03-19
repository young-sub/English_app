package com.example.bookhelper.tts

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.Locale

class AndroidTtsManager(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val initLock = Object()
    private var isReady = false
    private var tts: TextToSpeech? = createTextToSpeech(null)
    private val naturalVoiceSelector = NaturalVoiceSelector()
    private var enginePreference: TtsEnginePreference = TtsEnginePreference.SYSTEM_DEFAULT
    private var requestedEngineName: String? = null
    private val localModelTtsEngine = LocalModelTtsEngine()
    private var localModelRequested: Boolean = false
    private var localModelConfigured: Boolean = false
    private var localRuntimeReady: Boolean = false
    private var localRuntimeDirty: Boolean = true
    private var localRuntimeLastError: String? = null
    private var speechRate: Float = DEFAULT_SPEECH_RATE
    private val localRuntimeExecutor = Executors.newSingleThreadExecutor()
    private val localRuntimeLock = Any()
    private var localRuntimeFuture: Future<*>? = null

    override fun onInit(status: Int) {
        val currentTts = tts ?: return
        isReady = status == TextToSpeech.SUCCESS
        synchronized(initLock) {
            initLock.notifyAll()
        }
        if (!isReady) {
            if (!requestedEngineName.isNullOrBlank()) {
                restartTts(null)
            }
            return
        }

        val languageResult = currentTts.setLanguage(Locale.US)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            currentTts.setLanguage(Locale.getDefault())
        }
        currentTts.setSpeechRate(speechRate)
        applyNaturalVoicePreference()
    }

    fun setRate(rate: Float) {
        speechRate = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        if (!isReady) return
        tts?.setSpeechRate(speechRate)
    }

    fun setEnginePreference(preference: TtsEnginePreference) {
        if (enginePreference == preference) {
            return
        }
        enginePreference = preference
        val desiredEngine = resolvePreferredEngineName()
        if (desiredEngine == requestedEngineName) {
            return
        }
        restartTts(desiredEngine)
    }

    fun setLocalModelEnabled(enabled: Boolean) {
        localModelRequested = enabled
        if (enabled) {
            scheduleLocalRuntimeVerificationIfNeeded()
        } else {
            localModelTtsEngine.stop()
        }
    }

    fun setLocalModelPath(path: String?) {
        localModelTtsEngine.setModelPath(path)
        localModelConfigured = !path.isNullOrBlank()
        localRuntimeDirty = true
        localRuntimeLastError = null
        if (!localModelConfigured) {
            localRuntimeReady = false
            localRuntimeLastError = null
        } else {
            scheduleLocalRuntimeVerificationIfNeeded()
        }
    }

    fun setLocalSpeakerId(speakerId: Int) {
        localModelTtsEngine.setSpeakerId(speakerId)
        localRuntimeDirty = true
        localRuntimeLastError = null
        if (localModelConfigured) {
            scheduleLocalRuntimeVerificationIfNeeded()
        }
    }

    fun verifyLocalModelRuntime(): Boolean {
        if (!localModelConfigured) {
            localRuntimeDirty = false
            localRuntimeLastError = null
            return false
        }

        val result = localModelTtsEngine
            .benchmarkSynthesis(
                text = "Ready.",
                speed = speechRate,
            )
        result.exceptionOrNull()?.let { throwable ->
            Log.e(
                TAG,
                "Local model runtime verification failed. Falling back to system TTS.",
                throwable,
            )
        }
        localRuntimeReady = result.isSuccess
        localRuntimeDirty = false
        localRuntimeLastError = if (result.isSuccess) {
            null
        } else {
            val throwable = result.exceptionOrNull()
            if (throwable == null) {
                "runtime-verification-failed"
            } else {
                "${throwable::class.java.simpleName}: ${throwable.message ?: "(no message)"}"
            }
        }
        return localRuntimeReady
    }

    fun refreshLocalRuntime(): LocalTtsRuntimeStatus {
        if (localModelConfigured) {
            if (localRuntimeDirty) {
                scheduleLocalRuntimeVerificationIfNeeded()
                awaitLocalRuntimeCompletion()
            }
            if (localRuntimeDirty) {
                verifyLocalModelRuntime()
            }
        } else {
            localRuntimeReady = false
            localRuntimeDirty = false
        }
        return currentRuntimeStatus()
    }

    fun currentRuntimeStatus(): LocalTtsRuntimeStatus {
        return resolveLocalTtsRuntimeStatus(
            localRequested = localModelRequested,
            modelConfigured = localModelConfigured,
            runtimeReady = localRuntimeReady,
            runtimeDirty = localRuntimeDirty,
            lastFailureReason = localRuntimeLastError ?: localModelTtsEngine.latestFailureReason(),
        )
    }

    fun awaitReady(timeoutMs: Long): Boolean {
        if (isReady) {
            return true
        }
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1L)
        synchronized(initLock) {
            while (!isReady) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    break
                }
                runCatching { initLock.wait(remaining) }
            }
        }
        return isReady
    }

    fun speak(
        text: String,
        utteranceId: String = "sentence",
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        onResult: ((TtsSpeakResult) -> Unit)? = null,
    ): TtsSpeakResult {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            val result = TtsSpeakResult(
                accepted = false,
                route = TtsRoute.NONE,
                reason = "blank-text",
            )
            onResult?.invoke(result)
            return result
        }

        val runtimeStatus = if (localModelRequested && localModelConfigured && (localRuntimeDirty || !localRuntimeReady)) {
            refreshLocalRuntime()
        } else {
            currentRuntimeStatus()
        }
        if (canAttemptLocalSpeak(runtimeStatus)) {
            val localAccepted = localModelTtsEngine.speakAsync(
                text = normalizedText,
                speed = speechRate,
                onFailure = {
                    localRuntimeReady = false
                    localRuntimeDirty = false
                    localRuntimeLastError = localModelTtsEngine.latestFailureReason() ?: "local-speak-failed"
                    val fallbackAccepted = speakWithSystemEngine(
                        text = normalizedText,
                        utteranceId = utteranceId,
                        queueMode = queueMode,
                    )
                    val fallbackResult = TtsSpeakResult(
                        accepted = fallbackAccepted,
                        route = if (fallbackAccepted) TtsRoute.SYSTEM_TTS else TtsRoute.NONE,
                        reason = if (fallbackAccepted) "local-failed-system-fallback" else "local-and-system-failed",
                    )
                    onResult?.invoke(fallbackResult)
                },
            )
            if (localAccepted) {
                localRuntimeReady = true
                localRuntimeDirty = false
                localRuntimeLastError = null
                val result = TtsSpeakResult(
                    accepted = true,
                    route = TtsRoute.LOCAL_KOKORO,
                    reason = null,
                )
                onResult?.invoke(result)
                return result
            }
        }

        val localUnavailableReason = localUnavailableReason(currentRuntimeStatus())
        val systemAccepted = speakWithSystemEngine(
            text = normalizedText,
            utteranceId = utteranceId,
            queueMode = queueMode,
        )
        val result = TtsSpeakResult(
            accepted = systemAccepted,
            route = if (systemAccepted) TtsRoute.SYSTEM_TTS else TtsRoute.NONE,
            reason = if (systemAccepted) localUnavailableReason else (localUnavailableReason ?: "system-tts-unavailable"),
        )
        onResult?.invoke(result)
        return result
    }

    fun stop() {
        localModelTtsEngine.stop()
        tts?.stop()
    }

    fun shutdown() {
        localModelTtsEngine.shutdown()
        localRuntimeExecutor.shutdownNow()
        tts?.stop()
        tts?.shutdown()
    }

    private fun scheduleLocalRuntimeVerificationIfNeeded() {
        if (!localModelConfigured) {
            return
        }
        synchronized(localRuntimeLock) {
            if (!localRuntimeDirty && localRuntimeReady) {
                return
            }
            val running = localRuntimeFuture?.let { !it.isDone && !it.isCancelled } == true
            if (running) {
                return
            }
            localRuntimeDirty = true
            localRuntimeFuture = localRuntimeExecutor.submit {
                verifyLocalModelRuntime()
            }
        }
    }

    private fun awaitLocalRuntimeCompletion() {
        scheduleLocalRuntimeVerificationIfNeeded()
        val future = synchronized(localRuntimeLock) { localRuntimeFuture }
        if (future == null) {
            return
        }

        try {
            future.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            localRuntimeReady = false
            localRuntimeDirty = false
            localRuntimeLastError = "runtime-wait-interrupted"
            Log.w(TAG, "Interrupted while waiting for local runtime readiness", interrupted)
        } catch (throwable: Throwable) {
            localRuntimeReady = false
            localRuntimeDirty = false
            localRuntimeLastError = "runtime-wait-failed: ${throwable::class.java.simpleName}"
            Log.w(TAG, "Local runtime readiness wait failed", throwable)
        }
    }

    private fun applyNaturalVoicePreference() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        val currentTts = tts ?: return
        val available = currentTts.voices?.map { voice ->
            VoiceProfile(
                id = voice.name,
                localeTag = voice.locale?.toLanguageTag() ?: "",
                quality = voice.quality,
                latency = voice.latency,
                requiresNetwork = voice.isNetworkConnectionRequired,
            )
        }.orEmpty()
        val selected = naturalVoiceSelector.chooseBest(available, preferredLocaleTag = "en-us") ?: return
        val match = currentTts.voices?.firstOrNull { it.name == selected.id } ?: return
        currentTts.voice = match
    }

    private fun resolvePreferredEngineName(): String? {
        val installed = tts?.engines?.map { it.name }?.toSet().orEmpty()
        return when (enginePreference) {
            TtsEnginePreference.SYSTEM_DEFAULT -> null
            TtsEnginePreference.GOOGLE -> if (GOOGLE_ENGINE in installed) GOOGLE_ENGINE else null
            TtsEnginePreference.SAMSUNG -> if (SAMSUNG_ENGINE in installed) SAMSUNG_ENGINE else null
            TtsEnginePreference.ON_DEVICE -> null
        }
    }

    private fun restartTts(engineName: String?) {
        requestedEngineName = engineName
        tts?.stop()
        tts?.shutdown()
        isReady = false
        tts = createTextToSpeech(engineName)
        if (tts == null && !engineName.isNullOrBlank()) {
            Log.w(TAG, "Failed to create preferred TTS engine '$engineName'. Retrying with system default engine.")
            requestedEngineName = null
            tts = createTextToSpeech(null)
        }
    }

    private fun createTextToSpeech(engineName: String?): TextToSpeech? {
        return runCatching {
            if (engineName.isNullOrBlank()) {
                TextToSpeech(appContext, this)
            } else {
                TextToSpeech(appContext, this, engineName)
            }
        }.onFailure { throwable ->
            val targetEngine = engineName ?: "system-default"
            Log.e(
                TAG,
                "Failed to initialize TextToSpeech instance. engine=$targetEngine",
                throwable,
            )
        }.getOrNull()
    }

    private fun speakWithSystemEngine(
        text: String,
        utteranceId: String,
        queueMode: Int,
    ): Boolean {
        if (!isReady && !awaitReady(SYSTEM_TTS_READY_WAIT_MS)) {
            return false
        }
        val currentTts = tts ?: return false
        val result = currentTts.speak(text, queueMode, Bundle(), utteranceId)
        if (result == TextToSpeech.ERROR && !requestedEngineName.isNullOrBlank()) {
            restartTts(null)
            if (awaitReady(SYSTEM_TTS_READY_WAIT_MS)) {
                return tts?.speak(text, queueMode, Bundle(), utteranceId) != TextToSpeech.ERROR
            }
        }
        return result != TextToSpeech.ERROR
    }

    private companion object {
        const val TAG = "AndroidTtsManager"
        const val GOOGLE_ENGINE = "com.google.android.tts"
        const val SAMSUNG_ENGINE = "com.samsung.SMT"
        const val MIN_SPEECH_RATE = 0.85f
        const val MAX_SPEECH_RATE = 1.15f
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val SYSTEM_TTS_READY_WAIT_MS = 1_500L
    }
}

data class TtsSpeakResult(
    val accepted: Boolean,
    val route: TtsRoute,
    val reason: String?,
)
