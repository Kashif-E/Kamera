package com.kashif.cameraK.controller

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.utils.getActivityOrNull

/**
 * Binds this controller's use cases to the process camera provider and unbinds exactly those. Owns
 * the preview use case, the [ViewPort] built from the configured aspect ratio and the display's
 * rotation, and the display listener that rebuilds it when the display turns.
 *
 * Main thread only, like every CameraX binding call.
 */
internal class UseCaseBinder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val aspectRatio: AspectRatio,
    private val targetResolution: Pair<Int, Int>?,
    private val isReleased: () -> Boolean,
    private val onDisplayChanged: (PreviewView) -> Unit,
) {
    /** The preview the camera is bound with, or null before the first bind. */
    var previewView: PreviewView? = null
        private set

    /** The camera the last bind returned, or null before one succeeded. */
    var camera: Camera? = null
        private set

    private var cameraProvider: CameraBinding? = null

    // Every use case this controller has bound and not yet unbound. ProcessCameraProvider is shared
    // by the whole process, so rebinds and teardown unbind exactly these, never unbindAll().
    private val boundUseCases = linkedSetOf<UseCase>()

    // Portrait/landscape category of the display rotation used to build the current ViewPort. The
    // ViewPort is immutable once bound, so when the display flips category we must rebind to rebuild
    // it — otherwise the capture crop stays at the old orientation (reintroduces #136 after rotation).
    private var lastViewPortPortrait: Boolean? = null

    // Display events, not the accelerometer, drive the ViewPort and the capture rotation: the
    // accelerometer crosses a bucket hundreds of ms before the display rotates, and a host that
    // handles orientation changes itself is never recreated, so nothing else would rebuild the crop
    // (#136). Trade-off: in an orientation-locked host, captures follow the display orientation.
    private var displayListener: DisplayManager.DisplayListener? = null

    /** Makes [previewView] the preview of every later bind and starts following its display. */
    fun attach(previewView: PreviewView) {
        this.previewView = previewView
        listenForDisplayChanges()
    }

    /** Takes the provider a bind resolved and unbinds what this controller bound before. */
    fun useProvider(provider: CameraBinding) {
        cameraProvider = provider
        unbindOwnUseCases()
    }

    /** The resolution selector for the configured aspect ratio and target resolution. */
    fun resolutionSelector(): ResolutionSelector {
        // Always honor the configured aspect ratio. When a target resolution is also set, keep the
        // ratio as the primary constraint and use the resolution as the preferred size within it —
        // previously targetResolution dropped the ratio entirely, so a 16:9 target produced 16:9
        // captures even when 4:3 was requested (#136).
        val builder = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatio.toCameraXAspectRatioStrategy())
        targetResolution?.let { (w, h) ->
            builder.setResolutionStrategy(
                ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
        }
        return builder.build()
    }

    /** The preview use case for the next bind, drawing into [previewView]. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun buildPreview(resolutionSelector: ResolutionSelector, pinnedPhysicalId: String?, previewView: PreviewView) =
        Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .apply { pinnedPhysicalId?.let { Camera2Interop.Extender(this).setPhysicalCameraId(it) } }
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

    /**
     * Binds [useCases] as one group, cropped by a ViewPort for [previewView]'s display, and returns
     * the camera CameraX bound them to.
     */
    fun bindGroup(selector: CameraSelector, useCases: List<UseCase>, previewView: PreviewView): Camera? {
        val builder = UseCaseGroup.Builder()
        for (useCase in useCases) builder.addUseCase(useCase)

        // Build the ViewPort explicitly from the configured aspect ratio rather than reading
        // previewView.viewPort. The latter is null until the view is laid out (so the crop is
        // often dropped at bind time) and, with a FIT_CENTER preview, reflects the full sensor
        // buffer — which left the captured still at the sensor ratio (e.g. 16:9) even when a
        // 4:3 preview was requested (#136). An explicit ViewPort crops preview + capture +
        // analyzer to the same ratio, so what you see equals what you capture.
        buildViewPort(previewView)?.let { builder.setViewPort(it) }

        val useCaseGroup = builder.build()
        camera = cameraProvider?.bindToLifecycle(lifecycleOwner, selector, useCaseGroup)
        boundUseCases += useCaseGroup.useCases
        return camera
    }

    /** Binds [useCase] on its own beside the bound group, with the camera [selector] picks. */
    fun bindAlone(selector: () -> CameraSelector, useCase: UseCase) {
        cameraProvider?.bindToLifecycle(lifecycleOwner, selector(), useCase)
        boundUseCases += useCase
    }

    /** Unbinds every use case this controller bound, and nothing another CameraX consumer bound. */
    fun unbindOwnUseCases() {
        if (boundUseCases.isEmpty()) return
        val own = boundUseCases.toTypedArray()
        boundUseCases.clear()
        cameraProvider?.unbind(*own)
    }

    /**
     * Builds a [ViewPort] matching the configured aspect ratio so every use case (preview, capture,
     * analyzer) is cropped to the same field of view. The [Rational] is expressed as width:height in
     * the display's CURRENT orientation (the convention `PreviewView.getViewPort()` uses), so it is
     * flipped for portrait — e.g. `RATIO_4_3` is `3:4` in portrait and `4:3` in landscape.
     */
    private fun buildViewPort(previewView: PreviewView): ViewPort? {
        val rotation = displayRotation(previewView)
        // The ViewPort Rational is width:height in the display's CURRENT orientation. In portrait a
        // "4:3" capture is taller than wide (3:4); only in landscape is it 4:3. Hard-coding the
        // landscape Rational forced a landscape crop in portrait — preview and photo came out 4:3
        // when 3:4 was expected (#136). Flip width/height for portrait.
        val portrait = rotation.isPortraitRotation()
        lastViewPortPortrait = portrait
        val rational = when (aspectRatio) {
            AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 ->
                if (portrait) Rational(9, 16) else Rational(16, 9)
            AspectRatio.RATIO_4_3 ->
                if (portrait) Rational(3, 4) else Rational(4, 3)
            AspectRatio.RATIO_1_1 -> Rational(1, 1)
        }
        return ViewPort.Builder(rational, rotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
    }

    /**
     * Whether the display rotation has flipped between portrait and landscape since the bound
     * ViewPort was built, so the (immutable) ViewPort needs a rebind. Gated on the *display*
     * rotation: an activity locked to one orientation never rotates the display, so it never
     * needlessly rebinds, while an unlocked one keeps capture cropped to what's actually on screen.
     */
    fun viewPortOrientationChanged(previewView: PreviewView): Boolean {
        val portrait = displayRotation(previewView).isPortraitRotation()
        return lastViewPortPortrait != null && portrait != lastViewPortPortrait
    }

    /**
     * Current display rotation. `previewView.display` is null until the view is attached (bindCamera
     * runs from a DisposableEffect before layout), so fall back to the hosting activity's display —
     * otherwise a landscape start would build the ViewPort with the wrong rotation and crop to the
     * wrong orientation until a rebind.
     */
    fun displayRotation(previewView: PreviewView): Int = currentDisplay(previewView)?.rotation ?: Surface.ROTATION_0

    @Suppress("DEPRECATION")
    private fun currentDisplay(previewView: PreviewView): Display? = previewView.display
        ?: previewView.context.getActivityOrNull()?.windowManager?.defaultDisplay
        ?: context.getActivityOrNull()?.windowManager?.defaultDisplay

    /** Registers [displayListener] once, on the main thread. */
    private fun listenForDisplayChanges() {
        if (displayListener != null) return
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (isReleased()) return
                val pv = previewView ?: return
                if (currentDisplay(pv)?.displayId != displayId) return
                onDisplayChanged(pv)
            }

            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayListener = listener
    }

    fun stopListeningForDisplayChanges() {
        val listener = displayListener ?: return
        displayListener = null
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(listener)
    }
}

private fun Int.isPortraitRotation(): Boolean = this == Surface.ROTATION_0 || this == Surface.ROTATION_180

private fun AspectRatio.toCameraXAspectRatioStrategy(): AspectRatioStrategy = when (this) {
    AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    AspectRatio.RATIO_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
    AspectRatio.RATIO_1_1 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY // closest available
}
