package com.example.bookhelper.provisioning

import com.example.bookhelper.contracts.ProvisioningStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ProvisioningStateMachineTest {
    @Test
    fun startTransitionsNotReadyToPreparing() {
        val machine = ProvisioningStateMachine()
        machine.start()
        assertEquals(ProvisioningStatus.PREPARING, machine.status)
    }

    @Test
    fun recoverableFailureCanRestart() {
        val machine = ProvisioningStateMachine(ProvisioningStatus.FAILED_RECOVERABLE)
        machine.start()
        assertEquals(ProvisioningStatus.PREPARING, machine.status)
    }
}
