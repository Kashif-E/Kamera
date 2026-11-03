package com.kashif.cameraK.controller

import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionDidStartRunningNotification
import platform.AVFoundation.AVCaptureSessionErrorKey
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber

/**
 * A session the engine tests script as AVFoundation would run it. Its notifications are AVFoundation's
 * own, posted with a real (never started) AVCaptureSession as their object, so the engine's observers
 * are what the tests drive. [startRunning] returns at once: a held start returns with the session not
 * running yet, as a start AVFoundation is still opening, and [deliverOpen], [failOpen] or [interrupt]
 * finishes it. Every member is safe to call from any thread.
 */
internal class FakeSessionOps : CaptureSessionOps {
    private val lock = NSLock()
    private var running = false
    private var interrupted = false
    private var wantsRunning = false
    private var holdNext = false
    private var throwOnInterruptedRead = false
    private var interruptAtStart: Long? = null
    private var starts = 0
    private var teardowns = 0
    private val inputList = mutableListOf<AVCaptureInput>()
    private val refused = mutableSetOf<AVCaptureInput>()
    private val outputList = mutableListOf<AVCaptureOutput>()

    override val avSession: AVCaptureSession = AVCaptureSession()
    override val isRunning: Boolean get() = locked { running }
    override val isInterrupted: Boolean
        get() = locked {
            if (throwOnInterruptedRead) {
                throwOnInterruptedRead = false
                throw IllegalStateException("scripted failure inside a platform callback")
            }
            interrupted
        }
    override val inputs: List<AVCaptureInput> get() = locked { inputList.toList() }
    override val outputs: List<AVCaptureOutput> get() = locked { outputList.toList() }

    /** startRunning calls, and tearDown calls: the session's releases. */
    val startCount: Int get() = locked { starts }
    val tearDownCount: Int get() = locked { teardowns }

    override fun beginConfiguration() = Unit

    override fun commitConfiguration() = Unit

    override fun canSetSessionPreset(preset: String): Boolean = true

    override fun setSessionPreset(preset: String) = Unit

    override fun canAddInput(input: AVCaptureInput): Boolean = locked { input !in inputList && input !in refused }

    /** Makes canAddInput refuse [input], as AVFoundation does for an input the session cannot take. */
    fun refuse(input: AVCaptureInput) = locked { refused += input }

    override fun addInput(input: AVCaptureInput) = locked { inputList += input }

    override fun removeInput(input: AVCaptureInput) = locked { inputList -= input }

    override fun canAddOutput(output: AVCaptureOutput): Boolean = locked { output !in outputList }

    override fun addOutput(output: AVCaptureOutput) = locked { outputList += output }

    override fun removeOutput(output: AVCaptureOutput) = locked { outputList -= output }

    override fun startRunning() {
        val interruptWith = locked {
            starts++
            wantsRunning = true
            val reason = interruptAtStart.also { interruptAtStart = null }
            when {
                reason != null -> interrupted = true
                holdNext -> holdNext = false
                else -> running = true
            }
            reason
        }
        // AVFoundation posts the interruption on the thread that called startRunning, before it returns.
        interruptWith?.let { post(AVCaptureSessionWasInterruptedNotification, reasonInfo(it)) }
    }

    override fun stopRunning() = locked {
        running = false
        interrupted = false
        wantsRunning = false
    }

    override fun tearDown() = locked {
        teardowns++
        running = false
        interrupted = false
        wantsRunning = false
    }

    /** The next startRunning returns with the session not running yet. */
    fun holdNextStart() = locked { holdNext = true }

    /** The next startRunning leaves the session interrupted for [reason], an AVCaptureSessionInterruptionReason. */
    fun interruptNextStart(reason: Long) = locked { interruptAtStart = reason }

    /** The engine's next read of [isInterrupted], which it makes only inside a platform callback, throws. */
    fun throwOnNextInterruptedRead() = locked { throwOnInterruptedRead = true }

    /** The session runs: AVFoundation's did-start-running notification. */
    fun deliverOpen() {
        locked {
            running = true
            interrupted = false
        }
        post(AVCaptureSessionDidStartRunningNotification)
    }

    /** The start fails with [error], which AVFoundation reports as a runtime error. */
    fun failOpen(error: NSError) = fail(error)

    /** A runtime error: the session stops and AVFoundation reports [error]. */
    fun fail(error: NSError) {
        locked { running = false }
        post(AVCaptureSessionRuntimeErrorNotification, mapOf<Any?, Any?>(AVCaptureSessionErrorKey to error))
    }

    /** The system interrupts the session for [reason]; a session nobody started is not left interrupted. */
    fun interrupt(reason: Long) {
        locked {
            if (wantsRunning) {
                interrupted = true
                running = false
            }
        }
        post(AVCaptureSessionWasInterruptedNotification, reasonInfo(reason))
    }

    /** The interruption ends, and a session that was started runs again on its own. */
    fun endInterruption() {
        locked {
            interrupted = false
            running = wantsRunning
        }
        post(AVCaptureSessionInterruptionEndedNotification)
    }

    /** Posts [name] with this session as its object, as AVFoundation does. */
    fun post(name: String?, userInfo: Map<Any?, Any?>? = null) {
        NSNotificationCenter.defaultCenter.postNotificationName(name!!, avSession, userInfo)
    }

    private fun reasonInfo(reason: Long) =
        mapOf<Any?, Any?>(AVCaptureSessionInterruptionReasonKey to NSNumber(long = reason))

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** An AVFoundation error with [code], as the session reports it. */
        fun avError(code: Long): NSError = NSError.errorWithDomain(AVFoundationErrorDomain!!, code, null)
    }
}
