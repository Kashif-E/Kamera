package com.kashif.cameraK.capture

import android.net.Uri
import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.utils.InternalCameraKApi

/** The MediaStore entry, a `content://` URI. */
@CameraKPlatformApi
public val GalleryAsset.uri: Uri get() = TODO("S1' stub")

/** Builds the handle for a MediaStore entry. Only the media saver plugin produces gallery assets. */
@InternalCameraKApi
public fun GalleryAsset(displayName: String, uri: Uri): GalleryAsset = TODO("S1' stub")
