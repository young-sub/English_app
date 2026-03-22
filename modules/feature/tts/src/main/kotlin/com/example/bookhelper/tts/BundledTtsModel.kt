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
    val description: String = "",
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
        displayName = "여성/남성 3개씩",
        shortLabel = "다화자 모델",
        assetDirectory = "$AssetRoot/piper-en_US-libritts_r-medium",
        speakers = buildPiperPresetSpeakers(),
        modelKind = LocalTtsModelKind.PIPER_DERIVED,
        modelFormat = LocalTtsModelFormat.PIPER_ONNX,
    )

    val PiperEnUsLessacLow = BundledTtsModel(
        id = "piper-en_us-lessac-low",
        displayName = "미국 여성",
        shortLabel = "단일 모델",
        assetDirectory = "$AssetRoot/piper-en_US-lessac-low",
        speakers = listOf(
            LocalSpeakerProfile(0, "speaker_0", "미국 · 여성", SpeakerGender.FEMALE, "미국"),
        ),
        modelKind = LocalTtsModelKind.PIPER_DERIVED,
        modelFormat = LocalTtsModelFormat.PIPER_ONNX,
    )

    val All: List<BundledTtsModel> = listOf(
        PiperEnUsLessacLow,
        PiperEnUsLibriTtsRMedium,
    )

    val DefaultEnglish: BundledTtsModel = PiperEnUsLessacLow

    fun findById(id: String?): BundledTtsModel? {
        if (id.isNullOrBlank()) {
            return null
        }
        return All.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    private fun buildPiperPresetSpeakers(): List<LocalSpeakerProfile> {
        return listOf(
            LocalSpeakerProfile(
                id = 650,
                code = "speaker_650",
                displayLabel = "표준형",
                gender = SpeakerGender.FEMALE,
                accentLabel = "여성",
                description = "맑고 정돈된 기본 톤",
            ),
            LocalSpeakerProfile(
                id = 14,
                code = "speaker_14",
                displayLabel = "안내형",
                gender = SpeakerGender.FEMALE,
                accentLabel = "여성",
                description = "또렷하고 산뜻한 전달 톤",
            ),
            LocalSpeakerProfile(
                id = 551,
                code = "speaker_551",
                displayLabel = "친화형",
                gender = SpeakerGender.FEMALE,
                accentLabel = "여성",
                description = "밝고 유연한 콘텐츠 톤",
            ),
            LocalSpeakerProfile(
                id = 473,
                code = "speaker_473",
                displayLabel = "신뢰형",
                gender = SpeakerGender.MALE,
                accentLabel = "남성",
                description = "안정적인 저음의 설명 톤",
            ),
            LocalSpeakerProfile(
                id = 634,
                code = "speaker_634",
                displayLabel = "격식형",
                gender = SpeakerGender.MALE,
                accentLabel = "남성",
                description = "단단하고 절제된 포멀 톤",
            ),
            LocalSpeakerProfile(
                id = 5,
                code = "speaker_5",
                displayLabel = "낭독형",
                gender = SpeakerGender.MALE,
                accentLabel = "남성",
                description = "담백하고 중후한 범용 톤",
            ),
        )
    }
}
