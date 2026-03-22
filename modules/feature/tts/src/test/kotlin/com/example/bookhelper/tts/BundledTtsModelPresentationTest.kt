package com.example.bookhelper.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundledTtsModelPresentationTest {
    @Test
    fun lessacUsesSingleModelPresentation() {
        val model = BundledTtsModels.PiperEnUsLessacLow

        assertEquals("단일 모델", model.shortLabel)
        assertEquals("미국 여성", model.displayName)
        assertFalse(model.supportsSpeakerSelection)
        assertEquals("미국", model.speakers.single().accentLabel)
        assertEquals(SpeakerGender.FEMALE, model.speakers.single().gender)
    }

    @Test
    fun libriTtsUsesMultiSpeakerPresentation() {
        val model = BundledTtsModels.PiperEnUsLibriTtsRMedium

        assertEquals("다화자 모델", model.shortLabel)
        assertEquals("화자 선택", model.displayName)
        assertTrue(model.supportsSpeakerSelection)
        assertEquals("선택 후보/프리셋 1", "${model.speakers.first().accentLabel}/${model.speakers.first().displayLabel}")
    }
}
