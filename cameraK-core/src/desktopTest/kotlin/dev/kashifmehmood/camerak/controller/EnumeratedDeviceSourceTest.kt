package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capabilities.LensInfo
import org.bytedeco.javacv.FrameGrabber
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the enumerated cameras' source knows of the mode a constrained open asks for, from the sizes the
 * devices list at CameraK's 30 fps: the configured target, else the listed size nearest it; without a
 * target, only that 720p is missing; nothing when the devices cannot tell or a test camera stands in.
 */
class EnumeratedDeviceSourceTest {
    private val device = desktopLensInfo()

    @AfterTest
    fun tearDown() {
        defaultCameraOverride = null
    }

    private fun source(
        target: Pair<Int, Int>?,
        sizes: List<Pair<Int, Int>>?,
        rates: MutableList<Double> = mutableListOf(),
    ) = EnumeratedDeviceSource(
        object : DesktopDevices {
            override fun list(): List<LensInfo> = listOf(device)

            override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber = error("not opened")

            override fun sizes(id: String, frameRate: Double): List<Pair<Int, Int>>? {
                rates += frameRate
                return sizes
            }
        },
        target,
    )

    @Test
    fun aListedTargetIsAskedFor() {
        val rates = mutableListOf<Double>()
        val known = source(1920 to 1080, listOf(1280 to 720, 1920 to 1080), rates).knownMode(device)
        assertEquals(KnownMode.Listed(1920 to 1080), known)
        assertEquals(listOf(30.0), rates)
    }

    @Test
    fun aTargetTheCameraLacksGivesWayToTheNearestSizeItLists() {
        val sizes = listOf(640 to 480, 1280 to 720, 1920 to 1080, 1552 to 1552)
        assertEquals(KnownMode.Listed(1920 to 1080), source(3840 to 2160, sizes).knownMode(device))
        assertEquals(KnownMode.Listed(1280 to 720), source(1024 to 576, sizes).knownMode(device))
    }

    @Test
    fun aCameraWithNoSizeAtTheRateOpensAtItsOwnModeWithOrWithoutATarget() {
        assertEquals(KnownMode.DefaultMissing, source(1920 to 1080, emptyList()).knownMode(device))
        assertEquals(KnownMode.DefaultMissing, source(null, emptyList()).knownMode(device))
    }

    @Test
    fun withoutATargetOnlyAMissing720pIsKnown() {
        assertEquals(KnownMode.DefaultMissing, source(null, listOf(1920 to 1080)).knownMode(device))
        assertNull(source(null, listOf(1280 to 720, 1920 to 1080)).knownMode(device))
    }

    @Test
    fun devicesThatCannotTellOrATestCameraKnowNothing() {
        assertNull(source(3840 to 2160, null).knownMode(device))
        defaultCameraOverride = { error("not opened") }
        assertNull(source(3840 to 2160, listOf(1280 to 720)).knownMode(device))
    }
}
