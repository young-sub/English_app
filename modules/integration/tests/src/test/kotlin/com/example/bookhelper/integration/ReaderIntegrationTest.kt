package com.example.bookhelper.integration

import com.example.bookhelper.camera.FrameGate
import com.example.bookhelper.contracts.ProvisioningStatus
import com.example.bookhelper.dictionary.DictionaryEntry
import com.example.bookhelper.dictionary.InMemoryDictionaryRepository
import com.example.bookhelper.dictionary.Lemmatizer
import com.example.bookhelper.perf.BlurScoreCalculator
import com.example.bookhelper.perf.PageHashComparator
import com.example.bookhelper.provisioning.ProvisioningStateMachine
import com.example.bookhelper.text.SentenceSegmenter
import com.example.bookhelper.tts.TtsRoute
import com.example.bookhelper.tts.resolveLocalTtsRuntimeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderIntegrationTest {
    @Test
    fun endToEndCoreFlowSmokeTest() {
        val provisioning = ProvisioningStateMachine()
        provisioning.start()
        provisioning.markReady()
        assertEquals(ProvisioningStatus.READY, provisioning.status)

        val gate = FrameGate(300)
        assertTrue(gate.shouldProcess(1000))
        assertFalse(gate.shouldProcess(1100))

        val sentenceSegmenter = SentenceSegmenter()
        val sentences = sentenceSegmenter.split("I read a book. It is useful.")
        assertEquals(2, sentences.size)

        val lemmatizer = Lemmatizer()
        val lemma = lemmatizer.candidates("running")
        val repository = InMemoryDictionaryRepository(
            listOf(DictionaryEntry("run", "run", "move fast", "달리다")),
        )
        val entries = repository.lookupByLemma(lemma.last())
        assertTrue(entries.isNotEmpty())

        val ttsStatus = resolveLocalTtsRuntimeStatus(
            localRequested = true,
            modelConfigured = true,
            runtimeReady = true,
            runtimeDirty = false,
            lastFailureReason = null,
        )
        assertEquals(TtsRoute.LOCAL_KOKORO, ttsStatus.effectiveRoute)

        val hashComparator = PageHashComparator(2)
        assertTrue(hashComparator.isSamePage(0b1111, 0b1110))

        val blur = BlurScoreCalculator(100.0)
        assertFalse(blur.shouldSkip(120.0))
    }

}
