package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.engine.contract.CameraEngineContractTest
import dev.kashifmehmood.camerak.engine.contract.CaptureDriver
import dev.kashifmehmood.camerak.engine.contract.RecordingDriver
import dev.kashifmehmood.camerak.engine.contract.ThrowingCallbackDriver
import dev.kashifmehmood.camerak.engine.contract.UnsupportedConfigurationDriver

/**
 * The shared engine contract over the desktop engine. Two OFF-only webcams with a fixed zoom: the
 * kit runs the reported camera, the open on each device, the camera after a restart and after a
 * switch, and the flash step at the reported mode, and lists the rest.
 * `throwingPlatformCallbackDoesNotEscape` throws from the grab loop's frame listener. From D3' the
 * engine records and captures, so the recording, capture and unsupported-configuration cases run
 * too (HEIC is the configuration desktop cannot honour).
 */
internal class DesktopCameraEngineContractTest :
    CameraEngineContractTest(
        DesktopEngineDriver(),
        requires = setOf(
            ThrowingCallbackDriver::class,
            RecordingDriver::class,
            CaptureDriver::class,
            UnsupportedConfigurationDriver::class,
        ),
    )
