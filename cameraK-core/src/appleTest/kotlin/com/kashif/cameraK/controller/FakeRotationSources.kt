package com.kashif.cameraK.controller

import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.Foundation.NSLock
import kotlin.concurrent.Volatile

/**
 * Rotation coordinators for fake cameras: every source answers [captureAngle] and [previewAngle], and
 * [turnPreview] reports a new preview angle to each source still open, as the coordinator's
 * key-value observation would.
 */
internal class FakeRotationSources(
    @Volatile var captureAngle: Double = 90.0,
    @Volatile var previewAngle: Double = 90.0,
) : RotationSourceFactory {
    inner class Source(
        val device: CaptureDeviceOps,
        val previewLayer: AVCaptureVideoPreviewLayer?,
        private val onPreviewAngle: (Double) -> Unit,
    ) : RotationSource {
        @Volatile
        var closed = false
            private set

        override val captureAngle: Double get() = this@FakeRotationSources.captureAngle
        override val previewAngle: Double get() = this@FakeRotationSources.previewAngle

        override fun close() {
            closed = true
        }

        fun report(angle: Double) {
            if (!closed) onPreviewAngle(angle)
        }
    }

    private val lock = NSLock()
    private val madeList = mutableListOf<Source>()

    /** Every source the engine made, in order. */
    val made: List<Source>
        get() {
            lock.lock()
            try {
                return madeList.toList()
            } finally {
                lock.unlock()
            }
        }

    override fun make(
        device: CaptureDeviceOps,
        previewLayer: AVCaptureVideoPreviewLayer?,
        onPreviewAngle: (Double) -> Unit,
    ): RotationSource {
        val source = Source(device, previewLayer, onPreviewAngle)
        lock.lock()
        try {
            madeList += source
        } finally {
            lock.unlock()
        }
        return source
    }

    /** The preview angle becomes [degrees], and each open source reports it. */
    fun turnPreview(degrees: Double) {
        previewAngle = degrees
        made.forEach { it.report(degrees) }
    }
}
