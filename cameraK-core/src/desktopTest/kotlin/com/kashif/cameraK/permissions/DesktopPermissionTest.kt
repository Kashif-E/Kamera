package com.kashif.cameraK.permissions

import com.kashif.cameraK.controller.CameraAuthorization
import com.kashif.cameraK.controller.MacCameraAuthorization
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi
import java.awt.EventQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Desktop permissions (Decision 15): Linux and Windows have none, so everything is Granted; macOS
 * reads AVFoundation's authorization through the D1' JNA seam, and the checker lets the camera open
 * at NotDetermined, because the open shows macOS's prompt. A request prompts for nothing: it re-reads
 * the statuses and reports them on the AWT event thread, Compose Desktop's Main.
 */
@OptIn(InternalCameraKApi::class)
class DesktopPermissionTest {
    private val context = CameraKPlatformContext()

    private class RecordingRead(private val statuses: Map<CameraKPermission, PermissionStatus>) : MacAuthorizationRead {
        val reads = CopyOnWriteArrayList<CameraKPermission>()

        override fun status(permission: CameraKPermission): PermissionStatus {
            reads += permission
            return statuses.getValue(permission)
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
    fun macStatusComesFromTheJnaSeam() {
        val withoutJna =
            JnaMacAuthorizationRead(MacCameraAuthorization { throw NoClassDefFoundError("com/sun/jna/Native") })
        assertEquals(PermissionStatus.NotDetermined, withoutJna.status(CameraKPermission.CAMERA))

        val denied = JnaMacAuthorizationRead(CameraAuthorization { PermissionStatus.PermanentlyDenied })
        assertEquals(PermissionStatus.PermanentlyDenied, denied.status(CameraKPermission.CAMERA))
    }

    @Test
    fun launchRequestPromptsNothingAndReportsTheStatusesOnMain() {
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
                CameraKPermission.MICROPHONE to PermissionStatus.NotDetermined,
            ),
            assertNotNull(result),
        )
        // A status read per permission and nothing else: the seam has no prompt, and nothing asked twice.
        assertEquals(listOf(CameraKPermission.CAMERA, CameraKPermission.MICROPHONE), read.reads.toList())
    }
}
