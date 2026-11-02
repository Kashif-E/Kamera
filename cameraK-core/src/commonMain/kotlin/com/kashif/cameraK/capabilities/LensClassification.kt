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
// telephotos start ~48mm equiv and up, so these leave a safety margin on both sides. They also
// bound the band (exclusive) in which a facing's main lens is looked for.
private const val ULTRA_WIDE_MAX_EQUIV_MM = 20f
private const val TELEPHOTO_MIN_EQUIV_MM = 70f

// Ratios to the facing's main lens. A 3x telephoto at 69mm equiv beside a 23mm main sits under the
// absolute 70mm, and a 21mm ultra-wide beside a 28mm main sits over the absolute 20mm.
private const val TELEPHOTO_MIN_RATIO = 1.6f
private const val ULTRA_WIDE_MAX_RATIO = 0.75f

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
 * Classifies each camera of ONE facing by 35mm-equivalent focal length relative to the facing's
 * main lens — never by ordinal rank within the set (rank fabricates a TELEPHOTO on 2-lens
 * {ultra-wide, wide} devices like the Pixel 7). Rules:
 * - unreadable focal data → [CameraDeviceType.DEFAULT], excluded from siblings' comparison
 * - logical camera reporting multiple focal lengths → [CameraDeviceType.WIDE_ANGLE]
 *   (it opens at 1.0x, which resolves to the wide lens on current hardware); never the main
 *   lens and never compared
 * - a facing's single classifiable camera → [CameraDeviceType.WIDE_ANGLE] unconditionally
 * - the main lens is the largest readable sensor among the single-focal lenses whose equiv lies
 *   strictly between 20mm and 70mm, ties going to the shorter equiv. A depth or macro module can
 *   sit near 26mm, but its sensor is far smaller than the primary camera's.
 * - a lens with a readable sensor width, when there is a main lens: ratio ≥ 1.6 → TELEPHOTO,
 *   ratio ≤ 0.75 → ULTRA_WIDE, else WIDE_ANGLE
 * - with no main lens, or for a lens whose sensor width is unreadable (its "equiv" is then raw
 *   millimetres, which no ratio can compare): equiv ≤ 20mm → ULTRA_WIDE, equiv ≥ 70mm →
 *   TELEPHOTO, else WIDE_ANGLE
 */
internal fun deriveLensDeviceTypes(lenses: List<RawLensFocalInfo>): Map<String, CameraDeviceType> {
    val types = mutableMapOf<String, CameraDeviceType>()
    val toClassify = mutableListOf<ClassifiableLens>()

    for (lens in lenses) {
        when {
            lens.focalLengthsMm.isEmpty() -> types[lens.id] = CameraDeviceType.DEFAULT
            lens.isLogical && lens.focalLengthsMm.size > 1 -> types[lens.id] = CameraDeviceType.WIDE_ANGLE
            else -> toClassify.add(
                ClassifiableLens(
                    id = lens.id,
                    equivMm = equivalentFocalLengthMm(lens.focalLengthsMm.first(), lens.physicalSizeWidthMm),
                    sensorWidthMm = lens.physicalSizeWidthMm?.takeIf { it > 0f },
                ),
            )
        }
    }

    when (toClassify.size) {
        0 -> Unit
        1 -> types[toClassify.single().id] = CameraDeviceType.WIDE_ANGLE
        else -> {
            val main = mainLens(toClassify)
            for (lens in toClassify) {
                types[lens.id] = if (main != null && lens.sensorWidthMm != null) {
                    byRatio(lens.equivMm / main.equivMm)
                } else {
                    byAbsoluteEquiv(lens.equivMm)
                }
            }
        }
    }
    return types
}

private class ClassifiableLens(val id: String, val equivMm: Float, val sensorWidthMm: Float?)

private fun mainLens(lenses: List<ClassifiableLens>): ClassifiableLens? = lenses
    .filter { it.sensorWidthMm != null && it.equivMm > ULTRA_WIDE_MAX_EQUIV_MM && it.equivMm < TELEPHOTO_MIN_EQUIV_MM }
    .minWithOrNull(compareByDescending<ClassifiableLens> { it.sensorWidthMm }.thenBy { it.equivMm })

private fun byRatio(ratioToMain: Float): CameraDeviceType = when {
    ratioToMain >= TELEPHOTO_MIN_RATIO -> CameraDeviceType.TELEPHOTO
    ratioToMain <= ULTRA_WIDE_MAX_RATIO -> CameraDeviceType.ULTRA_WIDE
    else -> CameraDeviceType.WIDE_ANGLE
}

private fun byAbsoluteEquiv(equivMm: Float): CameraDeviceType = when {
    equivMm <= ULTRA_WIDE_MAX_EQUIV_MM -> CameraDeviceType.ULTRA_WIDE
    equivMm >= TELEPHOTO_MIN_EQUIV_MM -> CameraDeviceType.TELEPHOTO
    else -> CameraDeviceType.WIDE_ANGLE
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
