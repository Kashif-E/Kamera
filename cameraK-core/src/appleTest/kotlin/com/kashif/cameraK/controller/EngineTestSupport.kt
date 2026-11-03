@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import platform.Foundation.NSLock

/** The hardware an engine test starts with: the back camera, everything off, no zoom. */
internal fun backCamera(
    selection: CameraSelection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
) = DesiredHardware(
    selection = selection,
    flashMode = FlashMode.OFF,
    torchMode = TorchMode.OFF,
    zoomRatio = 1f,
    targetOrientation = null,
)

/** A [Tracer] that records every interval it opens and closes, and every marker, as "begin X" / "end X" / "mark X". */
internal class RecordingTracer : Tracer {
    private val lock = NSLock()
    private val recorded = mutableListOf<String>()

    val events: List<String> get() = locked { recorded.toList() }

    fun count(event: String): Int = events.count { it == event }

    override fun beginInterval(name: String): AutoCloseable {
        locked { recorded += "begin $name" }
        return AutoCloseable { locked { recorded += "end $name" } }
    }

    override fun marker(name: String) = locked { recorded += "mark $name" }

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * Every value [flow] emits, recorded in the emitting thread (an unconfined collector), so a value the
 * session queue publishes and replaces before the test scheduler runs is still seen.
 */
internal class Recorded<T>(scope: CoroutineScope, flow: Flow<T>) {
    private val lock = NSLock()
    private val recorded = mutableListOf<T>()
    val job: Job = scope.launch(Dispatchers.Unconfined) {
        flow.collect { value ->
            lock.lock()
            try {
                recorded += value
            } finally {
                lock.unlock()
            }
        }
    }

    val values: List<T>
        get() {
            lock.lock()
            try {
                return recorded.toList()
            } finally {
                lock.unlock()
            }
        }
}
