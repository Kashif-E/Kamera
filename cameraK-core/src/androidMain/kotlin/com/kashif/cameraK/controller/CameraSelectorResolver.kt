package com.kashif.cameraK.controller

import androidx.camera.core.CameraSelector
import com.kashif.cameraK.capabilities.AndroidLensDescriptor
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens

/** The camera a bind selects for a lens facing and a device type. */
internal sealed interface ResolvedCamera {
    val lens: CameraLens

    /** The facing's default camera, which opens wide. */
    data class FacingDefault(override val lens: CameraLens) : ResolvedCamera

    /** A camera CameraX lists itself, selected by its id. */
    data class TopLevel(override val lens: CameraLens, val cameraId: String) : ResolvedCamera

    /** A physical lens inside the logical multi-camera [parentLogicalId], pinned through Camera2Interop. */
    data class PhysicalLens(override val lens: CameraLens, val physicalId: String, val parentLogicalId: String?) :
        ResolvedCamera
}

/** Resolves a lens facing and a device type to a camera, over the lenses [enumerate] reports. */
internal class CameraSelectorResolver(private val enumerate: () -> List<AndroidLensDescriptor>) {
    fun lensSnapshot(): List<AndroidLensDescriptor> = TODO("A0: lens snapshot")

    fun resolve(lens: CameraLens, deviceType: CameraDeviceType): ResolvedCamera = TODO("A0: camera resolution")
}

internal val ResolvedCamera.pinnedPhysicalId: String? get() = TODO("A0: physical pin")

internal fun ResolvedCamera.toCameraSelector(): CameraSelector = TODO("A0: camera selector")
