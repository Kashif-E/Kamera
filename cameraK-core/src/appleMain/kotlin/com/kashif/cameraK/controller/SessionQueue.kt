package com.kashif.cameraK.controller

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_specific
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_set_specific
import platform.darwin.dispatch_sync
import platform.darwin.dispatch_time

/** Queue-specific key every session queue is tagged with; the value is unique per queue. */
@OptIn(ExperimentalForeignApi::class)
private val sessionQueueKey = nativeHeap.alloc<ByteVar>().ptr
private val sessionQueueIds = atomic(0L)

/**
 * The one serial queue every AVCaptureSession call (begin/commit, inputs, outputs, preset, start, stop)
 * of a controller runs on, Apple's AVCam pattern: the session is not thread-safe, and startRunning
 * between begin and commitConfiguration raises NSGenericException, which aborts a Kotlin/Native
 * process. Only preview-layer work and callbacks hop to main.
 */
@OptIn(ExperimentalForeignApi::class)
internal class SessionQueue {
    private val queue = dispatch_queue_create("com.kashif.cameraK.session", null)

    private val context = sessionQueueIds.incrementAndGet().toCPointer<CPointed>()

    init {
        dispatch_queue_set_specific(queue, sessionQueueKey, context, null)
    }

    /** Whether the calling code runs on this queue. */
    val isCurrent: Boolean
        get() = dispatch_get_specific(sessionQueueKey) == context

    /** Runs [block] on the queue after every block already queued. */
    fun async(block: () -> Unit) {
        dispatch_async(queue, block)
    }

    /** Runs [block] on the queue and waits for it; never call it on the queue itself. */
    fun sync(block: () -> Unit) {
        dispatch_sync(queue, block)
    }

    /** Runs [block] on the queue once [delayMs] have passed. */
    fun after(delayMs: Long, block: () -> Unit) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delayMs * 1_000_000), queue, block)
    }
}
