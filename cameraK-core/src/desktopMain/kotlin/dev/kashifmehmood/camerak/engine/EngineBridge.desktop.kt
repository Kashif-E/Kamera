@file:OptIn(CameraKEngineApi::class, CameraKPlatformApi::class)

package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.controller.DesktopCameraEngine
import dev.kashifmehmood.camerak.controller.DesktopDevices
import dev.kashifmehmood.camerak.controller.DeviceEnumerator
import dev.kashifmehmood.camerak.controller.EnumeratedDeviceSource
import dev.kashifmehmood.camerak.controller.FactoryGrabberSource
import dev.kashifmehmood.camerak.controller.OperatingSystem
import dev.kashifmehmood.camerak.controller.currentOperatingSystem
import dev.kashifmehmood.camerak.controller.desktopCapabilities
import dev.kashifmehmood.camerak.controller.desktopLensInfo
import dev.kashifmehmood.camerak.controller.platformDevices
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.utils.CameraKPlatformApi

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
