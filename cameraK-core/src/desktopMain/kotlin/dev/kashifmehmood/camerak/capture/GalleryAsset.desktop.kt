package dev.kashifmehmood.camerak.capture

import dev.kashifmehmood.camerak.utils.CameraKPlatformApi
import dev.kashifmehmood.camerak.utils.InternalCameraKApi

/** The absolute path of the file in the gallery folder. */
@CameraKPlatformApi
public val GalleryAsset.path: String get() = payload as String

/** Builds the handle for a file in a gallery folder. Only the media saver plugin produces gallery assets. */
@InternalCameraKApi
public fun GalleryAsset(displayName: String, path: String): GalleryAsset = GalleryAsset(displayName, payload = path)
