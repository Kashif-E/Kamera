@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import androidx.camera.core.Camera
import com.kashif.cameraK.capabilities.AndroidLensDescriptor
import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capabilities.capabilitiesOf
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CameraK"

/**
 * The cameras [AndroidCameraEngine] can open and what each can do (design §6.3). The lenses are
 * enumerated once, off Main, into [capabilities]; a [CameraSelection] resolves against that list,
 * and an external camera CameraX no longer offers is replaced by the back camera. After every bind
 * the engine has the bound lens's flash unit corrected from CameraX, what it meters with read from
 * CameraX, and its video qualities queried off Main. Apart from the enumeration and that query,
 * everything here runs on the main thread.
 */
internal class LensCatalog(
    lensSource: () -> List<AndroidLensDescriptor>,
    private val backend: CameraXBackend,
    private val scope: CoroutineScope,
    private val background: CoroutineDispatcher,
    private val mainImmediate: CoroutineDispatcher,
    private val isReleased: () -> Boolean,
    private val boundCamera: () -> Camera?,
) {
    private val selectorResolver = CameraSelectorResolver(lensSource)

    private val _capabilities = MutableStateFlow(CameraCapabilities.EMPTY)
    val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    /** The enumeration, started at construction; a start or a switch waits for it before it resolves. */
    val enumeration: Deferred<CameraCapabilities> = scope.async(background) {
        androidCapabilities(enumerateLenses()).also { _capabilities.value = it }
    }

    // The bound camera's video qualities (§6.3), queried off Main after every bind.
    private var boundQualities: Set<VideoQuality> = emptySet()
    private var qualityQuery: Deferred<Set<VideoQuality>>? = null

    // What each camera bound so far meters with, by lens id: CameraX describes a camera once it is bound.
    private val metering = mutableMapOf<String, MeteringFeatures>()

    /** The camera [selection] names, or null when no enumerated camera matches it. */
    fun resolve(selection: CameraSelection): ResolvedCamera? = when (selection) {
        is CameraSelection.ByLens -> selectorResolver.resolveFacing(selection.lens, selection.deviceType)
        is CameraSelection.ById -> selectorResolver.resolveId(selection.id)
        is CameraSelection.Default -> selectorResolver.resolveFacing(CameraLens.BACK, selection.deviceType)
    }

    /**
     * [resolved], or what stands in for it: an external camera the enumeration listed that CameraX no
     * longer offers (unplugged since) is replaced by the back camera, and with no back camera either
     * this is null, decided before anything unbinds.
     */
    fun offered(resolved: ResolvedCamera, binding: CameraBinding): ResolvedCamera? {
        if (resolved !is ResolvedCamera.External || binding.hasCamera(resolved)) return resolved
        val back = ResolvedCamera.FacingDefault(CameraLens.BACK)
        if (!binding.hasCamera(back)) {
            CameraKLogger.w(TAG, "External camera ${resolved.cameraId} not offered by CameraX, and no back camera")
            return null
        }
        CameraKLogger.w(TAG, "External camera ${resolved.cameraId} not offered by CameraX, using the back camera")
        return back
    }

    /** The facing [selection] asks for, which a `NoCamera` names; null for a camera id. */
    fun lensOf(selection: CameraSelection): CameraLens? = when (selection) {
        is CameraSelection.ByLens -> selection.lens
        is CameraSelection.Default -> CameraLens.BACK
        is CameraSelection.ById -> null
    }

    /** A bind is replacing the camera: its qualities are unknown until [queryVideoQualities] returns. */
    fun forgetBoundQualities() {
        boundQualities = emptySet()
    }

    /** The qualities the bound camera records, once the last bind's query has returned. */
    suspend fun videoQualities(): Set<VideoQuality> = qualityQuery?.await() ?: boundQualities

    // The bound camera's id as CameraX reports it, or the pinned physical lens inside it.
    fun reportedLens(resolved: ResolvedCamera, camera: Camera, binding: CameraBinding): LensInfo? {
        val id = (resolved as? ResolvedCamera.PhysicalLens)?.physicalId ?: try {
            binding.cameraIdOf(camera)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading the bound camera's id failed", e)
            return null
        }
        return _capabilities.value.allLenses.firstOrNull { it.id == id }
    }

    /**
     * The bound camera [lens] as CameraX describes it: its flash unit ([withFlashUnitOf]), and whether
     * it meters at a point and its exposure compensation range and step, which the capabilities then
     * report for that lens (w13 Z-focus-android).
     */
    fun describeBound(camera: Camera, lens: LensInfo?): LensInfo? {
        val described = withFlashUnitOf(camera, lens) ?: return null
        val features = try {
            meteringFeaturesOf(camera)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading the bound camera's focus and exposure support failed", e)
            return described
        }
        if (metering.put(described.id, features) != features) publish(_capabilities.value.allLenses)
        return described
    }

    // The bound camera, as CameraInfo.hasFlashUnit describes it: the value CameraX acts on, which the
    // enumeration's FLASH_INFO_AVAILABLE only estimated. A disagreement corrects the capabilities.
    private fun withFlashUnitOf(camera: Camera, lens: LensInfo?): LensInfo? {
        lens ?: return null
        val flashUnit = try {
            camera.cameraInfo.hasFlashUnit()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading the bound camera's flash unit failed", e)
            return lens
        }
        if (flashUnit == lens.hasFlash) return lens
        val corrected = LensInfo(
            lens.id,
            lens.deviceType,
            lens.lens,
            lens.minZoom,
            lens.maxZoom,
            flashUnit,
            lens.isLogical,
            lens.isExternal,
        )
        publish(_capabilities.value.allLenses.map { if (it.id == lens.id) corrected else it })
        return corrected
    }

    // The bound camera's video qualities, read off Main (the encoder profiles and codec list, §2.4)
    // and published once they are in, unless another bind replaced the camera meanwhile.
    fun queryVideoQualities(camera: Camera) {
        val query = scope.async(background) {
            try {
                backend.videoQualities(camera.cameraInfo)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Reading the bound camera's video qualities failed", e)
                emptySet()
            }
        }
        qualityQuery = query
        scope.launch(mainImmediate) {
            val qualities = query.await()
            if (isReleased() || camera !== boundCamera()) return@launch
            boundQualities = qualities
            publish(_capabilities.value.allLenses)
        }
    }

    private fun publish(lenses: List<LensInfo>) {
        _capabilities.value = androidCapabilities(lenses, boundQualities, metering)
    }

    private fun enumerateLenses(): List<LensInfo> = try {
        selectorResolver.lensSnapshot().map { it.info }
    } catch (e: Exception) {
        CameraKLogger.e(TAG, "Enumerating the cameras failed", e)
        emptyList()
    }
}

