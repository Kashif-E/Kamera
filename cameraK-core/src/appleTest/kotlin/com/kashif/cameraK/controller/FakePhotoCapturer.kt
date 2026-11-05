package com.kashif.cameraK.controller

import platform.AVFoundation.AVCaptureFlashMode
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVVideoCodecTypeJPEG
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSThread
import kotlin.concurrent.Volatile

/**
 * A [PhotoCapturer] that records each capture request instead of capturing, so a test can finish the
 * request with whatever outcome it needs through the request's [PhotoCaptureCallbacks].
 */
internal class FakePhotoCapturer : PhotoCapturer {
    class Request(
        val output: AVCapturePhotoOutput,
        val settings: AVCapturePhotoSettings,
        val rotation: VideoRotation,
        val mirrored: Boolean,
        val callbacks: PhotoCaptureCallbacks,
        val queue: String,
        val onMainThread: Boolean,
    ) {
        /** The orientation the photo connection was set to; null when it was turned by an angle. */
        val orientation: AVCaptureVideoOrientation? get() = (rotation as? VideoRotation.Orientation)?.orientation
    }

    private val lock = NSLock()
    private val recorded = mutableListOf<Request>()
    private val maxDimensions = mutableListOf<PhotoDimensions>()
    private val prepared = mutableListOf<List<AVCapturePhotoSettings>>()

    /** When set, every prepare fails with this error, as AVFoundation reports one it could not do. */
    @Volatile
    var prepareError: NSError? = null

    /**
     * The flash modes the output supports with the camera now behind it; null asks the real output,
     * which on the simulator has no camera and supports none.
     */
    @Volatile
    var flashModes: (() -> List<AVCaptureFlashMode>)? = null

    private val flashModeQueues = mutableListOf<String>()
    private val deferredStartOff = mutableListOf<AVCaptureOutput>()

    /** The codecs the output can encode a photo in; JPEG only unless a test adds HEVC. */
    @Volatile
    var codecTypes: List<String> = listOf(AVVideoCodecTypeJPEG!!)

    override fun availablePhotoCodecTypes(output: AVCapturePhotoOutput): List<String> = codecTypes

    /** Every output whose deferred start was turned off, in order. */
    val deferredStartDisabled: List<AVCaptureOutput>
        get() {
            lock.lock()
            try {
                return deferredStartOff.toList()
            } finally {
                lock.unlock()
            }
        }

    override fun disableDeferredStart(output: AVCaptureOutput) {
        lock.lock()
        try {
            deferredStartOff += output
        } finally {
            lock.unlock()
        }
    }

    /** The queue of every supportedFlashModes read, in order. */
    val flashModeReads: List<String>
        get() {
            lock.lock()
            try {
                return flashModeQueues.toList()
            } finally {
                lock.unlock()
            }
        }

    fun clearFlashModeReads() {
        lock.lock()
        try {
            flashModeQueues.clear()
        } finally {
            lock.unlock()
        }
    }

    override fun supportedFlashModes(output: AVCapturePhotoOutput): List<AVCaptureFlashMode> {
        lock.lock()
        try {
            flashModeQueues += currentQueueLabel()
        } finally {
            lock.unlock()
        }
        return flashModes?.invoke() ?: AVPhotoCapturer.supportedFlashModes(output)
    }

    /** The settings of every prepare call, in order. */
    val prepareCalls: List<List<AVCapturePhotoSettings>>
        get() {
            lock.lock()
            try {
                return prepared.toList()
            } finally {
                lock.unlock()
            }
        }

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
        rotation: VideoRotation,
        mirrored: Boolean,
        delegate: AVCapturePhotoCaptureDelegateProtocol,
    ) {
        val request = Request(
            output,
            settings,
            rotation,
            mirrored,
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

    override fun prepare(
        output: AVCapturePhotoOutput,
        settings: List<AVCapturePhotoSettings>,
        completion: (Boolean, NSError?) -> Unit,
    ) {
        lock.lock()
        try {
            prepared += settings
        } finally {
            lock.unlock()
        }
        prepareError?.let { completion(false, it) }
    }
}
