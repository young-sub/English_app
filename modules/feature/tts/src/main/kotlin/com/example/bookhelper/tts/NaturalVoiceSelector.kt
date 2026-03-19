package com.example.bookhelper.tts

data class VoiceProfile(
    val id: String,
    val localeTag: String,
    val quality: Int,
    val latency: Int,
    val requiresNetwork: Boolean,
)

class NaturalVoiceSelector {
    fun chooseBest(
        voices: List<VoiceProfile>,
        preferredLocaleTag: String = "en-US",
    ): VoiceProfile? {
        if (voices.isEmpty()) {
            return null
        }
        val normalizedLocale = preferredLocaleTag.lowercase()
        return voices
            .filter { it.localeTag.lowercase().startsWith(normalizedLocale.substringBefore('-')) }
            .ifEmpty { voices }
            .sortedByDescending { score(it, normalizedLocale) }
            .firstOrNull()
    }

    private fun score(voice: VoiceProfile, preferredLocaleTag: String): Int {
        var score = 0
        if (voice.localeTag.lowercase() == preferredLocaleTag) {
            score += 120
        }
        if (!voice.requiresNetwork) {
            score += 90
        }
        score += voice.quality * 3
        score -= voice.latency

        val id = voice.id.lowercase()
        if (id.contains("neural") || id.contains("wavenet") || id.contains("natural")) {
            score += 80
        }
        if (id.contains("female") || id.contains("male") || id.contains("journey")) {
            score += 12
        }
        return score
    }
}
