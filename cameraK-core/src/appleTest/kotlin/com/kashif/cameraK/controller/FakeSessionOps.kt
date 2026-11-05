@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.frames.FrameOutputDelegate
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionDidStartRunningNotification
import platform.AVFoundation.AVCaptureSessionErrorKey
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVFoundationErrorDomain
import platform.CoreMedia.CMSampleBufferRef
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.darwin.dispatch_queue_t
import platform.darwin.dispatch_sync

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
    private val presetList = mutableListOf<String>()
    private val audioSessionSettings = mutableListOf<Boolean>()
    private var depth = 0
    private var begins = 0

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

    override fun beginConfiguration() = locked {
        depth++
        begins++
        Unit
    }

    override fun commitConfiguration() = locked {
        depth--
        Unit
    }

    /** Configuration transactions open now, and begun since the session was made. */
    val openConfigurations: Int get() = locked { depth }
    val configurationsBegun: Int get() = locked { begins }

    override fun setAutomaticallyConfiguresApplicationAudioSession(configures: Boolean) =
        locked { audioSessionSettings += configures }

    /** Every automaticallyConfiguresApplicationAudioSession value set, in order. */
    val audioSessionConfigurations: List<Boolean> get() = locked { audioSessionSettings.toList() }

    /** What AVFoundation's session would do with the app's audio session now: its default is true. */
    val configuresTheAppAudioSession: Boolean get() = locked { audioSessionSettings.lastOrNull() ?: true }

    override fun canSetSessionPreset(preset: String): Boolean = true

    override fun setSessionPreset(preset: String) = locked { presetList += preset }

    /** Every preset the session was given, in order. */
    val presets: List<String> get() = locked { presetList.toList() }

    override fun canAddInput(input: AVCaptureInput): Boolean = locked { input !in inputList && input !in refused }

    /** Makes canAddInput refuse [input], as AVFoundation does for an input the session cannot take. */
    fun refuse(input: AVCaptureInput) = locked { refused += input }

    override fun addInput(input: AVCaptureInput) = locked { inputList += input }

    override fun removeInput(input: AVCaptureInput) = locked { inputList -= input }

    override fun canAddOutput(output: AVCaptureOutput): Boolean = locked { output !in outputList }

    override fun addOutput(output: AVCaptureOutput) = locked {
        outputList += output
        addedIn[output] = if (depth > 0) begins else 0
    }

    private val addedIn = mutableMapOf<AVCaptureOutput, Int>()

    /**
     * The configuration transaction [output] was last added in, counted from 1 as [configurationsBegun]
     * counts them; 0 for an add outside any transaction, null for an output never added.
     */
    fun transactionOf(output: AVCaptureOutput): Int? = locked { addedIn[output] }

    /** The video data outputs attached now: CameraK's frame output, when it is attached. */
    val videoDataOutputs: List<AVCaptureVideoDataOutput>
        get() = outputs.filterIsInstance<AVCaptureVideoDataOutput>()

    /**
     * AVFoundation delivering [buffer], captured through [input], to the attached video data output:
     * on the output's own callback queue, returning once the delegate has. Returns false, delivering
     * nothing, when no video data output with CameraK's delegate is attached.
     */
    fun deliverSampleBuffer(buffer: CMSampleBufferRef, input: AVCaptureInput?): Boolean {
        val (delegate, queue) = frameOutput() ?: return false
        dispatch_sync(queue) { delegate.deliver(buffer, input) }
        return true
    }

    /** AVFoundation dropping a late sample buffer at the attached video data output; false without one. */
    fun dropSampleBuffer(): Boolean {
        val (delegate, queue) = frameOutput() ?: return false
        dispatch_sync(queue) { delegate.drop() }
        return true
    }

    private fun frameOutput(): Pair<FrameOutputDelegate, dispatch_queue_t>? {
        val output = videoDataOutputs.firstOrNull() ?: return null
        val delegate = output.sampleBufferDelegate as? FrameOutputDelegate ?: return null
        val queue = output.sampleBufferCallbackQueue ?: return null
        return delegate to queue
    }

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
