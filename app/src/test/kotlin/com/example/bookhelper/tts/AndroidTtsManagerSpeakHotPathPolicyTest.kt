package com.example.bookhelper.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTtsManagerSpeakHotPathPolicyTest {
    @Test
    fun speakHotPathSchedulesAsyncVerificationWhenLocalRuntimeIsDirty() {
        val policy = resolveSpeakHotPathRuntimePolicy(
            localRequested = true,
            modelConfigured = true,
            runtimeDirty = true,
        )

        assertTrue(policy.scheduleAsyncVerification)
        assertFalse(policy.requiresBlockingRefresh)
    }

    @Test
    fun speakHotPathSkipsVerificationWhenRuntimeFailedButIsClean() {
        val policy = resolveSpeakHotPathRuntimePolicy(
            localRequested = true,
            modelConfigured = true,
            runtimeDirty = false,
        )

        assertFalse(policy.scheduleAsyncVerification)
        assertFalse(policy.requiresBlockingRefresh)
    }

    @Test
    fun speakHotPathSkipsVerificationWhenLocalRuntimeIsNotEligible() {
        val notRequested = resolveSpeakHotPathRuntimePolicy(
            localRequested = false,
            modelConfigured = true,
            runtimeDirty = true,
        )
        val notConfigured = resolveSpeakHotPathRuntimePolicy(
            localRequested = true,
            modelConfigured = false,
            runtimeDirty = true,
        )

        assertFalse(notRequested.scheduleAsyncVerification)
        assertFalse(notRequested.requiresBlockingRefresh)
        assertFalse(notConfigured.scheduleAsyncVerification)
        assertFalse(notConfigured.requiresBlockingRefresh)
    }
}
