package com.kashif.cameraK.engine.contract

import kotlin.test.Test
import kotlin.test.fail

/** Fails wherever the kit's source directory is compiled, which proves it runs on every target. Red only. */
class ContractKitSentinelTest {
    @Test
    fun sentinelFails() {
        fail("the contract kit's sources are compiled into this target's tests")
    }
}
