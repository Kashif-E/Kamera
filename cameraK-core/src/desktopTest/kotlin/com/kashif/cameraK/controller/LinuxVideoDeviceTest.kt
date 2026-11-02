package com.kashif.cameraK.controller

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On Linux the default camera is the lowest-numbered V4L2 node that captures video. Nodes sort by
 * number, not by name. Whether a node captures comes from the capabilities udev recorded for it
 * (`ID_V4L_CAPABILITIES`, which udev's v4l_id reads with VIDIOC_QUERYCAP); without a udev record, a
 * node whose sysfs `index` is not 0, such as a UVC camera's metadata node, is skipped. The device,
 * sysfs and udev trees here are built from what a laptop with a built-in UVC camera and a USB
 * camera or capture card shows.
 */
class LinuxVideoDeviceTest {

    private val root: File = Files.createTempDirectory("camerak-v4l2").toFile()
    private val dev = File(root, "dev").apply { mkdirs() }
    private val sys = File(root, "sys/class/video4linux").apply { mkdirs() }
    private val udev = File(root, "run/udev/data").apply { mkdirs() }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    // A V4L2 node: the device file, and its sysfs folder whose `index` is 0 for the capture node of
    // a camera and 1 for its metadata node. With [capabilities], udev's database also holds the
    // record v4l_id writes for the node (character device 81:number), as `:capture:` for a node that
    // captures video and `:` for a metadata node.
    private fun node(number: Int, index: Int, capabilities: String? = null) {
        File(dev, "video$number").writeText("")
        val sysNode = File(sys, "video$number").apply { mkdirs() }
        sysNode.resolve("index").writeText("$index\n")
        sysNode.resolve("dev").writeText("81:$number\n")
        if (capabilities != null) {
            File(udev, "c81:$number").writeText(
                "S:v4l/by-id/usb-camera-video-index$index\nE:ID_V4L_VERSION=2\n" +
                    "E:ID_V4L_CAPABILITIES=$capabilities\nE:ID_V4L_PRODUCT=Camera\n",
            )
        }
    }

    @Test
    fun nodesSortByNumber_soVideo2ComesBeforeVideo10() {
        node(2, index = 0)
        node(10, index = 0)

        assertEquals(File(dev, "video2").path, findBestVideoDevice(dev, sys, udev))
    }

    @Test
    fun aCamerasMetadataNode_isNeverChosen() {
        // No video0, and the lowest-numbered node is a metadata node: name order alone picks it.
        node(1, index = 1)
        node(2, index = 0)
        node(3, index = 1)

        assertEquals(File(dev, "video2").path, findBestVideoDevice(dev, sys, udev))
    }

    @Test
    fun video0_isChosenWhenItCaptures() {
        node(0, index = 0)
        node(1, index = 1)
        node(2, index = 0)

        assertEquals(File(dev, "video0").path, findBestVideoDevice(dev, sys, udev))
    }

    @Test
    fun withNoNodes_itFallsBackToVideo0() {
        assertEquals(File(dev, "video0").path, findBestVideoDevice(dev, sys, udev))
    }

    @Test
    fun aNodeUdevSaysDoesNotCapture_isSkippedEvenWithIndex0() {
        node(0, index = 0, capabilities = ":video_output:")
        node(2, index = 0, capabilities = ":capture:")

        assertEquals(File(dev, "video2").path, findBestVideoDevice(dev, sys, udev))
    }

    @Test
    fun aCaptureNodeWithANonZeroIndex_isChosenWhenUdevSaysItCaptures() {
        // A capture card's second input is its own capture node, with index 1.
        node(1, index = 1, capabilities = ":capture:")
        node(4, index = 0, capabilities = ":capture:")

        assertEquals(File(dev, "video1").path, findBestVideoDevice(dev, sys, udev))
    }

    @Test
    fun aMetadataNodeUdevKnows_isNeverChosen() {
        node(0, index = 0, capabilities = ":capture:")
        node(1, index = 1, capabilities = ":")

        assertEquals(File(dev, "video0").path, findBestVideoDevice(dev, sys, udev))
    }
}
