@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.capabilities

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger

/**
 * [LensInfo] plus the Android-only binding facts the selector needs.
 *
 * @property parentLogicalId For a physical sub-lens, the id of the enclosing top-level logical
 * camera it was expanded from; null for top-level entries. CameraSelector.select() (CameraX 1.5.1)
 * resolves purely from the CameraFilter set — setPhysicalCameraId does NOT influence which logical
 * camera is picked — so pinning a physical child to the right hardware requires filtering on this
 * parent id too (see CameraSelectorResolver).
 */
internal class AndroidLensDescriptor(val info: LensInfo, val isPhysicalChild: Boolean, val parentLogicalId: String?)

/**
 * The built-in lens a switch to [deviceType] on [lens] binds, or null when [lens] has no lens of that
 * type. External cameras are never a target: the selector requires a lens facing, and CameraX never
 * offers an external camera for one.
 */
internal fun List<AndroidLensDescriptor>.deviceTypeTarget(
    lens: CameraLens,
    deviceType: CameraDeviceType,
): AndroidLensDescriptor? = firstOrNull {
    it.info.lens == lens && !it.info.isExternal && it.info.deviceType == deviceType
}

/**
 * Camera2 snapshot of every lens on the device. Top-level ids come from
 * [CameraManager.cameraIdList]; a logical multi-camera's physical sub-lenses (which are
 * NOT in that list) are expanded via physicalCameraIds and read directly with
 * getCameraCharacteristics(physicalId) — Camera2 supports that since API 28. Crop
 * pseudo-lenses the HAL over-enumerates are dropped via [dedupeOpticallyDistinctLenses].
 */
internal object LensEnumerator {

