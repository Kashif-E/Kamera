package com.kashif.analyzerPlugin

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.CoreGraphics.CGImageRef
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.UIKit.UIImage
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

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
        setSampleBufferDelegate(analyzer, dispatch_get_main_queue())
        videoSettings = mapOf(
            kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA,
        )
    }

    addOutput(output)

    return AnalyzerHandle {
        output.setSampleBufferDelegate(null, null)
        removeOutput(output)
    }
}

/** Renders a camera frame to a CGImage. The caller owns the returned image (Create rule, +1). */
internal fun interface FrameRenderer {
    @OptIn(ExperimentalForeignApi::class)
    fun render(imageBuffer: CVPixelBufferRef): CGImageRef?
}

internal class CoreImageFrameRenderer : FrameRenderer {
    @OptIn(ExperimentalForeignApi::class)
    override fun render(imageBuffer: CVPixelBufferRef): CGImageRef? {
        val ciImage = CIImage.imageWithCVPixelBuffer(imageBuffer)
        val context = CIContext.contextWithOptions(null)
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
        val uiImage = bufferToUIImage(imageBuffer)

        uiImage?.let {
            onFrameAvailable(it.toByteArray())
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun bufferToUIImage(imageBuffer: CVPixelBufferRef): UIImage? {
        val cgImage = renderer.render(imageBuffer) ?: return null

        return UIImage.imageWithCGImage(cgImage)
    }
}
