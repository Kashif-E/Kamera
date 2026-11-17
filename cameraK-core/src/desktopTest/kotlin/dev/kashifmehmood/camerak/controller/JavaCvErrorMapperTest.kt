@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capabilities.LensInfo
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.StorageFailure
import dev.kashifmehmood.camerak.permissions.CameraKPermission
import dev.kashifmehmood.camerak.permissions.PermissionStatus
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.FrameRecorder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The desktop error mapper's ordered table (design §4.1): missing natives first, then the open
 * failures, retried once at the camera's own mode before EBUSY, a macOS denial and any other
 * failure are told apart. Rows 8 and 9 end a recording conditionally on the playable probe (§4.1):
 * the table runs over both values of `playable`.
 */
class JavaCvErrorMapperTest {
    private val granted = { PermissionStatus.Granted }
    private val noStatusRead: () -> PermissionStatus =
        { fail("the authorization status was read for a failure that does not need it") }

    @Test
    fun missingNativesInAllThreeShapesMapToNativeLibraryMissing() {
        val linkage = NoClassDefFoundError("Could not initialize class org.bytedeco.ffmpeg.global.avutil")
        val shapes = listOf(
            "a LinkageError" to linkage,
            "an UnsatisfiedLinkError" to UnsatisfiedLinkError("no jniavutil in java.library.path"),
            // CameraGrabber's missingNatives wrapper (CameraGrabber.kt).
            "CameraGrabber's wrapper" to IllegalStateException("CameraK could not load the bytedeco natives", linkage),
            // FFmpegFrameGrabber.tryLoad wraps whatever the loader threw.
            "JavaCV's tryLoad" to FrameGrabber.Exception(
                "Failed to load class org.bytedeco.javacv.FFmpegFrameGrabber",
                RuntimeException("loader"),
            ),
        )
        for ((shape, failure) in shapes) {
            assertTrue(JavaCvErrorMapper.isNativeLibraryMissing(failure), "$shape is not recognised as missing natives")
            val error = JavaCvErrorMapper.openFailure(failure, OperatingSystem.MAC, noStatusRead)
            val missing = assertIs<CameraKError.NativeLibraryMissing>(error, shape)
            assertEquals(false, missing.isRetryable, shape)
            assertSame(failure, missing.cause, "$shape: the cause is kept")
            assertTrue(missing.platform.isNotEmpty(), "$shape: no platform named")
        }
        assertEquals(false, JavaCvErrorMapper.isNativeLibraryMissing(FrameGrabber.Exception("Could not grab")))
    }

    @Test
    fun nativePlatformUsesTheBytedecoNames() {
        assertEquals("macosx-arm64", JavaCvErrorMapper.nativePlatform("Mac OS X", "aarch64"))
        assertEquals("linux-x86_64", JavaCvErrorMapper.nativePlatform("Linux", "amd64"))
        assertEquals("windows-x86_64", JavaCvErrorMapper.nativePlatform("Windows 11", "x86_64"))
    }

    @Test
    fun openFailureRetriesOnceWithoutConstraints() = runBlocking {
        val constrainedOnly = OpeningGrabber(FrameGrabber.Exception(openError(-22, "Invalid argument")))
        val ownMode = OpeningGrabber(null)
        val source = ScriptedSource(canRelax = true, constrainedOnly, ownMode)
        var owned: FrameGrabber? = null
        val released = mutableListOf<FrameGrabber>()

        val opened = source.open(device, own = { owned = it }, release = { released += it })

        assertSame(ownMode, opened.grabber)
        assertTrue(opened.relaxed, "the open that worked only without constraints is not marked relaxed")
        assertEquals(listOf(true, false), source.constrainedRequests, "creates, constrained first")
        assertEquals(listOf<FrameGrabber>(constrainedOnly), released, "the grabber that failed was not released")
        assertSame(ownMode, owned, "the caller does not own the grabber that opened")
    }

    // A failed avfoundation open, then the retry's open and release, crashed macOS's main thread twice in
    // three full device matrices (objc_release on an AVCaptureDeviceInput); a size known to be missing is
    // never asked for.
    @Test
    fun aDefaultSizeTheCameraLacksOpensAtItsOwnModeWithoutAFailedOpen() = runBlocking {
        val ownMode = OpeningGrabber(null)
        val source = ScriptedSource(canRelax = true, ownMode, known = KnownMode.DefaultMissing)
        val released = mutableListOf<FrameGrabber>()

        val opened = source.open(device, own = {}, release = { released += it }, os = OperatingSystem.MAC)

        assertSame(ownMode, opened.grabber)
        assertTrue(opened.relaxed)
        assertEquals(listOf(false), source.constrainedRequests, "the size the camera lacks was asked for")
        assertEquals(emptyList(), released)
    }

