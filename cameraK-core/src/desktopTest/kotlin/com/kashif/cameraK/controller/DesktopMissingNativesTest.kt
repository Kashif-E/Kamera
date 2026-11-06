package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.newDedicatedWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CameraK ships no bytedeco natives on desktop; the app supplies them. When it has not, JavaCV's
 * first native call throws NoClassDefFoundError ("Could not initialize class
 * org.bytedeco.ffmpeg.global.avutil", caused by the UnsatisfiedLinkError for jniavutil). That is an
 * Error, not an Exception, so it must still reach the run's listener, as a failure before the
 * grabber opened, instead of escaping the grab loop's thread.
 */
class DesktopMissingNativesTest {

    @Test
    fun start_withoutBytedecoNatives_reportsTheMissingNativesThroughTheListener() = runBlocking {
        val listener = RecordingGrabLoopListener()
        val escaped = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped += e })
        val sut = CameraGrabber()
        try {
            val run = sut.startRun(
                scope,
                CustomGrabberSource(GrabberWithoutNatives()),
                desktopLensInfo(),
                ::newDedicatedWorker,
                listener,
            )
            withTimeout(10_000) { run.join() }

            assertTrue(escaped.isEmpty(), "escaped the grab loop instead of reaching the listener: $escaped")
            val (report, opened) = listener.failures.firstOrNull() ?: (null to null)
            assertTrue(report != null, "the listener's onFailure was never called")
            assertEquals(false, opened, "the failure says the grabber opened")
            assertTrue(
                report.causes().any { it is NoClassDefFoundError },
                "the report does not carry the linkage error: $report",
            )
            assertTrue(
                "bytedeco" in report.message.orEmpty() && "natives" in report.message.orEmpty(),
                "the report does not say which natives are missing: ${report.message}",
            )
        } finally {
            sut.stop()
            scope.cancel()
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    /** Every native call fails the way JavaCV's grabbers do when no natives jar is on the classpath. */
    private class GrabberWithoutNatives : FrameGrabber() {
        private fun missingNatives(): Nothing = throw NoClassDefFoundError(
            "Could not initialize class org.bytedeco.ffmpeg.global.avutil",
        ).apply {
            initCause(ExceptionInInitializerError(UnsatisfiedLinkError("no jniavutil in java.library.path").message))
        }

        override fun start(): Unit = missingNatives()

        override fun stop(): Unit = missingNatives()

        override fun trigger(): Unit = missingNatives()

        override fun grab(): Frame = missingNatives()

        override fun release(): Unit = missingNatives()
    }
}
