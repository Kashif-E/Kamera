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
import dev.drewhamilton.poko.Poko

/**
 * Describes one physical (or logical) camera lens on the device. A plain class that compares by
 * value; it has no `copy` or `componentN`, so it can gain a field without breaking binary
 * compatibility.
 *
 * @property id Platform camera id: the Camera2 id on Android and the AVCaptureDevice.uniqueID on iOS. On
 * Desktop, the `/dev/v4l/by-id` link name (else the `/dev/videoN` path) on Linux, the uniqueID on macOS
 * and the DirectShow alternative name on Windows. "" is the one default entry, listed when the cameras
 * cannot be read, by an engine with a grabber factory and no camera, and by a 1.x-constructed controller.
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
@Poko
public class LensInfo(
    public val id: String,
    public val deviceType: CameraDeviceType,
    public val lens: CameraLens,
    public val minZoom: Float,
    public val maxZoom: Float,
    public val hasFlash: Boolean,
    public val isLogical: Boolean,
    public val isExternal: Boolean = false,
)

/**
 * Snapshot of the device's camera hardware: its lenses and what each platform supports. Obtain via
 * `CameraController.getCameraCapabilities()` or `CameraKStateHolder.getCameraCapabilities()`.
 *
 * Every collection is copied on construction so this class stays immutable, as camerak's
 * compose-stability.conf declares it to Compose, regardless of what the caller does with what it
 * passed in. Compares by value over every member.
 *
 * Defaults never claim support: a member an engine has not filled reports nothing supported, so a
 * command that depends on it fails with Unsupported instead of succeeding silently.
 *
 * @param flashModes The flash modes per [LensInfo.id].
 * @param torchModes The torch modes per [LensInfo.id].
 */
public class CameraCapabilities @CameraKEngineApi constructor(
    allLenses: List<LensInfo>,
    flashModes: Map<String, Set<FlashMode>>,
    torchModes: Map<String, Set<TorchMode>>,
    public val supportsPauseResume: Boolean,
    supportedVideoQualities: Set<VideoQuality>,
    public val supportsVideoBitrate: Boolean,
    supportedAspectRatios: Set<AspectRatio>,
    supportedImageFormats: Set<ImageFormat>,
    supportedAnalysisFormats: Set<FrameFormat>,
    public val supportsSquareCapture: Boolean,
) {
    /**
     * The 1.x shape, which the 1.x controllers build. Everything it does not name is reported
     * unsupported: flash OFF, AUTO and ON and torch OFF and ON for a lens with a flash unit, OFF only
     * otherwise; no pause and resume, video qualities, video bitrate, analysis formats or square
     * capture; JPEG only; the 4:3, 16:9 and 9:16 aspect ratios.
     */
    @OptIn(CameraKEngineApi::class)
    public constructor(allLenses: List<LensInfo>) : this(
        allLenses = allLenses,
        flashModes = allLenses.associate {
            it.id to if (it.hasFlash) setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON) else setOf(FlashMode.OFF)
        },
        torchModes = allLenses.associate {
            it.id to if (it.hasFlash) setOf(TorchMode.OFF, TorchMode.ON) else setOf(TorchMode.OFF)
        },
        supportsPauseResume = false,
        supportedVideoQualities = emptySet(),
        supportsVideoBitrate = false,
        supportedAspectRatios = setOf(AspectRatio.RATIO_4_3, AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16),
        supportedImageFormats = setOf(ImageFormat.JPEG),
        supportedAnalysisFormats = emptySet(),
        supportsSquareCapture = false,
    )

    public val allLenses: List<LensInfo> = allLenses.toList()
    private val flashModes: Map<String, Set<FlashMode>> = flashModes.mapValues { it.value.toSet() }
    private val torchModes: Map<String, Set<TorchMode>> = torchModes.mapValues { it.value.toSet() }
    public val supportedVideoQualities: Set<VideoQuality> = supportedVideoQualities.toSet()
    public val supportedAspectRatios: Set<AspectRatio> = supportedAspectRatios.toSet()
    public val supportedImageFormats: Set<ImageFormat> = supportedImageFormats.toSet()
    public val supportedAnalysisFormats: Set<FrameFormat> = supportedAnalysisFormats.toSet()

    /** Built-in lenses facing [lens], in enumeration order. External cameras are not included. */
    public fun lenses(lens: CameraLens): List<LensInfo> = allLenses.filter { it.lens == lens && !it.isExternal }

    /** Cameras attached to the device rather than built in, in enumeration order. */
    public val externalLenses: List<LensInfo> get() = allLenses.filter { it.isExternal }

    /** Device types selectable via `setPreferredCameraDeviceType` for [lens]. */
    public fun availableDeviceTypes(lens: CameraLens): Set<CameraDeviceType> =
        lenses(lens).mapTo(mutableSetOf()) { it.deviceType }

    /** The flash modes of the camera [cameraId]; OFF only for an id that is not in [allLenses]. */
    public fun supportedFlashModes(cameraId: String): Set<FlashMode> =
        flashModes[cameraId]?.takeIf { isLens(cameraId) } ?: setOf(FlashMode.OFF)

    /** The torch modes of the camera [cameraId]; OFF only for an id that is not in [allLenses]. */
    public fun supportedTorchModes(cameraId: String): Set<TorchMode> =
        torchModes[cameraId]?.takeIf { isLens(cameraId) } ?: setOf(TorchMode.OFF)

    private fun isLens(cameraId: String): Boolean = allLenses.any { it.id == cameraId }

    override fun equals(other: Any?): Boolean = this === other ||
        other is CameraCapabilities &&
        allLenses == other.allLenses &&
        flashModes == other.flashModes &&
        torchModes == other.torchModes &&
        supportsPauseResume == other.supportsPauseResume &&
        supportedVideoQualities == other.supportedVideoQualities &&
        supportsVideoBitrate == other.supportsVideoBitrate &&
        supportedAspectRatios == other.supportedAspectRatios &&
        supportedImageFormats == other.supportedImageFormats &&
        supportedAnalysisFormats == other.supportedAnalysisFormats &&
        supportsSquareCapture == other.supportsSquareCapture

    override fun hashCode(): Int = listOf(
        allLenses,
        flashModes,
        torchModes,
        supportsPauseResume,
        supportedVideoQualities,
        supportsVideoBitrate,
        supportedAspectRatios,
        supportedImageFormats,
        supportedAnalysisFormats,
        supportsSquareCapture,
    ).hashCode()

    override fun toString(): String = "CameraCapabilities(" +
        "allLenses=$allLenses, " +
        "flashModes=$flashModes, " +
        "torchModes=$torchModes, " +
        "supportsPauseResume=$supportsPauseResume, " +
        "supportedVideoQualities=$supportedVideoQualities, " +
        "supportsVideoBitrate=$supportsVideoBitrate, " +
        "supportedAspectRatios=$supportedAspectRatios, " +
        "supportedImageFormats=$supportedImageFormats, " +
        "supportedAnalysisFormats=$supportedAnalysisFormats, " +
        "supportsSquareCapture=$supportsSquareCapture)"

    public companion object {
        public val EMPTY: CameraCapabilities = CameraCapabilities(emptyList())
    }
}
