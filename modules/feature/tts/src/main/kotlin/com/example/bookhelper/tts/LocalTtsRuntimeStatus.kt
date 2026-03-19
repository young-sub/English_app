package com.example.bookhelper.tts

enum class TtsRoute {
    LOCAL_KOKORO,
    SYSTEM_TTS,
    NONE,
}

data class LocalTtsRuntimeStatus(
    val localRequested: Boolean,
    val modelConfigured: Boolean,
    val runtimeReady: Boolean,
    val runtimeDirty: Boolean,
    val runtimeChecking: Boolean,
    val effectiveRoute: TtsRoute,
    val lastFailureReason: String?,
)

fun resolveLocalTtsRuntimeStatus(
    localRequested: Boolean,
    modelConfigured: Boolean,
    runtimeReady: Boolean,
    runtimeDirty: Boolean,
    lastFailureReason: String?,
): LocalTtsRuntimeStatus {
    val runtimeChecking = localRequested && modelConfigured && runtimeDirty
    val effectiveLocal = localRequested && modelConfigured && runtimeReady && !runtimeDirty
    val route = if (effectiveLocal) TtsRoute.LOCAL_KOKORO else TtsRoute.SYSTEM_TTS
    return LocalTtsRuntimeStatus(
        localRequested = localRequested,
        modelConfigured = modelConfigured,
        runtimeReady = runtimeReady,
        runtimeDirty = runtimeDirty,
        runtimeChecking = runtimeChecking,
        effectiveRoute = route,
        lastFailureReason = lastFailureReason,
    )
}

fun canAttemptLocalSpeak(status: LocalTtsRuntimeStatus): Boolean {
    return status.localRequested && status.modelConfigured && (status.runtimeReady || status.runtimeChecking)
}

fun localUnavailableReason(status: LocalTtsRuntimeStatus): String? {
    return if (status.localRequested && !status.modelConfigured) {
        "local-model-not-configured"
    } else if (status.localRequested && !status.runtimeReady) {
        "local-runtime-not-ready"
    } else {
        null
    }
}
