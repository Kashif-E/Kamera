package com.kashif.cameraK.capabilities

import com.kashif.cameraK.enums.CameraDeviceType
import kotlin.math.roundToInt

/**
 * Raw per-camera focal data used to classify a lens — platform-independent so the
 * classification math is unit-testable in desktopTest without Camera2/AVFoundation.
 * [focalLengthsMm] is LENS_INFO_AVAILABLE_FOCAL_LENGTHS verbatim (a logical multi-camera
 * reports the union of its physical cameras' focal lengths). [physicalSizeWidthMm] is
 * SENSOR_INFO_PHYSICAL_SIZE width, used to normalize to 35mm-equivalent — comparing raw mm
 * across different sensor sizes misclassifies (the bug this replaces). Null when unreadable.
 */
internal class RawLensFocalInfo(
    val id: String,
    val focalLengthsMm: List<Float>,
    val physicalSizeWidthMm: Float?,
    val isLogical: Boolean,
)

// Absolute-ish 35mm-equivalent boundaries. Phone ultra-wides sit ~13-18mm equiv and phone
// telephotos start ~48mm equiv and up, so these leave a safety margin on both sides.
private const val ULTRA_WIDE_MAX_EQUIV_MM = 20f
private const val TELEPHOTO_MIN_EQUIV_MM = 70f

// Two physical sub-lenses whose raw focal lengths match within this tolerance share the
// same optics (one is a crop readout of the other).
private const val FOCAL_LENGTH_GROUPING_TOLERANCE_MM = 0.05f

/**
 * Standard 35mm ("full-frame") equivalent focal length: actual focal length scaled by
 * 36mm (full-frame sensor width) over this sensor's own width. Falls back to the raw
 * focal length when the sensor size is unreadable.
 */
internal fun equivalentFocalLengthMm(focalLengthMm: Float, physicalSizeWidthMm: Float?): Float =
    if (physicalSizeWidthMm != null && physicalSizeWidthMm > 0f) {
        focalLengthMm * (36f / physicalSizeWidthMm)
    } else {
        focalLengthMm
    }

/**
 * Classifies each camera of ONE facing by 35mm-equivalent focal length against absolute
 * thresholds — never by ordinal rank within the set (rank fabricates a TELEPHOTO on
 * 2-lens {ultra-wide, wide} devices like the Pixel 7). Rules:
 * - unreadable focal data → [CameraDeviceType.DEFAULT], excluded from siblings' comparison
 * - logical camera reporting multiple focal lengths → [CameraDeviceType.WIDE_ANGLE]
 *   (it opens at 1.0x, which resolves to the wide lens on current hardware)
 * - a facing's single classifiable camera → [CameraDeviceType.WIDE_ANGLE] unconditionally
 * - otherwise: equiv ≤ 20mm → ULTRA_WIDE, equiv ≥ 70mm → TELEPHOTO, else WIDE_ANGLE
 */
internal fun deriveLensDeviceTypes(lenses: List<RawLensFocalInfo>): Map<String, CameraDeviceType> {
    val types = mutableMapOf<String, CameraDeviceType>()
    val toClassify = mutableListOf<Pair<String, Float>>()

    for (lens in lenses) {
        when {
            lens.focalLengthsMm.isEmpty() -> types[lens.id] = CameraDeviceType.DEFAULT
            lens.isLogical && lens.focalLengthsMm.size > 1 -> types[lens.id] = CameraDeviceType.WIDE_ANGLE
            else -> toClassify.add(
                lens.id to equivalentFocalLengthMm(lens.focalLengthsMm.first(), lens.physicalSizeWidthMm),
            )
        }
    }

    when (toClassify.size) {
        0 -> Unit
        1 -> types[toClassify.single().first] = CameraDeviceType.WIDE_ANGLE
        else -> for ((id, equivMm) in toClassify) {
            types[id] = when {
                equivMm <= ULTRA_WIDE_MAX_EQUIV_MM -> CameraDeviceType.ULTRA_WIDE
                equivMm >= TELEPHOTO_MIN_EQUIV_MM -> CameraDeviceType.TELEPHOTO
                else -> CameraDeviceType.WIDE_ANGLE
            }
        }
    }
    return types
}

/**
 * Dedupes a logical camera's physical sub-lenses down to genuinely-distinct optics.
 * The HAL over-enumerates: some physical ids are crop pseudo-lenses sharing another
 * id's exact focal length on a smaller readout (Pixel 7's "telephoto" is a 2x crop of
 * the wide). Groups by raw focal length (±[FOCAL_LENGTH_GROUPING_TOLERANCE_MM]) and keeps
 * the largest-sensor entry per group. Unreadable-focal lenses survive as singletons.
 */
internal fun dedupeOpticallyDistinctLenses(physicals: List<RawLensFocalInfo>): List<RawLensFocalInfo> {
    val groups = LinkedHashMap<Any, MutableList<RawLensFocalInfo>>()
    for (lens in physicals) {
        val focal = lens.focalLengthsMm.firstOrNull()
        val key: Any = if (focal != null) roundToGroupingStep(focal) else lens.id
        groups.getOrPut(key) { mutableListOf() }.add(lens)
    }
    return groups.values.map { group -> group.maxByOrNull { it.physicalSizeWidthMm ?: -1f } ?: group.first() }
}

private fun roundToGroupingStep(focalLengthMm: Float): Float =
    (focalLengthMm / FOCAL_LENGTH_GROUPING_TOLERANCE_MM).roundToInt() * FOCAL_LENGTH_GROUPING_TOLERANCE_MM
