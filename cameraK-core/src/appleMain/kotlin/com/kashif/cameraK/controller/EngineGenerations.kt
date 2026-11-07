@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVCaptureInput
import platform.Foundation.NSLock
import kotlin.coroutines.resume

private const val TAG = "CameraK"

/** The lock an iOS engine's collaborators keep their shared state under. NSLock is not reentrant. */
internal class EngineLock {
    val nsLock = NSLock()

    inline fun <T> locked(block: () -> T): T {
        nsLock.lock()
        try {
            return block()
        } finally {
            nsLock.unlock()
        }
    }
}

/**
 * Runs one platform callback. Nothing it throws crosses the GCD block or the notification it
 * runs in: the throwable is logged and the callback does nothing more.
 */
internal inline fun platformCallback(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        CameraKLogger.e("CameraK", "handling $what failed", e)
    }
}

internal val ENGINE_CLOSED = CameraKResult.Failure(CameraKError.Closed)

internal fun engineFailure(error: CameraKError) = CameraKResult.Failure(error)

internal enum class EnginePhase { CLOSED, STARTING, LIVE, FAILED }

internal class PendingStart(val generation: Int, val continuation: CancellableContinuation<CameraKResult<Unit>>)

/** What a stop or close cut: the start it ended, if one was waiting. */
internal class GenerationCut(val start: PendingStart?)

/**
 * An iOS engine's start generations and its [state] (design §2.3): each start opens a generation, which
 * goes live, fails, or ends at a stop, a close or a cancelled start, and a platform report applies only
 * to the generation it arrived in. The first-frame interval, a lens switch waiting for its first frame
 * and the media-services restart belong to the generation. Everything here is read and written under
 * [lock]; the members named "Locked" are for a caller that already holds it.
 */
