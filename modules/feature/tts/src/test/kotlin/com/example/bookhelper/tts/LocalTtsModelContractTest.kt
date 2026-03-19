package com.example.bookhelper.tts

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalTtsModelContractTest {
    @Test
    fun sharedModelContractRepresentsKokoroModel() {
        val contract = LocalTtsModelContract(
            id = "kokoro-en-v0_19",
            displayName = "Kokoro EN v0.19",
            modelKind = LocalTtsModelKind.KOKORO,
            modelFormat = LocalTtsModelFormat.KOKORO_ONNX,
            assetDirectory = "tts-models/kokoro-en-v0_19",
        )

        assertEquals(LocalTtsModelKind.KOKORO, contract.modelKind)
        assertEquals(LocalTtsModelFormat.KOKORO_ONNX, contract.modelFormat)
    }

    @Test
    fun sharedModelContractRepresentsPiperDerivedModel() {
        val contract = LocalTtsModelContract(
            id = "piper-en-us-amy-medium",
            displayName = "Piper EN US Amy",
            modelKind = LocalTtsModelKind.PIPER_DERIVED,
            modelFormat = LocalTtsModelFormat.PIPER_ONNX,
            assetDirectory = "tts-models/piper-en-us-amy-medium",
        )

        assertEquals(LocalTtsModelKind.PIPER_DERIVED, contract.modelKind)
        assertEquals(LocalTtsModelFormat.PIPER_ONNX, contract.modelFormat)
    }
}
