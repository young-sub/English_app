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
        assertEquals("여성/남성 3개씩", model.displayName)
        assertTrue(model.supportsSpeakerSelection)
        assertEquals(6, model.speakers.size)
        assertEquals("여성/표준형", "${model.speakers.first().accentLabel}/${model.speakers.first().displayLabel}")
        assertEquals("맑고 정돈된 기본 톤", model.speakers.first().description)
        assertEquals("신뢰형", model.speakers.first { it.gender == SpeakerGender.MALE }.displayLabel)
    }
}
