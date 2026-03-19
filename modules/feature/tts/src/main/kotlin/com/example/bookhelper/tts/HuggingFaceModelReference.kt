package com.example.bookhelper.tts

data class HuggingFaceModelReference(
    val modelId: String,
    val artifactPath: String,
    val revision: String = "main",
) {
    fun resolveUrl(): String {
        return "https://huggingface.co/$modelId/resolve/$revision/$artifactPath"
    }

    companion object {
        fun isHuggingFaceUrl(url: String): Boolean {
            val normalized = url.trim().lowercase()
            return normalized.startsWith("https://huggingface.co/")
        }
    }
}
