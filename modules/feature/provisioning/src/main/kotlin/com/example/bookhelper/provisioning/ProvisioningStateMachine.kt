package com.example.bookhelper.provisioning

import com.example.bookhelper.contracts.ProvisioningStatus

class ProvisioningStateMachine(initial: ProvisioningStatus = ProvisioningStatus.NOT_READY) {
    var status: ProvisioningStatus = initial
        private set

    fun start() {
        if (status == ProvisioningStatus.NOT_READY || status == ProvisioningStatus.FAILED_RECOVERABLE) {
            status = ProvisioningStatus.PREPARING
        }
    }

    fun markReady() {
        status = ProvisioningStatus.READY
    }

    fun markRecoverableFailure() {
        status = ProvisioningStatus.FAILED_RECOVERABLE
    }

    fun markFatalFailure() {
        status = ProvisioningStatus.FAILED_FATAL
    }
}
