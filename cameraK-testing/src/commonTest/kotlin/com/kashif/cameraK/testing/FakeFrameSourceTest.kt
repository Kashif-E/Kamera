package com.kashif.cameraK.testing

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.utils.CameraKLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The fake frame source's rules: who is active, who is rejected, and who receives an emitted frame.
 * No `@OptIn` here: an app's test drives the fake the same way.
 */
class FakeFrameSourceTest {
    private fun yuvFrame() = TestFrame.luma(2, 2, ByteArray(4))

    @Test
    fun registrationCloseIsIdempotent() {
        val source = FakeFrameSource()
        val first = mutableListOf<CameraFrame>()
        val second = mutableListOf<CameraFrame>()
        val closing = source.register(FrameSpec()) { first += it }
        source.register(FrameSpec()) { second += it }
        assertTrue(closing.active.value, "an accepted registration is active")

        closing.close()
        closing.close()
        assertFalse(closing.active.value, "a closed registration is inactive")
        source.emit(yuvFrame())

        assertEquals(emptyList(), first, "a closed registration received a frame")
        assertEquals(1, second.size, "closing one registration twice cut off another")
        assertEquals(1, source.registrations.size)
    }

    @Test
    fun unacceptedSessionFormatGivesInactiveRegistrationWithUnsupportedAnalysisFormat() {
        val source = FakeFrameSource(sessionFormat = { FrameFormat.RGBA_8888 }, isOpen = { true })
        val received = mutableListOf<CameraFrame>()

        val rejected = source.register(FrameSpec(acceptedFormats = setOf(FrameFormat.YUV_420))) { received += it }

        val rejection = assertIs<CameraKError.Unsupported>(rejected.rejection)
        assertEquals(CameraFeature.ANALYSIS_FORMAT, rejection.feature)
        assertFalse(rejected.active.value, "a rejected registration is active")
        source.emit(TestFrame.rgba(1, 1, ByteArray(4)))
        assertEquals(emptyList(), received, "a rejected registration received a frame")
    }

    @Test
    fun acceptedRegistrationReceivesEveryEmittedFrameInOrder() {
        val source = FakeFrameSource(sessionFormat = { FrameFormat.RGBA_8888 }, isOpen = { true })
        val received = mutableListOf<CameraFrame>()
        val first = TestFrame.rgba(1, 1, ByteArray(4), timestampNanos = 1)
        val second = TestFrame.rgba(1, 1, ByteArray(4), timestampNanos = 2)

        val registration = source.register(FrameSpec(acceptedFormats = setOf(FrameFormat.RGBA_8888))) { received += it }
        source.emit(first)
        source.emit(second)

        assertNull(registration.rejection)
        assertTrue(registration.active.value)
        assertEquals(listOf<CameraFrame>(first, second), received)
    }

    @Test
    fun nothingIsDeliveredWhileTheSessionIsNotOpen() {
        var open = false
        val source = FakeFrameSource(sessionFormat = { FrameFormat.YUV_420 }, isOpen = { open })
        val received = mutableListOf<CameraFrame>()
        source.register(FrameSpec()) { received += it }

        source.emit(yuvFrame())
        assertEquals(emptyList(), received, "a frame reached an analyzer before the session opened")

        open = true
        source.emit(yuvFrame())
        assertEquals(1, received.size, "a frame emitted while open")
    }

    @Test
    fun aFrameInAnotherFormatThanTheSessionIsRefused() {
        val source = FakeFrameSource()

        assertFailsWith<IllegalArgumentException> { source.emit(TestFrame.rgba(1, 1, ByteArray(4))) }
    }

    @Test
    fun aThrowingAnalyzerIsLoggedAndTheOthersStillReceiveTheFrame() {
        val failure = IllegalStateException("decoder failed")
        val logged = mutableListOf<Throwable?>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, _, throwable -> logged += throwable }
        try {
            val source = FakeFrameSource()
            val received = mutableListOf<CameraFrame>()
            source.register(FrameSpec()) { throw failure }
            source.register(FrameSpec()) { received += it }
            val frame = yuvFrame()

            source.emit(frame)

            assertEquals(listOf<CameraFrame>(frame), received, "the analyzer after a throwing one")
            assertEquals(1, logged.size, "logged: $logged")
            assertSame(failure, logged.single())
        } finally {
            CameraKLogger.sink = null
            CameraKLogger.enabled = false
        }
    }

    @Test
    fun aNonFatalFailureIsLoggedAndTheNextFrameIsDelivered() {
        val failure = IllegalStateException("decoder failed")
        val logged = mutableListOf<Throwable?>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, _, throwable -> logged += throwable }
        try {
            val source = FakeFrameSource()
            val received = mutableListOf<CameraFrame>()
            var calls = 0
            source.register(FrameSpec()) {
                calls++
                if (calls == 1) throw failure
                received += it
            }
            val next = yuvFrame()

            source.emit(yuvFrame())
            source.emit(next)

            assertEquals(listOf<CameraFrame>(next), received, "the frame after a non-fatal failure")
            assertSame(failure, logged.single())
        } finally {
            CameraKLogger.sink = null
            CameraKLogger.enabled = false
        }
    }

    @Test
    fun anAssertionErrorInAnAnalyzerIsCaughtAndLogged() {
        val failure = AssertionError("expected a decoded code")
        val logged = mutableListOf<Throwable?>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, _, throwable -> logged += throwable }
        try {
            val source = FakeFrameSource()
            val received = mutableListOf<CameraFrame>()
            source.register(FrameSpec()) { throw failure }
            source.register(FrameSpec()) { received += it }
            val frame = yuvFrame()

            val escaped = runCatching { source.emit(frame) }.exceptionOrNull()

            assertNull(escaped, "emit let a non-fatal AssertionError through")
            assertEquals(listOf<CameraFrame>(frame), received, "the analyzer after the throwing one")
            assertSame(failure, logged.single())
        } finally {
            CameraKLogger.sink = null
            CameraKLogger.enabled = false
        }
    }
}
