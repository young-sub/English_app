package com.example.bookhelper.tts

enum class LocalTtsModelKind {
    KOKORO,
    PIPER_DERIVED,
}

enum class LocalTtsModelFormat {
    KOKORO_ONNX,
    PIPER_ONNX,
}

data class LocalTtsModelContract(
    val id: String,
    val displayName: String,
    val modelKind: LocalTtsModelKind,
    val modelFormat: LocalTtsModelFormat,
    val assetDirectory: String,
)
