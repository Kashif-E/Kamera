package com.kashif.cameraK.capture

import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalCameraKApi::class, CameraKPlatformApi::class)
class GalleryAssetFactoryTest {
    @Test
    fun factoryRoundTripsThePlatformPayload() {
        val asset = GalleryAsset("IMG_0001.HEIC", localIdentifier = "9F983DBA-EC35-42B8-8773-B597CF782EDD/L0/001")

        assertEquals("IMG_0001.HEIC", asset.displayName)
        assertEquals("9F983DBA-EC35-42B8-8773-B597CF782EDD/L0/001", asset.localIdentifier)
        assertEquals(
            asset,
            GalleryAsset("IMG_0001.HEIC", localIdentifier = "9F983DBA-EC35-42B8-8773-B597CF782EDD/L0/001"),
        )
    }
}
