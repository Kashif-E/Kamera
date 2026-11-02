package com.kashif.cameraK.controller

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CameraK ships no bytedeco natives on desktop; the app supplies them. When it has not, JavaCV's
 * first native call throws NoClassDefFoundError ("Could not initialize class
 * org.bytedeco.ffmpeg.global.avutil", caused by the UnsatisfiedLinkError for jniavutil). That is an
 * Error, not an Exception, so it must still reach the error handler instead of escaping the grab
 * loop's thread.
 */
class DesktopMissingNativesTest {

    @Test
    fun start_withoutBytedecoNatives_reportsTheMissingNativesThroughTheErrorHandler() = runBlocking {
        val reported = CopyOnWriteArrayList<Throwable>()
        val escaped = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped += e })
        val frames = MutableSharedFlow<BufferedImage>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val sut = CameraGrabber(frames, { reported += it })
        try {
            sut.start(scope, GrabberWithoutNatives())
            withTimeout(10_000) { scope.coroutineContext.job.children.toList().joinAll() }

            assertTrue(escaped.isEmpty(), "escaped the grab loop instead of reaching the error handler: $escaped")
            val report = reported.firstOrNull()
            assertTrue(report != null, "the error handler was never called")
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
