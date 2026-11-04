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

    /** Whether the system holds the session interrupted: a phone call, another app on the camera. */
    val isInterrupted: Boolean get() = false

    val inputs: List<AVCaptureInput>
    val outputs: List<AVCaptureOutput>

    fun beginConfiguration()

    fun commitConfiguration()

    fun canSetSessionPreset(preset: String): Boolean

    fun setSessionPreset(preset: String)

    fun canAddInput(input: AVCaptureInput): Boolean

    fun addInput(input: AVCaptureInput)

    fun removeInput(input: AVCaptureInput)

    fun canAddOutput(output: AVCaptureOutput): Boolean

    fun addOutput(output: AVCaptureOutput)

    fun removeOutput(output: AVCaptureOutput)

    fun startRunning()

    fun stopRunning()

    /**
     * Whether the session sets the app's AVAudioSession up for its audio input. AVFoundation's default
     * is true; CameraK keeps it true only while an audio recording is configured.
     */
    fun setAutomaticallyConfiguresApplicationAudioSession(configures: Boolean)

    /** Releases the session for good: the engine calls it once, when it closes. */
    fun tearDown() {
        if (isRunning) stopRunning()
    }
}

internal class AVCaptureSessionOps(override val avSession: AVCaptureSession = AVCaptureSession()) : CaptureSessionOps {
    override val isRunning: Boolean get() = avSession.isRunning()
    override val isInterrupted: Boolean get() = avSession.isInterrupted()
    override val inputs: List<AVCaptureInput> get() = avSession.inputs.filterIsInstance<AVCaptureInput>()
    override val outputs: List<AVCaptureOutput> get() = avSession.outputs.filterIsInstance<AVCaptureOutput>()

    override fun beginConfiguration() = avSession.beginConfiguration()

    override fun commitConfiguration() = avSession.commitConfiguration()

    override fun canSetSessionPreset(preset: String): Boolean = avSession.canSetSessionPreset(preset)

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

    override fun setAutomaticallyConfiguresApplicationAudioSession(configures: Boolean) {
        avSession.automaticallyConfiguresApplicationAudioSession = configures
    }
}
