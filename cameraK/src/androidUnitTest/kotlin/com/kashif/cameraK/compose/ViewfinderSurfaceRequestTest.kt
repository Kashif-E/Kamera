@file:OptIn(CameraKEngineApi::class, ExperimentalTestApi::class)

package com.kashif.cameraK.compose

import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraInfo
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.CameraInfoInternal
import androidx.camera.core.impl.CameraInternal
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.PreviewAttachment
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.utils.CameraKEngineApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android preview renders the engine's SurfaceRequests in CameraXViewfinder (design §7.1, §12.4 UA).
 * Robolectric never creates a real surface, so "reaches the viewfinder" is the request the viewfinder is
 * composed with, and the transformer is read once the real viewfinder has laid out a request.
 */
@RunWith(RobolectricTestRunner::class)
class ViewfinderSurfaceRequestTest {
    @Test
    fun eachSurfaceRequestReachesTheViewfinder() {
        val source = KeptProviderSource()
        val received = mutableListOf<SurfaceRequest>()
        var shown by mutableStateOf(true)
        val requestA = surfaceRequest()
        val requestB = surfaceRequest()

        runComposeUiTest {
            setContent {
                if (shown) {
                    ViewfinderPreview(
                        source = source,
                        deviceOrientation = DeviceOrientation.PORTRAIT,
                        modifier = Modifier.size(100.dp),
                        overlay = null,
                        viewfinder = { request, _, _ ->
                            DisposableEffect(request) {
                                received += request
                                onDispose { }
                            }
                        },
                    )
                }
            }
            waitForIdle()
            val provider = assertNotNull(source.provider, "the preview attached no surface provider to the engine")
            assertEquals(1, source.attachments.size, "the preview attached more than once")

            runOnUiThread { provider.onSurfaceRequested(requestA) }
            waitForIdle()
            assertEquals(listOf(requestA), received, "request A did not reach the viewfinder, or not alone")

            runOnUiThread { provider.onSurfaceRequested(requestB) }
            waitForIdle()
            assertEquals(listOf(requestA, requestB), received, "request B did not replace A in the viewfinder")

            shown = false
            waitForIdle()
            assertTrue(source.attachments.single().closed, "leaving the composition did not close the attachment")
        }
        requestA.willNotProvideSurface()
        requestB.willNotProvideSurface()
    }

    @Test
    fun transformerMapsViewToSensorCoordinates() {
        val source = KeptProviderSource()
        val transformer = MutableCoordinateTransformer()
        // A 640x480 buffer whose 640x360 crop is rotated 90 degrees for display: a 360x640 portrait frame,
        // fitted into a 300x320 px view at scale 1/2, so 180 px wide and centred, 60 px from either side.
        // Whole pixels, because the viewfinder aligns the frame with Compose's Alignment, which rounds the
        // offset to a pixel. The sensor-to-buffer matrix is identity, so buffer and sensor coordinates
        // coincide.
        val request = surfaceRequest()
        request.updateTransformationInfo(
            SurfaceRequest.TransformationInfo.of(
                Rect(0, 60, 640, 420),
                90,
                Surface.ROTATION_0,
                true,
                Matrix(),
                false,
            ),
        )

        runComposeUiTest {
            setContent {
                val (width, height) = with(LocalDensity.current) { 300.toDp() to 320.toDp() }
                ViewfinderPreview(
                    source = source,
                    deviceOrientation = DeviceOrientation.PORTRAIT,
                    modifier = Modifier.size(width, height),
                    overlay = null,
                    coordinateTransformer = transformer,
                )
            }
            waitForIdle()
            val provider = assertNotNull(source.provider, "the preview attached no surface provider to the engine")
            runOnUiThread { provider.onSurfaceRequested(request) }
            waitForIdle()

            with(transformer) {
                // The view's centre is the crop's centre.
                assertNear(Offset(320f, 240f), Offset(150f, 160f).transform(), "the view's centre")
                // The top-left corner is in the left bar: 60 view px outside the frame, which is 120 buffer px
                // past the crop's bottom edge once rotated back. A cropped (not fitted) frame maps it to
                // (128, 420), on the crop's edge.
                assertNear(Offset(0f, 540f), Offset(0f, 0f).transform(), "the view's top-left corner")
            }
        }
        request.willNotProvideSurface()
    }

