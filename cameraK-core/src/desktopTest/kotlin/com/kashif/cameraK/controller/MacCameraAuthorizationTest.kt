package com.kashif.cameraK.controller

import com.kashif.cameraK.permissions.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * macOS camera authorization is `[AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo]`,
 * read through the Objective-C runtime over JNA. Without JNA at runtime nothing can be read, and
 * the status is NotDetermined, which lets the open itself show macOS's prompt (Decision 15).
 */
class MacCameraAuthorizationTest {
    @Test
    fun jnaAbsentReportsNotDetermined() {
        val withoutJna = MacCameraAuthorization { throw NoClassDefFoundError("com/sun/jna/Native") }

        assertEquals(PermissionStatus.NotDetermined, withoutJna.cameraStatus())
    }

    @Test
    fun statusValuesMapToPermissionStatus() {
        val expected = mapOf(
            0L to PermissionStatus.NotDetermined,
            1L to PermissionStatus.Restricted,
            2L to PermissionStatus.PermanentlyDenied,
            3L to PermissionStatus.Granted,
            // A value a later macOS might add is not a grant.
            7L to PermissionStatus.NotDetermined,
        )
        for ((raw, status) in expected) {
            val runtime = FakeObjCRuntime(raw)
            assertEquals(status, MacCameraAuthorization { runtime }.cameraStatus(), "AVAuthorizationStatus $raw")
            val statusRead = FakeObjCRuntime.Call(
                FakeObjCRuntime.DEVICE_CLASS,
                FakeObjCRuntime.STATUS_SELECTOR,
                FakeObjCRuntime.VIDEO,
            )
            assertEquals(
                listOf(statusRead),
                runtime.calls,
                "the message sent for AVAuthorizationStatus $raw",
            )
        }
    }

    @Test
    fun aRuntimeThatFailsReportsNotDetermined() {
        val failing = object : ObjCRuntime by FakeObjCRuntime(3) {
            override fun pointerConstant(library: String, symbol: String): Long =
                throw UnsatisfiedLinkError("AVFoundation is not loaded")
        }

        assertEquals(PermissionStatus.NotDetermined, MacCameraAuthorization { failing }.cameraStatus())
    }

    @Test
    fun aMissingClassReportsNotDetermined() {
        val noAvFoundation = object : ObjCRuntime by FakeObjCRuntime(3) {
            override fun getClass(name: String): Long = 0
        }

        assertEquals(PermissionStatus.NotDetermined, MacCameraAuthorization { noAvFoundation }.cameraStatus())
    }

    /** Answers [status] to `authorizationStatusForMediaType:` sent to AVCaptureDevice with AVMediaTypeVideo. */
    private class FakeObjCRuntime(private val status: Long) : ObjCRuntime {
        data class Call(val receiver: Long, val selector: Long, val argument: Long)

        val calls = mutableListOf<Call>()

        override fun getClass(name: String): Long = if (name == "AVCaptureDevice") DEVICE_CLASS else 0

        override fun selector(name: String): Long =
            if (name == "authorizationStatusForMediaType:") STATUS_SELECTOR else OTHER

        override fun sendLong(receiver: Long, selector: Long, argument: Long): Long {
            calls += Call(receiver, selector, argument)
            return if (receiver == DEVICE_CLASS && selector == STATUS_SELECTOR && argument == VIDEO) status else -1
        }

        override fun pointerConstant(library: String, symbol: String): Long =
            if (library == "AVFoundation" && symbol == "AVMediaTypeVideo") VIDEO else 0

        companion object {
            const val DEVICE_CLASS = 0x1000L
            const val STATUS_SELECTOR = 0x2000L
            const val VIDEO = 0x3000L
            const val OTHER = 0x4000L
        }
    }
}
