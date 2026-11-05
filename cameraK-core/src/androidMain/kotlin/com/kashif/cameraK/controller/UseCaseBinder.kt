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
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.lifecycle.LifecycleOwner
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.state.Resolution

/**
 * Binds the engine's use cases to the process camera provider and unbinds exactly those. Owns the
 * [ViewPort] built from the configured aspect ratio and the rotation of the display the preview is
 * attached on ([display], else the default display), and the display listener that rebuilds it when
 * that display turns.
 *
 * Main thread only, like every CameraX binding call.
 */
internal class UseCaseBinder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val aspectRatio: AspectRatio,
    private val targetResolution: Pair<Int, Int>?,
    private val isReleased: () -> Boolean,
    private val display: () -> Display?,
    private val onDisplayChanged: () -> Unit,
) {
    /** The camera the last bind returned, or null before one succeeded. */
    var camera: Camera? = null
        private set

    /** The provider of the last bind, or null before the first. */
    var cameraProvider: CameraBinding? = null
        private set

    // Every use case this engine has bound and not yet unbound. ProcessCameraProvider is shared by
    // the whole process, so rebinds and teardown unbind exactly these, never unbindAll().
    private val boundUseCases = linkedSetOf<UseCase>()

    // Portrait or landscape, and whether the rotation was a quarter turn, for the display the current
    // ViewPort was built on. The ViewPort is immutable once bound, so when either changes we must
    // rebind to rebuild it — otherwise the capture crop stays at the old orientation (reintroduces
    // #136 after rotation). On one display the two change together; a move to a panel of the other
    // natural orientation can change the quarter alone, and CameraX maps the ViewPort's ratio into
    // sensor space by its rotation, so that too crops the wrong orientation (#182). A half turn
    // changes neither and keeps the ViewPort: the centred crop is the same.
    private var lastViewPortPortrait: Boolean? = null
    private var lastViewPortQuarterTurned: Boolean? = null

    // Display events, not the accelerometer, drive the ViewPort and the capture rotation: the
    // accelerometer crosses a bucket hundreds of ms before the display rotates, and a host that
    // handles orientation changes itself is never recreated, so nothing else would rebuild the crop
    // (#136). Trade-off: in an orientation-locked host, captures follow the display orientation.
    private var displayListener: DisplayManager.DisplayListener? = null

    /** Takes the provider a bind resolved and unbinds what this engine bound before. */
    fun useProvider(provider: CameraBinding) {
        cameraProvider = provider
        unbindOwnUseCases()
        camera = null
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

    /**
     * The analysis resolution selector (design §8): the configured aspect ratio, as the preview has,
     * and [resolution] as the preferred size within it. Without one the preferred size is CameraX's
     * own analysis default, VGA: a selector with no resolution strategy would make CameraX pick the
     * largest size the camera has.
     */
    fun analysisResolutionSelector(resolution: Resolution?): ResolutionSelector {
        val preferred = resolution?.let { Size(it.width, it.height) } ?: ANALYSIS_DEFAULT_SIZE
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatio.toCameraXAspectRatioStrategy())
            .setResolutionStrategy(
                ResolutionStrategy(preferred, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
    }

    /** The preview use case for the next bind, with no surface yet; [configure] adds to its builder. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun buildPreview(
        resolutionSelector: ResolutionSelector,
        pinnedPhysicalId: String?,
        configure: (Preview.Builder) -> Unit,
    ): Preview = Preview.Builder()
        .setResolutionSelector(resolutionSelector)
        .apply { pinnedPhysicalId?.let { Camera2Interop.Extender(this).setPhysicalCameraId(it) } }
        .also(configure)
        .build()

    /**
     * Binds [useCases] as one group on [resolved], cropped by a ViewPort for the current display, and
     * returns the camera CameraX bound them to.
     */
    fun bindGroup(resolved: ResolvedCamera, useCases: List<UseCase>): Camera {
        val provider = checkNotNull(cameraProvider) { "bind before the camera provider resolved" }
        val builder = UseCaseGroup.Builder()
        for (useCase in useCases) builder.addUseCase(useCase)

        // An explicit ViewPort from the configured aspect ratio crops preview, capture and analysis
        // to the same ratio, so what you see equals what you capture (#136).
        builder.setViewPort(buildViewPort())

        val useCaseGroup = builder.build()
        val bound = provider.bindToLifecycle(lifecycleOwner, resolved, useCaseGroup)
        camera = bound
        boundUseCases += useCaseGroup.useCases
        return bound
    }

    /** Binds [useCase] on its own beside the bound group, on the camera [resolved] names. */
    fun bindAlone(resolved: () -> ResolvedCamera, useCase: UseCase) {
        cameraProvider?.bindToLifecycle(lifecycleOwner, resolved(), useCase)
        boundUseCases += useCase
    }

    /** Unbinds every use case this engine bound, and nothing another CameraX consumer bound. */
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
    private fun buildViewPort(): ViewPort {
        val rotation = displayRotation()
        val portrait = displayIsPortrait()
        lastViewPortPortrait = portrait
        lastViewPortQuarterTurned = rotation.isQuarterTurn()
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
     * Whether the display has flipped between portrait and landscape, or its rotation by a quarter
     * turn, since the bound ViewPort was built, so the (immutable) ViewPort needs a rebind. Gated on
     * the *display*: an activity locked to one orientation never rotates the display, so it never
     * needlessly rebinds, while an unlocked one keeps capture cropped to what's actually on screen.
     */
    fun viewPortOrientationChanged(): Boolean {
        val built = lastViewPortPortrait ?: return false
        return displayIsPortrait() != built || displayRotation().isQuarterTurn() != lastViewPortQuarterTurned
    }

    /**
     * Whether the display is portrait now: its natural orientation, turned a quarter at ROTATION_90
     * and ROTATION_270 (PR #182). The natural orientation is the panel's own, from its mode's physical
     * size, which does not turn with the display; ROTATION_0 is landscape on a landscape-first panel
     * (tablets, Chromebooks).
     */
    private fun displayIsPortrait(): Boolean {
        val display = currentDisplay() ?: return true
        val mode = display.mode
        val naturalPortrait = mode.physicalHeight >= mode.physicalWidth
        return naturalPortrait != display.rotation.isQuarterTurn()
    }

    /** The rotation of the display the preview is attached on, else the default display's. */
    fun displayRotation(): Int = currentDisplay()?.rotation ?: Surface.ROTATION_0

    private fun currentDisplay(): Display? = display()
        ?: (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)

    /** Registers [displayListener] once, on the main thread. */
    fun listenForDisplayChanges() {
        if (displayListener != null) return
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (isReleased()) return
                if (currentDisplay()?.displayId != displayId) return
                onDisplayChanged()
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

// ImageAnalysis's own default size in CameraX 1.5 (SizeUtil.RESOLUTION_VGA).
private val ANALYSIS_DEFAULT_SIZE = Size(640, 480)

private fun Int.isQuarterTurn(): Boolean = this == Surface.ROTATION_90 || this == Surface.ROTATION_270

private fun AspectRatio.toCameraXAspectRatioStrategy(): AspectRatioStrategy = when (this) {
    AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    AspectRatio.RATIO_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
    AspectRatio.RATIO_1_1 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY // closest available
}