    @Test
    fun aListedSizeIsTheOneAskedFor() = runBlocking {
        val listed = OpeningGrabber(null)
        val source = ScriptedSource(canRelax = true, listed, known = KnownMode.Listed(1920 to 1080))

        val opened = source.open(device, own = {}, release = {}, os = OperatingSystem.MAC)

        assertSame(listed, opened.grabber)
        assertEquals(false, opened.relaxed)
        assertEquals(listOf(1920 to 1080), source.sizeRequests, "the size asked for")
        assertEquals(emptyList(), source.constrainedRequests, "a grabber was made at CameraK's own size")
    }

    // The camera lists the size: a failed open is the camera's, and an own-mode retry after it is the
    // sequence that crashed.
    @Test
    fun aListedSizeIsNotRetriedAtItsOwnModeWhenItsOpenFails() = runBlocking {
        val failure = FrameGrabber.Exception(openError(-16, "Device or resource busy"))
        val source = ScriptedSource(
            canRelax = true,
            OpeningGrabber(failure),
            OpeningGrabber(null),
            known = KnownMode.Listed(1920 to 1080),
        )
        val released = mutableListOf<FrameGrabber>()

        val thrown = assertFailsWith<FrameGrabber.Exception> {
            source.open(device, own = {}, release = { released += it }, os = OperatingSystem.MAC)
        }

        assertSame(failure, thrown)
        assertEquals(listOf(1920 to 1080), source.sizeRequests, "the size asked for")
        assertEquals(emptyList(), source.constrainedRequests, "the camera was opened again at its own mode")
        assertEquals(emptyList(), released, "the caller owns the grabber that failed")
    }

    @Test
    fun openFailureIsNotRetriedWhenTheSourceCannotRelax() = runBlocking {
        val failure = FrameGrabber.Exception(openError(-5, "Input/output error"))
        val source = ScriptedSource(canRelax = false, OpeningGrabber(failure))

        val thrown = assertFailsWith<FrameGrabber.Exception> { source.open(device, own = {}, release = {}) }

        assertSame(failure, thrown)
        assertEquals(listOf(true), source.constrainedRequests)
    }

    @Test
    fun aFailedRetryKeepsTheFirstFailureAsSuppressed() = runBlocking {
        val first = FrameGrabber.Exception(openError(-22, "Invalid argument"))
        val retry = FrameGrabber.Exception(openError(-16, "Device or resource busy"))
        val source = ScriptedSource(canRelax = true, OpeningGrabber(first), OpeningGrabber(retry))
        val released = mutableListOf<FrameGrabber>()

        val thrown = assertFailsWith<FrameGrabber.Exception> {
            source.open(device, own = {}, release = { released += it })
        }

        assertSame(retry, thrown)
        assertEquals(listOf<Throwable>(first), thrown.suppressed.toList())
        assertEquals(1, released.size, "only the first grabber is the opener's to release; the caller owns the retry")
    }

    // A camera whose modes top out at 29.97 (an NTSC capture device) opens at no 30 fps mode; the
    // macOS own-mode open asks for 30 first, then for avfoundation's own default rate, 29.97.
    @Test
    fun onMacAnOwnModeOpenAtCameraKsRateIsTriedOnceMoreWithoutARate() = runBlocking {
        val constrained = OpeningGrabber(FrameGrabber.Exception(openError(-5, "Input/output error")))
        val atThirty = OpeningGrabber(FrameGrabber.Exception(openError(-5, "Input/output error")))
            .apply { frameRate = 30.0 }
        val atDefault = OpeningGrabber(null).apply { frameRate = 30.0 }
        val source = ScriptedSource(canRelax = true, constrained, atThirty, atDefault)
        var owned: FrameGrabber? = null
        val released = mutableListOf<FrameGrabber>()

        val opened = source.open(device, own = { owned = it }, release = { released += it }, os = OperatingSystem.MAC)

        assertSame(atDefault, opened.grabber)
        assertTrue(opened.relaxed)
        assertEquals(0.0, atDefault.frameRate, "the last attempt still asked for a frame rate")
        assertEquals(listOf(true, false, false), source.constrainedRequests)
        assertEquals(listOf<FrameGrabber>(constrained, atThirty), released)
        assertSame(atDefault, owned)
    }

