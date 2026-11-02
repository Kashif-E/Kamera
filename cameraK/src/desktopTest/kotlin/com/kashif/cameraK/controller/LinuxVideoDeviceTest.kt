package com.kashif.cameraK.controller

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On Linux the default camera is the lowest-numbered V4L2 node that captures video. Nodes sort by
 * number, not by name, and a UVC camera's metadata node (sysfs `index` other than 0) is never
 * chosen. The device and sysfs trees here are built from what a laptop with a built-in UVC camera
 * and a USB camera shows.
 */
class LinuxVideoDeviceTest {

    private val root: File = Files.createTempDirectory("camerak-v4l2").toFile()
    private val dev = File(root, "dev").apply { mkdirs() }
    private val sys = File(root, "sys/class/video4linux").apply { mkdirs() }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    // A V4L2 node: the device file, and its sysfs folder whose `index` is 0 for the capture node of
    // a camera and 1 for its metadata node.
    private fun node(number: Int, index: Int) {
        File(dev, "video$number").writeText("")
        File(sys, "video$number").apply { mkdirs() }.resolve("index").writeText("$index\n")
    }

    @Test
    fun nodesSortByNumber_soVideo2ComesBeforeVideo10() {
        node(2, index = 0)
        node(10, index = 0)

        assertEquals(File(dev, "video2").path, findBestVideoDevice(dev, sys))
    }

    @Test
    fun aCamerasMetadataNode_isNeverChosen() {
        // No video0, and the lowest-numbered node is a metadata node: name order alone picks it.
        node(1, index = 1)
        node(2, index = 0)
        node(3, index = 1)

        assertEquals(File(dev, "video2").path, findBestVideoDevice(dev, sys))
    }

    @Test
    fun video0_isChosenWhenItCaptures() {
        node(0, index = 0)
        node(1, index = 1)
        node(2, index = 0)

        assertEquals(File(dev, "video0").path, findBestVideoDevice(dev, sys))
    }

    @Test
    fun withNoNodes_itFallsBackToVideo0() {
        assertEquals(File(dev, "video0").path, findBestVideoDevice(dev, sys))
    }
}
