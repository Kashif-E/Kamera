package dev.kashifmehmood.camerak.capture

import dev.kashifmehmood.camerak.utils.CameraKPlatformApi
import dev.kashifmehmood.camerak.utils.InternalCameraKApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalCameraKApi::class, CameraKPlatformApi::class)
class GalleryAssetFactoryTest {
    @Test
    fun factoryRoundTripsThePlatformPayload() {
        val asset = GalleryAsset("IMG_1.jpg", path = "/home/me/Pictures/CameraK/IMG_1.jpg")

        assertEquals("IMG_1.jpg", asset.displayName)
        assertEquals("/home/me/Pictures/CameraK/IMG_1.jpg", asset.path)
        assertEquals(asset, GalleryAsset("IMG_1.jpg", path = "/home/me/Pictures/CameraK/IMG_1.jpg"))
    }
}
