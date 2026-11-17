package dev.kashifmehmood.camerak.qrscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A code is reported only once it has been read in [QRScannerConfig.confirmations] frames whose reads
 * are still inside the confirmation window (#190): a pattern that only looks like a code, a ventilation
 * grille, decodes to a different misread in each frame, so it never gathers two reads.
 */
class BarcodeConfirmationTest {
    private fun confirmed(confirmations: Int, window: Duration, vararg seen: Pair<Barcode, Long>): List<Boolean> {
        val confirmation = BarcodeConfirmation(confirmations, window)
        return seen.map { (barcode, millis) -> confirmation.sighted(barcode, millis * 1_000_000) }
    }

    @Test
    fun aCodeReadInOneFrameIsNotConfirmed() {
        assertEquals(listOf(false), confirmed(2, 1.seconds, qr("A") to 0))
    }

    @Test
    fun aCodeReadInTwoFramesInsideTheWindowIsConfirmedFromTheSecond() {
        assertEquals(
            listOf(false, true, true),
            confirmed(2, 1.seconds, qr("A") to 0, qr("A") to 200, qr("A") to 400),
        )
    }

    @Test
    fun aReadAsOldAsTheWindowNoLongerCounts() {
        assertEquals(
            listOf(false, false, true),
            confirmed(2, 1.seconds, qr("A") to 0, qr("A") to 1000, qr("A") to 1200),
        )
    }

    @Test
    fun misreadsThatDifferInEveryFrameAreNeverConfirmed() {
        val reads = (0 until 10).map { qr("grille $it") to it * 200L }.toTypedArray()

        assertEquals(List(10) { false }, confirmed(2, 1.seconds, *reads))
    }

    @Test
    fun eachCodeGathersItsOwnReads() {
        assertEquals(
            listOf(false, false, true, true),
            confirmed(2, 1.seconds, qr("A") to 0, qr("B") to 200, qr("A") to 400, qr("B") to 600),
        )
    }

    @Test
    fun theSameCodeTwiceInOneFrameIsOneRead() {
        assertEquals(listOf(false, false), confirmed(2, 1.seconds, qr("A") to 0, qr("A") to 0))
    }

    @Test
    fun oneConfirmationConfirmsTheFirstRead() {
        assertEquals(listOf(true, true), confirmed(1, 1.seconds, qr("A") to 0, qr("B") to 200))
    }

    @Test
    fun codesLongOutOfViewAreForgotten() {
        val confirmation = BarcodeConfirmation(2, 1.seconds)
        repeat(1000) { confirmation.sighted(qr("code $it"), it * 200_000_000L) }

        assertEquals(5, confirmation.remembered, "codes whose reads are still inside the window")
    }

    @Test
    fun theWindowIsASecondOrTheConfirmationsFrameIntervalsAtALowFrameRate() {
        assertEquals(1.seconds, BarcodeConfirmation.window(confirmations = 2, maxFps = 5f))
        assertEquals(2.seconds, BarcodeConfirmation.window(confirmations = 10, maxFps = 5f))
        assertEquals(4.seconds, BarcodeConfirmation.window(confirmations = 2, maxFps = 0.5f))
    }
}
