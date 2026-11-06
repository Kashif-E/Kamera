package com.kashif.mediasaverplugin

import com.kashif.cameraK.capture.GalleryAsset
import com.kashif.cameraK.utils.InternalCameraKApi

actual abstract class SaverTest actual constructor()

@OptIn(InternalCameraKApi::class)
internal actual fun fakeGalleryAsset(displayName: String): GalleryAsset =
    GalleryAsset(displayName, path = "/fake/Pictures/CameraK/$displayName")
