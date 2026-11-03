package com.kashif.cameraK.engine

import com.kashif.cameraK.engine.contract.CameraEngineContractTest
import com.kashif.cameraK.engine.contract.PreOpenInterruptionDriver
import com.kashif.cameraK.engine.contract.ReadBackFlashDriver
import com.kashif.cameraK.engine.contract.ThrowingCallbackDriver
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The engine contract over CameraX's camera state, with torch and zoom read back from CameraX's
 * observers and flash read back from the bound ImageCapture, on two cameras with a flash unit and a
 * zoom range. The kit lists as skipped only the recording, capture and unsupported-configuration
 * cases, which come with A3'.
 */
@RunWith(RobolectricTestRunner::class)
internal class AndroidCameraEngineContractTest :
    CameraEngineContractTest(
        AndroidEngineDriver(),
        requires = setOf(ThrowingCallbackDriver::class, PreOpenInterruptionDriver::class, ReadBackFlashDriver::class),
    )
