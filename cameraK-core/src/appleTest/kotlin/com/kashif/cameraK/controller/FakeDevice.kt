package com.kashif.cameraK.controller

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.AVCaptureExposureMode
import platform.AVFoundation.AVCaptureFocusMode
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
import kotlin.concurrent.Volatile

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
 *
 * What the device reports (the torch and zoom getters, and its key-value observers) follows each set
 * at once, unless [holdReports] is set: then a set reaches only what the device last received
 * ([lastTorchSet], [lastZoomSet]), and the report waits for [report], as the contract kit's driver
 * needs. A factor above [maxAvailableVideoZoomFactor] but inside the format's maximum is reported
 * clamped to it, as AVFoundation does. A zoom set outside [minVideoZoomFactor]..[maxVideoZoomFactor] is recorded in
 * [outOfRangeZoomSets], and a torch mode the device lacks in [unsupportedTorchSets]: AVFoundation
 * raises an exception nothing can catch for either.
 *
 * Focus and exposure: a device that meters at a point ([focusPointSupported], [exposurePointSupported])
 * supports every focus or exposure mode; its bias runs from [minExposureTargetBias] to
 * [maxExposureTargetBias], and a bias set outside it is recorded in [outOfRangeBiasSets]. A bias set
 * reaches [exposureTargetBias] through [biasReadBack] when its completion runs: at once, on the
 * calling thread, unless [holdBiasCompletions] holds it for [completeBiasChanges]. [adjustFocus] plays
 * the device starting or ending a focus, and [subjectAreaChanged] AVFoundation's subject-area
 * notification.
 */
