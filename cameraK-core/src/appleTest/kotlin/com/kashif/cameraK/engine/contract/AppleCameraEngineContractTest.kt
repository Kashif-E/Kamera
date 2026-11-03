package com.kashif.cameraK.engine.contract

/**
 * The iOS engine passes the contract kit over a fake session. The runtime-error, interruption and
 * did-start-running observers are its platform callbacks; the throwing one is the did-start-running
 * observer, whose read of the session's interruption the fake makes throw once.
 */
internal class AppleCameraEngineContractTest :
    CameraEngineContractTest(
        driver = AppleEngineDriver(),
        requires = setOf(ThrowingCallbackDriver::class, PreOpenInterruptionDriver::class),
    )
