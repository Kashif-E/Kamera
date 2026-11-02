package com.kashif.cameraK.capture

import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.utils.InternalCameraKApi

/** The Photos library's `PHAsset.localIdentifier`. */
@CameraKPlatformApi
public val GalleryAsset.localIdentifier: String get() = payload as String

/** Builds the handle for a Photos asset. Only the media saver plugin produces gallery assets. */
@InternalCameraKApi
public fun GalleryAsset(displayName: String, localIdentifier: String): GalleryAsset =
    GalleryAsset(displayName, payload = localIdentifier)
