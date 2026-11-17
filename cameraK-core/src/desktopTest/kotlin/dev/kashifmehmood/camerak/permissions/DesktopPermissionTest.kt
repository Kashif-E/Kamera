package dev.kashifmehmood.camerak.permissions

import dev.kashifmehmood.camerak.controller.MacCameraAuthorization
import dev.kashifmehmood.camerak.controller.MacMediaType
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.utils.InternalCameraKApi
import java.awt.EventQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop permissions (Decision 15): Linux and Windows have none, so everything is Granted; macOS
 * reads AVFoundation's authorization through the D1' JNA seam, and the checker lets the camera open
 * at NotDetermined, because the open shows macOS's prompt. A request asks macOS, through AVFoundation,
 * for what reads NotDetermined, off the AWT event thread, and reports the statuses on it, Compose
 * Desktop's Main.
 */
@OptIn(InternalCameraKApi::class)
class DesktopPermissionTest {
    private val context = CameraKPlatformContext()

    private class RecordingRead(private val statuses: Map<CameraKPermission, PermissionStatus>) : MacAuthorizationRead {
        val reads = CopyOnWriteArrayList<CameraKPermission>()
        val asked = CopyOnWriteArrayList<CameraKPermission>()

        override fun status(permission: CameraKPermission): PermissionStatus {
            reads += permission
            return statuses.getValue(permission)
        }

        // The user allows what is asked.
        override fun request(permission: CameraKPermission): PermissionStatus {
            asked += permission
            return PermissionStatus.Granted
        }
    }

    private fun <T> withChecker(checker: DesktopPermissionChecker, block: () -> T): T {
        val previous = desktopPermissionChecker
        desktopPermissionChecker = checker
        try {
            return block()
        } finally {
            desktopPermissionChecker = previous
        }
    }

    @Test
    fun notDeterminedAllowsOpen() {
        val checker = DesktopPermissionChecker("Mac OS X") { PermissionStatus.NotDetermined }

        assertTrue(checker.allowsOpen(PermissionStatus.NotDetermined), "macOS prompts when the camera opens")
        assertTrue(checker.allowsOpen(PermissionStatus.Granted))
        for (status in listOf(
            PermissionStatus.PermanentlyDenied,
            PermissionStatus.Restricted,
            PermissionStatus.Denied(shouldShowRationale = false),
            PermissionStatus.Limited,
        )) {
            assertFalse(checker.allowsOpen(status), "$status opened")
        }
    }

    @Test
    fun linuxAndWindowsReportGranted() {
        for (os in listOf("Linux", "Windows 11", "Windows Server 2022")) {
            val read = RecordingRead(CameraKPermission.entries.associateWith { PermissionStatus.PermanentlyDenied })
            val checker = DesktopPermissionChecker(os, read)

            for (permission in CameraKPermission.entries) {
                assertEquals(PermissionStatus.Granted, checker.status(permission), "$permission on $os")
            }
            assertEquals(emptyList(), read.reads.toList(), "$os asked macOS")
        }
        val mac = DesktopPermissionChecker("Mac OS X") { PermissionStatus.PermanentlyDenied }
        assertEquals(PermissionStatus.PermanentlyDenied, mac.status(CameraKPermission.CAMERA), "macOS reads the seam")
        assertEquals(PermissionStatus.Granted, mac.status(CameraKPermission.GALLERY_ADD), "the gallery is a folder")
    }

    @Test
    fun aMacPromptsOnOpenOnlyAtANotDeterminedItRead() {
        val read = DesktopPermissionChecker("Mac OS X", JnaMacAuthorizationRead { PermissionStatus.NotDetermined })
        assertTrue(read.promptsOnOpen(CameraKPermission.CAMERA, PermissionStatus.NotDetermined))
        assertTrue(read.promptsOnOpen(CameraKPermission.MICROPHONE, PermissionStatus.NotDetermined))
        assertFalse(read.promptsOnOpen(CameraKPermission.CAMERA, PermissionStatus.Granted))
        assertFalse(read.promptsOnOpen(CameraKPermission.GALLERY_ADD, PermissionStatus.NotDetermined), "a folder")

        // Without JNA, or with AVFoundation unloaded, NotDetermined only means the OS could not be asked.
        val unreadable = DesktopPermissionChecker("Mac OS X", JnaMacAuthorizationRead { null })
        assertEquals(PermissionStatus.NotDetermined, unreadable.status(CameraKPermission.CAMERA))
        assertTrue(unreadable.allowsOpen(PermissionStatus.NotDetermined), "the open may still prompt")
        assertFalse(unreadable.promptsOnOpen(CameraKPermission.CAMERA, PermissionStatus.NotDetermined))

        assertFalse(
            DesktopPermissionChecker("Linux") { PermissionStatus.Granted }.promptsOnOpen(
                CameraKPermission.CAMERA,
                PermissionStatus.NotDetermined,
            ),
        )
    }

