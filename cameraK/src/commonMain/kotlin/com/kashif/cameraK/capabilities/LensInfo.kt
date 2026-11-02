package com.kashif.cameraK.capabilities

import androidx.compose.runtime.Immutable
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens

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
@Immutable
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
 * [allLenses] is copied on construction so this class's `@Immutable` contract holds regardless of
 * what the caller does with the list it passed in.
 */
@Immutable
class CameraCapabilities(allLenses: List<LensInfo>) {

    val allLenses: List<LensInfo> = allLenses.toList()

    /** Built-in lenses facing [lens], in enumeration order. External cameras are not included. */
    fun lenses(lens: CameraLens): List<LensInfo> = allLenses.filter { it.lens == lens && !it.isExternal }

    /** Cameras attached to the device rather than built in, in enumeration order. */
    val externalLenses: List<LensInfo> get() = allLenses.filter { it.isExternal }

    /** Device types selectable via `setPreferredCameraDeviceType` for [lens]. */
    fun availableDeviceTypes(lens: CameraLens): Set<CameraDeviceType> =
        lenses(lens).mapTo(mutableSetOf()) { it.deviceType }

    companion object {
        val EMPTY = CameraCapabilities(emptyList())
    }
}
