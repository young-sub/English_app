package com.example.bookhelper.tts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalTtsSelectionPolicyTest {
    @Test
    fun localStaysDisabledWhenOnDevicePreferenceIsNotSelected() {
        val enabled = resolveLocalTtsEnabled(
            preference = TtsEnginePreference.SYSTEM_DEFAULT,
            modelReady = true,
        )

        assertFalse(enabled)
    }

    @Test
    fun localStaysDisabledWhenModelIsNotReady() {
        val enabled = resolveLocalTtsEnabled(
            preference = TtsEnginePreference.ON_DEVICE,
            modelReady = false,
        )

        assertFalse(enabled)
    }

    @Test
    fun localEnablesOnlyWhenOnDevicePreferenceSelectedAndModelReady() {
        val enabled = resolveLocalTtsEnabled(
            preference = TtsEnginePreference.ON_DEVICE,
            modelReady = true,
        )

        assertTrue(enabled)
    }
}
