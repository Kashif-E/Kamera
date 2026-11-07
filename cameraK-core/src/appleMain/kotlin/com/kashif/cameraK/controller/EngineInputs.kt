@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

private const val TAG = "CameraK"

/**
 * Which camera an iOS engine's session runs: the camera a start binds, configuring the session the
 * first time, and the lens switches after it. A camera that becomes the session's is bound: its
 * capabilities read, its torch and zoom observed, the rotation coordinator made for it, and the
 * desired zoom and exposure index applied.
 */
internal class EngineInputs(
    private val generations: EngineGenerations,
    private val custom: CustomCameraController,
    private val session: CaptureSessionOps?,
    private val capabilities: EngineCapabilities,
    private val controls: EngineControls,
    private val rotation: EngineRotation,
    private val events: EngineSessionEvents,
    private val tracer: Tracer,
    private val frameOutputAttached: () -> Boolean,
) {
    /** Test seam: runs on the session queue between a lens switch's live check and its commit. */
    @Volatile
    var afterSwitchLiveCheck: () -> Unit = {}

    /** Session queue: configures the session or moves it to the selected camera, then starts it. */
    fun startOnQueue(generation: Int, desired: DesiredHardware) {
        if (!generations.isCurrent(generation, EnginePhase.STARTING)) return
        val selection = desired.selection
        val device = resolve(selection)
            ?: return generations.fail(generation, CameraKError.NoCamera(selection.lens()))
        val failure = when {
            !custom.isConfigured -> custom.configureOnQueue(device, device.lens(), selection.deviceType())
            device !== custom.currentDevice -> custom.useDeviceOnQueue(device)
            else -> null
        }
        if (failure != null) return generations.fail(generation, engineErrorFor(failure, selection.lens()))
        rotation.targetOrientation = desired.targetOrientation
        controls.takeDesired(desired)
        bindHardware(device)
        // A stop or close since the block was queued wins: the session is not started for nobody.
        if (!generations.isCurrent(generation, EnginePhase.STARTING)) return
        // Starting the session applies the torch; one already running keeps the torch it had.
        if (session?.isRunning == true) custom.reapplyTorchOnQueue() else custom.startRunningOnQueue()
        controls.applyDesiredZoom(device)
        controls.applyDesiredExposure(device)
        controls.publishHardware()
        events.confirm(generation)
    }

    suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> {
        generations.unusable()?.let { return it }
        capabilities.enumerated.await()
        return suspendCancellableCoroutine { continuation -> switchCamera(selection) { continuation.resume(it) } }
    }

    /**
     * Moves the open session to the camera [selection] names, with the 1.x lens switch, and passes
     * the camera live afterwards to [done] on the session queue. The work is queued before this
     * returns, so it runs after any session work already requested. The switch is one
     * `CameraK.lensSwitch` interval. With no frame output attached it ends when the swap has
     * committed (the session keeps running through it); with one, at the first sample buffer through
     * the new camera's input, or, marked noFrame, at the engine's stop, close, failure or next switch.
     * A switch whose generation a stop or close cut while it was committing ends at once, marked noFrame;
     * a refused switch ends at once, marked failed.
     */
    fun switchCamera(selection: CameraSelection, done: (CameraKResult<LensInfo>) -> Unit) {
        generations.endPendingSwitch()
        val interval = tracer.beginInterval(TraceNames.LENS_SWITCH)
        custom.afterQueuedSessionWork {
            val generation = generations.current
            val result = try {
                switchOnQueue(selection, generation)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "switching the camera failed", e)
                engineFailure(CameraKError.Internal(e))
            }
            // The input whose first sample buffer ends the interval, when a frame output is attached.
            val awaited = custom.currentVideoInput
                .takeIf { result is CameraKResult.Success && frameOutputAttached() }
            // The phase and generation are checked under the lock that publishes the switch, so a stop
            // since the live check ends the switch here instead of leaving it waiting.
            val pending = awaited != null && generations.awaitSwitchFrame(generation, interval, awaited)
            if (!pending) {
                when {
                    result is CameraKResult.Failure -> tracer.marker(AppleTraceNames.LENS_SWITCH_FAILED)
                    awaited != null -> tracer.marker(AppleTraceNames.LENS_SWITCH_NO_FRAME)
                }
                interval.close()
            }
            done(result)
        }
    }

    private fun switchOnQueue(selection: CameraSelection, generation: Int): CameraKResult<LensInfo> {
        generations.unusable()?.let { return it }
        if (!generations.isCurrent(generation, EnginePhase.LIVE)) return engineFailure(CameraKError.NotReady)
        afterSwitchLiveCheck()
        val device = resolve(selection) ?: return engineFailure(CameraKError.NoCamera(selection.lens()))
        if (custom.recordingInProgress()) return engineFailure(CameraKError.Busy(CameraOperation.LENS))
        // A refused input puts the previous camera back, inside the same transaction.
        custom.useDeviceOnQueue(device)?.let { return engineFailure(engineErrorFor(it, selection.lens())) }
        bindHardware(device)
        controls.applyDesiredZoom(device)
        controls.applyDesiredExposure(device)
        controls.publishHardware()
        return CameraKResult.Success(capabilities.lensInfo(device))
    }

    private fun resolve(selection: CameraSelection): CaptureDeviceOps? = when (selection) {
        is CameraSelection.ById -> custom.findCameraById(selection.id)
        is CameraSelection.ByLens -> custom.findCamera(selection.lens, selection.deviceType)
        is CameraSelection.Default -> custom.findCamera(CameraLens.BACK, selection.deviceType)
    }

    /**
     * Session queue, after [device] became the session's camera: its flash modes, image formats and
     * recording qualities from the photo output and the device now behind it, and the device's torch and
     * zoom observed in place of the previous camera's.
     */
    private fun bindHardware(device: CaptureDeviceOps) {
        capabilities.bind(device)
        controls.observe(device)
        rotation.bindOnQueue()
    }
}
