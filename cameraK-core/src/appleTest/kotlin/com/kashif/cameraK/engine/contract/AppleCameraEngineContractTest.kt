package com.kashif.cameraK.engine.contract

/**
 * The iOS engine passes the contract kit over a fake session whose two cameras each have a flash, a
 * torch and a zoom range, so every hardware step runs, including the ones after a camera switch, which
 * check that the switch re-applies the desired hardware. The runtime-error, interruption and did-start-running
 * observers are its platform callbacks; the throwing one is the did-start-running observer, whose
 * read of the session's interruption the fake makes throw once. Flash is read back from the photo
 * output, so the driver is a [ReadBackFlashDriver]. Its unsupported configuration is an RGBA_8888
 * analysis format: iOS frames are 420f only (I4').
 */
internal class AppleCameraEngineContractTest :
    CameraEngineContractTest(
        driver = AppleEngineDriver(adjustableBack = true, adjustableFront = true),
        requires = setOf(
            ThrowingCallbackDriver::class,
            PreOpenInterruptionDriver::class,
            ReadBackFlashDriver::class,
            CaptureDriver::class,
            RecordingDriver::class,
            UnsupportedConfigurationDriver::class,
        ),
    )
