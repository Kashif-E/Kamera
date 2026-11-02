package com.kashif.cameraK.capabilities

import com.kashif.cameraK.enums.CameraDeviceType
import kotlin.test.Test
import kotlin.test.assertEquals

class LensClassificationTest {

    private fun raw(id: String, focals: List<Float>, sensorWidth: Float? = 6.4f, isLogical: Boolean = false) =
        RawLensFocalInfo(id, focals, sensorWidth, isLogical)

    // ── equivalentFocalLengthMm ──────────────────────────────────────

    @Test
    fun equivalent_normalizesTo35mm() {
        // 4.38mm lens on a 6.4mm-wide sensor → 4.38 * 36 / 6.4 = 24.64mm equiv
        assertEquals(24.6375f, equivalentFocalLengthMm(4.38f, 6.4f), 0.001f)
    }

    @Test
    fun equivalent_fallsBackToRawWhenSensorUnknown() {
        assertEquals(4.38f, equivalentFocalLengthMm(4.38f, null), 0.001f)
        assertEquals(4.38f, equivalentFocalLengthMm(4.38f, 0f), 0.001f)
    }

    // ── deriveLensDeviceTypes ────────────────────────────────────────

    @Test
    fun pixel7Case_noFabricatedTelephoto() {
        // Pixel 7 back physicals: 6.81mm on 9.79mm sensor (≈25mm equiv, wide)
        // + 2.35mm on 5.04mm sensor (≈16.8mm equiv, ultra-wide). No telephoto glass.
        val kinds = deriveLensDeviceTypes(
            listOf(
                raw("2", listOf(6.81f), 9.79f),
                raw("3", listOf(2.35f), 5.04f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["2"])
        assertEquals(CameraDeviceType.ULTRA_WIDE, kinds["3"])
    }

    @Test
    fun telephotoThreshold() {
        // 13.0mm on 6.4mm sensor → 73.1mm equiv → TELEPHOTO
        val kinds = deriveLensDeviceTypes(
            listOf(
                raw("0", listOf(4.38f)), // 24.6mm equiv → WIDE
                raw("1", listOf(13.0f)), // 73.1mm equiv → TELE
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["0"])
        assertEquals(CameraDeviceType.TELEPHOTO, kinds["1"])
    }

    @Test
    fun logicalMultiFocal_isWideAngle() {
        // Logical camera reporting merged focal lengths must never be tagged ULTRA_WIDE
        // just because one merged focal length is short — it opens at 1.0x (wide).
        val kinds = deriveLensDeviceTypes(
            listOf(raw("0", listOf(2.35f, 6.81f), isLogical = true)),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["0"])
    }

    @Test
    fun singleLens_isWideAngleUnconditionally() {
        // A 2.0mm focal on tiny front sensor would classify ULTRA_WIDE by threshold,
        // but a facing's only camera is in practice the primary lens.
        val kinds = deriveLensDeviceTypes(listOf(raw("1", listOf(2.0f), 3.6f)))
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["1"])
    }

    @Test
    fun unreadableFocal_isDefaultAndExcludedFromOrdering() {
        val kinds = deriveLensDeviceTypes(
            listOf(
                raw("0", emptyList()),
                raw("1", listOf(4.38f)),
            ),
        )
        assertEquals(CameraDeviceType.DEFAULT, kinds["0"])
        // "1" is the only classifiable lens → single-lens rule applies → WIDE_ANGLE
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["1"])
    }

    // ── dedupeOpticallyDistinctLenses ────────────────────────────────

    @Test
    fun pixel7CropPseudoLens_isDropped() {
        // id4 shares id2's exact 6.81mm optics on a smaller (4.90mm) readout — a digital
        // crop, not real telephoto glass. Keep the largest-sensor entry per focal group.
        val survivors = dedupeOpticallyDistinctLenses(
            listOf(
                raw("2", listOf(6.81f), 9.79f),
                raw("3", listOf(2.35f), 5.04f),
                raw("4", listOf(6.81f), 4.90f),
            ),
        )
        assertEquals(listOf("2", "3"), survivors.map { it.id })
    }

    @Test
    fun unreadableFocal_survivesAsSingleton() {
        val survivors = dedupeOpticallyDistinctLenses(
            listOf(
                raw("2", listOf(6.81f), 9.79f),
                raw("9", emptyList(), null),
            ),
        )
        assertEquals(listOf("2", "9"), survivors.map { it.id })
    }
}
