package org.company.app

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.tracing.Trace
import kotlinx.atomicfu.atomic

/**
 * CameraX with no CameraK: one Preview into a PreviewView on the default back camera. It is the
 * baseline of the release gate's TimeToFirstFrameBenchmark.againstBareCameraX. Its
 * `Baseline.firstFrame` section spans what CameraK's `CameraK.firstFrame` spans: from just before
 * the camera provider is requested to the preview's first completed capture. The camera permission
 * must already be granted; the benchmark grants it.
 */
class BareCameraXActivity : ComponentActivity() {
    private val firstFrameSeen = atomic(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previewView = PreviewView(this)
        setContentView(previewView)
        Trace.beginAsyncSection(FIRST_FRAME_SECTION, FIRST_FRAME_COOKIE)
        val provider = ProcessCameraProvider.getInstance(this)
        provider.addListener({ bind(provider.get(), previewView) }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bind(provider: ProcessCameraProvider, previewView: PreviewView) {
        val builder = Preview.Builder()
        Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (firstFrameSeen.compareAndSet(expect = false, update = true)) {
                        Trace.endAsyncSection(FIRST_FRAME_SECTION, FIRST_FRAME_COOKIE)
                    }
                }
            },
        )
        val preview = builder.build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
    }

    private companion object {
        const val FIRST_FRAME_SECTION = "Baseline.firstFrame"
        const val FIRST_FRAME_COOKIE = 1
    }
}