internal class EngineGenerations(
    private val lock: EngineLock,
    private val tracer: Tracer,
    private val custom: CustomCameraController,
    private val unsupported: CameraKError?,
    private val frameOutputAttached: () -> Boolean,
) {
    private val _state = MutableStateFlow<EngineState>(unsupported?.let(EngineState::Failed) ?: EngineState.Closed)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var generation = 0
    private var phase = EnginePhase.CLOSED
    private var closed = false
    private var pendingStart: PendingStart? = null
    private var firstFrame: AutoCloseable? = null

    /** A lens switch whose interval waits for the first sample buffer from [PendingSwitch.input]. */
    private var pendingSwitch: PendingSwitch? = null

    /** Set by a media-services reset's restart, cleared when the session runs again. */
    private var restartedAfterReset = false
    private var resetRestarts = 0

    /** Lock held: whether the engine is closed. */
    val closedLocked: Boolean get() = closed

    /** Lock held: whether a generation is starting or live in an engine not closed. */
    val startingOrLiveLocked: Boolean
        get() = !closed && (phase == EnginePhase.STARTING || phase == EnginePhase.LIVE)

    val isClosed: Boolean get() = lock.locked { closed }

    val isStartingOrLive: Boolean get() = lock.locked { startingOrLiveLocked }

    val isLive: Boolean get() = lock.locked { phase == EnginePhase.LIVE }

    /** The generation now. */
    val current: Int get() = lock.locked { generation }

    fun isCurrent(generation: Int, expected: EnginePhase): Boolean =
        lock.locked { !closed && this.generation == generation && phase == expected }

    fun isLiveOrStarting(generation: Int): Boolean = lock.locked {
        !closed && this.generation == generation && (phase == EnginePhase.STARTING || phase == EnginePhase.LIVE)
    }

    /** Whether [generation] is the current one and a stop ended it, in an engine not closed. */
    fun isStopped(generation: Int): Boolean =
        lock.locked { this.generation == generation && phase == EnginePhase.CLOSED && !closed }

    /** Failed with [error] unless closed: a start that cannot run (below iOS 16, an unsupported format). */
    fun failUnusable(error: CameraKError) {
        lock.locked { if (!closed) _state.value = EngineState.Failed(error) }
    }

    /** Closed unless already closed: a stop below iOS 16. */
    fun closeUnusable() {
        lock.locked { if (!closed) _state.value = EngineState.Closed }
    }

    /** Opens a new generation waiting on [continuation]; null when the engine is closed. */
    fun open(continuation: CancellableContinuation<CameraKResult<Unit>>): Int? = lock.locked {
        if (closed) return@locked null
        generation++
        phase = EnginePhase.STARTING
        restartedAfterReset = false
        pendingStart = PendingStart(generation, continuation)
        endPendingSwitchLocked()
        firstFrame?.close()
        firstFrame = tracer.beginInterval(TraceNames.FIRST_FRAME)
        _state.value = EngineState.Opening
        generation
    }

    /** Ends [generation] if it is still starting; returns whether it did. */
    fun cancelStart(generation: Int): Boolean = lock.locked {
        if (closed || this.generation != generation || phase != EnginePhase.STARTING) return@locked false
        this.generation++
        phase = EnginePhase.CLOSED
        pendingStart = null
        endFirstFrame()
        endPendingSwitchLocked()
        _state.value = EngineState.Closed
        true
    }

    /** Ends the generation for a stop; null when the engine is closed. */
    fun stop(): GenerationCut? = lock.locked {
        if (closed) return@locked null
        generation++
        phase = EnginePhase.CLOSED
        endFirstFrame()
        endPendingSwitchLocked()
        _state.value = EngineState.Closed
        GenerationCut(pendingStart.also { pendingStart = null })
    }

    /** Closes the engine for good; null when it was closed already. */
    fun close(): GenerationCut? = lock.locked {
        if (closed) return@locked null
        closed = true
        generation++
        phase = EnginePhase.CLOSED
        endFirstFrame()
        endPendingSwitchLocked()
        _state.value = EngineState.Closed
        GenerationCut(pendingStart.also { pendingStart = null })
    }

    fun becomeLive(generation: Int, next: EngineState) {
        val started = lock.locked {
            if (closed || this.generation != generation) return
            if (phase != EnginePhase.STARTING && phase != EnginePhase.LIVE) return
            phase = EnginePhase.LIVE
            _state.value = next
            if (next == EngineState.Open) {
                // With a frame output the first frame is its first sample buffer (onSampleBuffer).
                if (!frameOutputAttached()) endFirstFrame()
                restartedAfterReset = false
            }
            pendingStart.also { pendingStart = null }
        }
        started?.continuation?.resume(CameraKResult.Success(Unit))
    }

    fun fail(generation: Int, error: CameraKError) {
        val failedStart = lock.locked {
            if (closed || this.generation != generation) return
            if (phase != EnginePhase.STARTING && phase != EnginePhase.LIVE) return
            phase = EnginePhase.FAILED
            endFirstFrame()
            endPendingSwitchLocked()
            _state.value = EngineState.Failed(error)
            pendingStart.also { pendingStart = null }
        }
        CameraKLogger.e(TAG, "the camera session failed", error.cause)
        failedStart?.continuation?.resume(engineFailure(error))
    }

    /** Claims the one restart a media-services reset gets; null when it was claimed already. */
    fun claimResetRestart(): Int? = lock.locked {
        if (restartedAfterReset) return@locked null
        restartedAfterReset = true
        ++resetRestarts
    }

    /** Whether [restart] is the latest reset restart and the session has not run since. */
    fun isResetRestartPending(restart: Int): Boolean = lock.locked { restartedAfterReset && resetRestarts == restart }

    // Lock held.
    private fun endFirstFrame() {
        firstFrame?.close()
        firstFrame = null
    }

    /** Ends a lens switch still waiting for its first frame, marked noFrame. */
    fun endPendingSwitch() {
        lock.locked { endPendingSwitchLocked() }
    }

    // Lock held: a switch whose first frame never came ends here, marked noFrame.
    private fun endPendingSwitchLocked() {
        val switch = pendingSwitch ?: return
        pendingSwitch = null
        tracer.marker(AppleTraceNames.LENS_SWITCH_NO_FRAME)
        switch.interval.close()
    }

    /**
     * Leaves a committed lens switch's [interval] waiting for the first sample buffer through [input],
     * in place of any switch still waiting, unless a stop or close since the live check cut
     * [generation]; returns whether it waits.
     */
    fun awaitSwitchFrame(generation: Int, interval: AutoCloseable, input: AVCaptureInput): Boolean = lock.locked {
        if (closed || this.generation != generation || phase != EnginePhase.LIVE) return@locked false
        endPendingSwitchLocked()
        pendingSwitch = PendingSwitch(interval, input)
        true
    }

    /**
     * The frame output's queue, before any analyzer: the start's first sample buffer ends its
     * first-frame interval, and the first one through the new camera's input ends a lens switch's.
     */
    fun onSampleBuffer(input: AVCaptureInput?) {
        lock.locked {
            if (!closed && (phase == EnginePhase.STARTING || phase == EnginePhase.LIVE)) endFirstFrame()
            val switch = pendingSwitch
            if (switch != null && input != null && switch.input === input) {
                pendingSwitch = null
                switch.interval.close()
            }
        }
    }

    fun unusable(): CameraKResult.Failure? = when {
        isClosed -> ENGINE_CLOSED
        unsupported != null -> engineFailure(unsupported)
        else -> null
    }

    /** Hardware commands need an Open camera, as on every engine and the fake. */
    fun notOpen(): CameraKResult.Failure? =
        unusable() ?: engineFailure(CameraKError.NotReady).takeIf { _state.value != EngineState.Open }

    /** Runs [block] on the session queue and returns its result; nothing it throws leaves the queue. */
    suspend fun <T> onSessionQueue(what: String, block: () -> CameraKResult<T>): CameraKResult<T> =
        suspendCancellableCoroutine { continuation ->
            custom.afterQueuedSessionWork {
                val result = try {
                    unusable() ?: block()
                } catch (e: Exception) {
                    CameraKLogger.e(TAG, "$what failed", e)
                    engineFailure(CameraKError.Internal(e))
                }
                continuation.resume(result)
            }
        }

    private class PendingSwitch(val interval: AutoCloseable, val input: AVCaptureInput)
}
