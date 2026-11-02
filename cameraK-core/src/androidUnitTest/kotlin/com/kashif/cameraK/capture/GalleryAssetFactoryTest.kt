package com.kashif.cameraK.capture

import android.net.Uri
import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.utils.InternalCameraKApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@OptIn(InternalCameraKApi::class, CameraKPlatformApi::class)
class GalleryAssetFactoryTest {
    @Test
    fun factoryRoundTripsThePlatformPayload() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val asset = GalleryAsset("IMG_1.jpg", uri = uri)

        assertEquals("IMG_1.jpg", asset.displayName)
        assertEquals(uri, asset.uri)
        assertEquals(asset, GalleryAsset("IMG_1.jpg", uri = Uri.parse("content://media/external/images/media/42")))
    }
}
