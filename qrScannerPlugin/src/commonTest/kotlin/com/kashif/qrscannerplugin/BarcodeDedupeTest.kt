package com.kashif.qrscannerplugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One dedupe policy for every platform (qr-detection-semantics-diverge): a code, keyed by format and
 * payload, is reported at most once per window on the capture clock, whatever other codes are seen
 * in between.
 */
class BarcodeDedupeTest {
    private fun admitted(window: Duration, vararg seen: Pair<Barcode, Long>): List<Boolean> {
        val dedupe = BarcodeDedupe(window)
        return seen.map { (barcode, millis) -> dedupe.admit(barcode, millis * 1_000_000) }
    }

    @Test
    fun theSameCodeInsideTheWindowIsReportedOnce() {
        assertEquals(
            listOf(true, false, false),
            admitted(1.seconds, qr("A") to 0, qr("A") to 400, qr("A") to 999),
        )
    }

    @Test
    fun theSameCodeIsReportedAgainOnceTheWindowHasPassed() {
        assertEquals(
            listOf(true, true, false, true),
            admitted(1.seconds, qr("A") to 0, qr("A") to 1000, qr("A") to 1500, qr("A") to 2000),
        )
    }

    @Test
    fun alternatingCodesAreEachReportedOncePerWindow() {
        assertEquals(
            listOf(true, true, false, false, true),
            admitted(1.seconds, qr("A") to 0, qr("B") to 100, qr("A") to 200, qr("B") to 300, qr("A") to 1000),
        )
    }

    @Test
    fun theSamePayloadInAnotherFormatIsAnotherCode() {
        assertEquals(
            listOf(true, true),
            admitted(1.seconds, qr("123") to 0, qr("123", BarcodeFormat.CODE_128) to 10),
        )
    }

    @Test
    fun binaryPayloadsCompareByContent() {
        val first = Barcode(null, byteArrayOf(1, 2, 3), BarcodeFormat.QR_CODE, null)
        val same = Barcode(null, byteArrayOf(1, 2, 3), BarcodeFormat.QR_CODE, null)
        val other = Barcode(null, byteArrayOf(1, 2, 4), BarcodeFormat.QR_CODE, null)

        assertEquals(listOf(true, false, true), admitted(1.seconds, first to 0, same to 10, other to 20))
    }

    @Test
    fun aZeroWindowReportsEveryDecode() {
        assertEquals(listOf(true, true, true), admitted(Duration.ZERO, qr("A") to 0, qr("A") to 0, qr("A") to 1))
    }

    @Test
    fun codesLongOutOfViewAreForgotten() {
        val dedupe = BarcodeDedupe(100.milliseconds)
        repeat(1000) { dedupe.admit(qr("code $it"), it * 1_000_000_000L) }

        assertEquals(true, dedupe.admit(qr("code 999"), 999_200_000_000L), "a code seen 200 ms ago")
        assertEquals(1, dedupe.remembered, "codes remembered once their windows have passed")
    }
}
