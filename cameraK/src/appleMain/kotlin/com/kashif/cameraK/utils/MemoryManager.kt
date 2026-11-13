package com.kashif.cameraK.utils

import kotlinx.atomicfu.atomic
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

/**
 * Manages memory resources for camera operations
 * Monitors memory pressure and optimizes memory usage for image capture operations
 */
object MemoryManager {
    private val memoryPressure = atomic(false)

    /**
     * Initialize memory monitoring
     */
    fun initialize() {
        registerMemoryWarningNotification()
        updateMemoryStatus()
    }

    /**
     * Register for system memory warning notifications
     */
    private fun registerMemoryWarningNotification() {
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidReceiveMemoryWarningNotification,
            null,
            NSOperationQueue.mainQueue,
            { _ -> memoryPressure.value = true },
        )
    }

    /**
     * Update current memory status
     * This should be called periodically, especially before major memory operations
     */
    fun updateMemoryStatus() {
        // No-op: iOS exposes no cheap, reliable per-process memory gauge, so pressure is driven
        // solely by the system memory-warning notification (see registerMemoryWarningNotification),
        // which callers can observe via isUnderMemoryPressure(). The previous code compared
        // physicalMemory to itself — pinning "usage" at 100% and latching pressure on permanently,
        // which made every capture downshift the session preset and underexpose the photo (#138).
        // Kept as a hook so existing call sites stay valid.
    }

    /**
     * Get optimal image quality based on memory conditions
     */
    fun getOptimalImageQuality(): Double = if (memoryPressure.value) 0.6 else 0.95

    /**
     * Check if memory is under pressure
     */
    fun isUnderMemoryPressure(): Boolean = memoryPressure.value
}
