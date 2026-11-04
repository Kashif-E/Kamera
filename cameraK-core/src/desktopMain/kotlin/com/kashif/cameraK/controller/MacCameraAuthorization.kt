package com.kashif.cameraK.controller

import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.utils.CameraKLogger
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/** The camera's authorization status as the OS reports it. */
internal fun interface CameraAuthorization {
    fun cameraStatus(): PermissionStatus
}

/** The Objective-C runtime calls the macOS authorization read needs. Pointers are raw addresses; 0 is nil. */
internal interface ObjCRuntime {
    fun getClass(name: String): Long

    fun selector(name: String): Long

    /** `objc_msgSend(receiver, selector, argument)` for a method that returns an NSInteger. */
    fun sendLong(receiver: Long, selector: Long, argument: Long): Long

    /** The value of the pointer constant [symbol] exported by [library]. */
    fun pointerConstant(library: String, symbol: String): Long

    /** `objc_msgSend(receiver, selector)` for a method with no argument that returns an object or an NSInteger. */
    fun send(receiver: Long, selector: Long): Long

    /** The text of the NSString at [string]; null for nil. */
    fun string(string: Long): String?
}

/** The AVFoundation media types whose authorization CameraK reads. */
internal enum class MacMediaType(val constant: String) {
    VIDEO("AVMediaTypeVideo"),
    AUDIO("AVMediaTypeAudio"),
}

/**
 * macOS camera authorization: `[AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo]`,
 * read through the Objective-C runtime over JNA (Decision 15). JNA is compile-only: an app that
 * does not ship it gets NotDetermined, which the desktop permission checker allows to open, so the
 * open itself shows macOS's prompt. Authorized is Granted, Denied is PermanentlyDenied (macOS never
 * asks again), Restricted is Restricted. The error mapper reads the camera's; PM''s desktop
 * permission checker reads the camera's and, through [status], the microphone's with the same
 * mapping and the same rule without JNA.
 */
internal class MacCameraAuthorization(
    // A lambda, not a constructor reference, so nothing resolves JnaObjCRuntime before the read.
    private val runtime: () -> ObjCRuntime = { JnaObjCRuntime() },
) : CameraAuthorization {
    /** The authorization status of [type]; NotDetermined whenever it cannot be read. */
    fun status(type: MacMediaType): PermissionStatus {
        val objc = try {
            runtime()
        } catch (e: LinkageError) {
            CameraKLogger.w(TAG, "JNA is not available, so the AVFoundation authorization reads as not determined", e)
            return PermissionStatus.NotDetermined
        }
        return try {
            // Reading the constant loads AVFoundation, which registers AVCaptureDevice with the runtime.
            val mediaType = objc.pointerConstant("AVFoundation", type.constant)
            val device = objc.getClass("AVCaptureDevice")
            if (device == 0L || mediaType == 0L) {
                CameraKLogger.w(TAG, "AVFoundation is not loaded, so the authorization reads as not determined")
                return PermissionStatus.NotDetermined
            }
            statusOf(objc.sendLong(device, objc.selector("authorizationStatusForMediaType:"), mediaType))
        } catch (e: Exception) {
            CameraKLogger.w(TAG, "Reading an AVFoundation authorization failed; it reads as not determined", e)
            PermissionStatus.NotDetermined
        } catch (e: LinkageError) {
            CameraKLogger.w(TAG, "Reading an AVFoundation authorization failed; it reads as not determined", e)
            PermissionStatus.NotDetermined
        }
    }

    /** The camera's status: [status] of [MacMediaType.VIDEO]. */
    override fun cameraStatus(): PermissionStatus = status(MacMediaType.VIDEO)

    private fun statusOf(raw: Long): PermissionStatus = when (raw) {
        AUTHORIZED -> PermissionStatus.Granted
        DENIED -> PermissionStatus.PermanentlyDenied
        RESTRICTED -> PermissionStatus.Restricted
        // NotDetermined, and any status a later macOS adds: neither is a grant.
        else -> PermissionStatus.NotDetermined
    }

    private companion object {
        const val TAG = "CameraK"

        // AVAuthorizationStatus
        const val RESTRICTED = 1L
        const val DENIED = 2L
        const val AUTHORIZED = 3L
    }
}

/** The Objective-C runtime over JNA. Loading this class throws NoClassDefFoundError when JNA is absent. */
internal class JnaObjCRuntime : ObjCRuntime {
    private val objc = NativeLibrary.getInstance("objc")

    override fun getClass(name: String): Long =
        Pointer.nativeValue(objc.getFunction("objc_getClass").invokePointer(arrayOf(name)))

    override fun selector(name: String): Long =
        Pointer.nativeValue(objc.getFunction("sel_registerName").invokePointer(arrayOf(name)))

    override fun sendLong(receiver: Long, selector: Long, argument: Long): Long =
        objc.getFunction("objc_msgSend").invokeLong(arrayOf(Pointer(receiver), Pointer(selector), Pointer(argument)))

    override fun pointerConstant(library: String, symbol: String): Long =
        Pointer.nativeValue(NativeLibrary.getInstance(library).getGlobalVariableAddress(symbol).getPointer(0))

    override fun send(receiver: Long, selector: Long): Long =
        objc.getFunction("objc_msgSend").invokeLong(arrayOf(Pointer(receiver), Pointer(selector)))

    override fun string(string: Long): String? {
        if (string == 0L) return null
        val utf8 = send(string, selector("UTF8String"))
        return if (utf8 == 0L) null else Pointer(utf8).getString(0, "UTF-8")
    }
}
