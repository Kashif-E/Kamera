@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.desktopEngine
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FrameGrabber
import org.junit.Assume.assumeFalse
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Desktop devices are enumerated without opening any (§2.4 desktop, D2'): Linux from sysfs, udev's
 * records and `/dev/v4l/by-id`; macOS through Objective-C runtime calls over JNA, which offers only
 * the default device when JNA is absent. The system default is listed first. Enumeration runs on
 * the engine's scope after construction, never in the constructor.
 */
class DeviceEnumeratorTest {
    private val root: File = Files.createTempDirectory("camerak-devices").toFile()
    private val dev = File(root, "dev").apply { mkdirs() }
    private val sys = File(root, "sys/class/video4linux").apply { mkdirs() }
    private val udev = File(root, "run/udev/data").apply { mkdirs() }
    private val byId = File(dev, "v4l/by-id")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun linuxEnumeratesFromSysfsWithoutOpening() {
        // Each node is a FIFO: opening one for reading blocks until a writer comes, which never does.
        captureNode(2, index = 0)
        captureNode(0, index = 0)
        captureNode(1, index = 1)
        val linux = LinuxDevices(dev, sys, udev, byId, openInput = { _, _ -> error("enumeration opened a camera") })

        var listed: List<LensInfo>? = null
        val enumeration = Thread { listed = linux.list() }.apply {
            isDaemon = true
            start()
        }
        enumeration.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

        assertTrue(!enumeration.isAlive, "enumeration blocked: it opened a device node")
        assertEquals(listOf(webcam(path(0)), webcam(path(2))), listed, "video1 is video0's metadata node")
    }

    @Test
    fun linuxIdIsTheByIdLinkElseTheNode() {
        captureNode(0, index = 0)
        captureNode(1, index = 1)
        captureNode(2, index = 0)
        byId.mkdirs()
        // udev's links are relative, and the metadata node gets one too.
        Files.createSymbolicLink(File(byId, "usb-Logitech_C920-video-index0").toPath(), Paths.get("../../video0"))
        Files.createSymbolicLink(File(byId, "usb-Logitech_C920-video-index1").toPath(), Paths.get("../../video1"))
        val linux = LinuxDevices(dev, sys, udev, byId)

        val ids = linux.list().map { it.id }

        assertEquals(listOf("usb-Logitech_C920-video-index0", path(2)), ids)
        assertEquals(DeviceInput(File(byId, "usb-Logitech_C920-video-index0").path, null), linux.input(ids[0]))
        assertEquals(DeviceInput(path(2), null), linux.input(ids[1]))
    }

    @Test
    fun macEnumeratesThroughObjcRuntime() {
        val runtime = FakeMacCameras(
            listOf(
                FakeMacCameras.Device("0x1420000005ac8600", "FaceTime HD Camera", 2, FakeMacCameras.BUILT_IN),
                FakeMacCameras.Device("0x14100000046d0825", "C920 HD Pro Webcam", 0, FakeMacCameras.EXTERNAL),
                FakeMacCameras.Device("6A2E0D1C-iphone", "iPhone Camera", 1, FakeMacCameras.CONTINUITY),
            ),
        )

        val lenses = MacDevices({ runtime }).list()

        assertEquals(
            listOf(
                webcam("0x1420000005ac8600", CameraLens.FRONT),
                webcam("0x14100000046d0825", isExternal = true),
                webcam("6A2E0D1C-iphone"),
            ),
            lenses,
        )
    }

    @Test
    fun defaultDeviceIsListedFirst() {
        val runtime = FakeMacCameras(
            listOf(
                FakeMacCameras.Device("usb", "USB Camera", 0, FakeMacCameras.EXTERNAL),
                FakeMacCameras.Device("built-in", "FaceTime HD Camera", 0, FakeMacCameras.BUILT_IN),
                FakeMacCameras.Device("other", "Another Camera", 0, FakeMacCameras.EXTERNAL),
            ),
            defaultId = "built-in",
        )

        assertEquals(listOf("built-in", "usb", "other"), MacDevices({ runtime }).list().map { it.id })

        // Linux's default is the node findBestVideoDevice picks: the lowest-numbered capture node.
        captureNode(3, index = 0)
        captureNode(1, index = 0)
        val linux = LinuxDevices(dev, sys, udev, byId).list()
        assertEquals(findBestVideoDevice(dev, sys, udev), linux.first().id)
    }

    @Test
    fun jnaAbsentOffersOnlyTheDefaultDevice() {
        val withoutJna = MacDevices({ throw NoClassDefFoundError("com/sun/jna/Native") })

        assertEquals(listOf(desktopLensInfo()), DeviceEnumerator(withoutJna).lenses())
        assertEquals(DeviceInput("0", "avfoundation"), MacDevices({ FakeMacCameras(emptyList()) }).input(""))
    }

    @Test
    fun enumerationNeverRunsInConstructor() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val devices = CountingDevices(listOf(webcam("a"), webcam("b")))

        val sut = desktopEngine(
            config = CameraConfiguration(),
            context = CameraKPlatformContext(),
            environment = rig.environment(),
            devices = devices,
        )
        try {
            assertEquals(0, devices.lists, "the constructor enumerated the cameras")
            assertSame(CameraCapabilities.EMPTY, sut.capabilities.value, "capabilities before enumeration")

            runCurrent()

            assertEquals(1, devices.lists, "enumerations on the engine's scope")
            assertEquals(listOf("a", "b"), sut.capabilities.value.allLenses.map { it.id })
        } finally {
            sut.close()
        }
    }

    private class CountingDevices(private val lenses: List<LensInfo>) : DesktopDevices {
        @Volatile var lists = 0

        override fun list(): List<LensInfo> {
            lists++
            return lenses
        }

        override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber = error("nothing opens here")
    }

    // A V4L2 node as a FIFO, its sysfs folder (index 0 for a capture node, 1 for a metadata node) and
    // the dev number udev's records are keyed on. Windows has no FIFOs: the Linux cases run elsewhere.
    private fun captureNode(number: Int, index: Int) {
        assumeFalse("Linux device nodes are FIFOs, which Windows cannot make", isWindows)
        val node = File(dev, "video$number")
        val mkfifo = ProcessBuilder("mkfifo", node.path).inheritIO().start()
        check(mkfifo.waitFor() == 0 && node.exists()) { "mkfifo failed for ${node.path}" }
        val sysNode = File(sys, "video$number").apply { mkdirs() }
        sysNode.resolve("index").writeText("$index\n")
        sysNode.resolve("dev").writeText("81:$number\n")
    }

    private fun path(number: Int) = File(dev, "video$number").path

    private companion object {
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        const val TIMEOUT_SECONDS = 10L

        fun webcam(id: String, lens: CameraLens = CameraLens.BACK, isExternal: Boolean = false) = LensInfo(
            id = id,
            deviceType = CameraDeviceType.DEFAULT,
            lens = lens,
            minZoom = 1f,
            maxZoom = 1f,
            hasFlash = false,
            isLogical = false,
            isExternal = isExternal,
        )
    }
}
