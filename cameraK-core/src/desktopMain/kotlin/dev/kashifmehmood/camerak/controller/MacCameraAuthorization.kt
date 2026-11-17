package dev.kashifmehmood.camerak.controller

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import dev.kashifmehmood.camerak.permissions.PermissionStatus
import dev.kashifmehmood.camerak.recording.RECORDING_START_TIMEOUT
import dev.kashifmehmood.camerak.state.PERMISSION_PROMPT_TIMEOUT
import dev.kashifmehmood.camerak.utils.CameraKLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

    /** `objc_msgSend(receiver, selector)` for a method with no argument that returns a double. */
    fun sendDouble(receiver: Long, selector: Long): Double

    /** The C function [function] of [library], called with one pointer, returning at most eight bytes. */
    fun callLong(library: String, function: String, argument: Long): Long

    /** The text of the NSString at [string]; null for nil. */
    fun string(string: Long): String?

    /** `objc_msgSend(receiver, selector, argument, block)` for a method that returns nothing. */
    fun sendWithBlock(receiver: Long, selector: Long, argument: Long, block: Long)

    /** A block that does nothing when called, for a completion handler whose answer is read afterwards. */
    fun noOpBlock(): Long

    /** `[[NSBundle mainBundle] bundlePath]`; null when it cannot be read. */
    fun mainBundlePath(): String?

    /** The main bundle's Info.plist string for [key]; null when it has none. */
    fun mainBundleInfo(key: String): String?
}

/** The AVFoundation media types whose authorization CameraK reads. */
internal enum class MacMediaType(val constant: String, val usageKey: String) {
    VIDEO("AVMediaTypeVideo", "NSCameraUsageDescription"),
    AUDIO("AVMediaTypeAudio", "NSMicrophoneUsageDescription"),
}

/**
 * macOS camera authorization: `[AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo]`,
 * read through the Objective-C runtime over JNA (Decision 15). JNA is compile-only: an app that
 * does not ship it gets NotDetermined, which the desktop permission checker allows to open, so the
 * open itself shows macOS's prompt. Authorized is Granted, Denied is PermanentlyDenied (macOS never
 * asks again), Restricted is Restricted. The error mapper reads the camera's; PM''s desktop
 * permission checker reads the camera's and, through [status], the microphone's with the same
 * mapping and the same rule without JNA. [requestAccess] asks macOS through AVFoundation, which shows
 * its prompt, where the app may: a packaged app only for what its Info.plist gives a reason for.
 */
