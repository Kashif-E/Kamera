package com.kashif.analyzerPlugin

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create

/** Frames arrive, are converted and reach [onFrameAvailable] on a private serial queue, never main. */
actual fun startAnalyzer(cameraController: CameraController, onFrameAvailable: (ByteArray) -> Unit): AnalyzerHandle =
    startAnalyzer(
        addOutput = cameraController::safeAddOutput,
        removeOutput = cameraController::safeRemoveOutput,
        onFrameAvailable = onFrameAvailable,
    )

@OptIn(ExperimentalForeignApi::class)
internal fun startAnalyzer(
    addOutput: (AVCaptureOutput) -> Unit,
    removeOutput: (AVCaptureOutput) -> Unit,
    onFrameAvailable: (ByteArray) -> Unit,
    renderer: FrameRenderer = CoreImageFrameRenderer(),
): AnalyzerHandle {
    val analyzer = CameraAnalyzer(onFrameAvailable = onFrameAvailable, renderer = renderer)

    val output = AVCaptureVideoDataOutput().apply {
        setSampleBufferDelegate(analyzer, dispatch_queue_create("com.kashif.analyzerPlugin.frames", null))
        videoSettings = mapOf(
            kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA,
        )
    }

    addOutput(output)

    return AppleAnalyzerHandle(output, analyzer, removeOutput)
}

/**
 * AVCaptureVideoDataOutput holds its delegate weakly, so the handle keeps the analyzer alive until
 * [stop]; without it the analyzer is collected and frames stop arriving.
 */
private class AppleAnalyzerHandle(
    private val output: AVCaptureVideoDataOutput,
    private var analyzer: CameraAnalyzer?,
    private val removeOutput: (AVCaptureOutput) -> Unit,
) : AnalyzerHandle {
    override fun stop() {
        output.setSampleBufferDelegate(null, null)
        analyzer = null
        removeOutput(output)
    }
}

/** Renders a camera frame to a CGImage. The caller owns the returned image (Create rule, +1). */
internal fun interface FrameRenderer {
    @OptIn(ExperimentalForeignApi::class)
    fun render(imageBuffer: CVPixelBufferRef): CGImageRef?
}

/** Renders through one CIContext for the analyzer's lifetime; a context is too heavy to build per frame. */
internal class CoreImageFrameRenderer : FrameRenderer {
    private val context = CIContext.contextWithOptions(null)

    @OptIn(ExperimentalForeignApi::class)
    override fun render(imageBuffer: CVPixelBufferRef): CGImageRef? {
        val ciImage = CIImage.imageWithCVPixelBuffer(imageBuffer)
        return context.createCGImage(ciImage, ciImage.extent)
    }
}

internal class CameraAnalyzer(
    private val onFrameAvailable: (ByteArray) -> Unit,
    private val renderer: FrameRenderer = CoreImageFrameRenderer(),
) : NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol {

    @OptIn(ExperimentalForeignApi::class)
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        val buffer = didOutputSampleBuffer ?: return
        val imageBuffer = CMSampleBufferGetImageBuffer(buffer) ?: return
        analyze(imageBuffer)
    }

    /** Converts one camera frame and hands the encoded image to the consumer. */
    @OptIn(ExperimentalForeignApi::class)
    internal fun analyze(imageBuffer: CVPixelBufferRef) {
        val jpeg = autoreleasepool {
            val cgImage = renderer.render(imageBuffer) ?: run {
                CameraKLogger.w("CameraK", "Analyzer: rendering a frame failed; frame skipped")
                return
            }
            try {
                UIImageJPEGRepresentation(UIImage.imageWithCGImage(cgImage), 1.0)?.toByteArray()
            } finally {
                CGImageRelease(cgImage)
            }
        }
        if (jpeg == null) {
            CameraKLogger.w("CameraK", "Analyzer: JPEG encoding of a frame failed; frame skipped")
            return
        }
        onFrameAvailable(jpeg)
    }
}
