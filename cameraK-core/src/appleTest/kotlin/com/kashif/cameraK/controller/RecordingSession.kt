package com.kashif.cameraK.controller

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.Foundation.NSLock
import platform.Foundation.NSThread
import platform.darwin.dispatch_queue_get_label

/** Label of the dispatch queue the calling code is running on; empty on a thread no queue owns. */
@OptIn(ExperimentalForeignApi::class)
internal fun currentQueueLabel(): String = dispatch_queue_get_label(null)?.toKString().orEmpty()

/**
 * A [CaptureSessionOps] that records, for every call, the queue and thread it ran on, how many
 * configuration transactions were open when it was made, and whether another call was in progress
 * at the same moment. It is safe to call from any thread, so unsynchronised callers are recorded
 * rather than crashing the test process the way a real AVCaptureSession would.
 */
internal class RecordingSession : CaptureSessionOps {
    class Call(
        val op: String,
        val subject: Any?,
        val queue: String,
        val onMainThread: Boolean,
        val openConfigurations: Int,
        val overlapped: Boolean,
    ) {
        override fun toString(): String = "$op on '$queue'${if (onMainThread) " (main thread)" else ""}" +
            " with $openConfigurations open configuration(s)${if (overlapped) ", overlapping another call" else ""}"
    }

    private val lock = NSLock()
    private val inFlight = atomic(0)
    private val recorded = mutableListOf<Call>()
    private var depth = 0
    private var running = false
    private val inputList = mutableListOf<AVCaptureInput>()
    private val outputList = mutableListOf<AVCaptureOutput>()
    private val refused = mutableSetOf<AVCaptureInput>()
    private val refusedPresets = mutableSetOf<String?>()

    val calls: List<Call> get() = locked { recorded.toList() }

    /** Forgets the calls recorded so far. */
    fun clearCalls() = locked { recorded.clear() }
    val openConfigurations: Int get() = locked { depth }

    override val avSession: AVCaptureSession? = null
    override val isRunning: Boolean get() = locked { running }
    override val inputs: List<AVCaptureInput>
        get() {
            record("inputs") {}
            return locked { inputList.toList() }
        }
    override val outputs: List<AVCaptureOutput> get() = locked { outputList.toList() }

    override fun beginConfiguration() = record("beginConfiguration") { depth++ }

    override fun commitConfiguration() = record("commitConfiguration") { depth-- }

    /** Presets canSetSessionPreset refuses, as AVFoundation does for one the camera cannot deliver. */
    fun refusePreset(preset: String?) = locked { refusedPresets.add(preset) }

    override fun canSetSessionPreset(preset: String): Boolean = locked { preset !in refusedPresets }

    override fun setSessionPreset(preset: String) = record("setSessionPreset", preset) {}

    /** Makes canAddInput refuse [input], as AVFoundation does for an input the session cannot take. */
    fun refuse(input: AVCaptureInput) = locked { refused.add(input) }

    fun allow(input: AVCaptureInput) = locked { refused.remove(input) }

    override fun canAddInput(input: AVCaptureInput): Boolean = locked { input !in refused && input !in inputList }

    override fun addInput(input: AVCaptureInput) = record("addInput", input) { inputList += input }

    override fun removeInput(input: AVCaptureInput) = record("removeInput", input) { inputList -= input }

    override fun canAddOutput(output: AVCaptureOutput): Boolean = locked { output !in outputList }

    override fun addOutput(output: AVCaptureOutput) = record("addOutput", output) { outputList += output }

    override fun removeOutput(output: AVCaptureOutput) = record("removeOutput", output) { outputList -= output }

    override fun startRunning() = record("startRunning") { running = true }

    override fun stopRunning() = record("stopRunning") { running = false }

    override fun setAutomaticallyConfiguresApplicationAudioSession(configures: Boolean) =
        record("setAutomaticallyConfiguresApplicationAudioSession", configures) {}

    private fun record(op: String, subject: Any? = null, mutate: () -> Unit) {
        val overlapped = inFlight.incrementAndGet() > 1
        try {
            val call = Call(op, subject, currentQueueLabel(), NSThread.isMainThread, locked { depth }, overlapped)
            locked {
                recorded += call
                mutate()
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
