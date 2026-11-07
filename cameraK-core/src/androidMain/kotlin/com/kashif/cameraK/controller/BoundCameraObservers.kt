package com.kashif.cameraK.controller

import androidx.camera.core.Camera
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.kashif.cameraK.utils.CameraKLogger

private const val TAG = "CameraK"

/**
 * The LiveData observers [EngineSession] keeps on the bound camera. A report reaches its handler only
 * while [isCurrent] holds for the camera it came from, and a handler that throws is logged, not
 * rethrown into CameraX. [clear] removes them all when a bind replaces the camera. Main thread.
 */
internal class BoundCameraObservers(private val isCurrent: (Camera) -> Boolean) {
    private val removers = mutableListOf<() -> Unit>()

    fun <T> observe(camera: Camera, data: LiveData<T>, report: String, onValue: (T) -> Unit) {
        val observer = Observer<T> { value ->
            if (!isCurrent(camera)) return@Observer
            try {
                onValue(value)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Handling a $report report failed", e)
            }
        }
        removers += { data.removeObserver(observer) }
        data.observeForever(observer)
    }

    fun clear() {
        removers.forEach { it() }
        removers.clear()
    }
}
