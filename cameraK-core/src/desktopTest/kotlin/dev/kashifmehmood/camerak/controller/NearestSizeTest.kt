package dev.kashifmehmood.camerak.controller

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The size a desktop camera opens at for a targetResolution, as CameraX picks one: the target when
 * the camera lists it; else the smallest size that holds the target once cropped to its aspect ratio;
 * else the size whose crop holds the most of it.
 */
class NearestSizeTest {
    private val faceTime = listOf(1920 to 1080, 1280 to 720, 1552 to 1552, 640 to 480)

    @Test
    fun aListedTargetIsItself() {
        assertEquals(1280 to 720, nearestSize(faceTime, 1280 to 720))
    }

    @Test
    fun aTargetLargerThanEverySizeTakesTheLargestOfItsAspectRatio() {
        assertEquals(1920 to 1080, nearestSize(faceTime, 3840 to 2160))
    }

    @Test
    fun aTargetBetweenSizesTakesTheSmallestThatCoversIt() {
        assertEquals(1920 to 1080, nearestSize(faceTime, 1600 to 900))
        assertEquals(1280 to 720, nearestSize(faceTime, 960 to 540))
    }

    // Desktop cameras stream landscape: a portrait target written for the phones is turned first.
    @Test
    fun aPortraitTargetIsTurnedWhenTheCameraListsNoPortraitSize() {
        assertEquals(1280 to 720, nearestSize(listOf(1920 to 1080, 1280 to 720, 640 to 480), 720 to 1280))
        assertEquals(1920 to 1080, nearestSize(faceTime, 1080 to 1920))
    }

    @Test
    fun aTargetOfAnotherAspectRatioIsMeasuredByWhatACropToItHolds() {
        assertEquals(1920 to 1080, nearestSize(faceTime, 1440 to 1080))
        assertEquals(1552 to 1552, nearestSize(faceTime, 4000 to 3000))
        assertEquals(640 to 480, nearestSize(listOf(640 to 480, 320 to 240), 2048 to 1536))
    }
}
