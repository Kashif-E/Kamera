package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.CameraEngineContractTest
import com.kashif.cameraK.engine.contract.ThrowingCallbackDriver

/**
 * The shared engine contract over the desktop engine. One OFF-only camera with a fixed zoom: the
 * kit runs the reported camera, the open on that device, the camera after a restart and the
 * flash step at the reported mode, and lists the rest. `throwingPlatformCallbackDoesNotEscape`
 * throws from the grab loop's frame listener.
 */
internal class DesktopCameraEngineContractTest :
    CameraEngineContractTest(DesktopEngineDriver(), requires = setOf(ThrowingCallbackDriver::class))
