package com.kashif.cameraK.engine

import com.kashif.cameraK.engine.contract.CameraEngineContractTest
import com.kashif.cameraK.engine.contract.CaptureDriver
import com.kashif.cameraK.engine.contract.PreOpenInterruptionDriver
import com.kashif.cameraK.engine.contract.ReadBackFlashDriver
import com.kashif.cameraK.engine.contract.RecordingDriver
import com.kashif.cameraK.engine.contract.ThrowingCallbackDriver
import com.kashif.cameraK.engine.contract.UnsupportedConfigurationDriver
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The engine contract over CameraX's camera state, with torch and zoom read back from CameraX's
 * observers and flash read back from the bound ImageCapture, on two cameras with a flash unit and a
 * zoom range. From A3' it records, captures and refuses HEIC at start, so the kit skips nothing.
 */
@RunWith(RobolectricTestRunner::class)
internal class AndroidCameraEngineContractTest :
    CameraEngineContractTest(
        AndroidEngineDriver(),
        requires = setOf(
            ThrowingCallbackDriver::class,
            PreOpenInterruptionDriver::class,
            ReadBackFlashDriver::class,
            RecordingDriver::class,
            CaptureDriver::class,
            UnsupportedConfigurationDriver::class,
        ),
    )
