package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.toNSData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cancelling takePictureToFile does not stop AVFoundation: the photo output keeps working on the
 * request until its last callback. The cancelled capture keeps its slot until then and gives it back
 * exactly once, so the count of captures in flight never drifts below zero.
 */
class CaptureCancellationTest {
    private val rig = CaptureRig()

    @AfterTest
    fun tearDown() = rig.tearDown()

    @Test
    fun aCancelledCaptureHoldsItsSlotUntilAVFoundationFinishesAndReleasesItOnce() {
        rig.start()
        val (capture, request) = rig.capture()

        capture.job.cancel()
        awaitTrue("the cancelled capture to return") { capture.job.isCompleted }
        drainSessionThenMain(rig.custom)
        assertEquals(
            1,
            rig.controller.capturesInFlight,
            "a cancelled capture gave up its slot while AVFoundation was still working on it",
        )

        request.callbacks.photoProcessed(ByteArray(16) { it.toByte() }.toNSData(), null)
        request.callbacks.captureFinished(null)
        awaitTrue("AVFoundation's last callback to release the slot") { rig.controller.capturesInFlight <= 0 }
        drainSessionThenMain(rig.custom)

        assertEquals(0, rig.controller.capturesInFlight, "the cancelled capture released its slot more than once")
        assertTrue(capture.result == null, "a cancelled capture returned a result")
    }
}
