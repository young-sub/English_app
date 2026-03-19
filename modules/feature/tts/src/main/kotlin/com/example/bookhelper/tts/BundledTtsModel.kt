package com.example.bookhelper.tts

data class BundledTtsModel(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val assetDirectory: String,
    val speakers: List<LocalSpeakerProfile>,
    val modelKind: LocalTtsModelKind = LocalTtsModelKind.KOKORO,
    val modelFormat: LocalTtsModelFormat = LocalTtsModelFormat.KOKORO_ONNX,
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
    get() = speakers.size > 1 || modelKind == LocalTtsModelKind.PIPER_DERIVED

fun BundledTtsModel.normalizeSpeakerId(candidate: Int?): Int {
    if (modelKind == LocalTtsModelKind.PIPER_DERIVED) {
        return candidate?.coerceAtLeast(0) ?: defaultSpeakerId
    }
    val resolved = candidate ?: defaultSpeakerId
    return if (speakers.any { it.id == resolved }) resolved else defaultSpeakerId
}

val BundledTtsModel.requiredInstallFiles: Set<String>
    get() = when (modelKind) {
        LocalTtsModelKind.KOKORO -> setOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/")
        LocalTtsModelKind.PIPER_DERIVED -> setOf("model.onnx", "tokens.txt", "espeak-ng-data/")
    }

val BundledTtsModel.isDownloadedModel: Boolean
    get() = assetDirectory.isBlank()

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
        modelKind = LocalTtsModelKind.KOKORO,
        modelFormat = LocalTtsModelFormat.KOKORO_ONNX,
    )

    val PiperEnUsLibriTtsRMedium = BundledTtsModel(
        id = "piper-en_us-libritts_r-medium",
        displayName = "Piper EN LibriTTS-R Medium",
        shortLabel = "Piper LibriTTS-R",
        assetDirectory = "$AssetRoot/piper-en_US-libritts_r-medium",
        speakers = listOf(
            LocalSpeakerProfile(0, "speaker_0", "Speaker 0", SpeakerGender.UNKNOWN, "US"),
        ),
        modelKind = LocalTtsModelKind.PIPER_DERIVED,
        modelFormat = LocalTtsModelFormat.PIPER_ONNX,
    )

    val All: List<BundledTtsModel> = listOf(
        KokoroEnV019,
        PiperEnUsLibriTtsRMedium,
    )

    val DefaultEnglish: BundledTtsModel = KokoroEnV019

    fun findById(id: String?): BundledTtsModel? {
        if (id.isNullOrBlank()) {
            return null
        }
        return All.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
