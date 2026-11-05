@file:OptIn(CameraKEngineApi::class, InternalCameraKApi::class)

package com.kashif.cameraK.engine

import android.view.Display
import androidx.camera.core.Preview
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The preview attaches a surface to a session the engine owns. Closing an attachment clears the
 * surface provider only while it is still the current one, so an outgoing preview in a crossfade
 * never blanks the incoming one.
 */
@RunWith(RobolectricTestRunner::class)
class PreviewAttachmentTest {
    private val platform = FakeAndroidPlatform()
    private val first = Preview.SurfaceProvider { }
    private val second = Preview.SurfaceProvider { }

    @Test
    fun staleAttachmentCloseKeepsNewProvider() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)

        val outgoing = engine.preview.attach(first, null)
        val incoming = engine.preview.attach(second, null)
        platform.settle(testScheduler)
        outgoing.close()
        platform.settle(testScheduler)
        assertSame(second, platform.boundPreview().surfaceProvider, "the stale attachment's close blanked the preview")

        incoming.close()
        platform.settle(testScheduler)
        assertNull(platform.boundPreview().surfaceProvider, "closing the current attachment left its provider")
        engine.closeAndJoin()
    }

    @Test
    fun attachmentBeforeStartReachesTheBoundPreview() = runTest {
        val engine = platform.engine(testScheduler)
        engine.preview.attach(first, null)
        platform.settle(testScheduler)
        assertEquals(0, platform.backend.binding.bindCalls, "attaching a preview opened the camera")

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        assertSame(first, platform.boundPreview().surfaceProvider)
        engine.closeAndJoin()
    }

    @Test
    fun theEnginesSourceReportsTheConfiguredAspectRatio() = runTest {
        for (ratio in AspectRatio.entries) {
            val engine = platform.engine(testScheduler, CameraConfiguration { aspectRatio = ratio })
            assertEquals(ratio, engine.preview.previewAspectRatio(), "the engine's preview source at $ratio")
            engine.closeAndJoin()
        }
    }

    @Test
    fun anotherSourceReportsNoAspectRatio() {
        val other = object : PlatformPreviewSource {
            override fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment =
                object : PreviewAttachment {
                    override fun close() = Unit
                }
        }
        assertNull(other.previewAspectRatio(), "a source that is not the engine's")
    }
}
