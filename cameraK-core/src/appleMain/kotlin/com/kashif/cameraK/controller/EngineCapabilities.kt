@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CameraK"

/** AVCaptureFileOutput pauses from iOS 18. */
private const val PAUSE_RESUME_IOS = 18

/**
 * An iOS engine's [capabilities] (design §6.3): the lenses enumerated once at construction, their torch
 * modes, and what each camera bound so far reports through the photo output (flash modes) and the
 * device (image formats, recording qualities). Its state is kept under [lock].
 */
internal class EngineCapabilities(
    private val lock: EngineLock,
    private val generations: EngineGenerations,
    private val custom: CustomCameraController,
    private val osVersion: Int,
    private val enumerateLenses: () -> List<LensInfo>,
) {
    private val _capabilities = MutableStateFlow(CameraCapabilities.EMPTY)
    val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    /** Completes when the lenses are enumerated, or the engine closes or cannot run. */
    val enumerated = CompletableDeferred<Unit>()

    private var enumeratedLenses: List<LensInfo> = emptyList()
    private var torchModesById: Map<String, Set<TorchMode>> = emptyMap()

    /** Each bound camera's flash modes, as the photo output reported them with that camera behind it. */
    private val boundFlashModes = mutableMapOf<String, Set<FlashMode>>()

    /** The image formats and recording qualities of the camera bound last; JPEG and PNG and none before. */
    private var boundImageFormats: Set<ImageFormat> = setOf(ImageFormat.JPEG, ImageFormat.PNG)
    private var boundVideoQualities: Set<VideoQuality> = emptySet()

    suspend fun enumerate() {
        val (lenses, torchModes) = try {
            val lenses = enumerateLenses()
            lenses to lenses.associate { it.id to torchModesOf(it) }
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "enumerating the cameras failed; reporting none", e)
            emptyList<LensInfo>() to emptyMap()
        }
        lock.locked {
            enumeratedLenses = lenses
            torchModesById = torchModes
        }
        publish()
        enumerated.complete(Unit)
    }

    /** The torch modes [lens]'s device supports; from its hasFlash when the device cannot be found. */
    private fun torchModesOf(lens: LensInfo): Set<TorchMode> {
        val device = custom.findCameraById(lens.id)
            ?: return if (lens.hasFlash) setOf(TorchMode.OFF, TorchMode.ON) else setOf(TorchMode.OFF)
        if (!device.hasTorch) return setOf(TorchMode.OFF)
        return setOf(TorchMode.OFF) + listOf(TorchMode.ON, TorchMode.AUTO)
            .filter { device.isTorchModeSupported(it.toAVCaptureTorchMode()) }
    }

    private fun publish() {
        val capabilities = lock.locked {
            if (generations.closedLocked) return
            capabilitiesOf(
                enumeratedLenses,
                torchModesById,
                boundFlashModes.toMap(),
                boundImageFormats,
                boundVideoQualities,
            )
        }
        _capabilities.value = capabilities
    }

    /**
     * A lens's flash modes are the photo output's once the lens has been bound, and come from its
     * hasFlash before; its torch modes are its device's. The image formats and recording qualities are
     * the bound camera's.
     */
    private fun capabilitiesOf(
        lenses: List<LensInfo>,
        torchModes: Map<String, Set<TorchMode>>,
        boundFlash: Map<String, Set<FlashMode>>,
        imageFormats: Set<ImageFormat>,
        videoQualities: Set<VideoQuality>,
    ) = CameraCapabilities(
        allLenses = lenses,
        flashModes = lenses.associate {
            it.id to (
                boundFlash[it.id]
                    ?: if (it.hasFlash) setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON) else setOf(FlashMode.OFF)
                )
        },
        torchModes = lenses.associate { it.id to (torchModes[it.id] ?: setOf(TorchMode.OFF)) },
        // AVCaptureFileOutput pauses from iOS 18.
        supportsPauseResume = osVersion >= PAUSE_RESUME_IOS,
        // The qualities whose preset the bound camera delivers; none before the first bind.
        supportedVideoQualities = videoQualities,
        // AVCaptureMovieFileOutput records at its session preset's rate; no bitrate is applied (§6.3).
        supportsVideoBitrate = false,
        // 16:9 and 1:1 are crops of the full-resolution photo (StillWriter), so 1:1 is a true square.
        supportedAspectRatios = AspectRatio.entries.toSet(),
        // HEIC where the bound camera's photo output can encode HEVC.
        supportedImageFormats = imageFormats,
        // AVCaptureVideoDataOutput delivers CameraK's frames in 420f only (design §8).
        supportedAnalysisFormats = setOf(FrameFormat.YUV_420),
        supportsSquareCapture = true,
    )

    /**
     * Session queue, after [device] became the session's camera: its flash modes, image formats and
     * recording qualities from the photo output and the device now behind it.
     */
    fun bind(device: CaptureDeviceOps) {
        val flash = custom.captureFlashModes.map { it.toFlashMode() }.toSet() + FlashMode.OFF
        val formats =
            setOf(ImageFormat.JPEG, ImageFormat.PNG) + listOfNotNull(ImageFormat.HEIC.takeIf { custom.canCaptureHeic })
        val qualities = boundQualities()
        lock.locked {
            boundFlashModes[device.uniqueId] = flash
            boundImageFormats = formats
            boundVideoQualities = qualities
        }
        publish()
    }

    /** The recording qualities the bound camera can deliver: those whose session preset it supports. */
    fun boundQualities(): Set<VideoQuality> {
        val device = custom.currentDevice ?: return emptySet()
        return VideoQuality.entries.filter { device.supportsSessionPreset(it.sessionPreset) }.toSet()
    }

    /** [device] as the enumeration lists it, or described from the device when the enumeration does not. */
    fun lensInfo(device: CaptureDeviceOps): LensInfo =
        capabilities.value.allLenses.firstOrNull { it.id == device.uniqueId } ?: LensInfo(
            id = device.uniqueId,
            deviceType = CameraDeviceType.DEFAULT,
            lens = device.lens(),
            minZoom = 1f,
            maxZoom = device.maxAvailableVideoZoomFactor.toFloat(),
            hasFlash = false,
            isLogical = false,
        )
}
