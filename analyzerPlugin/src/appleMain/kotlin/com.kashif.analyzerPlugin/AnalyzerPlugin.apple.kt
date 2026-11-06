package com.kashif.analyzerPlugin

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.UIKit.UIImage
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile

@OptIn(ExperimentalForeignApi::class)
actual fun startAnalyzer(cameraController: CameraController, onFrameAvailable: (ByteArray) -> Unit): AnalyzerHandle {
    val analyzer = CameraAnalyzer(onFrameAvailable = onFrameAvailable)

    // Frames are converted on a serial background queue (like Android), never on the main thread.
    val output = AVCaptureVideoDataOutput().apply {
        setSampleBufferDelegate(analyzer, dispatch_queue_create("com.kashif.analyzerPlugin.videoQueue", null))
        videoSettings = mapOf(
            kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA,
        )
    }

    // The capture session may not exist yet when Ready fires, so queue the add until it does.
    cameraController.queueConfigurationChange {
        cameraController.safeAddOutput(output)
    }

    // The handle holds the analyzer, which keeps the delegate alive while the output is attached.
    return object : AnalyzerHandle {
        override fun stop() {
            analyzer.stopped = true
            output.setSampleBufferDelegate(null, null)
            cameraController.safeRemoveOutput(output)
        }
    }
}

internal class CameraAnalyzer(private val onFrameAvailable: (ByteArray) -> Unit) :
    NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    // Creating a CIContext is expensive, so one is reused for every frame.
    private val ciContext = CIContext.contextWithOptions(null)

    // Set when the handle stops, so a frame still being converted isn't delivered afterwards.
    @Volatile
    var stopped = false

    @OptIn(ExperimentalForeignApi::class)
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        val buffer = didOutputSampleBuffer ?: return

        // Drain the per-frame UIImage and JPEG data now instead of letting them pile up on the queue.
        val frame = autoreleasepool {
            bufferToUIImage(buffer)?.toByteArray()
        } ?: return

        if (!stopped) onFrameAvailable(frame)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun bufferToUIImage(sampleBuffer: CMSampleBufferRef): UIImage? {
        val imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) ?: return null

        val ciImage = CIImage.imageWithCVPixelBuffer(imageBuffer)
        val cgImage = ciContext.createCGImage(ciImage, ciImage.extent) ?: return null

        // createCGImage returns an owned reference; the UIImage retains its own.
        return try {
            UIImage.imageWithCGImage(cgImage)
        } finally {
            CGImageRelease(cgImage)
        }
    }
}
