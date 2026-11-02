package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Capture listeners are added and removed by plugins on whatever thread they run, while captures are
 * delivered on the processing executor. Listeners registered at the same time from different threads
 * must all be kept, and those removed must all be gone.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureListenerConcurrencyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend, imageProcessingExecutor = processingExecutor)
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        hostScope.cancel()
        processingExecutor.shutdownNow()
    }

    @Test
    fun listenersAddedAndRemovedConcurrently_areAllKeptOrAllGone() {
        val delivered = AtomicInteger()
        val removedDelivered = AtomicInteger()
        val toRemove = List(THREADS * PER_THREAD) {
            { _: ByteArray ->
                removedDelivered.incrementAndGet()
                Unit
            }
        }
        toRemove.forEach(controller::addImageCaptureListener)
        val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val start = CountDownLatch(1)

        val workers = List(THREADS) { index ->
            thread(name = "registrar-$index") {
                try {
                    start.await()
                    repeat(PER_THREAD) { n ->
                        controller.addImageCaptureListener { delivered.incrementAndGet() }
                        controller.removeImageCaptureListener(toRemove[index * PER_THREAD + n])
                    }
                } catch (e: Throwable) {
                    failures += e
                }
            }
        }
        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        assertEquals(emptyList(), failures.map { it.toString() }, "registering a listener threw")

        captureAndSave()

        assertEquals(THREADS * PER_THREAD, delivered.get(), "listeners added concurrently were lost")
        assertEquals(0, removedDelivered.get(), "listeners removed concurrently still got the image")
    }

    private fun captureAndSave() {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        backend.captures.single().deliverSaved(byteArrayOf(4, 2))
        idleMainLooper()
        processingExecutor.shutdown()
        assertTrue(processingExecutor.awaitTermination(30, TimeUnit.SECONDS), "listener work did not finish")
    }

    private companion object {
        const val THREADS = 8
        const val PER_THREAD = 2_000
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