    fun snapshot(context: Context): List<AndroidLensDescriptor> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        return snapshot(CameraManagerReader(manager))
    }

    fun snapshot(reader: Camera2Reader): List<AndroidLensDescriptor> {
        val cameraIds = try {
            reader.cameraIds()
        } catch (e: Exception) {
            CameraKLogger.w("CameraK", "Lens enumeration failed to list cameras: ${e.message}")
            emptyArray()
        }

        // External cameras are classified apart from built-in ones: they share no hardware with them.
        val rawByFacing = mutableMapOf<Facing, MutableList<RawCamera>>()
        for (id in cameraIds) {
            val raw = describe(reader, id, isPhysicalChild = false, parentLogicalId = null) ?: continue
            rawByFacing.getOrPut(raw.facing) { mutableListOf() }.add(raw)
            if (raw.isLogical) {
                for (physicalId in raw.physicalCameraIds) {
                    val child = describe(reader, physicalId, isPhysicalChild = true, parentLogicalId = id)
                        ?: continue
                    rawByFacing.getOrPut(child.facing) { mutableListOf() }.add(child)
                }
            }
        }

        val result = mutableListOf<AndroidLensDescriptor>()
        for ((facing, allRaw) in rawByFacing) {
            val rawCameras = dedupeCropSiblings(allRaw)
            val types = deriveLensDeviceTypes(
                rawCameras.map { RawLensFocalInfo(it.id, it.focalLengthsMm, it.physicalSizeWidthMm, it.isLogical) },
            )
            for (raw in rawCameras) {
                // Focal length can't detect macro; keep the pre-existing min-focus-distance rule
                // for top-level ids (matches the old createCameraSelector MACRO filter).
                val deviceType = if (!raw.isPhysicalChild && raw.minFocusDistance > MACRO_MIN_DIOPTERS) {
                    CameraDeviceType.MACRO
                } else {
                    types[raw.id] ?: CameraDeviceType.DEFAULT
                }
                result.add(
                    AndroidLensDescriptor(
                        info = LensInfo(
                            id = raw.id,
                            deviceType = deviceType,
                            lens = facing.lens,
                            minZoom = raw.minZoomRatio,
                            maxZoom = raw.maxZoomRatio,
                            hasFlash = raw.hasFlash,
                            isLogical = raw.isLogical,
                            isExternal = facing.isExternal,
                        ),
                        isPhysicalChild = raw.isPhysicalChild,
                        parentLogicalId = raw.parentLogicalId,
                    ),
                )
            }
        }
        return result
    }

    // LENS_INFO_MINIMUM_FOCUS_DISTANCE is in diopters (1/meters) — larger means it focuses
    // closer, not farther. A typical main/wide camera reports ~8-12 diopters (focuses down to
    // ~8-12cm); dedicated macro lenses report ~20-33 diopters (focus at ~3-5cm or closer). 20 is
    // comfortably above the main-camera range while still catching real macro lenses. A
    // fixed-focus lens reports 0 and stays excluded naturally.
    private const val MACRO_MIN_DIOPTERS = 20f

    // Dedup pool is per PARENT logical camera, not per facing: two logical cameras on the same
    // facing (e.g. two back logical multi-cameras) can each have a child at the same focal length
    // without being crop siblings of each other — pooling all children of a facing together would
    // wrongly collapse one representative across both parents.
    private fun dedupeCropSiblings(rawCameras: List<RawCamera>): List<RawCamera> {
        val (children, topLevel) = rawCameras.partition { it.isPhysicalChild }
        val survivingIds = children
            .groupBy { it.parentLogicalId }
            .values
            .flatMap { group ->
                dedupeOpticallyDistinctLenses(
                    group.map { RawLensFocalInfo(it.id, it.focalLengthsMm, it.physicalSizeWidthMm, it.isLogical) },
                )
            }
            .mapTo(mutableSetOf()) { it.id }
        return topLevel + children.filter { it.id in survivingIds }
    }

    /**
     * Which way a camera faces. An external camera faces neither way; it reports [CameraLens.BACK]
     * with [isExternal] set, since [CameraLens] has no entry for it.
     */
    private data class Facing(val lens: CameraLens, val isExternal: Boolean)

    private class RawCamera(
        val id: String,
        val facing: Facing,
        val minZoomRatio: Float,
        val maxZoomRatio: Float,
        val hasFlash: Boolean,
        val isLogical: Boolean,
        val isPhysicalChild: Boolean,
        val parentLogicalId: String?,
        val focalLengthsMm: List<Float>,
        val physicalSizeWidthMm: Float?,
        val physicalCameraIds: Set<String>,
        val minFocusDistance: Float,
    )

    private fun describe(
        reader: Camera2Reader,
        id: String,
        isPhysicalChild: Boolean,
        parentLogicalId: String?,
    ): RawCamera? {
        val characteristics = try {
            reader.characteristics(id)
        } catch (e: Exception) {
            CameraKLogger.w("CameraK", "Lens enumeration failed on camera $id: ${e.message}")
            null
        } ?: return null

        val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> Facing(CameraLens.FRONT, isExternal = false)
            CameraCharacteristics.LENS_FACING_EXTERNAL -> Facing(CameraLens.BACK, isExternal = true)
            else -> Facing(CameraLens.BACK, isExternal = false)
        }

        // CONTROL_ZOOM_RATIO_RANGE is an API 30+ Key FIELD — referencing it below 30 throws
        // NoSuchFieldError (an Error, uncatchable by catch(Exception)). Guard by SDK level.
        val (minZoom, maxZoom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            (range?.lower ?: 1.0f) to (range?.upper ?: 1.0f)
        } else {
            val maxDigital = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            1.0f to maxDigital
        }

        val physicalIds = try {
            reader.physicalCameraIds(characteristics)
        } catch (e: Exception) {
            emptySet()
        }

        return RawCamera(
            id = id,
            facing = facing,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            isLogical = physicalIds.isNotEmpty(),
            isPhysicalChild = isPhysicalChild,
            parentLogicalId = parentLogicalId,
            focalLengthsMm = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
            )?.toList().orEmpty(),
            physicalSizeWidthMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width,
            physicalCameraIds = physicalIds,
            minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
        )
    }
}

/** The Camera2 reads [LensEnumerator] makes. */
internal interface Camera2Reader {
    fun cameraIds(): Array<String>

    fun characteristics(id: String): CameraCharacteristics

    /** The physical lenses of a logical multi-camera; none below API 28. */
    fun physicalCameraIds(characteristics: CameraCharacteristics): Set<String>
}

private class CameraManagerReader(private val manager: CameraManager) : Camera2Reader {
    override fun cameraIds(): Array<String> = manager.cameraIdList

    override fun characteristics(id: String): CameraCharacteristics = manager.getCameraCharacteristics(id)

    // physicalCameraIds is API 28+: referencing it below throws NoSuchMethodError, which no catch of
    // Exception stops.
    override fun physicalCameraIds(characteristics: CameraCharacteristics): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) characteristics.physicalCameraIds else emptySet()
}
