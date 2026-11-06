@file:OptIn(CameraKEngineApi::class, CameraKPlatformApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.DesktopCameraEngine
import com.kashif.cameraK.controller.DesktopDevices
import com.kashif.cameraK.controller.DeviceEnumerator
import com.kashif.cameraK.controller.EnumeratedDeviceSource
import com.kashif.cameraK.controller.FactoryGrabberSource
import com.kashif.cameraK.controller.OperatingSystem
import com.kashif.cameraK.controller.currentOperatingSystem
import com.kashif.cameraK.controller.desktopCapabilities
import com.kashif.cameraK.controller.desktopLensInfo
import com.kashif.cameraK.controller.platformDevices
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKPlatformApi

/** The desktop engine over the enumerated cameras (§2.4 desktop). */
internal actual fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine = desktopEngine(config, context, environment)

/**
 * The bridge's desktop engine. It enumerates [devices] on its own scope after construction, with or
 * without the context's grabber factory, because enumeration opens nothing. The factory replaces
 * only how a device opens: every open asks it for the selected device's id. With no local camera a
 * factory engine offers the one default entry, so a machine without a camera can still open a
 * stream; without a factory it offers nothing, and a start is NoCamera.
 */
internal fun desktopEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
    os: OperatingSystem = currentOperatingSystem(),
    devices: DesktopDevices = platformDevices(os),
): DesktopCameraEngine {
    val factory = context.grabberFactory
    val enumerator = DeviceEnumerator(devices)
    return DesktopCameraEngine(
        config = config,
        environment = environment,
        source = factory?.let(::FactoryGrabberSource) ?: EnumeratedDeviceSource(
            devices,
            config.targetResolution?.let { it.width to it.height },
        ),
        os = os,
        enumerate = {
            val lenses = enumerator.lenses()
            desktopCapabilities(if (lenses.isEmpty() && factory != null) listOf(desktopLensInfo()) else lenses)
        },
    )
}
