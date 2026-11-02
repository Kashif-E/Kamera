package com.kashif.cameraK.capture

import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.utils.InternalCameraKApi

/** The absolute path of the file in the gallery folder. */
@CameraKPlatformApi
public val GalleryAsset.path: String get() = payload as String

/** Builds the handle for a file in a gallery folder. Only the media saver plugin produces gallery assets. */
@InternalCameraKApi
public fun GalleryAsset(displayName: String, path: String): GalleryAsset = GalleryAsset(displayName, payload = path)
