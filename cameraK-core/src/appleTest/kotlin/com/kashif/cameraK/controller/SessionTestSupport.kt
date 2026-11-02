package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSince1970
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.test.fail

/** Waits until [condition] holds or [seconds] pass, servicing the main queue meanwhile. */
internal fun spinUntil(seconds: Double, condition: () -> Boolean): Boolean {
    val deadline = NSDate().timeIntervalSince1970 + seconds
    while (!condition()) {
        if (NSDate().timeIntervalSince1970 > deadline) return false
        if (NSThread.isMainThread) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        } else {
            NSThread.sleepForTimeInterval(0.01)
        }
    }
    return true
}

internal fun awaitTrue(what: String, timeoutSeconds: Double = 10.0, condition: () -> Boolean) {
    if (!spinUntil(timeoutSeconds, condition)) fail("Timed out after ${timeoutSeconds}s waiting for $what")
}

/**
 * Returns once everything already handed to [controller]'s session queue has run, and after it every
 * block that work queued on main: both queues are FIFO.
 */
internal fun drainSessionThenMain(controller: CustomCameraController) {
    val drained = atomic(false)
    controller.afterQueuedSessionWork {
        dispatch_async(dispatch_get_main_queue()) { drained.value = true }
    }
    awaitTrue("the session queue, then the main queue, to drain") { drained.value }
}

/** Captures what CameraKLogger emits while [block] runs, restoring the logger afterwards. */
internal fun <T> capturingLogs(block: (logs: () -> List<String>) -> T): T {
    val lock = NSLock()
    val lines = mutableListOf<String>()
    val wasEnabled = CameraKLogger.enabled
    val previousSink = CameraKLogger.sink
    CameraKLogger.sink = { level, _, message, throwable ->
        lock.lock()
        try {
            lines += "$level $message${throwable?.let { " ($it)" } ?: ""}"
        } finally {
            lock.unlock()
        }
    }
    CameraKLogger.enabled = true
    try {
        return block {
            lock.lock()
            try {
                lines.toList()
            } finally {
                lock.unlock()
            }
        }
    } finally {
        CameraKLogger.enabled = wasEnabled
        CameraKLogger.sink = previousSink
    }
}

/** One line CameraKLogger emitted, with the throwable it was given. */
internal class LogEntry(val level: CameraKLogger.Level, val message: String, val throwable: Throwable?) {
    override fun toString(): String = "$level $message${throwable?.let { " ($it)" } ?: ""}"
}

/** Captures what CameraKLogger emits while [block] runs, throwables included, restoring the logger afterwards. */
internal fun <T> capturingLogEntries(block: (entries: () -> List<LogEntry>) -> T): T {
    val lock = NSLock()
    val entries = mutableListOf<LogEntry>()
    val wasEnabled = CameraKLogger.enabled
    val previousSink = CameraKLogger.sink
    CameraKLogger.sink = { level, _, message, throwable ->
        lock.lock()
        try {
            entries += LogEntry(level, message, throwable)
        } finally {
            lock.unlock()
        }
    }
    CameraKLogger.enabled = true
    try {
        return block {
            lock.lock()
            try {
                entries.toList()
            } finally {
                lock.unlock()
            }
        }
    } finally {
        CameraKLogger.enabled = wasEnabled
        CameraKLogger.sink = previousSink
    }
}
