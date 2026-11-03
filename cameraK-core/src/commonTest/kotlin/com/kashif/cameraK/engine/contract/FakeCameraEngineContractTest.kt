package com.kashif.cameraK.engine.contract

/** The fake engine every holder and plugin test stands on passes the whole kit, with every driver sub-interface. */
internal class FakeCameraEngineContractTest :
    CameraEngineContractTest(
        driver = FakeEngineDriver(),
        requires = setOf(
            ThrowingCallbackDriver::class,
            RecordingDriver::class,
            CaptureDriver::class,
            UnsupportedConfigurationDriver::class,
        ),
    )
