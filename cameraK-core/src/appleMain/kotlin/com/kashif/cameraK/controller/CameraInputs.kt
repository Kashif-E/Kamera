@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.controller.CustomCameraController.CameraException
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import platform.AVFoundation.*
import kotlin.concurrent.Volatile

private const val TAG = "CameraK"

/**
 * A controller's cameras and the session's camera input: the cameras setup finds, the one in use and
 * its facing, and the lens and device-type switches that swap the input on the session queue. A swap
 * hands the new camera to [controls], [stills] and [preview], which follow it.
 */
internal class CameraInputs(
    private val queue: SessionQueue,
    private val session: () -> CaptureSessionOps?,
    private val inputConfigurator: ((CaptureSessionOps, CameraDeviceType) -> Boolean)?,
    private val lensSwitcher: ((CaptureSessionOps, Boolean) -> Unit)?,
    private val cameraDevices: CameraDeviceProvider,
    private val controls: CameraControls,
    private val stills: StillCapture,
    private val preview: PreviewLayers,
    private val reportError: (CameraException) -> Unit,
) {
    private var backCamera: CaptureDeviceOps? = null
    private var frontCamera: CaptureDeviceOps? = null

    /** The camera in use: the one the session's video input captures from. */
    @Volatile
    var currentCamera: CaptureDeviceOps? = null
        private set

    /** The camera's input in the session, so a switch removes it and nothing else. Session queue only. */
    var videoInput: AVCaptureInput? = null
        private set

    @Volatile
    var isUsingFrontCamera = false
        private set

    /** Whether a recording is in progress; the engine answers from its recorder. */
    @Volatile
    var recordingInProgress: () -> Boolean = { false }

    /**
     * Enumerates every built-in lens (wide, telephoto, ultra-wide) on both positions.
     */
    fun lenses(): List<LensInfo> =
        discoverDevices(allLensDeviceTypes(), AVCaptureDevicePositionUnspecified).map { device ->
            LensInfo(
                id = device.uniqueID,
                deviceType = when (device.deviceType) {
                    AVCaptureDeviceTypeBuiltInUltraWideCamera -> CameraDeviceType.ULTRA_WIDE
                    AVCaptureDeviceTypeBuiltInTelephotoCamera -> CameraDeviceType.TELEPHOTO
                    AVCaptureDeviceTypeBuiltInWideAngleCamera -> CameraDeviceType.WIDE_ANGLE
                    else -> CameraDeviceType.DEFAULT
                },
                lens = if (device.position == AVCaptureDevicePositionFront) CameraLens.FRONT else CameraLens.BACK,
                minZoom = device.minAvailableVideoZoomFactor.toFloat(),
                maxZoom = device.maxAvailableVideoZoomFactor.toFloat(),
                hasFlash = device.hasFlash,
                isLogical = false,
            )
        }

    /**
     * Adds the initial camera input to [ops] inside the setup transaction: [device], or the camera [lens]
     * and [cameraDeviceType] find, or the input configurator's. Returns false when there is none.
     */
    fun setUp(
        ops: CaptureSessionOps,
        lens: CameraLens,
        cameraDeviceType: CameraDeviceType,
        device: CaptureDeviceOps?,
    ): Boolean {
        inputConfigurator?.let { return it(ops, cameraDeviceType) }
        val camera = if (device != null) {
            if (device.isFront) frontCamera = device else backCamera = device
            device
        } else {
            backCamera = cameraDevices.find(CameraLens.BACK, cameraDeviceType)
            frontCamera = cameraDevices.find(CameraLens.FRONT, cameraDeviceType)
            when (lens) {
                CameraLens.FRONT -> frontCamera ?: backCamera
                CameraLens.BACK -> backCamera ?: frontCamera
            } ?: cameraDevices.find(null, cameraDeviceType)?.also { found ->
                if (found.isFront) frontCamera = found else backCamera = found
            }
        } ?: return false

        currentCamera = camera
        controls.forgetRequestedZoom()
        isUsingFrontCamera = camera.isFront

        val input = camera.makeInput().getOrElse { throw cameraInputFailure(it) }
        return if (ops.canAddInput(input)) {
            ops.addInput(input)
            videoInput = input
            true
        } else {
            false
        }
    }

    /** Forgets every camera and the input with the released session. Session queue only. */
    fun releaseOnQueue() {
        currentCamera = null
        videoInput = null
        backCamera = null
        frontCamera = null
    }

    /**
     * The camera facing [lens] of [type], else the one facing the other way, else any camera of that
     * type: the order setup has always picked in.
     */
    fun findCamera(lens: CameraLens, type: CameraDeviceType): CaptureDeviceOps? {
        val other = if (lens == CameraLens.FRONT) CameraLens.BACK else CameraLens.FRONT
        return cameraDevices.find(lens, type) ?: cameraDevices.find(other, type) ?: cameraDevices.find(null, type)
    }

    fun findCameraById(id: String): CaptureDeviceOps? = cameraDevices.findById(id)

    /**
     * Moves the session to [camera]'s input in one transaction, as a lens switch does: the session
     * keeps running and the configured torch follows. Session queue only; returns why it failed.
     */
    fun useDeviceOnQueue(camera: CaptureDeviceOps): CameraException? {
        val ops = session() ?: return CameraException.DeviceNotAvailable("The session is not set up")
        swapVideoInput(ops, camera)?.let { return it }
        if (camera.isFront) frontCamera = camera else backCamera = camera
        useCamera(camera)
        if (ops.isRunning) controls.applyConfiguredTorch()
        return null
    }

    /**
     * Switches to a specific camera device type (e.g. wide-angle, telephoto, ultra-wide)
     * while keeping the same camera position (front/back).
     */
    fun switchToDeviceType(deviceType: CameraDeviceType) {
        queue.async {
            val ops = session() ?: return@async
            if (deviceType.toAVCaptureDeviceType() == null) return@async
            if (refusedWhileRecording("change the camera device type")) return@async

            // The requested type facing the current way, falling back to either way
            val lens = if (isUsingFrontCamera) CameraLens.FRONT else CameraLens.BACK
            val newDevice = cameraDevices.find(lens, deviceType)
                ?: cameraDevices.find(null, deviceType)
                ?: return@async

            val failure = swapVideoInput(ops, newDevice)
            if (failure != null) {
                CameraKLogger.e(TAG, "switching camera device type failed", failure)
                reportError(failure)
                return@async
            }
            if (newDevice.isFront) frontCamera = newDevice else backCamera = newDevice
            useCamera(newDevice)
            if (ops.isRunning) controls.applyConfiguredTorch()
        }
    }

    fun switchCamera() {
        queue.async {
            val ops = session() ?: return@async
            if (refusedWhileRecording("switch the lens")) return@async
            val toFront = !isUsingFrontCamera

            val switcher = lensSwitcher
            if (switcher != null) {
                ops.beginConfiguration()
                try {
                    videoInput?.let(ops::removeInput)
                    switcher(ops, toFront)
                } finally {
                    ops.commitConfiguration()
                }
                isUsingFrontCamera = toFront
                preview.mirrorOnMain(isUsingFrontCamera)
                return@async
            }

            val camera = if (toFront) frontCamera else backCamera
            val failure = if (camera == null) CameraException.DeviceNotAvailable() else swapVideoInput(ops, camera)
            if (failure != null) {
                CameraKLogger.e(TAG, "switching camera failed", failure)
                reportError(failure)
                return@async
            }
            useCamera(camera!!)
            if (ops.isRunning) controls.applyConfiguredTorch()
        }
    }

    /**
     * Refuses a lens change while a recording runs: replacing the camera input cuts the movie file
     * output's video connection and ends the recording. Runs on the session queue.
     */
    private fun refusedWhileRecording(operation: String): Boolean {
        if (!recordingInProgress()) return false
        CameraKLogger.w(TAG, "cannot $operation while recording; stop the recording first")
        reportError(CameraException.ConfigurationError("Cannot $operation while recording"))
        return true
    }

    /**
     * Replaces the camera input with [camera]'s in one configuration transaction. AVFoundation swaps
     * inputs on a running session, so the session keeps running and only the camera input is
     * touched (a microphone stays). A refused input puts the previous one back. Runs on the session
     * queue; returns why the swap failed, or null.
     */
    private fun swapVideoInput(ops: CaptureSessionOps, camera: CaptureDeviceOps): CameraException? {
        val newInput = camera.makeInput().getOrElse { return cameraInputFailure(it) }
        val previous = videoInput
        ops.beginConfiguration()
        try {
            previous?.let(ops::removeInput)
            if (ops.canAddInput(newInput)) {
                ops.addInput(newInput)
                videoInput = newInput
                return null
            }
            if (previous != null && ops.canAddInput(previous)) {
                ops.addInput(previous)
            }
            return CameraException.ConfigurationError("Cannot add input")
        } finally {
            ops.commitConfiguration()
        }
    }

    /** Why a camera's input could not be made, keeping what AVFoundation reported as the cause. */
    private fun cameraInputFailure(cause: Throwable): CameraException.ConfigurationError {
        val reported = (cause as? NSErrorException)?.error?.let { ": ${it.summary}" }.orEmpty()
        return CameraException.ConfigurationError("Could not open the camera$reported", cause)
    }

    /** Records [camera] as the one in use after a successful swap. Runs on the session queue. */
    private fun useCamera(camera: CaptureDeviceOps) {
        currentCamera = camera
        controls.forgetRequestedZoom()
        isUsingFrontCamera = camera.isFront
        preview.mirrorOnMain(isUsingFrontCamera)
        stills.applyMaxPhotoDimensions()
        stills.prepareOnQueue()
    }
}
