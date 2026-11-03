@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The deprecated 1.x facade is live over the engine: until the plugins move to the 2.0 frame and
 * capture APIs, they register analyzers and capture listeners on `Ready.controller`, which is this
 * facade, read through LegacyControllerAccess with no holder.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyFacadeLiveTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun analyzerRegisteredThroughTheFacadeReceivesFrames() = runTest {
        val engine = platform.engine(testScheduler)
        val facade = (engine as LegacyControllerAccess).legacyController
        val received = mutableListOf<ImageProxy>()
        facade.registerImageAnalyzer(
            ImageAnalysis.Analyzer {
                received += it
                it.close()
            },
        )

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)

        val analysis = platform.backend.binding.bound.filterIsInstance<ImageAnalysis>().single()
        val dispatcher = assertNotNull(platform.backend.analyzers[analysis], "the bound ImageAnalysis feeds no one")
        var released = 0
        dispatcher.analyze(imageProxy { released++ })
        // The 1.x multiplexer hands each analyzer its own view of the frame and releases the frame
        // once every view is closed.
        assertEquals(1, received.size, "the facade's analyzer did not get the engine's frame")
        assertEquals(1, released, "the frame was not released after the analyzer closed its view")
        engine.closeAndJoin()
    }

    @Test
    fun captureListenerOnTheFacadeFires() = runTest {
        val engine = platform.engine(testScheduler)
        val facade = (engine as LegacyControllerAccess).legacyController
        val heard = CountDownLatch(1)
        val delivered = mutableListOf<ByteArray>()
        facade.addImageCaptureListener {
            delivered += it
            heard.countDown()
        }
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)

        val capture = async { facade.takePictureToFile() }
        platform.settle(testScheduler)
        platform.backend.captures.single().deliverSaved(BYTES)
        platform.settle(testScheduler)

        withContext(Dispatchers.Default) { assertTrue(heard.await(5, TimeUnit.SECONDS), "the listener never ran") }
        assertContentEquals(BYTES, delivered.single())
        platform.settle(testScheduler)
        assertIs<ImageCaptureResult.SuccessWithFile>(capture.await())
        engine.closeAndJoin()
    }

    @Test
    fun createPlatformEngineReturnsTheAndroidEngineWithItsFacade() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val engine = createPlatformEngine(
            CameraConfiguration(),
            CameraKPlatformContext(platform.context),
            CameraKEnvironment(dispatchers = CameraKDispatchers(main = main, default = main, io = main)),
        )

        val android = assertIs<AndroidCameraEngine>(engine, "the bridge still returns the 1.x adapter")
        assertSame(android, android.legacyController.engine, "the facade is not over the engine the bridge returned")
        engine.closeAndJoin()
    }

    private fun imageProxy(onClose: () -> Unit): ImageProxy = Proxy.newProxyInstance(
        ImageProxy::class.java.classLoader,
        arrayOf(ImageProxy::class.java),
    ) { self, method, args ->
        when (method.name) {
            "close" -> {
                onClose()
                null
            }
            "getWidth", "getHeight", "getFormat" -> 0
            "getPlanes" -> emptyArray<ImageProxy.PlaneProxy>()
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.firstOrNull()
            "toString" -> "TestFrame"
            else -> null
        }
    } as ImageProxy

    private companion object {
        val BYTES = byteArrayOf(4, 5, 6)
    }
}
