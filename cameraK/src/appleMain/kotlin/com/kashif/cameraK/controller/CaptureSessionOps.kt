package com.kashif.cameraK.controller

import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession

/**
 * The capture-session operations [CustomCameraController] performs. Production wraps an
 * [AVCaptureSession]; tests substitute an implementation that records which queue each call ran on,
 * since the simulator has no camera to drive a real session with.
 */
internal interface CaptureSessionOps {
    /** The AVFoundation session behind these operations, for the preview layer; null when there is none. */
    val avSession: AVCaptureSession?

    val isRunning: Boolean
    val inputs: List<AVCaptureInput>
    val outputs: List<AVCaptureOutput>

    fun beginConfiguration()

    fun commitConfiguration()

    fun setSessionPreset(preset: String)

    fun canAddInput(input: AVCaptureInput): Boolean

    fun addInput(input: AVCaptureInput)

    fun removeInput(input: AVCaptureInput)

    fun canAddOutput(output: AVCaptureOutput): Boolean

    fun addOutput(output: AVCaptureOutput)

    fun removeOutput(output: AVCaptureOutput)

    fun startRunning()

    fun stopRunning()
}

internal class AVCaptureSessionOps(override val avSession: AVCaptureSession = AVCaptureSession()) : CaptureSessionOps {
    override val isRunning: Boolean get() = avSession.isRunning()
    override val inputs: List<AVCaptureInput> get() = avSession.inputs.filterIsInstance<AVCaptureInput>()
    override val outputs: List<AVCaptureOutput> get() = avSession.outputs.filterIsInstance<AVCaptureOutput>()

    override fun beginConfiguration() = avSession.beginConfiguration()

    override fun commitConfiguration() = avSession.commitConfiguration()

    override fun setSessionPreset(preset: String) {
        avSession.sessionPreset = preset
    }

    override fun canAddInput(input: AVCaptureInput): Boolean = avSession.canAddInput(input)

    override fun addInput(input: AVCaptureInput) = avSession.addInput(input)

    override fun removeInput(input: AVCaptureInput) = avSession.removeInput(input)

    override fun canAddOutput(output: AVCaptureOutput): Boolean = avSession.canAddOutput(output)

    override fun addOutput(output: AVCaptureOutput) = avSession.addOutput(output)

    override fun removeOutput(output: AVCaptureOutput) = avSession.removeOutput(output)

    override fun startRunning() = avSession.startRunning()

    override fun stopRunning() = avSession.stopRunning()
}