    @Test
    fun offMacAFailedOwnModeOpenIsNotTriedAgain() = runBlocking {
        val failure = FrameGrabber.Exception(openError(-5, "Input/output error"))
        val source = ScriptedSource(
            canRelax = true,
            OpeningGrabber(FrameGrabber.Exception(openError(-22, "Invalid argument"))),
            OpeningGrabber(failure).apply { frameRate = 30.0 },
        )

        val thrown = assertFailsWith<FrameGrabber.Exception> {
            source.open(device, own = {}, release = {}, os = OperatingSystem.LINUX)
        }

        assertSame(failure, thrown)
        assertEquals(listOf(true, false), source.constrainedRequests)
    }

    @Test
    fun missingNativesAreNotRetried() = runBlocking {
        val failure = FrameGrabber.Exception("Failed to load class org.bytedeco.javacv.FFmpegFrameGrabber")
        val source = ScriptedSource(canRelax = true, OpeningGrabber(failure))

        assertFailsWith<FrameGrabber.Exception> { source.open(device, own = {}, release = {}) }
        assertEquals(listOf(true), source.constrainedRequests)
    }

    @Test
    fun ebusyMapsToCameraInUse() {
        val failure = FrameGrabber.Exception(openError(-16, "Device or resource busy"))

        val error = JavaCvErrorMapper.openFailure(failure, OperatingSystem.LINUX, noStatusRead)

        assertIs<CameraKError.CameraInUse>(error)
        assertSame(failure, error.cause)
        assertEquals(-16, JavaCvErrorMapper.platformCode(failure))
    }

    @Test
    fun otherOpenFailureMapsToHardware() {
        val coded = FrameGrabber.Exception(openError(-5, "Input/output error"))
        val hardware =
            assertIs<CameraKError.Hardware>(JavaCvErrorMapper.openFailure(coded, OperatingSystem.LINUX, granted))
        assertEquals(-5, hardware.platformCode)
        assertSame(coded, hardware.cause)
        assertTrue(hardware.isRetryable)

        // The code may sit lower in the cause chain.
        val wrapped = IllegalStateException("open failed", coded)
        val fromChain = JavaCvErrorMapper.openFailure(wrapped, OperatingSystem.LINUX, granted)
        assertEquals(-5, assertIs<CameraKError.Hardware>(fromChain).platformCode)

        val uncoded = Exception("no camera in unit tests")
        val withoutCode = JavaCvErrorMapper.openFailure(uncoded, OperatingSystem.WINDOWS, granted)
        assertEquals(null, assertIs<CameraKError.Hardware>(withoutCode).platformCode)

        // A granted or undetermined camera on macOS is not a permission failure.
        for (status in listOf(PermissionStatus.Granted, PermissionStatus.NotDetermined)) {
            val error = JavaCvErrorMapper.openFailure(coded, OperatingSystem.MAC) { status }
            assertIs<CameraKError.Hardware>(error, "$status")
        }
    }

    @Test
    fun macDeniedStatusMapsToPermission() {
        val failure = FrameGrabber.Exception(openError(-5, "Input/output error"))
        val cases = listOf(
            PermissionStatus.PermanentlyDenied to PermissionStatus.PermanentlyDenied,
            PermissionStatus.Denied(shouldShowRationale = false) to PermissionStatus.PermanentlyDenied,
            PermissionStatus.Restricted to PermissionStatus.Restricted,
        )
        for ((read, expected) in cases) {
            val error = JavaCvErrorMapper.openFailure(failure, OperatingSystem.MAC) { read }
            val permission = assertIs<CameraKError.Permission>(error, "status $read")
            assertEquals(CameraKPermission.CAMERA, permission.permission)
            assertEquals(expected, permission.status, "status $read")
            assertSame(failure, permission.cause)
        }
        // Only macOS reads the status: nothing JavaCV reports elsewhere is a permission denial.
        val elsewhere = JavaCvErrorMapper.openFailure(failure, OperatingSystem.LINUX, noStatusRead)
        assertIs<CameraKError.Hardware>(elsewhere)
    }

