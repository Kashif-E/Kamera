package com.kashif.cameraK.error

import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CameraKErrorTest {
    private val cause = IllegalStateException("platform failure")

    /** Every subtype, built with [cause] wherever it takes one, and whether it is retryable. */
    private val retryability: List<Pair<CameraKError, Boolean>> = listOf(
        CameraKError.Permission(CameraKPermission.CAMERA, PermissionStatus.Denied(false), cause) to true,
        CameraKError.CameraInUse(cause) to true,
        CameraKError.CameraDisabled(cause) to false,
        CameraKError.NoCamera(CameraLens.FRONT, cause) to false,
        CameraKError.Disconnected(cause) to true,
        CameraKError.Storage(StorageFailure.NOT_WRITABLE, cause = cause) to true,
        CameraKError.Unsupported(CameraFeature.TORCH, cause) to false,
        CameraKError.NativeLibraryMissing("macosx-arm64", cause) to false,
        CameraKError.Hardware(3, cause) to true,
        CameraKError.Timeout(CameraOperation.START, cause) to true,
        CameraKError.Busy(CameraOperation.CAPTURE) to true,
        CameraKError.InvalidState(CameraOperation.RECORD_STOP) to false,
        CameraKError.NotReady to true,
        CameraKError.Closed to false,
        CameraKError.Internal(cause) to false,
    )

    @Test
    fun retryabilityPerSubtype() {
        val wrong = retryability.filter { (error, retryable) -> error.isRetryable != retryable }
            .map { (error, retryable) ->
                "${error::class.simpleName} should be ${if (retryable) "" else "non-"}retryable"
            }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun storageFullIsNotRetryable() {
        assertFalse(CameraKError.Storage(StorageFailure.FULL).isRetryable)
        listOf(StorageFailure.NOT_WRITABLE, StorageFailure.NOT_FOUND, StorageFailure.GALLERY_IMPORT_FAILED).forEach {
            assertTrue(CameraKError.Storage(it).isRetryable, "Storage($it) should be retryable")
        }
    }

    @Test
    fun storageCarriesKeptFilePath() {
        val kept = CameraKError.Storage(StorageFailure.FULL, keptFilePath = "/app/files/VID_1.mp4")
        assertEquals("/app/files/VID_1.mp4", kept.keptFilePath)
        assertEquals(StorageFailure.FULL, kept.kind)
        assertNull(CameraKError.Storage(StorageFailure.NOT_WRITABLE).keptFilePath, "nothing kept unless named")
    }

    @Test
    fun nativeLibraryMissingIsNotRetryable() {
        val error = CameraKError.NativeLibraryMissing("linux-x86_64")
        assertFalse(error.isRetryable)
        assertEquals("linux-x86_64", error.platform)
    }

    @Test
    fun causeIsPreserved() {
        val withoutCause =
            setOf(
                CameraKError.Busy::class,
                CameraKError.InvalidState::class,
                CameraKError.NotReady::class,
                CameraKError.Closed::class,
            )
        val lost = retryability.map { it.first }
            .filter { it::class !in withoutCause && it.cause !== cause }
            .map { it::class.simpleName }
        assertTrue(lost.isEmpty(), "these errors lost their cause: $lost")
        assertNull(CameraKError.Internal(null).cause)
        assertNull(CameraKError.Busy(CameraOperation.ZOOM).cause, "Busy has no platform cause")
    }

    @Test
    fun permissionNeedsSettingsOnlyWhenPermanentlyDenied() {
        assertTrue(CameraKError.Permission(CameraKPermission.CAMERA, PermissionStatus.PermanentlyDenied).needsSettings)
        assertFalse(CameraKError.Permission(CameraKPermission.CAMERA, PermissionStatus.Denied(true)).needsSettings)
        assertSame(
            CameraKPermission.MICROPHONE,
            CameraKError.Permission(CameraKPermission.MICROPHONE, PermissionStatus.NotDetermined).permission,
        )
    }
}
