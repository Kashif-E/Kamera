package com.kashif.cameraK.capabilities

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoQuality

/**
 * Describes one physical (or logical) camera lens on the device.
 *
 * @property id Platform camera id (Camera2 id on Android, AVCaptureDevice.uniqueID on iOS, "" on Desktop).
 * @property deviceType Classified lens type. Unclassifiable lenses report [CameraDeviceType.DEFAULT].
 * @property lens Which side of the device the lens faces.
 * @property minZoom Minimum zoom ratio (can be < 1.0 on logical multi-cameras that reach ultra-wide via zoom-out).
 * @property maxZoom Maximum zoom ratio.
 * @property hasFlash Whether this lens has a flash unit.
 * @property isLogical Android logical multi-camera (merges several physical lenses behind one id); false elsewhere.
 * @property isExternal A camera attached to the device rather than built in (a USB camera on a
 * Chromebook, tablet or TV). It faces neither way, so it reports [CameraLens.BACK] and
 * [CameraCapabilities.lenses] leaves it out; find it in [CameraCapabilities.externalLenses].
 */
data class LensInfo(
    val id: String,
    val deviceType: CameraDeviceType,
    val lens: CameraLens,
    val minZoom: Float,
    val maxZoom: Float,
    val hasFlash: Boolean,
    val isLogical: Boolean,
    val isExternal: Boolean = false,
)

/**
 * Snapshot of the device's camera hardware. Obtain via `CameraController.getCameraCapabilities()`
 * or `CameraKStateHolder.getCameraCapabilities()`.
 *
 * [allLenses] is copied on construction so this class stays immutable, as camerak's
 * compose-stability.conf declares it to Compose, regardless of what the caller does with the list it
 * passed in.
 */
class CameraCapabilities @CameraKEngineApi constructor(
    allLenses: List<LensInfo>,
    flashModes: Map<String, Set<FlashMode>>,
    torchModes: Map<String, Set<TorchMode>>,
    val supportsPauseResume: Boolean,
    supportedVideoQualities: Set<VideoQuality>,
    val supportsVideoBitrate: Boolean,
    supportedAspectRatios: Set<AspectRatio>,
    supportedImageFormats: Set<ImageFormat>,
    supportedAnalysisFormats: Set<FrameFormat>,
    val supportsSquareCapture: Boolean,
) {
    @OptIn(CameraKEngineApi::class)
    constructor(allLenses: List<LensInfo>) : this(
        allLenses,
        emptyMap(),
        emptyMap(),
        false,
        emptySet(),
        false,
        emptySet(),
        emptySet(),
        emptySet(),
        false,
    )

    val allLenses: List<LensInfo> = allLenses.toList()

    /** Built-in lenses facing [lens], in enumeration order. External cameras are not included. */
    fun lenses(lens: CameraLens): List<LensInfo> = allLenses.filter { it.lens == lens && !it.isExternal }

    /** Cameras attached to the device rather than built in, in enumeration order. */
    val externalLenses: List<LensInfo> get() = allLenses.filter { it.isExternal }

    /** Device types selectable via `setPreferredCameraDeviceType` for [lens]. */
    fun availableDeviceTypes(lens: CameraLens): Set<CameraDeviceType> =
        lenses(lens).mapTo(mutableSetOf()) { it.deviceType }

    fun supportedFlashModes(cameraId: String): Set<FlashMode> = TODO("S1' stub")

    fun supportedTorchModes(cameraId: String): Set<TorchMode> = TODO("S1' stub")

    val supportedVideoQualities: Set<VideoQuality> get() = TODO("S1' stub")

    val supportedAspectRatios: Set<AspectRatio> get() = TODO("S1' stub")

    val supportedImageFormats: Set<ImageFormat> get() = TODO("S1' stub")

    val supportedAnalysisFormats: Set<FrameFormat> get() = TODO("S1' stub")

    companion object {
        val EMPTY = CameraCapabilities(emptyList())
    }
}