internal class FakeDevice(
    override val isFront: Boolean = false,
    override val uniqueId: String = "fake-camera",
    override val hasTorch: Boolean = true,
    private val supportedTorchModes: Set<AVCaptureTorchMode> =
        setOf(AVCaptureTorchModeOff, AVCaptureTorchModeOn, AVCaptureTorchModeAuto),
    override val maxVideoZoomFactor: Double = 10.0,
    override val supportedMaxPhotoDimensions: List<PhotoDimensions> = emptyList(),
    var lockSucceeds: Boolean = true,
    val input: AVCaptureInput? = captureInputWithoutCamera(),
    /** The error makeInput fails with, as AVFoundation does for a camera the user has not authorised. */
    private val inputError: NSError? = null,
    override val minVideoZoomFactor: Double = 1.0,
    val holdReports: Boolean = false,
    /** The session presets the camera can deliver; null for every preset. */
    var supportedPresets: Set<String>? = null,
    override val isFocusPointOfInterestSupported: Boolean = false,
    override val isExposurePointOfInterestSupported: Boolean = false,
    override var minExposureTargetBias: Float = 0f,
    override var maxExposureTargetBias: Float = 0f,
    override val activeFormatDimensions: PhotoDimensions? = PhotoDimensions(4032, 3024),
    /** What the device refuses its configuration lock with while [lockSucceeds] is false. */
    var lockRefusal: Throwable = IllegalStateException("the device refused its configuration lock"),
) : CaptureDeviceOps {
    class Call(val op: String, val value: Any?, val queue: String, val onMainThread: Boolean, val locked: Boolean) {
        override fun toString(): String = "$op($value) on '$queue'${if (onMainThread) " (main thread)" else ""}" +
            if (locked) " under the configuration lock" else " WITHOUT the configuration lock"
    }

    private val lock = NSLock()
    private val recorded = mutableListOf<Call>()
    private val observers = mutableListOf<() -> Unit>()
    private val outOfRange = mutableListOf<Double>()
    private val unsupportedTorch = mutableListOf<AVCaptureTorchMode>()
    private var locked = false
    private var torch: AVCaptureTorchMode = AVCaptureTorchModeOff
    private var torchActive = false
    private var zoom = 1.0
    private var setTorch: AVCaptureTorchMode? = null
    private var setZoom: Double? = null
    private var adjusting = false
    private var bias = 0f
    private var setBias: Float? = null
    private val outOfRangeBias = mutableListOf<Float>()
    private val heldBiasChanges = mutableListOf<() -> Unit>()
    private val focusObservers = mutableListOf<() -> Unit>()
    private val subjectAreaObservers = mutableListOf<() -> Unit>()

    /** Bias set completions wait for [completeBiasChanges]. */
    @Volatile
    var holdBiasCompletions: Boolean = false

    /** The bias the device reports once a set completes, from the one it was given. */
    @Volatile
    var biasReadBack: (Float) -> Float = { it }

    /** Below [maxVideoZoomFactor] on a virtual camera delivering depth data; tests lower it. */
    @Volatile
    override var maxAvailableVideoZoomFactor: Double = maxVideoZoomFactor

    val calls: List<Call> get() = guarded { recorded.toList() }
    val isLocked: Boolean get() = guarded { locked }
    override val torchMode: AVCaptureTorchMode get() = guarded { torch }
    override val isTorchActive: Boolean get() = guarded { torchActive }
    override val videoZoomFactor: Double get() = guarded { zoom }

    /** The torch mode and zoom factor the device was last given; null before the first. */
    val lastTorchSet: AVCaptureTorchMode? get() = guarded { setTorch }
    val lastZoomSet: Double? get() = guarded { setZoom }

    /** Every zoom factor set outside the device's range. */
    val outOfRangeZoomSets: List<Double> get() = guarded { outOfRange.toList() }

    /** Every torch mode set on a device without a torch, or one its torch does not support. */
    val unsupportedTorchSets: List<AVCaptureTorchMode> get() = guarded { unsupportedTorch.toList() }

    /** Key-value observers registered and not yet closed. */
    val hardwareObservers: Int get() = guarded { observers.size }

    override fun isTorchModeSupported(mode: AVCaptureTorchMode): Boolean = mode in supportedTorchModes

    override fun supportsSessionPreset(preset: String): Boolean = supportedPresets?.contains(preset) ?: true

    override fun lockForConfiguration(): Throwable? = if (lockSucceeds) {
        record("lockForConfiguration", null) { locked = true }
        null
    } else {
        record("lockForConfiguration (refused)", null) {}
        lockRefusal
    }

    override fun unlockForConfiguration() = record("unlockForConfiguration", null) { locked = false }

    override fun setTorchMode(mode: AVCaptureTorchMode) {
        record("setTorchMode", mode) {
            setTorch = mode
            if (!hasTorch || mode !in supportedTorchModes) unsupportedTorch += mode
        }
        if (!holdReports) report(torch = mode)
    }

    override fun setVideoZoomFactor(factor: Double) {
        record("setVideoZoomFactor", factor) {
            setZoom = factor
            if (factor < minVideoZoomFactor || factor > maxVideoZoomFactor) outOfRange += factor
        }
        if (!holdReports) report(zoom = factor.coerceAtMost(maxAvailableVideoZoomFactor))
    }

    override fun observeHardware(onChange: () -> Unit): AutoCloseable {
        guarded { observers += onChange }
        return AutoCloseable { guarded { observers -= onChange } }
    }

    /** The device reports [torch] (lit when on) and [zoom] to its observers; null leaves a value alone. */
    fun report(torch: AVCaptureTorchMode? = null, zoom: Double? = null) {
        val notify = guarded {
            torch?.let {
                this.torch = it
                torchActive = it == AVCaptureTorchModeOn
            }
            zoom?.let { this.zoom = it }
            observers.toList()
        }
        notify.forEach { it() }
    }

    /** The system turns a lit torch off to protect the device: torchMode stays on, the torch goes dark. */
    fun thermalShutoff() {
        val notify = guarded {
            torchActive = false
            observers.toList()
        }
        notify.forEach { it() }
    }

    override val isAdjustingFocus: Boolean get() = guarded { adjusting }
    override val exposureTargetBias: Float get() = guarded { bias }

    /** The bias the device was last given; null before the first. */
    val lastBiasSet: Float? get() = guarded { setBias }

    /** Every bias set outside the device's range. */
    val outOfRangeBiasSets: List<Float> get() = guarded { outOfRangeBias.toList() }

    /** Focus and subject-area observers registered and not yet closed. */
    val focusObserverCount: Int get() = guarded { focusObservers.size }
    val subjectAreaObserverCount: Int get() = guarded { subjectAreaObservers.size }

    override fun isFocusModeSupported(mode: AVCaptureFocusMode): Boolean = isFocusPointOfInterestSupported

    override fun isExposureModeSupported(mode: AVCaptureExposureMode): Boolean = isExposurePointOfInterestSupported

    override fun setFocusPointOfInterest(point: PointOfInterest) = record("setFocusPointOfInterest", point) {}

    override fun setFocusMode(mode: AVCaptureFocusMode) = record("setFocusMode", mode) {}

    override fun setExposurePointOfInterest(point: PointOfInterest) = record("setExposurePointOfInterest", point) {}

    override fun setExposureMode(mode: AVCaptureExposureMode) = record("setExposureMode", mode) {}

    override fun setSubjectAreaChangeMonitoringEnabled(enabled: Boolean) =
        record("setSubjectAreaChangeMonitoringEnabled", enabled) {}

    override fun setExposureTargetBias(bias: Float, completion: () -> Unit) {
        val complete = {
            guarded { this.bias = biasReadBack(bias) }
            completion()
        }
        val held = record("setExposureTargetBias", bias) {
            setBias = bias
            if (bias < minExposureTargetBias || bias > maxExposureTargetBias) outOfRangeBias += bias
            if (holdBiasCompletions) heldBiasChanges += complete
            holdBiasCompletions
        }
        if (!held) complete()
    }

    /** Runs the bias completions held so far, in order. */
    fun completeBiasChanges() {
        val held = guarded { heldBiasChanges.toList().also { heldBiasChanges.clear() } }
        held.forEach { it() }
    }

    override fun observeAdjustingFocus(onChange: () -> Unit): AutoCloseable {
        guarded { focusObservers += onChange }
        return AutoCloseable { guarded { focusObservers -= onChange } }
    }

    override fun observeSubjectAreaChange(onChange: () -> Unit): AutoCloseable {
        guarded { subjectAreaObservers += onChange }
        return AutoCloseable { guarded { subjectAreaObservers -= onChange } }
    }

    /** The device starts ([adjusting] true) or ends a focus, and tells its observers. */
    fun adjustFocus(adjusting: Boolean) {
        val notify = guarded {
            this.adjusting = adjusting
            focusObservers.toList()
        }
        notify.forEach { it() }
    }

    /** AVFoundation posts AVCaptureDeviceSubjectAreaDidChangeNotification for this device. */
    fun subjectAreaChanged() = guarded { subjectAreaObservers.toList() }.forEach { it() }

    override fun makeInput(): Result<AVCaptureInput> = when {
        inputError != null -> Result.failure(NSErrorException(inputError))
        input != null -> Result.success(input)
        else -> Result.failure(IllegalStateException("this fake camera has no input"))
    }

    fun callsOf(op: String): List<Call> = calls.filter { it.op == op }

    private fun <T> record(op: String, value: Any?, mutate: () -> T): T = guarded {
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