    @Test
    fun macStatusComesFromTheJnaSeam() {
        val withoutJna =
            JnaMacAuthorizationRead(
                read = MacCameraAuthorization {
                    throw NoClassDefFoundError("com/sun/jna/Native")
                }::status,
            )
        assertEquals(PermissionStatus.NotDetermined, withoutJna.status(CameraKPermission.CAMERA))

        val denied = JnaMacAuthorizationRead { PermissionStatus.PermanentlyDenied }
        assertEquals(PermissionStatus.PermanentlyDenied, denied.status(CameraKPermission.CAMERA))
    }

    @Test
    fun macMicrophoneReadsTheAudioStatus() {
        val types = CopyOnWriteArrayList<MacMediaType>()
        val read = JnaMacAuthorizationRead { type ->
            types += type
            if (type == MacMediaType.AUDIO) PermissionStatus.PermanentlyDenied else PermissionStatus.Granted
        }

        assertEquals(PermissionStatus.PermanentlyDenied, read.status(CameraKPermission.MICROPHONE), "the microphone")
        assertEquals(PermissionStatus.Granted, read.status(CameraKPermission.CAMERA), "the camera")
        assertEquals(listOf(MacMediaType.AUDIO, MacMediaType.VIDEO), types.toList())

        // The production read's mapping; that AUDIO asks AVFoundation for AVMediaTypeAudio is D2''s test.
        assertEquals(MacMediaType.AUDIO, macMediaTypeOf(CameraKPermission.MICROPHONE))
        assertEquals(MacMediaType.VIDEO, macMediaTypeOf(CameraKPermission.CAMERA))
        assertNull(macMediaTypeOf(CameraKPermission.GALLERY_ADD), "the gallery is a folder")
    }

    @Test
    fun launchRequestAsksWhatIsNotDeterminedAndReportsTheStatusesOnMain() {
        val read = RecordingRead(
            mapOf(
                CameraKPermission.CAMERA to PermissionStatus.PermanentlyDenied,
                CameraKPermission.MICROPHONE to PermissionStatus.NotDetermined,
                CameraKPermission.GALLERY_ADD to PermissionStatus.PermanentlyDenied,
            ),
        )
        val done = CountDownLatch(1)
        var onEventThread = false
        var result: Map<CameraKPermission, PermissionStatus>? = null

        withChecker(DesktopPermissionChecker("Mac OS X", read)) {
            requestPlatformPermissions(context, listOf(CameraKPermission.CAMERA, CameraKPermission.MICROPHONE)) {
                onEventThread = EventQueue.isDispatchThread()
                result = it
                done.countDown()
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "no result")
        }

        assertTrue(onEventThread, "the result was delivered off the event thread")
        assertEquals(
            mapOf(
                CameraKPermission.CAMERA to PermissionStatus.PermanentlyDenied,
                CameraKPermission.MICROPHONE to PermissionStatus.Granted,
            ),
            assertNotNull(result),
        )
        // macOS never asks again after a denial, so only the undecided microphone is asked, once.
        assertEquals(listOf(CameraKPermission.MICROPHONE), read.asked.toList())
    }

    @Test
    fun aMacPromptIsPendingOnlyWhileTheCameraOrMicrophoneReadsNotDetermined() {
        var camera: PermissionStatus = PermissionStatus.NotDetermined
        val mac = DesktopPermissionChecker("Mac OS X") {
            if (it == CameraKPermission.CAMERA) camera else PermissionStatus.Granted
        }
        withChecker(mac) {
            assertTrue(platformPermissionPromptPending(context, CameraKPermission.CAMERA))
            assertFalse(platformPermissionPromptPending(context, CameraKPermission.MICROPHONE))
            assertFalse(platformPermissionPromptPending(context, CameraKPermission.GALLERY_ADD))
            camera = PermissionStatus.Granted
            assertFalse(platformPermissionPromptPending(context, CameraKPermission.CAMERA), "answered")
        }
        withChecker(DesktopPermissionChecker("Linux") { PermissionStatus.NotDetermined }) {
            assertFalse(platformPermissionPromptPending(context, CameraKPermission.CAMERA), "Linux has no prompt")
        }
    }

