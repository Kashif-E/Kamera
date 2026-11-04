package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.toNSData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cancelling takePictureToFile does not stop AVFoundation: the photo output keeps working on the
 * request until its last callback. AVFoundation does not retain a capture's delegate, so the
 * cancelled capture's delegate is kept alive until then and released exactly once, and the cancelled
 * capture returns nothing.
 */
class CaptureCancellationTest {
    private val rig = CaptureRig()

    @AfterTest
    fun tearDown() = rig.tearDown()

    @Test
    fun aCancelledCapturesDelegateLivesUntilAVFoundationFinishesAndIsReleasedOnce() {
        rig.start()
        val (capture, request) = rig.capture()

        capture.job.cancel()
        awaitTrue("the cancelled capture to return") { capture.job.isCompleted }
        drainSessionThenMain(rig.custom)
        assertEquals(
            1,
            rig.custom.outstandingPhotoDelegates,
            "a cancelled capture's delegate was released while AVFoundation was still working on it",
        )

        request.callbacks.photoProcessed(ByteArray(16) { it.toByte() }.toNSData(), null)
        request.callbacks.captureFinished(null)
        awaitTrue("AVFoundation's last callback to release the delegate") { rig.custom.outstandingPhotoDelegates <= 0 }
        drainSessionThenMain(rig.custom)

        assertEquals(0, rig.custom.outstandingPhotoDelegates, "the cancelled capture's delegate was released twice")
        assertTrue(capture.result == null, "a cancelled capture returned a result")
    }
}
