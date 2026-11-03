package com.kashif.cameraK.engine.contract

/**
 * The iOS engine passes the contract kit over a fake session whose back camera has a flash, a torch
 * and a zoom range, so every hardware step runs. The runtime-error, interruption and did-start-running
 * observers are its platform callbacks; the throwing one is the did-start-running observer, whose
 * read of the session's interruption the fake makes throw once. Flash is read back from the photo
 * output, so the driver is a [ReadBackFlashDriver].
 */
internal class AppleCameraEngineContractTest :
    CameraEngineContractTest(
        driver = AppleEngineDriver(adjustableBack = true),
        requires = setOf(ThrowingCallbackDriver::class, PreOpenInterruptionDriver::class, ReadBackFlashDriver::class),
    )