/**
 * The capabilities the engine reports for [lenses] (design §6.3): flash OFF, AUTO and ON and torch
 * OFF and ON on a lens with a flash unit, OFF only otherwise (CameraX has no AUTO torch); pause and
 * resume, which CameraX's Recorder supports; [videoQualities], the bound camera's own (empty before
 * the first bind), each recorded at its `VideoQuality.bitrateBps` as the encoder's target rate; JPEG
 * and PNG; every aspect ratio, 1:1 as the square file the 1:1 ViewPort crops from a 4:3 stream;
 * YUV_420 and RGBA_8888 analysis, the two formats ImageAnalysis outputs; a focus point and exposure
 * compensation for each camera [metering] describes as having them, which is each camera bound so far.
 */
internal fun androidCapabilities(
    lenses: List<LensInfo>,
    videoQualities: Set<VideoQuality> = emptySet(),
    metering: Map<String, MeteringFeatures> = emptyMap(),
): CameraCapabilities {
    val plain = capabilitiesOf(lenses)
    val compensated = metering.filterValues { it.exposureRange != null && it.exposureStep != null }
    return CameraCapabilities(
        allLenses = lenses,
        flashModes = lenses.associate { it.id to plain.supportedFlashModes(it.id) },
        torchModes = lenses.associate { it.id to plain.supportedTorchModes(it.id) },
        supportsPauseResume = true,
        supportedVideoQualities = videoQualities,
        supportsVideoBitrate = true,
        supportedAspectRatios = AspectRatio.entries.toSet(),
        supportedImageFormats = setOf(ImageFormat.JPEG, ImageFormat.PNG),
        supportedAnalysisFormats = setOf(FrameFormat.YUV_420, FrameFormat.RGBA_8888),
        supportsSquareCapture = true,
        focusPointCameras = metering.filterValues { it.focusPoint }.keys,
        exposureCompensationRanges = compensated.mapValues { checkNotNull(it.value.exposureRange) },
        exposureCompensationSteps = compensated.mapValues { checkNotNull(it.value.exposureStep) },
    )
}
