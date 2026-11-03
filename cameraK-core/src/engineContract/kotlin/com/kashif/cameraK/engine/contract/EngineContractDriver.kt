@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * What the contract kit needs from one engine's fake platform. Never published. Each engine's test
 * source set implements it over its own platform fake; the kit's cases call nothing else.
 */
internal interface EngineContractDriver {
    fun createEngine(config: CameraConfiguration = CameraConfiguration()): CameraEngine

    /** The next platform open waits until [deliverOpen] or [failOpen]. */
    fun holdOpen()

    fun deliverOpen()

    /** A platform open failure that maps to a retryable error. */
    fun failOpen()

    fun interrupt(reason: InterruptionReason)

    fun endInterruption()

    /** A runtime platform failure while open. */
    fun fail()

    /** Reported as a platform observer would. [change] names only what the engine's capabilities allow. */
    fun changeHardware(change: HardwareChange)

    /** Flash, torch, zoom and camera the fake platform last received. */
    fun boundSettings(): BoundSettings

    /**
     * Native releases since [createEngine]: Android, the engine's final unbind of its own use cases
     * with the registry DESTROYED; iOS, the session's teardown; desktop, the grabber's release. The
     * kit opens one session, closes twice and expects 1.
     */
    fun releaseCount(): Int

    /**
     * Per-platform quiescence: drain the engine's scheduler or looper (the session queue, the main
     * looper, the producer), including the capability enumeration the engine launched after
     * construction. The kit settles once after [createEngine], before any case reads
     * `engine.capabilities`. Never a sleep.
     */
    suspend fun settle()

    /**
     * Runs one case. A driver overrides it to set its platform up around the case, for example to
     * give its engines dispatchers on the case's test scheduler.
     */
    fun runCase(body: suspend TestScope.() -> Unit): TestResult = runTest { body() }
}

/** A platform observer report. Null leaves that value alone. */
internal class HardwareChange(
    val torchMode: TorchMode? = null,
    val zoomRatio: Float? = null,
    val flashMode: FlashMode? = null,
)

/** What the fake platform last received. [cameraId] is a `LensInfo.id`; null before the first open. */
internal data class BoundSettings(
    val flashMode: FlashMode,
    val torchMode: TorchMode,
    val zoomRatio: Float,
    val cameraId: String?,
)

/**
 * Flash has no platform observer (Android, iOS): the engine reads the platform object back right
 * after the platform call and publishes that read-back before `setFlashMode` returns. Over a driver
 * that implements it, the kit's flash steps expect [EngineContractDriver.boundSettings]' flash mode
 * in `hardware` right after the command, and report no flash change through
 * [EngineContractDriver.changeHardware].
 */
internal interface ReadBackFlashDriver : EngineContractDriver

internal interface ThrowingCallbackDriver : EngineContractDriver {
    /** The next scripted platform callback throws inside the engine's callback boundary. */
    fun throwFromNextCallback()
}

/**
 * The platform can interrupt a start it holds, before the camera opens: CameraX reports a recoverable
 * error while opening, AVFoundation an interruption of a session still starting. [interrupt] during a
 * held start then gives a start that succeeds with `Interrupted`, which [endInterruption] turns into
 * Open with no second start. A driver whose engine has no interruption before Open (desktop) leaves
 * it out, and the kit lists the case as skipped.
 */
internal interface PreOpenInterruptionDriver : EngineContractDriver

/**
 * The kit starts a recording, then calls [deliverRecordingStarted] for the platform's start event,
 * and, after it requests the end (stopRecording, stop or close), [deliverRecordingFinalized] for
 * the platform's finalize event.
 */
internal interface RecordingDriver : EngineContractDriver {
    fun deliverRecordingStarted()

    fun deliverRecordingFinalized()
}

internal interface CaptureDriver : EngineContractDriver {
    /** The next capture's platform callback waits until [releaseCapture]. */
    fun holdNextCapture()

    fun releaseCapture()
}

/** What a platform cannot honour differs by engine and by slice, so each driver names its own case. */
internal interface UnsupportedConfigurationDriver : EngineContractDriver {
    fun unsupportedConfiguration(): UnsupportedCase
}

internal class UnsupportedCase(val config: CameraConfiguration, val feature: CameraFeature)
