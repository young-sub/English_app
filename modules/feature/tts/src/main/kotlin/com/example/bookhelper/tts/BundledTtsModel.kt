package com.example.bookhelper.tts

data class BundledTtsModel(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val assetDirectory: String,
    val speakers: List<LocalSpeakerProfile>,
)

data class LocalSpeakerProfile(
    val id: Int,
    val code: String,
    val displayLabel: String,
    val gender: SpeakerGender,
    val accentLabel: String,
)

enum class SpeakerGender {
    FEMALE,
    MALE,
    UNKNOWN,
}

val BundledTtsModel.defaultSpeakerId: Int
    get() = speakers.firstOrNull()?.id ?: 0

val BundledTtsModel.supportsSpeakerSelection: Boolean
    get() = speakers.size > 1

fun BundledTtsModel.normalizeSpeakerId(candidate: Int?): Int {
    val resolved = candidate ?: defaultSpeakerId
    return if (speakers.any { it.id == resolved }) resolved else defaultSpeakerId
}

object BundledTtsModels {
    const val AssetRoot = "tts-models"

    val KokoroEnV019 = BundledTtsModel(
        id = "kokoro-en-v0_19",
        displayName = "Kokoro EN v0.19",
        shortLabel = "Kokoro EN",
        assetDirectory = "$AssetRoot/kokoro-en-v0_19",
        speakers = listOf(
            LocalSpeakerProfile(0, "af", "AF", SpeakerGender.FEMALE, "US"),
            LocalSpeakerProfile(1, "af_bella", "Bella", SpeakerGender.FEMALE, "US"),
            LocalSpeakerProfile(2, "af_nicole", "Nicole", SpeakerGender.FEMALE, "US"),
            LocalSpeakerProfile(3, "af_sarah", "Sarah", SpeakerGender.FEMALE, "US"),
            LocalSpeakerProfile(4, "af_sky", "Sky", SpeakerGender.FEMALE, "US"),
            LocalSpeakerProfile(5, "am_adam", "Adam", SpeakerGender.MALE, "US"),
            LocalSpeakerProfile(6, "am_michael", "Michael", SpeakerGender.MALE, "US"),
            LocalSpeakerProfile(7, "bf_emma", "Emma", SpeakerGender.FEMALE, "UK"),
            LocalSpeakerProfile(8, "bf_isabella", "Isabella", SpeakerGender.FEMALE, "UK"),
            LocalSpeakerProfile(9, "bm_george", "George", SpeakerGender.MALE, "UK"),
            LocalSpeakerProfile(10, "bm_lewis", "Lewis", SpeakerGender.MALE, "UK"),
        ),
    )

    val All: List<BundledTtsModel> = listOf(KokoroEnV019)

    val DefaultEnglish: BundledTtsModel = KokoroEnV019

    fun findById(id: String?): BundledTtsModel? {
        if (id.isNullOrBlank()) {
            return null
        }
        return All.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
