package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDevicePosition
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDevicePositionUnspecified
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTelephotoCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInUltraWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.deviceType

/** A camera AVFoundation's discovery found, with its AVCaptureDeviceType. */
internal class DiscoveredCamera(val deviceType: String, val camera: CaptureDeviceOps)

/** The built-in lens types CameraK knows, as AVCaptureDeviceType strings. */
internal fun allLensDeviceTypes(): List<String> = listOfNotNull(
    AVCaptureDeviceTypeBuiltInWideAngleCamera,
    AVCaptureDeviceTypeBuiltInTelephotoCamera,
    AVCaptureDeviceTypeBuiltInUltraWideCamera,
)

/**
 * The AVCaptureDeviceTypes that can stand for [type] facing [lens], best first. On the front, DEFAULT
 * is the built-in ultra-wide where the device has one and the wide-angle otherwise: the iPhone 17
 * family's front camera, a square sensor for Center Stage, is an ultra-wide to AVFoundation, and its
 * wide-angle-only DEFAULT squashed the preview (#170). Elsewhere DEFAULT is the wide-angle.
 */
internal fun preferredDeviceTypes(lens: CameraLens?, type: CameraDeviceType): List<String> = when {
    type == CameraDeviceType.DEFAULT && lens == CameraLens.FRONT ->
        listOfNotNull(AVCaptureDeviceTypeBuiltInUltraWideCamera, AVCaptureDeviceTypeBuiltInWideAngleCamera)
    else -> type.toAVCaptureDeviceType()?.let { listOf(it) } ?: allLensDeviceTypes()
}

/**
 * The cameras setup and the lens switches open, found with AVCaptureDevice discovery. [discover]
 * answers for AVCaptureDeviceDiscoverySession: the cameras of the given types at the given position.
 * With no lens given and nothing discovered, the system's default video device.
 */
internal class DiscoveryCameraDeviceProvider(
    private val discover: (deviceTypes: List<String>, position: AVCaptureDevicePosition) -> List<DiscoveredCamera> =
        ::discoverBuiltInCameras,
    private val defaultCamera: () -> CaptureDeviceOps? = {
        (AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) as? AVCaptureDevice)?.let(::AVCaptureDeviceOps)
    },
    private val cameraWithId: (String) -> CaptureDeviceOps? = { id ->
        AVCaptureDevice.deviceWithUniqueID(id)?.let(::AVCaptureDeviceOps)
    },
) : CameraDeviceProvider {
    override fun find(lens: CameraLens?, type: CameraDeviceType): CaptureDeviceOps? {
        val position = when (lens) {
            CameraLens.FRONT -> AVCaptureDevicePositionFront
            CameraLens.BACK -> AVCaptureDevicePositionBack
            null -> AVCaptureDevicePositionUnspecified
        }
        val types = preferredDeviceTypes(lens, type)
        val discovered = discover(types, position)
        // The discovery session promises no order, so the preference is applied here.
        val preferred = types.firstNotNullOfOrNull { wanted -> discovered.firstOrNull { it.deviceType == wanted } }
        return (preferred ?: discovered.firstOrNull())?.camera ?: defaultCamera().takeIf { lens == null }
    }

    override fun findById(id: String): CaptureDeviceOps? = cameraWithId(id)
}

/** AVCaptureDeviceDiscoverySession's cameras of [deviceTypes] at [position]. */
internal fun discoverBuiltInCameras(
    deviceTypes: List<String>,
    position: AVCaptureDevicePosition,
): List<DiscoveredCamera> =
    discoverDevices(deviceTypes, position).map { DiscoveredCamera(it.deviceType.orEmpty(), AVCaptureDeviceOps(it)) }

/** The one discovery entry point: setup, the switches and the capability enumeration all discover here. */
internal fun discoverDevices(deviceTypes: List<String>, position: AVCaptureDevicePosition): List<AVCaptureDevice> =
    AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
        deviceTypes,
        AVMediaTypeVideo,
        position,
    ).devices.mapNotNull { it as? AVCaptureDevice }