    private fun openError(code: Int, reason: String) =
        "avformat_open_input() error $code: Could not open input \"0\". (Has setFormat() been called?) $reason"

    @Test
    fun recordingFailuresFollowRowsEightAndNineOverBothPlayableValues() {
        val path = "/home/me/Movies/CameraK/VID_1.mp4"
        val encoder = FrameRecorder.Exception("av_interleaved_write_frame() error -22 while writing video packet")
        val io = FrameRecorder.Exception("Could not write the packet", IOException("Input/output error"))
        val noSpace = FrameRecorder.Exception("av_interleaved_write_frame() error -28 while writing video packet")

        // Row 9: an encoder error keeps a playable file (Success(partial)), else Hardware with FFmpeg's code.
        assertNull(JavaCvErrorMapper.recordingFailure(encoder, playable = true, path, diskFull = false))
        val hardware = assertIs<CameraKError.Hardware>(
            JavaCvErrorMapper.recordingFailure(encoder, playable = false, path, diskFull = false),
        )
        assertEquals(-22, hardware.platformCode)
        assertSame(encoder, hardware.cause)

        // Row 8: an I/O failure keeps a playable file, else NOT_WRITABLE.
        assertNull(JavaCvErrorMapper.recordingFailure(io, playable = true, path, diskFull = false))
        val unwritable = assertIs<CameraKError.Storage>(
            JavaCvErrorMapper.recordingFailure(io, playable = false, path, diskFull = false),
        )
        assertEquals(StorageFailure.NOT_WRITABLE, unwritable.kind)
        assertNull(unwritable.keptFilePath)

        // A full disk is the one exception to the partial-file rule: FULL either way, the file kept when playable.
        for ((failure, diskFull) in listOf(io to true, noSpace to false)) {
            val kept = assertIs<CameraKError.Storage>(JavaCvErrorMapper.recordingFailure(failure, true, path, diskFull))
            assertEquals(StorageFailure.FULL, kept.kind)
            assertEquals(path, kept.keptFilePath, "a playable file on a full disk is kept")
            val lost =
                assertIs<CameraKError.Storage>(JavaCvErrorMapper.recordingFailure(failure, false, path, diskFull))
            assertEquals(StorageFailure.FULL, lost.kind)
            assertNull(lost.keptFilePath, "an unplayable file on a full disk is not kept")
        }
    }

    @Test
    fun captureWriteFailuresAreStorageErrors() {
        val io = IOException("No space left on device")
        assertEquals(
            StorageFailure.NOT_WRITABLE,
            assertIs<CameraKError.Storage>(JavaCvErrorMapper.captureFailure(io, diskFull = false)).kind,
        )
        assertEquals(
            StorageFailure.FULL,
            assertIs<CameraKError.Storage>(JavaCvErrorMapper.captureFailure(io, diskFull = true)).kind,
        )
        val other = IllegalStateException("no writer accepts this image")
        assertSame(other, assertIs<CameraKError.Internal>(JavaCvErrorMapper.captureFailure(other, false)).cause)
    }

    private val device =
        LensInfo("", CameraDeviceType.DEFAULT, CameraLens.BACK, 1f, 1f, hasFlash = false, isLogical = false)

    private class ScriptedSource(
        override val canRelax: Boolean,
        vararg grabbers: FrameGrabber,
        private val known: KnownMode? = null,
    ) : GrabberSource {
        private val next = ArrayDeque(grabbers.toList())
        val constrainedRequests = mutableListOf<Boolean>()
        val sizeRequests = mutableListOf<Pair<Int, Int>>()
        override fun deviceKey(device: LensInfo): Any = this

        override fun knownMode(device: LensInfo): KnownMode? = known

        override fun create(device: LensInfo, constrained: Boolean): FrameGrabber {
            constrainedRequests += constrained
            return next.removeFirst()
        }

        override fun create(device: LensInfo, size: Pair<Int, Int>): FrameGrabber {
            sizeRequests += size
            return next.removeFirst()
        }
    }

    /** A grabber whose start() throws [failure], or opens when it is null. */
    private class OpeningGrabber(private val failure: Exception?) : FrameGrabber() {
        override fun start() {
            failure?.let { throw it }
        }

        override fun stop() = Unit

        override fun trigger() = Unit

        override fun grab(): Frame? = null

        override fun release() = Unit
    }
}