    @Test
    fun overlaySpansTheImageBoxAt16By9And4By3InAPortraitContainer() {
        // A 300x450 px portrait container: the image box is the configured ratio turned portrait and
        // fitted inside it, centred, so the overlay's coordinates are the camera image's, with no bars.
        // 9:16 fills the height (450 * 9 / 16 = 253.125 px wide), 3:4 fills the width (300 * 4 / 3 = 400).
        val cases = listOf(AspectRatio.RATIO_16_9 to IntSize(253, 450), AspectRatio.RATIO_4_3 to IntSize(300, 400))
        for ((ratio, box) in cases) {
            var container: Rect? = null
            var overlay: Rect? = null
            runComposeUiTest {
                setContent {
                    val (width, height) = with(LocalDensity.current) { 300.toDp() to 450.toDp() }
                    ViewfinderPreview(
                        source = KeptProviderSource(),
                        deviceOrientation = DeviceOrientation.PORTRAIT,
                        modifier = Modifier.size(width, height).onGloballyPositioned { container = it.pixelBounds() },
                        overlay = {
                            Box(Modifier.fillMaxSize().onGloballyPositioned { overlay = it.pixelBounds() })
                        },
                        aspectRatio = ratio,
                    )
                }
                waitForIdle()
            }
            val outer = assertNotNull(container, "the container was never laid out")
            val inner = assertNotNull(overlay, "the overlay was never laid out at $ratio")
            assertEquals(box, IntSize(inner.width(), inner.height()), "the overlay's size at $ratio")
            assertTrue(
                abs(inner.centerX() - outer.centerX()) <= 1 && abs(inner.centerY() - outer.centerY()) <= 1,
                "the overlay at $ratio is not centred in its container: $inner in $outer",
            )
        }
    }
}

// android.graphics.Matrix computes in single precision; across the scale, rotation and inversion here
// that is well under a hundredth of a buffer pixel.
private const val MATRIX_TOLERANCE = 0.01f

private fun LayoutCoordinates.pixelBounds(): Rect = boundsInRoot().let {
    Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
}

private fun assertNear(expected: Offset, actual: Offset, what: String) {
    assertTrue(
        abs(expected.x - actual.x) < MATRIX_TOLERANCE && abs(expected.y - actual.y) < MATRIX_TOLERANCE,
        "the transformer maps $what to $actual, not $expected",
    )
}

/** Keeps the provider the preview attaches, as the engine's preview does, so a test can send it requests. */
private class KeptProviderSource : PlatformPreviewSource {
    var provider: Preview.SurfaceProvider? = null
    val attachments = mutableListOf<RecordingAttachment>()

    override fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment {
        this.provider = provider
        return RecordingAttachment().also(attachments::add)
    }
}

private class RecordingAttachment : PreviewAttachment {
    var closed = false

    override fun close() {
        closed = true
    }
}

private fun surfaceRequest() = SurfaceRequest(Size(640, 480), stubCamera(), {})

/**
 * The camera a SurfaceRequest names. CameraXViewfinder reads only its info's implementation type, to
 * choose a SurfaceView or a TextureView; anything else it calls fails the test with the method's name.
 * The stub is a LEGACY camera, for which CameraX's own choice is a TextureView: Robolectric never creates
 * a SurfaceView's surface, and the viewfinder lays a SurfaceView out (and fills the transformer) only once
 * its surface exists.
 */
private fun stubCamera(): CameraInternal {
    val info =
        stub<CameraInfoInternal> { name ->
            when (name) {
                "getImplementationType" -> CameraInfo.IMPLEMENTATION_TYPE_CAMERA2_LEGACY
                else -> null
            }
        }
    return stub { name ->
        when (name) {
            "getCameraInfo", "getCameraInfoInternal" -> info
            else -> null
        }
    }
}

private inline fun <reified T> stub(crossinline answer: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.firstOrNull()
            "toString" -> "Stub${T::class.java.simpleName}"
            else ->
                answer(method.name)
                    ?: throw UnsupportedOperationException("${T::class.java.simpleName}.${method.name} is not stubbed")
        }
    } as T
