package com.kashif.cameraK.controller

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureMetadataInput
import platform.AVFoundation.AVCaptureTorchMode
import platform.AVFoundation.AVCaptureTorchModeAuto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.CoreFoundation.CFArrayRef
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreMedia.CMClockGetHostTimeClock
import platform.CoreMedia.CMMetadataFormatDescriptionCreateWithMetadataSpecifications
import platform.CoreMedia.CMMetadataFormatDescriptionRefVar
import platform.CoreMedia.kCMMetadataBaseDataType_UTF8
import platform.CoreMedia.kCMMetadataFormatDescriptionMetadataSpecificationKey_DataType
import platform.CoreMedia.kCMMetadataFormatDescriptionMetadataSpecificationKey_Identifier
import platform.CoreMedia.kCMMetadataFormatType_Boxed
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSThread

/**
 * A real [AVCaptureInput] that needs no camera: an AVCaptureMetadataInput over a text metadata
 * format. Sessions in tests only store inputs and compare them by identity, so any input will do.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun captureInputWithoutCamera(): AVCaptureInput = memScoped {
    fun key(ref: CFStringRef?): String = CFBridgingRelease(CFRetain(ref)) as String
    val specification = mapOf<Any?, Any?>(
        key(kCMMetadataFormatDescriptionMetadataSpecificationKey_Identifier) to "mdta/com.kashif.cameraK.test",
        key(kCMMetadataFormatDescriptionMetadataSpecificationKey_DataType) to key(kCMMetadataBaseDataType_UTF8),
    )
    val specifications = CFBridgingRetain(listOf(specification))
    val description = alloc<CMMetadataFormatDescriptionRefVar>()
    try {
        val status = CMMetadataFormatDescriptionCreateWithMetadataSpecifications(
            null,
            kCMMetadataFormatType_Boxed,
            specifications as CFArrayRef?,
            description.ptr,
        )
        check(status == 0) { "CMMetadataFormatDescriptionCreateWithMetadataSpecifications failed: $status" }
    } finally {
        CFBridgingRelease(specifications)
    }
    AVCaptureMetadataInput.metadataInputWithFormatDescription(description.value, CMClockGetHostTimeClock())
}

/**
 * A [CaptureDeviceOps] that records every call with the queue it ran on and whether the
 * configuration lock was held, standing in for a camera on the simulator.
 */
internal class FakeDevice(
    override val isFront: Boolean = false,
    override val hasTorch: Boolean = true,
    private val supportedTorchModes: Set<AVCaptureTorchMode> =
        setOf(AVCaptureTorchModeOff, AVCaptureTorchModeOn, AVCaptureTorchModeAuto),
    override val maxVideoZoomFactor: Double = 10.0,
    override val supportedMaxPhotoDimensions: List<PhotoDimensions> = emptyList(),
    var lockSucceeds: Boolean = true,
    val input: AVCaptureInput? = captureInputWithoutCamera(),
    /** The error makeInput fails with, as AVFoundation does for a camera the user has not authorised. */
    private val inputError: NSError? = null,
) : CaptureDeviceOps {
    class Call(val op: String, val value: Any?, val queue: String, val onMainThread: Boolean, val locked: Boolean) {
        override fun toString(): String = "$op($value) on '$queue'${if (onMainThread) " (main thread)" else ""}" +
            if (locked) " under the configuration lock" else " WITHOUT the configuration lock"
    }

    private val lock = NSLock()
    private val recorded = mutableListOf<Call>()
    private var locked = false
    private var torch: AVCaptureTorchMode = AVCaptureTorchModeOff
    private var zoom = 1.0

    val calls: List<Call> get() = guarded { recorded.toList() }
    val isLocked: Boolean get() = guarded { locked }
    override val torchMode: AVCaptureTorchMode get() = guarded { torch }
    override val videoZoomFactor: Double get() = guarded { zoom }

    override fun isTorchModeSupported(mode: AVCaptureTorchMode): Boolean = mode in supportedTorchModes

    override fun lockForConfiguration(): Throwable? = if (lockSucceeds) {
        record("lockForConfiguration", null) { locked = true }
        null
    } else {
        record("lockForConfiguration (refused)", null) {}
        IllegalStateException("the device refused its configuration lock")
    }

    override fun unlockForConfiguration() = record("unlockForConfiguration", null) { locked = false }

    override fun setTorchMode(mode: AVCaptureTorchMode) = record("setTorchMode", mode) { torch = mode }

    override fun setVideoZoomFactor(factor: Double) = record("setVideoZoomFactor", factor) { zoom = factor }

    override fun makeInput(): Result<AVCaptureInput> = when {
        inputError != null -> Result.failure(NSErrorException(inputError))
        input != null -> Result.success(input)
        else -> Result.failure(IllegalStateException("this fake camera has no input"))
    }

    fun callsOf(op: String): List<Call> = calls.filter { it.op == op }

    private fun record(op: String, value: Any?, mutate: () -> Unit) = guarded {
        recorded += Call(op, value, currentQueueLabel(), NSThread.isMainThread, locked)
        mutate()
    }

    private fun <T> guarded(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
