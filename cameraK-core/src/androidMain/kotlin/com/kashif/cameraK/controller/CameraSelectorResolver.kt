package com.kashif.cameraK.controller

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import com.kashif.cameraK.capabilities.AndroidLensDescriptor
import com.kashif.cameraK.capabilities.PhysicalLensFacts
import com.kashif.cameraK.capabilities.deviceTypeTarget
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.utils.CameraKLogger

private const val TAG = "CameraK"

/** The camera a bind selects for a lens facing and a device type. */
internal sealed interface ResolvedCamera {
    val lens: CameraLens

    /** The facing's default camera, which opens wide. */
    data class FacingDefault(override val lens: CameraLens) : ResolvedCamera

    /** A camera CameraX lists itself, selected by its id. */
    data class TopLevel(override val lens: CameraLens, val cameraId: String) : ResolvedCamera

    /**
     * A physical lens inside the logical multi-camera [parentLogicalId], pinned through Camera2Interop;
     * [facts] is what the lens can do on its own, null when the enumeration has none.
     */
    data class PhysicalLens(
        override val lens: CameraLens,
        val physicalId: String,
        val parentLogicalId: String?,
        val facts: PhysicalLensFacts? = null,
    ) : ResolvedCamera

    /**
     * An external (USB) camera, selected by its id alone: it faces neither way, and CameraX's facing
     * filter drops it. [lens] is the one its `LensInfo` reports. When CameraX no longer offers it
     * (unplugged since the enumeration), the engine binds the back camera in its place, or reports
     * `NoCamera` when there is none, before anything unbinds.
     */
    data class External(override val lens: CameraLens, val cameraId: String) : ResolvedCamera
}

/**
 * Resolves a lens facing and a device type to a camera, over the lenses [enumerate] reports, with
 * the 35mm-equivalent classification of the lens snapshot rather than raw focal-length thresholds.
 *
 * - WIDE_ANGLE and DEFAULT: the facing's default camera, which opens wide.
 * - A type that maps to a top-level camera id: that id.
 * - A type that maps to a physical sub-lens: that lens, pinned (see [pinnedPhysicalId]).
 * - A type the facing lacks: a warning and the facing's default camera.
 */
internal class CameraSelectorResolver(private val enumerate: () -> List<AndroidLensDescriptor>) {
    // Hardware doesn't change at runtime; enumerate once.
    private var cachedSnapshot: List<AndroidLensDescriptor>? = null

    fun lensSnapshot(): List<AndroidLensDescriptor> = cachedSnapshot ?: enumerate().also {
        // Don't cache an empty result: a transient camera-service failure on the first call
        // would otherwise permanently disable lens switching for the controller's lifetime.
        if (it.isNotEmpty()) cachedSnapshot = it
    }

    fun resolve(lens: CameraLens, deviceType: CameraDeviceType): ResolvedCamera {
        if (deviceType == CameraDeviceType.DEFAULT || deviceType == CameraDeviceType.WIDE_ANGLE) {
            return ResolvedCamera.FacingDefault(lens)
        }
        val target = lensSnapshot().deviceTypeTarget(lens, deviceType)
        if (target == null) {
            CameraKLogger.w(TAG, "$deviceType not available for $lens, using default camera")
            return ResolvedCamera.FacingDefault(lens)
        }
        return if (target.isPhysicalChild) {
            ResolvedCamera.PhysicalLens(lens, target.info.id, target.parentLogicalId)
        } else {
            ResolvedCamera.TopLevel(lens, target.info.id)
        }
    }

    /**
     * [resolve], or null when the snapshot lists cameras and none faces [lens]: no camera, decided
     * before binding. An empty snapshot (the camera service failed) leaves the choice to CameraX.
     */
    fun resolveFacing(lens: CameraLens, deviceType: CameraDeviceType): ResolvedCamera? {
        val snapshot = lensSnapshot()
        if (snapshot.isNotEmpty() && snapshot.none { it.info.lens == lens && !it.info.isExternal }) return null
        return resolve(lens, deviceType)
    }

    /** The camera whose `LensInfo.id` is [id], or null when the snapshot has none. */
    fun resolveId(id: String): ResolvedCamera? {
        val descriptor = lensSnapshot().firstOrNull { it.info.id == id } ?: return null
        val lens = descriptor.info.lens
        return when {
            descriptor.info.isExternal -> ResolvedCamera.External(lens, id)
            descriptor.isPhysicalChild -> ResolvedCamera.PhysicalLens(lens, id, descriptor.parentLogicalId)
            else -> ResolvedCamera.TopLevel(lens, id)
        }
    }
}

/**
 * The physical lens every use-case builder must pin. CameraSelector.setPhysicalCameraId ALONE
 * silently no-ops on real hardware: it must be paired with Camera2Interop.Extender.setPhysicalCameraId
 * on EVERY use-case builder (verified on real hardware).
 */
internal val ResolvedCamera.pinnedPhysicalId: String? get() = (this as? ResolvedCamera.PhysicalLens)?.physicalId

/**
 * The CameraX selector for this camera; the use-case builders apply the Camera2Interop half of a pin.
 *
 * CameraX hands each filter an unmodifiable view and trims the filter's result in place, so a
 * fallback returns a copy, never the view it was given.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun ResolvedCamera.toCameraSelector(): CameraSelector {
    val builder = CameraSelector.Builder()
    if (this !is ResolvedCamera.External) builder.requireLensFacing(lens.toCameraXLensFacing())
    return when (this) {
        is ResolvedCamera.FacingDefault -> builder.build()
        // setPhysicalCameraId ALONE does not influence which logical camera CameraSelector
        // picks: CameraSelector.select() (verified against CameraX 1.5.1 source) resolves
        // purely from the CameraFilter set, and the physical id is only consulted later by
        // the use-case builders' Camera2Interop.Extender. Without this filter, on a facing
        // with multiple logical multi-cameras, selection could land on the wrong logical
        // camera while still (silently) trying to pin a physical id that belongs to a
        // different one. Constrain selection to the physical child's parent logical camera;
        // fall back to the unfiltered set if for some reason it's not offered.
        is ResolvedCamera.PhysicalLens -> builder.setPhysicalCameraId(physicalId)
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == parentLogicalId }
                    .ifEmpty { cameraInfos.toList() }
            }.build()
        is ResolvedCamera.TopLevel -> builder.addCameraFilter { cameraInfos ->
            cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                .ifEmpty {
                    CameraKLogger.w(TAG, "Camera $cameraId not offered by CameraX, using default")
                    cameraInfos.toList()
                }
        }.build()
        is ResolvedCamera.External -> builder.addCameraFilter { cameraInfos ->
            cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
        }.build()
    }
}

private fun CameraLens.toCameraXLensFacing(): Int = when (this) {
    CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
    CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
}
