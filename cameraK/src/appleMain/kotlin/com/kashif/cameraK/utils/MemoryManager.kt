package com.kashif.cameraK.utils

import kotlinx.atomicfu.atomic
import platform.Foundation.NSLock
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

/**
 * Manages memory resources for camera operations
 * Monitors memory pressure and optimizes memory usage for image capture operations
 */
object MemoryManager {
    private val smallBufferLock = NSLock()
    private val mediumBufferLock = NSLock()
    private val largeBufferLock = NSLock()

    private val memoryPressure = atomic(false)

    private val smallBufferPool = mutableListOf<ByteArray>()
    private val mediumBufferPool = mutableListOf<ByteArray>()
    private val largeBufferPool = mutableListOf<ByteArray>()

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
            { _ ->
                memoryPressure.value = true
                handleHighMemoryPressure()
            },
        )
    }

    /**
     * Update current memory status
     * This should be called periodically, especially before major memory operations
     */
    fun updateMemoryStatus() {
        // Pressure is driven by the system memory-warning notification (the reliable iOS signal,
        // see registerMemoryWarningNotification). We can't cheaply measure this process's footprint
        // here, and the previous code compared physicalMemory to itself — pinning "usage" at 100%
        // and latching pressure on forever, which made every capture downshift the session preset
        // and underexpose the photo (#138). Treat a prior warning as transient: now that buffers
        // have been freed and we're between operations, clear the flag.
        if (memoryPressure.value) {
            memoryPressure.value = false
        }
    }

    /**
     * Handle high memory pressure situation
     */
    private fun handleHighMemoryPressure() {
        clearBufferPools()
    }

    /**
     * Clear all buffer pools to free memory
     * Should be called when memory pressure is detected
     */
    fun clearBufferPools() {
        smallBufferLock.lock()
        try {
            smallBufferPool.clear()
        } finally {
            smallBufferLock.unlock()
        }

        mediumBufferLock.lock()
        try {
            mediumBufferPool.clear()
        } finally {
            mediumBufferLock.unlock()
        }

        largeBufferLock.lock()
        try {
            largeBufferPool.clear()
        } finally {
            largeBufferLock.unlock()
        }
    }

    /**
     * Get buffer from pool or create new one
     * Uses sized pools to efficiently reuse memory
     * @param size Required buffer size in bytes
     * @return ByteArray of at least the requested size
     */
    fun getBuffer(size: Int): ByteArray = when {
        size <= 16 * 1024 -> getFromPool(smallBufferPool, smallBufferLock, size)
        size <= 1 * 1024 * 1024 -> getFromPool(mediumBufferPool, mediumBufferLock, size)
        else -> getFromPool(largeBufferPool, largeBufferLock, size)
    }

    /**
     * Return buffer to pool when done
     * Helps reduce memory allocations and GC pressure
     * @param buffer ByteArray to recycle
     */
    fun recycleBuffer(buffer: ByteArray) {
        when {
            buffer.size <= 16 * 1024 -> returnToPool(smallBufferPool, smallBufferLock, buffer)
            buffer.size <= 1 * 1024 * 1024 ->
                returnToPool(
                    mediumBufferPool,
                    mediumBufferLock,
                    buffer,
                )

            else -> returnToPool(largeBufferPool, largeBufferLock, buffer)
        }
    }

    /**
     * Helper function to get buffer from a pool
     */
    private fun getFromPool(pool: MutableList<ByteArray>, lock: NSLock, size: Int): ByteArray {
        lock.lock()
        try {
            val index = pool.indexOfFirst { it.size >= size }

            return if (index >= 0) {
                pool.removeAt(index)
            } else {
                ByteArray(size)
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Helper function to return buffer to a pool
     */
    private fun returnToPool(pool: MutableList<ByteArray>, lock: NSLock, buffer: ByteArray) {
        val maxPoolSize = 5

        lock.lock()
        try {
            if (pool.size < maxPoolSize) {
                pool.add(buffer)
            }
        } finally {
            lock.unlock()
        }
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
