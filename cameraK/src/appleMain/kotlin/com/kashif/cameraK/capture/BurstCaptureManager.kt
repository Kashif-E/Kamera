package com.kashif.cameraK.capture

import com.kashif.cameraK.utils.MemoryManager
import kotlinx.atomicfu.atomic
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSQualityOfServiceUserInteractive
import platform.Foundation.timeIntervalSince1970
import platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.math.max

/**
 * Manager for handling burst mode captures efficiently
 * Optimizes for rapid sequential photo captures with minimal delay
 * Implements throttling and quality adaptation based on system load
 */
class BurstCaptureManager {
    private val capturesInProgress = atomic(0)
    private val pendingCaptures = atomic(0)

    private val maxParallelCaptures = 3
    private val maxTotalCaptures = 10
    private val minCaptureIntervalMs = 250.0

    private var lastCaptureTime = atomic(0.0)

    private val processingQueue =
        NSOperationQueue().apply {
            maxConcurrentOperationCount = 2
            qualityOfService = NSQualityOfServiceUserInteractive
        }

    private val burstModeActive = atomic(false)
    private val lock = NSLock()

    private val captureQuality = atomic(0.95)

    init {
        MemoryManager.initialize()
    }

    /**
     * Request a capture
     * @param captureFunction Function to call to initiate capture
     * @param onComplete Callback when capture is complete
     * @return true if capture was initiated or queued, false if rejected
     */
    fun requestCapture(captureFunction: () -> Unit, onComplete: () -> Unit): Boolean {
        lock.lock()
        try {
            if (pendingCaptures.value + capturesInProgress.value >= maxTotalCaptures) {
                return false
            }

            val currentTime = NSDate().timeIntervalSince1970
            val timeSinceLastCapture = (currentTime - lastCaptureTime.value) * 1000.0

            if (timeSinceLastCapture < minCaptureIntervalMs && capturesInProgress.value > 0) {
                pendingCaptures.incrementAndGet()
                burstModeActive.value = true
                updateCaptureQuality()
                return true
            }

            lastCaptureTime.value = currentTime
            pendingCaptures.incrementAndGet()

            if (pendingCaptures.value > 2) {
                burstModeActive.value = true
                updateCaptureQuality()
            }

            if (capturesInProgress.value < maxParallelCaptures) {
                startCapture(captureFunction, onComplete)
            }

            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * Start a capture operation
     */
    private fun startCapture(captureFunction: () -> Unit, onComplete: () -> Unit) {
        capturesInProgress.incrementAndGet()

        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) {
            try {
                MemoryManager.updateMemoryStatus()

                captureFunction()
            } finally {
                completeCapture(onComplete)
            }
        }
    }

    /**
     * Complete a capture and process next in queue
     */
    private fun completeCapture(onComplete: () -> Unit) {
        capturesInProgress.decrementAndGet()

        dispatch_async(dispatch_get_main_queue()) {
            onComplete()
        }

        processNextInQueue()
    }

    /**
     * Process next capture in queue if any
     */
    private fun processNextInQueue() {
        lock.lock()
        try {
            if (pendingCaptures.decrementAndGet() <= 0) {
                pendingCaptures.value = 0
                burstModeActive.value = false

                captureQuality.value = 0.95
                return
            }

            val currentTime = NSDate().timeIntervalSince1970
            val timeSinceLastCapture = (currentTime - lastCaptureTime.value) * 1000.0

            if (capturesInProgress.value < maxParallelCaptures &&
                (timeSinceLastCapture >= minCaptureIntervalMs || pendingCaptures.value > maxTotalCaptures / 2)
            ) {
                lastCaptureTime.value = currentTime
                updateCaptureQuality()
                signalQueueReady()
            } else {
                val delayNeeded = max(0.0, minCaptureIntervalMs - timeSinceLastCapture)

                if (delayNeeded > 0) {
                    val delayNanoseconds = (delayNeeded * 1_000_000).toLong()
                    dispatch_after(
                        dispatch_time(DISPATCH_TIME_NOW, delayNanoseconds),
                        dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u),
                    ) {
                        signalQueueReady()
                    }
                } else {
                    signalQueueReady()
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Update quality settings based on current state
     */
    private fun updateCaptureQuality() {
        val currentPending = pendingCaptures.value
        val currentActive = capturesInProgress.value
        val total = currentPending + currentActive

        captureQuality.value =
            when {
                MemoryManager.isUnderMemoryPressure() -> 0.6
                total > 5 -> 0.65
                total > 3 -> 0.75
                burstModeActive.value -> 0.85
                else -> 0.95
            }
    }

    /**
     * Signal that the queue is ready for the next capture
     */
    private var onQueueReady: (() -> Unit)? = null

    fun setQueueReadyListener(listener: () -> Unit) {
        onQueueReady = listener
    }

    private fun signalQueueReady() {
        dispatch_async(dispatch_get_main_queue()) {
            onQueueReady?.invoke()
        }
    }

    /**
     * Check if burst mode is currently active
     */
    fun isBurstModeActive(): Boolean = burstModeActive.value

    /**
     * Get optimal quality setting based on current capture state and memory conditions
     */
    fun getOptimalQuality(): Double {
        updateCaptureQuality()
        return captureQuality.value
    }

    /**
     * Reset all capture state
     */
    fun reset() {
        lock.lock()
        try {
            capturesInProgress.value = 0
            pendingCaptures.value = 0
            burstModeActive.value = false
            lastCaptureTime.value = 0.0
            captureQuality.value = 0.95
        } finally {
            lock.unlock()
        }
    }

    /**
     * Clean up resources when no longer needed
     */
    fun shutdown() {
        reset()
        // NSOperationQueue cleanup is handled automatically by ARC
    }
}
