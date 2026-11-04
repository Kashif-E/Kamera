@file:OptIn(CameraKEngineApi::class, ExperimentalTestApi::class)

package com.kashif.cameraK.compose

import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraInfo
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.CameraInfoInternal
import androidx.camera.core.impl.CameraInternal
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.PreviewAttachment
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKEngineApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Both Android preview composables, the 1.x `CameraPreviewView` and the holder's `EnginePreview`
 * actual, render the engine's preview in CameraXViewfinder through `ViewfinderPreview`, never in a
 * PreviewView (w6 A2 carry-over 5; design §7.1). The view tree is the evidence: a PreviewView is a
 * View in it, CameraXViewfinder lays out a request as a TextureView on a LEGACY camera.
 */
@RunWith(RobolectricTestRunner::class)
class CameraPreviewPathTest {
    @Test
    fun cameraPreviewViewRendersThroughTheViewfinder() {
        val controller = legacyController()
        var root: View? = null

        try {
            runComposeUiTest {
                setContent {
                    root = LocalView.current.rootView
                    CameraPreviewView(controller, Modifier.size(100.dp), DeviceOrientation.PORTRAIT, null)
                }
                waitForIdle()

                val views = viewsUnder(assertNotNull(root, "nothing was composed"))
                assertTrue(views.none { it is PreviewView }, "CameraPreviewView composed a PreviewView: $views")
            }
        } finally {
            controller.cleanup()
        }
    }

    @Test
    fun enginePreviewRendersThroughTheViewfinder() {
        val source = PathTestPreviewSource()
        val request = SurfaceRequest(Size(640, 480), stubCamera(), {})
        // The viewfinder lays a request out once it knows how the buffer maps to the view.
        request.updateTransformationInfo(
            SurfaceRequest.TransformationInfo.of(Rect(0, 0, 640, 480), 0, Surface.ROTATION_0, true, Matrix(), false),
        )
        var root: View? = null

        runComposeUiTest {
            setContent {
                root = LocalView.current.rootView
                EnginePreview(source, DeviceOrientation.PORTRAIT, Modifier.size(100.dp), null)
            }
            waitForIdle()
            val provider = assertNotNull(source.provider, "EnginePreview attached no surface provider")
            assertEquals(1, source.attachments, "EnginePreview attached more than once")
            val attached = viewsUnder(assertNotNull(root, "nothing was composed"))
            assertTrue(attached.none { it is PreviewView }, "EnginePreview composed a PreviewView: $attached")

            runOnUiThread { provider.onSurfaceRequested(request) }
            waitForIdle()

            val views = viewsUnder(assertNotNull(root, "nothing was composed"))
            assertTrue(views.none { it is PreviewView }, "EnginePreview composed a PreviewView: $views")
            assertTrue(views.any { it is TextureView }, "the viewfinder did not lay the request out: $views")
        }
        request.willNotProvideSurface()
    }

    private fun legacyController() = CameraController(
        context = ApplicationProvider.getApplicationContext(),
        lifecycleOwner = ResumedOwner(),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        cameraLens = CameraLens.BACK,
        imageFormat = ImageFormat.JPEG,
        qualityPriority = QualityPrioritization.BALANCED,
        directory = Directory.DOCUMENTS,
        cameraDeviceType = CameraDeviceType.DEFAULT,
        aspectRatio = AspectRatio.RATIO_4_3,
    )
}

private fun viewsUnder(view: View): List<View> {
    val group = view as? ViewGroup ?: return listOf(view)
    return listOf(view) + (0 until group.childCount).flatMap { viewsUnder(group.getChildAt(it)) }
}

private class ResumedOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
}

/** Keeps the provider the preview attaches, so the test can send it a request. */
private class PathTestPreviewSource : PlatformPreviewSource {
    var provider: Preview.SurfaceProvider? = null
    var attachments = 0

    override fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment {
        this.provider = provider
        attachments++
        return object : PreviewAttachment {
            override fun close() = Unit
        }
    }
}

// A LEGACY camera, for which CameraXViewfinder picks a TextureView (see ViewfinderSurfaceRequestTest).
private fun stubCamera(): CameraInternal {
    val info = stub<CameraInfoInternal> { name ->
        if (name == "getImplementationType") CameraInfo.IMPLEMENTATION_TYPE_CAMERA2_LEGACY else null
    }
    return stub { name -> if (name == "getCameraInfo" || name == "getCameraInfoInternal") info else null }
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
