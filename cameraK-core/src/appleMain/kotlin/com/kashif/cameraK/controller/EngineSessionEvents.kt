@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.state.START_TIMEOUT
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFoundation.AVCaptureSessionDidStartRunningNotification
import platform.AVFoundation.AVCaptureSessionErrorKey
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber

private const val TAG = "CameraK"

/**
 * What AVFoundation reports about an iOS engine's [session]: did-start-running, interruptions and their
 * end, and runtime errors, observed with the session as their object and no queue. Each report hops to
 * the session queue and applies only to the start generation it arrived in. A runtime error ends its
 * generation with Failed, except a media-services reset, which interrupts the session and restarts it.
 * A second failure before the restart ran, or a restart that does not run within the start timeout,
 * gives Failed(Hardware).
 */
internal class EngineSessionEvents(
    private val generations: EngineGenerations,
    private val custom: CustomCameraController,
    private val session: CaptureSessionOps?,
    private val scope: CoroutineScope,
) {
    private val center = NSNotificationCenter.defaultCenter

    /** The reason of the latest interruption, recorded when AVFoundation posts it, before the hop to the queue. */
    private val lastInterruption = atomic<InterruptionReason?>(null)

    private val observers: List<Any> = observeSession()

    /** Removes the four session observers; close calls it. */
    fun removeObservers() {
        observers.forEach(center::removeObserver)
    }

    /** Session queue: Open when the session runs, Interrupted when the system holds it; otherwise waits. */
    fun confirm(generation: Int) {
        val ops = session ?: return
        val interrupted = ops.isInterrupted
        val running = ops.isRunning
        when {
            interrupted -> generations.becomeLive(
                generation,
                EngineState.Interrupted(lastInterruption.value ?: InterruptionReason.OTHER),
            )
            running -> generations.becomeLive(generation, EngineState.Open)
        }
    }

    /**
     * Registers the four session observers with the session as their object and no queue. Each
     * reads what it needs on the posting thread, then hops to the session queue carrying the
     * generation it arrived in.
     */
    private fun observeSession(): List<Any> {
        val avSession = session?.avSession ?: return emptyList()
        fun observe(name: String?, what: String, handle: (Int, NSNotification) -> Unit): Any =
            center.addObserverForName(name, avSession, null) { notification ->
                platformCallback(what) {
                    val generation = generations.current
                    if (notification != null) {
                        custom.afterQueuedSessionWork { platformCallback(what) { handle(generation, notification) } }
                    }
                }
            }
        return listOf(
            center.addObserverForName(AVCaptureSessionWasInterruptedNotification, avSession, null) { notification ->
                platformCallback("a session interruption") {
                    val code = notification?.userInfo?.get(AVCaptureSessionInterruptionReasonKey) as? NSNumber
                    val reason = interruptionReasonFor(code?.longValue)
                    lastInterruption.value = reason
                    val generation = generations.current
                    custom.afterQueuedSessionWork {
                        platformCallback("a session interruption") { onInterrupted(generation, reason) }
                    }
                }
            },
            observe(AVCaptureSessionInterruptionEndedNotification, "an interruption ending") { generation, _ ->
                onInterruptionEnded(generation)
            },
            observe(AVCaptureSessionDidStartRunningNotification, "the session starting") { generation, _ ->
                onDidStartRunning(generation)
            },
            observe(AVCaptureSessionRuntimeErrorNotification, "a session runtime error") { generation, notification ->
                onRuntimeError(generation, notification.userInfo?.get(AVCaptureSessionErrorKey) as? NSError)
            },
        )
    }

    private fun onInterrupted(generation: Int, reason: InterruptionReason) {
        if (generations.isLiveOrStarting(
                generation,
            )
        ) {
            generations.becomeLive(generation, EngineState.Interrupted(reason))
        }
    }

    private fun onInterruptionEnded(generation: Int) {
        if (!generations.isLiveOrStarting(generation)) return
        if (session?.isRunning == true) generations.becomeLive(generation, EngineState.Open)
    }

    private fun onDidStartRunning(generation: Int) {
        if (generations.isStopped(generation)) {
            // An open that arrives after the start it belonged to was stopped: the camera stays closed.
            custom.stopRunningOnQueue()
            return
        }
        if (generations.isLiveOrStarting(generation)) confirm(generation)
    }

    private fun onRuntimeError(generation: Int, error: NSError?) {
        if (!generations.isLiveOrStarting(generation)) return
        if (error == null) {
            CameraKLogger.e(TAG, "the session reported a runtime error without an NSError")
            return generations.fail(generation, CameraKError.Hardware(null))
        }
        val next = sessionErrorState(error)
        if (next is EngineState.Failed) return generations.fail(generation, next.error)
        val hardware = CameraKError.Hardware(error.code.toInt(), NSErrorException(error))
        // A reset restarts the session once; a second failure in a row, before the restart ran, ends the generation.
        val restart = generations.claimResetRestart() ?: return generations.fail(generation, hardware)
        generations.becomeLive(generation, next)
        custom.startRunningOnQueue()
        confirm(generation)
        failUnlessRunningWithinTheStartTimeout(generation, restart, hardware)
    }

    /**
     * A restart that leaves the session neither running nor interrupted may still run on its own, so the
     * engine waits for did-start-running as a start does, for [START_TIMEOUT], and then fails with [error].
     */
    private fun failUnlessRunningWithinTheStartTimeout(generation: Int, restart: Int, error: CameraKError) {
        scope.launch {
            delay(START_TIMEOUT)
            custom.afterQueuedSessionWork {
                platformCallback("a restart after a media-services reset") {
                    val pending = generations.isResetRestartPending(restart)
                    val ops = session
                    if (pending &&
                        ops != null &&
                        !ops.isRunning &&
                        !ops.isInterrupted
                    ) {
                        generations.fail(generation, error)
                    }
                }
            }
        }
    }
}
