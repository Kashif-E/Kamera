package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.permissions.PermissionStatus
import dev.kashifmehmood.camerak.recording.RECORDING_START_TIMEOUT
import dev.kashifmehmood.camerak.state.PERMISSION_PROMPT_TIMEOUT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * macOS camera authorization is `[AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo]`,
 * read through the Objective-C runtime over JNA. Without JNA at runtime nothing can be read, and
 * the status is NotDetermined, which lets the open itself show macOS's prompt (Decision 15). The
 * microphone's status (PM') is the same read with `AVMediaTypeAudio`, through the same mapping.
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

    @Test
    fun audioStatusReadsTheAudioMediaTypeConstant() {
        val runtime = FakeObjCRuntime(3)

        assertEquals(PermissionStatus.Granted, MacCameraAuthorization { runtime }.status(MacMediaType.AUDIO))
        val statusRead = FakeObjCRuntime.Call(
            FakeObjCRuntime.DEVICE_CLASS,
            FakeObjCRuntime.STATUS_SELECTOR,
            FakeObjCRuntime.AUDIO,
        )
        assertEquals(listOf(statusRead), runtime.calls, "the message sent for the microphone")
    }

    @Test
    fun audioStatusUsesTheSameMappingAndJnaAbsentRule() {
        val expected = mapOf(
            0L to PermissionStatus.NotDetermined,
            1L to PermissionStatus.Restricted,
            2L to PermissionStatus.PermanentlyDenied,
            3L to PermissionStatus.Granted,
            7L to PermissionStatus.NotDetermined,
        )
        for ((raw, status) in expected) {
            val runtime = FakeObjCRuntime(raw)
            assertEquals(
                status,
                MacCameraAuthorization { runtime }.status(MacMediaType.AUDIO),
                "AVAuthorizationStatus $raw for audio",
            )
        }
        val withoutJna = MacCameraAuthorization { throw NoClassDefFoundError("com/sun/jna/Native") }
        assertEquals(PermissionStatus.NotDetermined, withoutJna.status(MacMediaType.AUDIO))
    }

    @Test
    fun cameraStatusIsTheVideoStatus() {
        for (raw in listOf(0L, 1L, 2L, 3L, 7L)) {
            val runtime = FakeObjCRuntime(raw)
            val sut = MacCameraAuthorization { runtime }
            assertEquals(sut.status(MacMediaType.VIDEO), sut.cameraStatus(), "AVAuthorizationStatus $raw")
        }
    }

    @Test
    fun aRequestAsksAVFoundationAndReadsTheStatusItAnswered() {
        val runtime = FakeObjCRuntime(0, answer = 3)

        assertEquals(PermissionStatus.Granted, MacCameraAuthorization { runtime }.requestAccess(MacMediaType.AUDIO))
        val request = runtime.blockCalls.single()
        assertEquals(FakeObjCRuntime.DEVICE_CLASS, request.receiver)
        assertEquals(FakeObjCRuntime.REQUEST_SELECTOR, request.selector)
        assertEquals(FakeObjCRuntime.AUDIO, request.argument)
        assertEquals(FakeObjCRuntime.BLOCK, request.block, "the completion handler")
    }

    @Test
    fun aRequestNobodyAnswersReadsTheStatusAfterItsWait() {
        val runtime = FakeObjCRuntime(0, answer = null)

        val status = MacCameraAuthorization { runtime }
            .requestAccess(MacMediaType.VIDEO, wait = 50.milliseconds, poll = 10.milliseconds)

        assertEquals(PermissionStatus.NotDetermined, status)
        assertEquals(FakeObjCRuntime.VIDEO, runtime.blockCalls.single().argument)
    }

    @Test
    fun aPackagedAppWithoutTheUsageKeyIsNotAsked() {
        val runtime = FakeObjCRuntime(0, answer = 3, bundlePath = "/Applications/Some.app")

        val status = MacCameraAuthorization { runtime }.requestAccess(MacMediaType.AUDIO)

        assertEquals(PermissionStatus.NotDetermined, status)
        assertEquals(emptyList(), runtime.blockCalls, "macOS was asked by an app that declares no reason")
    }

    @Test
    fun aPackagedAppWithTheUsageKeyIsAsked() {
        val runtime = FakeObjCRuntime(
            0,
            answer = 3,
            bundlePath = "/Applications/Some.app",
            info = mapOf("NSCameraUsageDescription" to "Takes photos."),
        )

        assertEquals(PermissionStatus.Granted, MacCameraAuthorization { runtime }.requestAccess(MacMediaType.VIDEO))
        assertEquals(FakeObjCRuntime.VIDEO, runtime.blockCalls.single().argument)
    }

    // An unbundled JVM (`./gradlew run`) is asked for: macOS asks on behalf of the app that started it.
    @Test
    fun anUnbundledJvmIsAsked() {
        val runtime =
            FakeObjCRuntime(0, answer = 3, bundlePath = "/Library/Java/JavaVirtualMachines/jdk/Contents/Home/bin")

        assertEquals(PermissionStatus.Granted, MacCameraAuthorization { runtime }.requestAccess(MacMediaType.AUDIO))
    }

    @Test
    fun aRequestWaitsNoLongerThanTheRecordingStartThatAsks() {
        assertTrue(
            MAC_PROMPT_WAIT <= RECORDING_START_TIMEOUT + PERMISSION_PROMPT_TIMEOUT,
            "a request outlasting the start leaves the engine busy after the start reported",
        )
    }

    // A shrinker may drop a field that is written and never read; the block's memory must not hang on one.
    @Test
    fun theNoOpBlocksMemoryHasNoOwnerAShrinkerCouldDrop() {
        val holder = Class.forName("dev.kashifmehmood.camerak.controller.NoOpBlock", false, javaClass.classLoader)
        val owners = holder.declaredFields.filter { !it.type.isPrimitive && it.name != "INSTANCE" }

        assertEquals(emptyList(), owners.map { "${it.name}: ${it.type.name}" })
    }

    @Test
    fun aRequestWithoutJnaAsksNothing() {
        val withoutJna = MacCameraAuthorization { throw NoClassDefFoundError("com/sun/jna/Native") }

        assertEquals(null, withoutJna.requestAccess(MacMediaType.AUDIO))
    }

    /**
     * Answers [status] to `authorizationStatusForMediaType:` sent to AVCaptureDevice with
     * AVMediaTypeVideo or AVMediaTypeAudio. A `requestAccessForMediaType:completionHandler:` sets
     * the status to [answer] a moment later, as the user does; a null [answer] leaves the prompt
     * unanswered.
     */
    private class FakeObjCRuntime(
        @Volatile private var status: Long,
        private val answer: Long? = null,
        private val bundlePath: String? = null,
        private val info: Map<String, String> = emptyMap(),
    ) : ObjCRuntime {
        override fun sendDouble(receiver: Long, selector: Long): Double = 0.0

        override fun callLong(library: String, function: String, argument: Long): Long = 0

        data class Call(val receiver: Long, val selector: Long, val argument: Long, val block: Long = 0)

        val calls = mutableListOf<Call>()
        val blockCalls = mutableListOf<Call>()

        override fun getClass(name: String): Long = if (name == "AVCaptureDevice") DEVICE_CLASS else 0

        override fun selector(name: String): Long = when (name) {
            "authorizationStatusForMediaType:" -> STATUS_SELECTOR
            "requestAccessForMediaType:completionHandler:" -> REQUEST_SELECTOR
            else -> OTHER
        }

        override fun sendWithBlock(receiver: Long, selector: Long, argument: Long, block: Long) {
            blockCalls += Call(receiver, selector, argument, block)
            val answered = answer ?: return
            if (receiver != DEVICE_CLASS || selector != REQUEST_SELECTOR) return
            // The user answers on a thread of macOS's own, after the request has returned.
            Thread {
                Thread.sleep(20)
                status = answered
            }.start()
        }

        override fun noOpBlock(): Long = BLOCK

        override fun mainBundlePath(): String? = bundlePath

        override fun mainBundleInfo(key: String): String? = info[key]

        override fun sendLong(receiver: Long, selector: Long, argument: Long): Long {
            calls += Call(receiver, selector, argument)
            val mediaType = argument == VIDEO || argument == AUDIO
            return if (receiver == DEVICE_CLASS && selector == STATUS_SELECTOR && mediaType) status else -1
        }

        override fun pointerConstant(library: String, symbol: String): Long = when {
            library != "AVFoundation" -> 0
            symbol == "AVMediaTypeVideo" -> VIDEO
            symbol == "AVMediaTypeAudio" -> AUDIO
            else -> 0
        }

        // The authorization read sends nothing else.
        override fun send(receiver: Long, selector: Long): Long = 0

        override fun string(string: Long): String? = null

        companion object {
            const val DEVICE_CLASS = 0x1000L
            const val STATUS_SELECTOR = 0x2000L
            const val VIDEO = 0x3000L
            const val AUDIO = 0x3100L
            const val OTHER = 0x4000L
            const val REQUEST_SELECTOR = 0x5000L
            const val BLOCK = 0x6000L
        }
    }
}
