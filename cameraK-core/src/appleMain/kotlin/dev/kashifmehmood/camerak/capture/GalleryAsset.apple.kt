package dev.kashifmehmood.camerak.capture

import dev.kashifmehmood.camerak.utils.CameraKPlatformApi
import dev.kashifmehmood.camerak.utils.InternalCameraKApi

/** The Photos library's `PHAsset.localIdentifier`. */
@CameraKPlatformApi
public val GalleryAsset.localIdentifier: String get() = payload as String

/** Builds the handle for a Photos asset. Only the media saver plugin produces gallery assets. */
@InternalCameraKApi
public fun GalleryAsset(displayName: String, localIdentifier: String): GalleryAsset =
    GalleryAsset(displayName, payload = localIdentifier)
