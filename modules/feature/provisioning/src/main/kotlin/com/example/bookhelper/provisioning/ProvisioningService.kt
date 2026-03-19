package com.example.bookhelper.provisioning

import com.example.bookhelper.contracts.ProvisioningStatus

data class ProvisioningReadiness(
    val ocrModelReady: Boolean,
    val ttsVoiceReady: Boolean,
)

class ProvisioningService {
    private val stateMachine = ProvisioningStateMachine()

    fun prepare(readiness: ProvisioningReadiness): ProvisioningStatus {
        stateMachine.start()
        return if (readiness.ocrModelReady && readiness.ttsVoiceReady) {
            stateMachine.markReady()
            stateMachine.status
        } else {
            stateMachine.markRecoverableFailure()
            stateMachine.status
        }
    }
}
