@file:OptIn(CameraKEngineApi::class, InternalCameraKApi::class)

package com.kashif.cameraK.engine

import android.view.Display
import androidx.camera.core.Preview
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The preview attaches a surface to a session the engine owns. Closing an attachment takes its
 * surface provider away only while it is still the current one, so an outgoing preview in a crossfade
 * never blanks the incoming one. The last one's close hands the preview to a stand-in that drops the
 * frames: a Preview left without a surface mid-session keeps no repeating request in CameraX 1.5.3, so
 * no capture result arrives, and a photo or an exposure change never completes (Galaxy S23). Giving the
 * Preview a surface rebuilds the capture session, so the stand-in comes only after a grace period, which
 * a preview attaching again, as on a rotation, cuts short.
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
        advanceTimeBy(STAND_IN_GRACE_MS + 1)
        platform.settle(testScheduler)
        val standIn = platform.boundPreview().surfaceProvider
        assertNotNull(standIn, "closing the current attachment left the preview without a surface")
        assertNotSame(second, standIn, "closing the current attachment left its provider")
        engine.closeAndJoin()
    }

    @Test
    fun theStandInComesOnlyAfterTheGracePeriod() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        val attachment = engine.preview.attach(first, null)
        platform.settle(testScheduler)

        attachment.close()
        platform.settle(testScheduler)
        advanceTimeBy(STAND_IN_GRACE_MS - 1)
        platform.settle(testScheduler)

        assertNull(platform.boundPreview().surfaceProvider, "the stand-in came before the grace period ended")
        engine.closeAndJoin()
    }

    // A rotation recreates the Activity: the old preview closes, the new one attaches. A stand-in between
    // them would rebuild the capture session twice.
    @Test
    fun aPreviewAttachedWithinTheGracePeriodKeepsItsSurface() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        val outgoing = engine.preview.attach(first, null)
        platform.settle(testScheduler)

        outgoing.close()
        platform.settle(testScheduler)
        engine.preview.attach(second, null)
        platform.settle(testScheduler)
        advanceTimeBy(STAND_IN_GRACE_MS + 1)
        platform.settle(testScheduler)

        assertSame(second, platform.boundPreview().surfaceProvider, "the stand-in replaced the new preview")
        engine.closeAndJoin()
    }

    // Two previews of one holder, as in a preview in a dialog over a screen's: the newer draws. Once it
    // closes, the older draws again instead of a stand-in.
    @Test
    fun closingTheNewestPreviewGivesTheCameraBackToTheOneBefore() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        engine.preview.attach(first, null)
        val dialog = engine.preview.attach(second, null)
        platform.settle(testScheduler)

        dialog.close()
        platform.settle(testScheduler)
        advanceTimeBy(STAND_IN_GRACE_MS + 1)
        platform.settle(testScheduler)

        assertSame(first, platform.boundPreview().surfaceProvider, "the older preview did not get the camera back")
        engine.closeAndJoin()
    }

    // Closing previews in any order leaves the newest still open drawing.
    @Test
    fun closingAnOlderPreviewKeepsTheNewestDrawing() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        val third = Preview.SurfaceProvider { }
        engine.preview.attach(first, null)
        val middle = engine.preview.attach(second, null)
        val newest = engine.preview.attach(third, null)
        platform.settle(testScheduler)

        middle.close()
        platform.settle(testScheduler)
        assertSame(third, platform.boundPreview().surfaceProvider, "closing an older preview took the newest's surface")

        newest.close()
        platform.settle(testScheduler)

        assertSame(first, platform.boundPreview().surfaceProvider, "the oldest preview did not get the camera back")
        engine.closeAndJoin()
    }

    // An ImageAnalysis with an analyzer keeps the capture session's repeating request with no preview
    // surface, so a stand-in would only add a stream.
    @Test
    fun withAnAnalyzerNoStandInComes() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        engine.frames.register(FrameSpec(synchronous = true)) { }
        platform.settle(testScheduler)
        val attachment = engine.preview.attach(first, null)
        platform.settle(testScheduler)

        attachment.close()
        platform.settle(testScheduler)
        advanceTimeBy(STAND_IN_GRACE_MS + 1)
        platform.settle(testScheduler)

        assertNull(platform.boundPreview().surfaceProvider, "a stand-in came beside a streaming ImageAnalysis")
        engine.closeAndJoin()
    }

    // Without an analyzer the ImageAnalysis is inactive in CameraX and streams nothing: the stand-in takes
    // over before the last analyzer is cleared.
    @Test
    fun theStandInComesBeforeTheLastAnalyzerLeaves() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        val registration = engine.frames.register(FrameSpec(synchronous = true)) { }
        platform.settle(testScheduler)
        val attachment = engine.preview.attach(first, null)
        platform.settle(testScheduler)
        attachment.close()
        platform.settle(testScheduler)
        advanceTimeBy(STAND_IN_GRACE_MS + 1)
        platform.settle(testScheduler)
        val preview = platform.boundPreview()
        var providerAsTheAnalyzerWent: Preview.SurfaceProvider? = null
        platform.backend.beforeClearAnalyzer = { providerAsTheAnalyzerWent = preview.surfaceProvider }

        registration.close()
        platform.settle(testScheduler)

        assertNotNull(providerAsTheAnalyzerWent, "the analyzer was cleared before the stand-in came")
        engine.closeAndJoin()
    }

    // CameraX gives a session with no other stream one of its own, but counts an ImageAnalysis as one even
    // without an analyzer, when it streams nothing: a photo then never completed (Galaxy S23).
    @Test
    fun aBindWithAnIdleAnalysisAndNoPreviewHasTheStandIn() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)

        assertNotNull(platform.boundPreview().surfaceProvider, "a bind with an idle ImageAnalysis has no stream")
        engine.closeAndJoin()
    }

    // A registration closed on the analysis thread posts its consumer change to main; a rebind queued on main
    // before it builds the new ImageAnalysis without an analyzer, so the bind must not count the old one's stream.
    @Test
    fun aRebindAsTheLastAnalyzerLeavesHasTheStandIn() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        val registration = engine.frames.register(FrameSpec(synchronous = true)) { }
        platform.settle(testScheduler)
        val applyConsumers = engine.framePipeline.onConsumersChanged
        engine.framePipeline.onConsumersChanged = {}

        registration.close()
        val switch = async { engine.selectCamera(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)) }
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<*>>(switch.await(), "the lens switch")

        assertNotNull(platform.boundPreview().surfaceProvider, "the rebind left no stream as the last analyzer went")
        engine.framePipeline.onConsumersChanged = applyConsumers
        engine.closeAndJoin()
    }

    // An idle ImageAnalysis, bound with no analyzer, streams nothing: the stand-in comes as without one.
    @Test
    fun withAnIdleAnalysisTheStandInComes() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        val attachment = engine.preview.attach(first, null)
        platform.settle(testScheduler)

        attachment.close()
        platform.settle(testScheduler)
        advanceTimeBy(STAND_IN_GRACE_MS + 1)
        platform.settle(testScheduler)

        assertNotNull(platform.boundPreview().surfaceProvider, "no stand-in beside an idle ImageAnalysis")
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

    private companion object {
        /** EnginePreviewSource's grace period before the stand-in surface. */
        const val STAND_IN_GRACE_MS = 1_000L
    }
}
