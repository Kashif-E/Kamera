package com.kashif.cameraK.engine

import com.kashif.cameraK.engine.contract.CameraEngineContractTest
import com.kashif.cameraK.engine.contract.PreOpenInterruptionDriver
import com.kashif.cameraK.engine.contract.ThrowingCallbackDriver
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The engine contract over CameraX's camera state. The kit lists the torch, zoom and flash steps that
 * need read-back from a camera with a flash unit and a zoom range; A2 brings both.
 */
@RunWith(RobolectricTestRunner::class)
internal class AndroidCameraEngineContractTest :
    CameraEngineContractTest(
        AndroidEngineDriver(),
        requires = setOf(ThrowingCallbackDriver::class, PreOpenInterruptionDriver::class),
    )
