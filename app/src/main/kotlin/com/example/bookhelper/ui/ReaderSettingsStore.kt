package com.example.bookhelper.ui

import android.content.Context
import com.example.bookhelper.tts.BundledTtsModels
import com.example.bookhelper.tts.TtsEnginePreference

data class ReaderSettings(
    val speechRate: Float,
    val autoSpeakEnabled: Boolean,
    val speechTarget: SpeechTarget,
    val tapSelectionWindowMs: Long,
    val dragSelectionMinDistancePx: Float,
    val ttsEnginePreference: TtsEnginePreference,
    val localModelId: String,
    val localSpeakerId: Int,
)

class ReaderSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ReaderSettings {
        val speechTarget = runCatching {
            SpeechTarget.valueOf(prefs.getString(KEY_SPEECH_TARGET, SpeechTarget.WORD.name).orEmpty())
        }.getOrElse { SpeechTarget.WORD }
        val storedEnginePreference = TtsEnginePreference.fromStored(prefs.getString(KEY_TTS_ENGINE_PREFERENCE, null))
        val hasStoredEnginePreference = prefs.contains(KEY_TTS_ENGINE_PREFERENCE)
        val legacyLocalModelEnabled = prefs.getBoolean(KEY_LOCAL_MODEL_ENABLED, false)
        val ttsEnginePreference = when {
            hasStoredEnginePreference -> storedEnginePreference
            legacyLocalModelEnabled -> TtsEnginePreference.ON_DEVICE
            else -> TtsEnginePreference.SYSTEM_DEFAULT
        }

        if (!hasStoredEnginePreference && legacyLocalModelEnabled) {
            prefs.edit()
                .putString(KEY_TTS_ENGINE_PREFERENCE, ttsEnginePreference.name)
                .remove(KEY_LOCAL_MODEL_ENABLED)
                .apply()
        }

        return ReaderSettings(
            speechRate = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE).coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE),
            autoSpeakEnabled = prefs.getBoolean(KEY_AUTO_SPEAK_ENABLED, false),
            speechTarget = speechTarget,
            tapSelectionWindowMs = prefs.getLong(KEY_TAP_SELECTION_WINDOW_MS, 1200L).coerceIn(300L, 3000L),
            dragSelectionMinDistancePx = prefs.getFloat(KEY_DRAG_SELECTION_MIN_DISTANCE_PX, 24f).coerceIn(4f, 200f),
            ttsEnginePreference = ttsEnginePreference,
            localModelId = prefs.getString(KEY_LOCAL_MODEL_ID, BundledTtsModels.DefaultEnglish.id)
                ?: BundledTtsModels.DefaultEnglish.id,
            localSpeakerId = prefs.getInt(KEY_LOCAL_SPEAKER_ID, 0),
        )
    }

    fun saveSpeechRate(value: Float) {
        prefs.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)).apply()
    }

    fun saveAutoSpeakEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SPEAK_ENABLED, value).apply()
    }

    fun saveSpeechTarget(value: SpeechTarget) {
        prefs.edit().putString(KEY_SPEECH_TARGET, value.name).apply()
    }

    fun saveTapSelectionWindowMs(value: Long) {
        prefs.edit().putLong(KEY_TAP_SELECTION_WINDOW_MS, value.coerceIn(300L, 3000L)).apply()
    }

    fun saveDragSelectionMinDistancePx(value: Float) {
        prefs.edit().putFloat(KEY_DRAG_SELECTION_MIN_DISTANCE_PX, value.coerceIn(4f, 200f)).apply()
    }

    fun saveTtsEnginePreference(value: TtsEnginePreference) {
        prefs.edit().putString(KEY_TTS_ENGINE_PREFERENCE, value.name).apply()
    }

    fun saveLocalModelId(value: String) {
        prefs.edit().putString(KEY_LOCAL_MODEL_ID, value).apply()
    }

    fun saveLocalSpeakerId(value: Int) {
        prefs.edit().putInt(KEY_LOCAL_SPEAKER_ID, value).apply()
    }

    private companion object {
        const val PREFS_NAME = "reader_settings"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_AUTO_SPEAK_ENABLED = "auto_speak_enabled"
        const val KEY_SPEECH_TARGET = "speech_target"
        const val KEY_TAP_SELECTION_WINDOW_MS = "tap_selection_window_ms"
        const val KEY_DRAG_SELECTION_MIN_DISTANCE_PX = "drag_selection_min_distance_px"
        const val KEY_TTS_ENGINE_PREFERENCE = "tts_engine_preference"
        const val KEY_LOCAL_MODEL_ENABLED = "local_model_enabled"
        const val KEY_LOCAL_MODEL_ID = "local_model_id"
        const val KEY_LOCAL_SPEAKER_ID = "local_speaker_id"
        const val MIN_SPEECH_RATE = 0.85f
        const val MAX_SPEECH_RATE = 1.15f
        const val DEFAULT_SPEECH_RATE = 1.0f
    }
}
