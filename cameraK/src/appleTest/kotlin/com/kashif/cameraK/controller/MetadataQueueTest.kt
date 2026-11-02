package com.kashif.cameraK.controller

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_get_label
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * A scanner's metadata callbacks run on a serial queue of CameraK's own, not on main, where they
 * would compete with the UI for every frame AVFoundation scans.
 */
@OptIn(ExperimentalForeignApi::class)
class MetadataQueueTest {
    private class Scanner :
        NSObject(),
        AVCaptureMetadataOutputObjectsDelegateProtocol

    private val rig = CaptureRig()

    @AfterTest
    fun tearDown() = rig.tearDown()

    @Test
    fun metadataCallbacksRunOnASerialQueueOffMain() {
        rig.start()
        val scanner = Scanner()

        rig.controller.setMetadataObjectsDelegate(scanner)
        drainSessionThenMain(rig.custom)

        val output = assertNotNull(
            rig.session.outputs.filterIsInstance<AVCaptureMetadataOutput>().singleOrNull(),
            "setting a scanner did not attach the metadata output",
        )
        assertSame(scanner, output.metadataObjectsDelegate, "the scanner is not the output's delegate")
        val queue = assertNotNull(output.metadataObjectsCallbackQueue, "the output has no callback queue")
        val label = dispatch_queue_get_label(queue)?.toKString().orEmpty()
        assertFalse(label == "com.apple.main-thread", "metadata callbacks run on the main queue")
        // Global queues are concurrent: AVFoundation's callbacks for successive frames could overlap.
        assertFalse(label.startsWith("com.apple.root."), "metadata callbacks run on a concurrent global queue: $label")
    }
}
