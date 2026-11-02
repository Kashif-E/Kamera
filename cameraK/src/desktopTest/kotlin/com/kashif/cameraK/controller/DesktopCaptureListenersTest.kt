package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.ImageCaptureResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Capture listeners on desktop are a set, as on Android and iOS: adding one never drops another,
 * so an auto-saving plugin and an app listener both see every capture.
 */
class DesktopCaptureListenersTest {

    private val home: File = Files.createTempDirectory("camerak-home").toFile()
    private val sut = CameraController(
        imageFormat = ImageFormat.JPEG,
        directory = Directory.PICTURES,
        customGrabber = FakeFrameGrabber().apply { openGate() },
        sessionDispatcher = Dispatchers.Unconfined,
        recorderFactory = { _, _ -> FakeFrameRecorder() },
        userHome = home,
    )

    @AfterTest
    fun tearDown() {
        sut.cleanup()
        home.deleteRecursively()
    }

    private fun capture(): File = runBlocking {
        val result = withTimeout(10_000) { sut.takePictureToFile() }
        File(assertIs<ImageCaptureResult.SuccessWithFile>(result, "capture result: $result").filePath)
    }

    @Test
    fun twoListeners_bothReceiveTheCapture() {
        var first: ByteArray? = null
        var second: ByteArray? = null
        sut.addImageCaptureListener { first = it }
        sut.addImageCaptureListener { second = it }
        sut.startSession()

        val bytes = capture().readBytes()

        assertContentEquals(bytes, assertNotNull(first, "the first listener got nothing"))
        assertContentEquals(bytes, assertNotNull(second, "the second listener got nothing"))
    }

    @Test
    fun removingOneListener_keepsTheOther() {
        var removedCalls = 0
        var keptCalls = 0
        val removed: (ByteArray) -> Unit = { removedCalls++ }
        sut.addImageCaptureListener { keptCalls++ }
        sut.addImageCaptureListener(removed)
        sut.removeImageCaptureListener(removed)
        sut.startSession()

        capture()

        assertEquals(1, keptCalls, "calls to the listener that stayed")
        assertEquals(0, removedCalls, "calls to the removed listener")
    }

    @Test
    fun aThrowingListener_doesNotStopTheNextOne() {
        var received: ByteArray? = null
        sut.addImageCaptureListener { throw IllegalStateException("a consumer's listener failed") }
        sut.addImageCaptureListener { received = it }
        sut.startSession()

        val bytes = capture().readBytes()

        assertContentEquals(bytes, assertNotNull(received, "the listener after a throwing one got nothing"))
    }
}
