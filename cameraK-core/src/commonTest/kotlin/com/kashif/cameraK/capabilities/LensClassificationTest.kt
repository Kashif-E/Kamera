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

    // ── relative to the main lens ────────────────────────────────────
    // Sensor widths of 9.0, 4.5 and 18.0 mm scale by exactly 4, 8 and 2, so the equivalents below are exact.

    @Test
    fun telephotoAtRatioOnePointSixOfMainLens() {
        // Galaxy S23 Ultra back, by its published 23 mm and 69 mm equivalents. The focal lengths and sensor widths
        // are chosen to give those equivalents, not read from a characteristics dump. 69 < 70 kept the 3x out of
        // TELEPHOTO in 1.x; it is 3.0x the main.
        val s23 = deriveLensDeviceTypes(
            listOf(
                raw("main", listOf(6.3f), 9.86f),
                raw("tele3x", listOf(10.0f), 5.22f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, s23["main"])
        assertEquals(CameraDeviceType.TELEPHOTO, s23["tele3x"])

        // 25 mm main; 40 mm is exactly 1.6x, 37.5 mm is 1.5x.
        val boundary = deriveLensDeviceTypes(
            listOf(
                raw("main", listOf(6.25f), 9.0f),
                raw("x1_6", listOf(5.0f), 4.5f),
                raw("x1_5", listOf(4.6875f), 4.5f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, boundary["main"])
        assertEquals(CameraDeviceType.TELEPHOTO, boundary["x1_6"])
        assertEquals(CameraDeviceType.WIDE_ANGLE, boundary["x1_5"])
    }

    @Test
    fun ultraWideAtRatioPointSevenFive() {
        // 28 mm main; 21 mm is 0.75x (above the absolute 20 mm), 24 mm is 0.86x.
        val kinds = deriveLensDeviceTypes(
            listOf(
                raw("main", listOf(7.0f), 9.0f),
                raw("x0_75", listOf(2.625f), 4.5f),
                raw("x0_86", listOf(3.0f), 4.5f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["main"])
        assertEquals(CameraDeviceType.ULTRA_WIDE, kinds["x0_75"])
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["x0_86"])

        // With a main lens the ratio replaces the absolute 20 mm: 19 mm beside a 24 mm main is 0.79x, so
        // WIDE_ANGLE, where 1.x said ULTRA_WIDE.
        val above = deriveLensDeviceTypes(
            listOf(
                raw("main", listOf(6.0f), 9.0f),
                raw("x0_79", listOf(2.375f), 4.5f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, above["main"])
        assertEquals(CameraDeviceType.WIDE_ANGLE, above["x0_79"])
    }

    @Test
    fun absoluteThresholdsWhenNoMainLens() {
        // 16 mm and 120 mm: nothing in the 20-70 mm band, so no main lens.
        val noneInBand = deriveLensDeviceTypes(
            listOf(
                raw("uw", listOf(2.0f), 4.5f),
                raw("tele", listOf(15.0f), 4.5f),
            ),
        )
        assertEquals(CameraDeviceType.ULTRA_WIDE, noneInBand["uw"])
        assertEquals(CameraDeviceType.TELEPHOTO, noneInBand["tele"])

        // No sensor width readable: the raw focal lengths meet the absolute thresholds.
        val unreadable = deriveLensDeviceTypes(
            listOf(
                raw("uw", listOf(16.0f), null),
                raw("wide", listOf(26.0f), null),
                raw("tele", listOf(120.0f), null),
            ),
        )
        assertEquals(CameraDeviceType.ULTRA_WIDE, unreadable["uw"])
        assertEquals(CameraDeviceType.WIDE_ANGLE, unreadable["wide"])
        assertEquals(CameraDeviceType.TELEPHOTO, unreadable["tele"])

        // The only lens in the band has no sensor width, so it is not the main lens. Were its raw 22 mm the main,
        // the readable 18 mm lens would be 0.82x and WIDE_ANGLE; with no main it is ULTRA_WIDE by the absolute rule.
        val inBandUnreadable = deriveLensDeviceTypes(
            listOf(
                raw("noSensor", listOf(22.0f), null),
                raw("uw", listOf(2.25f), 4.5f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, inBandUnreadable["noSensor"])
        assertEquals(CameraDeviceType.ULTRA_WIDE, inBandUnreadable["uw"])
    }

    @Test
    fun mainLensIsTheLargestSensorInTheWideBand() {
        // A 27 mm depth module on a 2.9 mm sensor is listed first, beside a 24 mm main on a 9.8 mm sensor.
        // The 39 mm lens is 1.63x the main (TELEPHOTO) and would be 1.44x the depth module (WIDE_ANGLE).
        val kinds = deriveLensDeviceTypes(
            listOf(
                raw("depth", listOf(27.0f * 2.9f / 36f), 2.9f),
                raw("main", listOf(24.0f * 9.8f / 36f), 9.8f),
                raw("x1_63", listOf(39.0f * 5.6f / 36f), 5.6f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["main"])
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["depth"])
        assertEquals(CameraDeviceType.TELEPHOTO, kinds["x1_63"])

        // Equal sensor widths: the shorter equivalent (24 mm) is the main, so 39 mm is TELEPHOTO. Were the
        // 39 mm lens the main, the 24 mm lens would be 0.62x and ULTRA_WIDE.
        val tie = deriveLensDeviceTypes(
            listOf(
                raw("long", listOf(19.5f), 18.0f),
                raw("short", listOf(12.0f), 18.0f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, tie["short"])
        assertEquals(CameraDeviceType.TELEPHOTO, tie["long"])

        // The largest sensor outside the band is never the main: a 16 mm ultra-wide on a 9.0 mm sensor beside a
        // 24 mm lens on a 4.5 mm sensor. Against the 24 mm main the ultra-wide is 0.67x; were it the main, the
        // 24 mm lens would be 1.5x and both would be WIDE_ANGLE.
        val outOfBand = deriveLensDeviceTypes(
            listOf(
                raw("uw", listOf(4.0f), 9.0f),
                raw("wide", listOf(3.0f), 4.5f),
            ),
        )
        assertEquals(CameraDeviceType.ULTRA_WIDE, outOfBand["uw"])
        assertEquals(CameraDeviceType.WIDE_ANGLE, outOfBand["wide"])
    }

    @Test
    fun unreadableSensorWidthUsesAbsoluteThresholds() {
        // 25 mm main. A lens with no sensor width has a raw focal length of 40 mm: 1.6x the main by ratio,
        // but the raw figure is not an equivalent, so the absolute thresholds make it WIDE_ANGLE.
        val kinds = deriveLensDeviceTypes(
            listOf(
                raw("main", listOf(6.25f), 9.0f),
                raw("noSensor", listOf(40.0f), null),
                raw("noSensorTele", listOf(80.0f), null),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["main"])
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["noSensor"])
        assertEquals(CameraDeviceType.TELEPHOTO, kinds["noSensorTele"])

        // A sensor width of 0 is unreadable too: the raw 40 mm stays WIDE_ANGLE rather than 1.6x the 25 mm main.
        val zeroWidth = deriveLensDeviceTypes(
            listOf(
                raw("main", listOf(6.25f), 9.0f),
                raw("zeroWidth", listOf(40.0f), 0f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, zeroWidth["main"])
        assertEquals(CameraDeviceType.WIDE_ANGLE, zeroWidth["zeroWidth"])
    }

    @Test
    fun logicalMultiFocalIsNeitherMainNorCompared() {
        // The logical entry has the largest sensor and its first focal length reads 30 mm, in the wide band.
        // Excluded, the main is the 28 mm lens and the 46 mm lens is 1.64x (TELEPHOTO); were the logical entry
        // the main, 46 mm would be 1.53x (WIDE_ANGLE).
        val kinds = deriveLensDeviceTypes(
            listOf(
                raw("logical", listOf(10.0f, 3.0f), 12.0f, isLogical = true),
                raw("main", listOf(7.0f), 9.0f),
                raw("x1_64", listOf(5.75f), 4.5f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["logical"])
        assertEquals(CameraDeviceType.WIDE_ANGLE, kinds["main"])
        assertEquals(CameraDeviceType.TELEPHOTO, kinds["x1_64"])

        // Pixel 7's logical back camera [2.35, 6.81] beside its physical children stays WIDE_ANGLE and leaves
        // them as they are without it.
        val pixel7 = deriveLensDeviceTypes(
            listOf(
                raw("0", listOf(2.35f, 6.81f), 9.79f, isLogical = true),
                raw("2", listOf(6.81f), 9.79f),
                raw("3", listOf(2.35f), 5.04f),
            ),
        )
        assertEquals(CameraDeviceType.WIDE_ANGLE, pixel7["0"])
        assertEquals(CameraDeviceType.WIDE_ANGLE, pixel7["2"])
        assertEquals(CameraDeviceType.ULTRA_WIDE, pixel7["3"])
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
