package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import org.bytedeco.javacv.FrameGrabber
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the enumerated cameras' source knows of the mode a constrained open asks for: the configured
 * target or CameraK's 720p at 30 fps, read from the devices; nothing when the devices cannot tell or a
 * test camera stands in.
 */
class EnumeratedDeviceSourceTest {
    private val device = desktopLensInfo()

    @AfterTest
    fun tearDown() {
        defaultCameraOverride = null
    }

    private fun source(target: Pair<Int, Int>?, has: Boolean?, asked: MutableList<Pair<Int, Int>> = mutableListOf()) =
        EnumeratedDeviceSource(
            object : DesktopDevices {
                override fun list(): List<LensInfo> = listOf(device)

                override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber = error("not opened")

                override fun hasMode(id: String, size: Pair<Int, Int>, frameRate: Double): Boolean? {
                    asked += size
                    return has
                }
            },
            target,
        )

    @Test
    fun aTargetTheCameraLacksOrHasIsKnown() {
        val asked = mutableListOf<Pair<Int, Int>>()
        assertEquals(KnownMode.TARGET_MISSING, source(3840 to 2160, has = false, asked).knownMode(device))
        assertEquals(KnownMode.TARGET_PRESENT, source(3840 to 2160, has = true, asked).knownMode(device))
        assertEquals(listOf(3840 to 2160, 3840 to 2160), asked)
    }

    @Test
    fun withoutATargetOnlyAMissing720pIsKnown() {
        val asked = mutableListOf<Pair<Int, Int>>()
        assertEquals(KnownMode.DEFAULT_MISSING, source(null, has = false, asked).knownMode(device))
        assertNull(source(null, has = true, asked).knownMode(device))
        assertEquals(listOf(1280 to 720, 1280 to 720), asked)
    }

    @Test
    fun devicesThatCannotTellOrATestCameraKnowNothing() {
        assertNull(source(3840 to 2160, has = null).knownMode(device))
        defaultCameraOverride = { error("not opened") }
        assertNull(source(3840 to 2160, has = false).knownMode(device))
    }
}