internal class MacCameraAuthorization(
    // A lambda, not a constructor reference, so nothing resolves JnaObjCRuntime before the read.
    private val runtime: () -> ObjCRuntime = { JnaObjCRuntime() },
) : CameraAuthorization {
    /** The authorization status of [type]; NotDetermined whenever it cannot be read. */
    fun status(type: MacMediaType): PermissionStatus = read(type) ?: PermissionStatus.NotDetermined

    /** The authorization status of [type]; null when it cannot be read. */
    fun read(type: MacMediaType): PermissionStatus? {
        val objc = try {
            runtime()
        } catch (e: LinkageError) {
            CameraKLogger.w(TAG, "JNA is not available, so the AVFoundation authorization reads as not determined", e)
            return null
        }
        return try {
            // Reading the constant loads AVFoundation, which registers AVCaptureDevice with the runtime.
            val mediaType = objc.pointerConstant("AVFoundation", type.constant)
            val device = objc.getClass("AVCaptureDevice")
            if (device == 0L || mediaType == 0L) {
                CameraKLogger.w(TAG, "AVFoundation is not loaded, so the authorization reads as not determined")
                return null
            }
            statusOf(objc.sendLong(device, objc.selector("authorizationStatusForMediaType:"), mediaType))
        } catch (e: Exception) {
            CameraKLogger.w(TAG, "Reading an AVFoundation authorization failed; it reads as not determined", e)
            null
        } catch (e: LinkageError) {
            CameraKLogger.w(TAG, "Reading an AVFoundation authorization failed; it reads as not determined", e)
            null
        }
    }

    /**
     * Asks macOS for [type] through AVFoundation, which shows its prompt at NotDetermined, then reads
     * the status every [poll] until the prompt is answered, for up to [wait]. AVFoundation's status
     * reads the answer at once in the process that asked; a prompt raised by anything else (Java
     * Sound's open of the microphone) leaves it NotDetermined there until the app starts again.
     */
    fun requestAccess(
        type: MacMediaType,
        wait: Duration = MAC_PROMPT_WAIT,
        poll: Duration = MAC_PROMPT_POLL,
    ): PermissionStatus? {
        val objc = try {
            runtime()
        } catch (e: LinkageError) {
            CameraKLogger.w(TAG, "JNA is not available, so macOS cannot be asked for an authorization", e)
            return null
        }
        try {
            val mediaType = objc.pointerConstant("AVFoundation", type.constant)
            val device = objc.getClass("AVCaptureDevice")
            if (device == 0L || mediaType == 0L) {
                CameraKLogger.w(TAG, "AVFoundation is not loaded, so macOS cannot be asked for an authorization")
                return null
            }
            if (!declaresUsage(objc, type)) {
                CameraKLogger.w(TAG, "The app's Info.plist has no ${type.usageKey}, so macOS is not asked for it")
                return read(type)
            }
            val request = objc.selector("requestAccessForMediaType:completionHandler:")
            objc.sendWithBlock(device, request, mediaType, objc.noOpBlock())
            val deadline = System.nanoTime() + wait.inWholeNanoseconds
            while (read(type) == PermissionStatus.NotDetermined) {
                if (System.nanoTime() >= deadline) {
                    CameraKLogger.w(TAG, "macOS's prompt was not answered within $wait")
                    break
                }
                Thread.sleep(poll.inWholeMilliseconds)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            CameraKLogger.w(TAG, "Asking macOS for an authorization failed", e)
            return null
        } catch (e: LinkageError) {
            CameraKLogger.w(TAG, "Asking macOS for an authorization failed", e)
            return null
        }
        return read(type)
    }

    // macOS ends a packaged app (.app) that asks without its Info.plist giving the reason; an unbundled
    // JVM is asked for on behalf of the app that started it, which gives its own.
    private fun declaresUsage(objc: ObjCRuntime, type: MacMediaType): Boolean {
        val bundle = objc.mainBundlePath() ?: return true
        return !bundle.endsWith(".app") || objc.mainBundleInfo(type.usageKey) != null
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

    override fun sendDouble(receiver: Long, selector: Long): Double =
        objc.getFunction("objc_msgSend").invokeDouble(arrayOf(Pointer(receiver), Pointer(selector)))

    override fun callLong(library: String, function: String, argument: Long): Long =
        NativeLibrary.getInstance(library).getFunction(function).invokeLong(arrayOf(Pointer(argument)))

    override fun sendWithBlock(receiver: Long, selector: Long, argument: Long, block: Long) {
        objc.getFunction("objc_msgSend")
            .invokeVoid(arrayOf(Pointer(receiver), Pointer(selector), Pointer(argument), Pointer(block)))
    }

    override fun noOpBlock(): Long = NoOpBlock.address

    override fun mainBundlePath(): String? = string(send(mainBundle(), selector("bundlePath")))

    override fun mainBundleInfo(key: String): String? =
        string(sendLong(mainBundle(), selector("objectForInfoDictionaryKey:"), nsString(key)))

    private fun mainBundle(): Long = send(getClass("NSBundle"), selector("mainBundle"))

    private fun nsString(text: String): Long {
        val utf8 = Memory(text.toByteArray().size + 1L).apply { setString(0, text, "UTF-8") }
        return objc.getFunction("objc_msgSend")
            .invokeLong(arrayOf(Pointer(getClass("NSString")), Pointer(selector("stringWithUTF8String:")), utf8))
    }

    override fun string(string: Long): String? {
        if (string == 0L) return null
        val utf8 = send(string, selector("UTF8String"))
        return if (utf8 == 0L) null else Pointer(utf8).getString(0, "UTF-8")
    }
}

/** How long a request waits for the user to answer macOS's prompt: as long as a recording start that asks. */
internal val MAC_PROMPT_WAIT = RECORDING_START_TIMEOUT + PERMISSION_PROMPT_TIMEOUT

/** How often a request reads the status while macOS's prompt is up. */
internal val MAC_PROMPT_POLL = 100.milliseconds

/**
 * A global Objective-C block (isa `_NSConcreteGlobalBlock`, so copying or releasing it does nothing)
 * whose invoke is getpid, which ignores the block and the BOOL it is called with. Made once, on first
 * use, and kept for the life of the process, since AVFoundation may call it after any request has
 * returned. No member's type names JNA, so the class loads without it.
 */
private object NoOpBlock {
    // The Block ABI's literal on a 64-bit Mac: isa, flags, reserved, invoke, descriptor.
    private const val LITERAL_SIZE = 32L
    private const val DESCRIPTOR_SIZE = 16L
    private const val BLOCK_IS_GLOBAL = 1 shl 28

    // malloc'd and never freed: no Java object owns the memory, so nothing frees it while AVFoundation holds the block.
    val address: Long = Native.malloc(LITERAL_SIZE)

    init {
        val descriptor = Native.malloc(DESCRIPTOR_SIZE)
        check(address != 0L && descriptor != 0L) { "The block's memory could not be allocated" }
        Pointer(descriptor).apply {
            setLong(0, 0)
            setLong(8, LITERAL_SIZE)
        }
        val system = NativeLibrary.getInstance("System")
        Pointer(address).apply {
            setPointer(0, system.getGlobalVariableAddress("_NSConcreteGlobalBlock"))
            setInt(8, BLOCK_IS_GLOBAL)
            setInt(12, 0)
            setPointer(16, system.getFunction("getpid"))
            setPointer(24, Pointer(descriptor))
        }
    }
}
