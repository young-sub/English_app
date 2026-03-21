package com.example.bookhelper.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TtsCoreTest {
    @Test
    fun naturalVoiceSelectorPrefersOfflineNaturalVoice() {
        val selector = NaturalVoiceSelector()
        val selected = selector.chooseBest(
            voices = listOf(
                VoiceProfile(
                    id = "en-us-standard-b",
                    localeTag = "en-US",
                    quality = 300,
                    latency = 150,
                    requiresNetwork = true,
                ),
                VoiceProfile(
                    id = "en-us-neural2-f",
                    localeTag = "en-US",
                    quality = 500,
                    latency = 80,
                    requiresNetwork = false,
                ),
            ),
        )
        assertNotNull(selected)
        assertEquals("en-us-neural2-f", selected.id)
    }

    @Test
    fun ttsEngineResolverUsesSelectedEngineWhenInstalled() {
        val resolver = TtsEngineResolver()
        val selected = resolver.resolve(
            preference = TtsEnginePreference.GOOGLE,
            installedEngines = setOf("com.google.android.tts", "com.samsung.SMT"),
            defaultEngine = "com.samsung.SMT",
        )
        assertEquals("com.google.android.tts", selected)
    }

    @Test
    fun ttsEngineResolverUsesDefaultEngineWhenPreferredEngineIsUnavailable() {
        val resolver = TtsEngineResolver()
        val selected = resolver.resolve(
            preference = TtsEnginePreference.SAMSUNG,
            installedEngines = setOf("com.google.android.tts"),
            defaultEngine = "com.google.android.tts",
        )
        assertEquals("com.google.android.tts", selected)
    }

    @Test
    fun ttsEngineResolverLeavesSystemEngineUnchangedForOnDevicePreference() {
        val resolver = TtsEngineResolver()
        val selected = resolver.resolve(
            preference = TtsEnginePreference.ON_DEVICE,
            installedEngines = setOf("com.google.android.tts", "com.samsung.SMT"),
            defaultEngine = "com.google.android.tts",
        )
        assertEquals("com.google.android.tts", selected)
    }

    @Test
    fun ttsEnginePreferenceParsesOnDeviceValue() {
        assertEquals(TtsEnginePreference.ON_DEVICE, TtsEnginePreference.fromStored("ON_DEVICE"))
    }

    @Test
    fun huggingFaceReferenceBuildsResolveUrl() {
        val reference = HuggingFaceModelReference(
            modelId = "org/model",
            artifactPath = "voices/en/model.onnx",
            revision = "main",
        )
        assertEquals(
            "https://huggingface.co/org/model/resolve/main/voices/en/model.onnx",
            reference.resolveUrl(),
        )
        assertTrue(HuggingFaceModelReference.isHuggingFaceUrl(reference.resolveUrl()))
    }

    @Test
    fun bundledModelsExposeFastDefaultAndOptionalMultiSpeakerModel() {
        assertEquals(2, BundledTtsModels.All.size)
        assertEquals(BundledTtsModels.PiperEnUsLessacLow.id, BundledTtsModels.All.first().id)
        assertEquals(BundledTtsModels.PiperEnUsLessacLow.id, BundledTtsModels.DefaultEnglish.id)
        assertEquals(LocalTtsModelKind.PIPER_DERIVED, BundledTtsModels.PiperEnUsLessacLow.modelKind)
        assertEquals(LocalTtsModelFormat.PIPER_ONNX, BundledTtsModels.PiperEnUsLessacLow.modelFormat)
        assertEquals(
            setOf("model.onnx", "tokens.txt", "espeak-ng-data/"),
            BundledTtsModels.PiperEnUsLessacLow.requiredInstallFiles,
        )
        assertEquals(LocalTtsModelKind.KOKORO, BundledTtsModels.KokoroEnV019.modelKind)
        assertEquals(LocalTtsModelFormat.KOKORO_ONNX, BundledTtsModels.KokoroEnV019.modelFormat)
        assertEquals(
            setOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/"),
            BundledTtsModels.KokoroEnV019.requiredInstallFiles,
        )
        assertTrue(BundledTtsModels.KokoroEnV019 !in BundledTtsModels.All)
        assertEquals(LocalTtsModelKind.PIPER_DERIVED, BundledTtsModels.PiperEnUsLibriTtsRMedium.modelKind)
        assertEquals(LocalTtsModelFormat.PIPER_ONNX, BundledTtsModels.PiperEnUsLibriTtsRMedium.modelFormat)
        assertEquals(
            setOf("model.onnx", "tokens.txt", "espeak-ng-data/"),
            BundledTtsModels.PiperEnUsLibriTtsRMedium.requiredInstallFiles,
        )
        assertEquals(10, BundledTtsModels.PiperEnUsLibriTtsRMedium.speakers.size)
        assertNull(BundledTtsModels.findById("unknown-model"))
    }

    @Test
    fun piperDerivedModelsUsePiperFileRequirements() {
        val model = BundledTtsModel(
            id = "piper-test",
            displayName = "Piper Test",
            shortLabel = "Piper",
            assetDirectory = "tts-models/piper-test",
            speakers = listOf(LocalSpeakerProfile(0, "default", "Default", SpeakerGender.UNKNOWN, "General")),
            modelKind = LocalTtsModelKind.PIPER_DERIVED,
            modelFormat = LocalTtsModelFormat.PIPER_ONNX,
        )

        assertEquals(
            setOf("model.onnx", "tokens.txt", "espeak-ng-data/"),
            model.requiredInstallFiles,
        )
        assertFalse(model.isDownloadedModel)
    }

    @Test
    fun piperDerivedModelsAllowDynamicSpeakerIds() {
        val model = BundledTtsModel(
            id = "piper-test",
            displayName = "Piper Test",
            shortLabel = "Piper",
            assetDirectory = "tts-models/piper-test",
            speakers = listOf(LocalSpeakerProfile(0, "default", "Default", SpeakerGender.UNKNOWN, "General")),
            modelKind = LocalTtsModelKind.PIPER_DERIVED,
            modelFormat = LocalTtsModelFormat.PIPER_ONNX,
        )

        assertTrue(model.supportsSpeakerSelection)
        assertEquals(12, model.normalizeSpeakerId(12))
        assertEquals(0, model.normalizeSpeakerId(-7))
    }

    @Test
    fun localRuntimeStatusMarksCheckingBeforeVerificationCompletes() {
        val status = resolveLocalTtsRuntimeStatus(
            localRequested = true,
            modelConfigured = true,
            runtimeReady = false,
            runtimeDirty = true,
            lastFailureReason = null,
        )

        assertTrue(status.runtimeChecking)
        assertEquals(TtsRoute.SYSTEM_TTS, status.effectiveRoute)
        assertTrue(canAttemptLocalSpeak(status))
    }

    @Test
    fun localRuntimeStatusActivatesLocalOnlyWhenReadyAndClean() {
        val status = resolveLocalTtsRuntimeStatus(
            localRequested = true,
            modelConfigured = true,
            runtimeReady = true,
            runtimeDirty = false,
            lastFailureReason = null,
        )

        assertFalse(status.runtimeChecking)
        assertEquals(TtsRoute.LOCAL_KOKORO, status.effectiveRoute)
        assertTrue(canAttemptLocalSpeak(status))
    }

    @Test
    fun localRuntimeStatusPreservesFailureReasonAfterFallback() {
        val status = resolveLocalTtsRuntimeStatus(
            localRequested = true,
            modelConfigured = true,
            runtimeReady = false,
            runtimeDirty = false,
            lastFailureReason = "local-speak-failed",
        )

        assertFalse(status.runtimeChecking)
        assertEquals(TtsRoute.SYSTEM_TTS, status.effectiveRoute)
        assertEquals("local-speak-failed", status.lastFailureReason)
        assertFalse(canAttemptLocalSpeak(status))
        assertEquals("local-runtime-not-ready", localUnavailableReason(status))
    }
}
