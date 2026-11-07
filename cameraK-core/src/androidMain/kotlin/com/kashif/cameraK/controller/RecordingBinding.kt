package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.error.CameraKError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The binds a recording makes or holds back (design §2.4, §5), for [AndroidCameraEngine]. VideoCapture
 * joins the bind as one [AndroidCameraEngine.VIDEO_BIND_TRACE] interval, and when CameraX refuses the
 * group the photo session is bound again without it. While a recording runs, two rebinds wait for its
 * end: the one a display turned between portrait and landscape asks for, and the late ImageAnalysis
 * bind a registration asks for (Decision 14). Main thread.
 */
internal class RecordingBinding(
    private val session: EngineSession,
    private val controls: EngineControls,
    private val orientation: OrientationTracker,
    private val binder: UseCaseBinder,
    private val framePipeline: FramePipeline,
    private val videoRecorder: VideoRecorder,
    private val tracer: Tracer,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val mainImmediate: CoroutineDispatcher,
    private val isReleased: () -> Boolean,
) {
    // Set when a portrait↔landscape flip is detected during recording (when rebinding would tear
    // down the recording). The deferred rebind runs once the recording finalizes.
    private var pendingViewPortRebind = false

    // A late bind of the ImageAnalysis a registration asked for is queued.
    private var analysisBindPending = false

    /**
     * Rebinds the bound camera with VideoCapture in the group, its main-thread part one
     * `CameraK.videoBind` interval (B1 records it). When CameraX refuses the group, the photo session
     * is bound again without VideoCapture and the error is returned.
     */
    fun bindWithVideo(): CameraKError? {
        val target = session.bindTarget() ?: return CameraKError.NotReady
        val error = tracer.beginInterval(AndroidCameraEngine.VIDEO_BIND_TRACE).use { session.bind(target) }
            ?: return null
        videoRecorder.dropVideoCapture()
        session.bind(target)?.let { rollback ->
            session.fail(target, rollback)
            return rollback
        }
        return error
    }

    // Dispatched, never run inline: a caller that gives up before the bind runs leaves nothing bound.
    suspend fun rebindAndAwait() {
        withTimeoutOrNull(STOP_FINALIZE_TIMEOUT_MS) { withContext(main) { session.rebind() } }
    }

    fun rebindAfterRecordingEndedByCameraX() {
        pendingViewPortRebind = false
        scope.launch(mainImmediate) { session.rebind() }
    }

    // Applies any rotation flip that was deferred while recording was in progress, then binds an
    // ImageAnalysis a registration made during the recording asked for, unless that rebind or the
    // recording's own included it.
    fun onNoActiveRecording() {
        if (pendingViewPortRebind && !isReleased()) {
            pendingViewPortRebind = false
            rebindIfViewPortOrientationChanged()
        }
        if (framePipeline.analysisMissing) scope.launch(mainImmediate) { bindAnalysisOrApplyConsumers() }
    }

    /**
     * Attaches or detaches the frame multiplexer for the current consumers, or, when a registration
     * asked for an ImageAnalysis the bind lacks, binds it once (Decision 14's late bind). Before the
     * first bind, and during a recording, it waits: that bind, or the one the recording's end makes
     * ([onNoActiveRecording]), includes it.
     */
    fun bindAnalysisOrApplyConsumers() {
        if (isReleased()) return
        if (!framePipeline.analysisMissing) {
            framePipeline.applyConsumers()
            return
        }
        if (!session.isLive || !session.isBound || videoRecorder.isRecording || analysisBindPending) return
        analysisBindPending = true
        scope.launch(mainImmediate) {
            try {
                if (framePipeline.analysisMissing) session.rebind()
            } finally {
                analysisBindPending = false
            }
        }
    }

    fun onDisplayChanged() {
        if (isReleased() || binder.camera == null) return
        // Auto mode only; an explicit target orientation stays as set.
        if (orientation.targetOrientation == null) controls.applyCaptureRotation()
        rebindIfViewPortOrientationChanged()
    }

    /**
     * Rebinds the camera when the display flips between portrait and landscape or its rotation turns
     * a quarter, so the (immutable) ViewPort is rebuilt with the matching Rational and rotation.
     */
    private fun rebindIfViewPortOrientationChanged() {
        if (binder.camera == null || !binder.viewPortOrientationChanged()) return
        if (videoRecorder.isRecording) {
            // Unbinding to rebind would tear down the VideoCapture session; the recording's Finalize
            // performs the rebind once it is safe.
            pendingViewPortRebind = true
            return
        }
        scope.launch(mainImmediate) { session.rebind() }
    }
}