    @Test
    fun aMacRequestAsksOnlyAtANotDeterminedItCouldRead() {
        val asked = CopyOnWriteArrayList<CameraKPermission>()
        fun mac(status: PermissionStatus, readable: Boolean = true) = DesktopPermissionChecker(
            "Mac OS X",
            object : MacAuthorizationRead {
                override fun status(permission: CameraKPermission) = status

                override fun readable(permission: CameraKPermission) = readable

                override fun request(permission: CameraKPermission): PermissionStatus {
                    asked += permission
                    return PermissionStatus.Granted
                }
            },
        )

        assertEquals(
            PermissionStatus.Granted,
            mac(PermissionStatus.NotDetermined).request(CameraKPermission.MICROPHONE),
        )
        assertEquals(listOf(CameraKPermission.MICROPHONE), asked.toList())
        asked.clear()
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            mac(PermissionStatus.PermanentlyDenied).request(CameraKPermission.CAMERA),
            "macOS never asks again after a denial",
        )
        assertEquals(
            PermissionStatus.NotDetermined,
            mac(PermissionStatus.NotDetermined, readable = false).request(CameraKPermission.CAMERA),
            "without JNA nothing can be asked",
        )
        assertEquals(
            PermissionStatus.Granted,
            mac(PermissionStatus.NotDetermined).request(CameraKPermission.GALLERY_ADD),
        )
        val linux = DesktopPermissionChecker("Linux") { PermissionStatus.NotDetermined }
        assertEquals(PermissionStatus.Granted, linux.request(CameraKPermission.CAMERA))
        assertEquals(emptyList(), asked.toList(), "asked where no prompt can show")
    }

    @Test
    fun aRequestAsksMacOSOffTheEventThreadThenReportsOnIt() {
        val askedOn = CopyOnWriteArrayList<Boolean>()
        var camera: PermissionStatus = PermissionStatus.NotDetermined
        val checker = DesktopPermissionChecker(
            "Mac OS X",
            object : MacAuthorizationRead {
                override fun status(permission: CameraKPermission) =
                    if (permission == CameraKPermission.CAMERA) camera else PermissionStatus.Granted

                override fun request(permission: CameraKPermission): PermissionStatus {
                    askedOn += EventQueue.isDispatchThread()
                    camera = PermissionStatus.Granted
                    return camera
                }
            },
        )
        val reported = CountDownLatch(1)
        var result: Map<CameraKPermission, PermissionStatus>? = null
        var onEventThread = false

        withChecker(checker) {
            requestPlatformPermissions(context, listOf(CameraKPermission.CAMERA, CameraKPermission.MICROPHONE)) {
                onEventThread = EventQueue.isDispatchThread()
                result = it
                reported.countDown()
            }
            assertTrue(reported.await(5, TimeUnit.SECONDS), "no result")
        }

        assertEquals(listOf(false), askedOn.toList(), "asked once, off the event thread")
        assertTrue(onEventThread, "reported off the event thread")
        assertEquals(
            mapOf(
                CameraKPermission.CAMERA to PermissionStatus.Granted,
                CameraKPermission.MICROPHONE to PermissionStatus.Granted,
            ),
            result,
        )
    }

    @Test
    fun theMicrophoneOpensOnlyOnAnAnswerOrWhereNothingCanAsk() {
        fun mac(status: PermissionStatus, answer: PermissionStatus = status, readable: Boolean = true) =
            DesktopPermissionChecker(
                "Mac OS X",
                object : MacAuthorizationRead {
                    override fun status(permission: CameraKPermission) = status

                    override fun readable(permission: CameraKPermission) = readable

                    override fun request(permission: CameraKPermission) = answer
                },
            )
        val microphone = CameraKPermission.MICROPHONE

        assertNull(mac(PermissionStatus.NotDetermined, answer = PermissionStatus.Granted).requestToOpen(microphone))
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            mac(PermissionStatus.NotDetermined, answer = PermissionStatus.PermanentlyDenied).requestToOpen(microphone),
        )
        assertEquals(
            PermissionStatus.NotDetermined,
            mac(PermissionStatus.NotDetermined).requestToOpen(microphone),
            "a prompt left unanswered refuses, rather than the open waiting on it with no deadline",
        )
        assertNull(
            mac(PermissionStatus.NotDetermined, readable = false).requestToOpen(microphone),
            "without JNA nothing was asked: the open shows macOS's prompt",
        )
        assertEquals(PermissionStatus.Restricted, mac(PermissionStatus.Restricted).requestToOpen(microphone))
        assertNull(DesktopPermissionChecker("Linux") { PermissionStatus.NotDetermined }.requestToOpen(microphone))
    }
}
