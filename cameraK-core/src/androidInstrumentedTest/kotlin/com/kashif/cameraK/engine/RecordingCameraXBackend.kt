package com.kashif.cameraK.engine

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageCapture
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.kashif.cameraK.controller.CameraBinding
import com.kashif.cameraK.controller.CameraXBackend
import com.kashif.cameraK.controller.ResolvedCamera
import com.kashif.cameraK.engine.contract.BoundSettings
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKLogger
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * [real], with every bind and unbind recorded: the owner, the use cases and the camera each bind
 * returned. The engine gets a delegating [Camera] whose [CameraControl] records each `enableTorch`,
 * `setZoomRatio` and `setExposureCompensationIndex` before passing it on, so [boundSettings] is what
 * the platform last received (design §12.4 B1).
 */
internal class RecordingCameraXBackend(private val real: CameraXBackend) : CameraXBackend by real {
    private val lock = Any()
    private val owners = mutableMapOf<UseCase, LifecycleOwner>()
    private val cameras = IdentityHashMap<Camera, RecordingCamera>()
    private val observedOwners = mutableSetOf<LifecycleOwner>()
    private val pendingControlCalls = mutableListOf<ListenableFuture<*>>()
    private var lastBound: RecordingCamera? = null
    private var releases = 0

    override fun requestProvider(context: Context, executor: Executor, onResult: (Result<CameraBinding>) -> Unit) {
        real.requestProvider(context, executor) { result -> onResult(result.map(::RecordingBinding)) }
    }

    /**
     * Flash from the bound ImageCapture; torch, zoom and the exposure index as the engine last sent
     * them to the bound camera, which starts, as CameraX opens one, with the torch off, zoom 1x and
     * index 0; the camera is the last bound one.
     */
    fun boundSettings(): BoundSettings = synchronized(lock) {
        val camera = lastBound
        val flash = camera?.useCases?.filterIsInstance<ImageCapture>()?.lastOrNull()?.flashMode
        BoundSettings(
            flashMode = when (flash) {
                ImageCapture.FLASH_MODE_ON -> FlashMode.ON
                ImageCapture.FLASH_MODE_AUTO -> FlashMode.AUTO
                else -> FlashMode.OFF
            },
            torchMode = camera?.torchMode ?: TorchMode.OFF,
            zoomRatio = camera?.zoomRatio ?: 1f,
            cameraId = camera?.id,
            exposureCompensationIndex = camera?.exposureIndex ?: 0,
        )
    }

    /** Unbinds made while the owner of an unbound use case was DESTROYED: the engine's release. */
    fun releaseCount(): Int = synchronized(lock) { releases }

    /**
     * Waits for every CameraX control call recorded since the last call. A call CameraX cancels,
     * because a newer one replaced it or the camera closed, has still been received.
     */
    fun awaitControlCalls() {
        val calls = synchronized(lock) { pendingControlCalls.toList().also { pendingControlCalls.clear() } }
        for (call in calls) {
            try {
                call.get(CONTROL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                CameraKLogger.w(TAG, "A camera control call ended in failure while the driver settled", e)
            } catch (e: CancellationException) {
                CameraKLogger.w(TAG, "A camera control call was cancelled while the driver settled", e)
            }
        }
    }

    private inner class RecordingBinding(private val binding: CameraBinding) : CameraBinding {
        override fun unbind(vararg useCases: UseCase) {
            synchronized(lock) {
                if (useCases.any { owners[it]?.lifecycle?.currentState == Lifecycle.State.DESTROYED }) releases++
                useCases.forEach { owners -= it }
                val unbound = useCases.toSet()
                cameras.values.forEach { camera ->
                    // CameraX closes a camera with nothing bound; the next open starts with the torch off, zoom 1x and
                    // exposure index 0.
                    if (camera.useCases.removeAll(unbound) && camera.useCases.isEmpty()) camera.reset()
                }
            }
            binding.unbind(*useCases)
        }

        override fun bindToLifecycle(
            owner: LifecycleOwner,
            camera: ResolvedCamera,
            useCaseGroup: UseCaseGroup,
        ): Camera = record(owner, useCaseGroup.useCases, binding.bindToLifecycle(owner, camera, useCaseGroup))

        override fun bindToLifecycle(owner: LifecycleOwner, camera: ResolvedCamera, vararg useCases: UseCase): Camera =
            record(owner, useCases.toList(), binding.bindToLifecycle(owner, camera, *useCases))

        override fun cameraIdOf(camera: Camera): String =
            binding.cameraIdOf((camera as? RecordingCamera)?.real ?: camera)

        override fun hasCamera(camera: ResolvedCamera): Boolean = binding.hasCamera(camera)

        // Main thread, where CameraX binds.
        private fun record(owner: LifecycleOwner, useCases: List<UseCase>, bound: Camera): Camera {
            val id = binding.cameraIdOf(bound)
            if (observedOwners.add(owner)) {
                // Stopping the owner closes its cameras, and the torch, zoom and exposure index with them.
                owner.lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) {
                            synchronized(lock) { cameras.values.filter { it.owner === owner }.forEach { it.reset() } }
                        }
                    },
                )
            }
            return synchronized(lock) {
                useCases.forEach { owners[it] = owner }
                val recording = cameras.getOrPut(bound) { RecordingCamera(bound, id, owner) }
                recording.useCases += useCases.filterNot { it in recording.useCases }
                lastBound = recording
                recording
            }
        }
    }

    /**
     * [real], with a [CameraControl] that records torch, zoom and the exposure index. Same object for
     * the same CameraX camera.
     */
    private inner class RecordingCamera(val real: Camera, val id: String, val owner: LifecycleOwner) : Camera by real {
        val useCases = mutableListOf<UseCase>()
        var torchMode = TorchMode.OFF
        var zoomRatio = 1f
        var exposureIndex = 0

        private val control = object : CameraControl by real.cameraControl {
            override fun enableTorch(torch: Boolean): ListenableFuture<Void> = synchronized(lock) {
                torchMode = if (torch) TorchMode.ON else TorchMode.OFF
                real.cameraControl.enableTorch(torch).also { pendingControlCalls += it }
            }

            override fun setZoomRatio(ratio: Float): ListenableFuture<Void> = synchronized(lock) {
                zoomRatio = ratio
                real.cameraControl.setZoomRatio(ratio).also { pendingControlCalls += it }
            }

            override fun setExposureCompensationIndex(value: Int): ListenableFuture<Int> = synchronized(lock) {
                exposureIndex = value
                real.cameraControl.setExposureCompensationIndex(value).also { pendingControlCalls += it }
            }
        }

        override fun getCameraControl(): CameraControl = control

        fun reset() {
            torchMode = TorchMode.OFF
            zoomRatio = 1f
            exposureIndex = 0
        }
    }

    private companion object {
        const val TAG = "RecordingCameraXBackend"
        const val CONTROL_CALL_TIMEOUT_SECONDS = 10L
    }
}
