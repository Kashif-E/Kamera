package com.kashif.cameraK.engine.contract

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ContractKitRequiresTest {
    // Throws from callbacks, but has no recording hooks.
    private class NoRecordingDriver(delegate: FakeEngineDriver = FakeEngineDriver()) :
        ThrowingCallbackDriver by delegate

    @Test
    fun missingRequiredSubInterfaceFails() {
        val cases = ContractCases(
            driver = NoRecordingDriver(),
            requires = setOf(ThrowingCallbackDriver::class, RecordingDriver::class),
            skips = ContractSkips(),
        )

        val failure = assertFailsWith<AssertionError> { cases.driverImplementsEveryRequiredSubInterface() }
        assertContains(failure.message.orEmpty(), "RecordingDriver")
    }
}
