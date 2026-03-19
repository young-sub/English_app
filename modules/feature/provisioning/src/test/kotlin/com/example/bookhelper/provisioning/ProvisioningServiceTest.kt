package com.example.bookhelper.provisioning

import com.example.bookhelper.contracts.ProvisioningStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ProvisioningServiceTest {
    @Test
    fun prepareReturnsReadyWhenDependenciesAreReady() {
        val service = ProvisioningService()
        val status = service.prepare(ProvisioningReadiness(ocrModelReady = true, ttsVoiceReady = true))
        assertEquals(ProvisioningStatus.READY, status)
    }

    @Test
    fun prepareReturnsRecoverableFailureWhenAnyDependencyMissing() {
        val service = ProvisioningService()
        val status = service.prepare(ProvisioningReadiness(ocrModelReady = true, ttsVoiceReady = false))
        assertEquals(ProvisioningStatus.FAILED_RECOVERABLE, status)
    }
}
