package com.example.bookhelper.tts

fun resolveLocalTtsEnabled(
    preference: TtsEnginePreference,
    modelReady: Boolean,
): Boolean {
    return preference.requestsOnDeviceTts() && modelReady
}
