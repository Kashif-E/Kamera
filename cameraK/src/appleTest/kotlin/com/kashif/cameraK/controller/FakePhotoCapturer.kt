package com.kashif.cameraK.controller

import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.Foundation.NSLock
import platform.Foundation.NSThread

/**
 * A [PhotoCapturer] that records each capture request instead of capturing, so a test can finish the
 * request with whatever outcome it needs through the request's [PhotoCaptureCallbacks].
 */
internal class FakePhotoCapturer : PhotoCapturer {
    class Request(
        val output: AVCapturePhotoOutput,
        val settings: AVCapturePhotoSettings,
        val callbacks: PhotoCaptureCallbacks,
        val queue: String,
        val onMainThread: Boolean,
    )

    private val lock = NSLock()
    private val recorded = mutableListOf<Request>()
    private val maxDimensions = mutableListOf<PhotoDimensions>()

    /** Every size setMaxPhotoDimensions was given, in order. */
    val maxPhotoDimensionsSet: List<PhotoDimensions>
        get() {
            lock.lock()
            try {
                return maxDimensions.toList()
            } finally {
                lock.unlock()
            }
        }

    val requests: List<Request>
        get() {
            lock.lock()
            try {
                return recorded.toList()
            } finally {
                lock.unlock()
            }
        }

    override fun capture(
        output: AVCapturePhotoOutput,
        settings: AVCapturePhotoSettings,
        delegate: AVCapturePhotoCaptureDelegateProtocol,
    ) {
        val request = Request(
            output,
            settings,
            (delegate as PhotoCaptureDelegate).callbacks,
            currentQueueLabel(),
            NSThread.isMainThread,
        )
        lock.lock()
        try {
            recorded += request
        } finally {
            lock.unlock()
        }
    }

    override fun setMaxPhotoDimensions(output: AVCapturePhotoOutput, dimensions: PhotoDimensions) {
        lock.lock()
        try {
            maxDimensions += dimensions
        } finally {
            lock.unlock()
        }
    }
}
