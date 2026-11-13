package com.kashif.cameraK.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.atomicfu.atomic

/**
 * Manages memory resources for camera operations on Android
 * Monitors memory pressure and optimizes memory usage for image capture operations
 */
object MemoryManager {
    private const val MEMORY_PRESSURE_THRESHOLD = 0.8

    private val memoryPressure = atomic(false)

    private var appContext: Context? = null

    /**
     * Initialize memory monitoring with application context
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        updateMemoryStatus()
    }

    /**
     * Update current memory status
     * This should be called periodically, especially before major memory operations
     */
    fun updateMemoryStatus() {
        val context = appContext ?: return

        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(memoryInfo)

        val availableMemory = memoryInfo.availMem
        val totalMemory =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                memoryInfo.totalMem
            } else {
                Runtime.getRuntime().totalMemory()
            }

        if (totalMemory > 0) {
            val usedMemory = totalMemory - availableMemory
            val usage = usedMemory.toDouble() / totalMemory.toDouble()

            if (usage > MEMORY_PRESSURE_THRESHOLD && !memoryPressure.value) {
                memoryPressure.value = true
                handleHighMemoryPressure()
            } else if (usage <= MEMORY_PRESSURE_THRESHOLD && memoryPressure.value) {
                memoryPressure.value = false
            }
        }

        if (memoryInfo.lowMemory && !memoryPressure.value) {
            memoryPressure.value = true
            handleHighMemoryPressure()
        }
    }

    /**
     * Handle high memory pressure situation.
     *
     * Only a collection hint: this used to also clear buffer pools that nothing ever filled.
     */
    private fun handleHighMemoryPressure() {
        System.gc()
    }

    /**
     * Check if memory is under pressure
     */
    fun isUnderMemoryPressure(): Boolean = memoryPressure.value
}
