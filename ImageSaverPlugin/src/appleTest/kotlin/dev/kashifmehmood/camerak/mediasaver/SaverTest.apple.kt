package dev.kashifmehmood.camerak.mediasaver

import dev.kashifmehmood.camerak.capture.GalleryAsset
import dev.kashifmehmood.camerak.utils.InternalCameraKApi

actual abstract class SaverTest actual constructor()

@OptIn(InternalCameraKApi::class)
internal actual fun fakeGalleryAsset(displayName: String): GalleryAsset =
    GalleryAsset(displayName, localIdentifier = "FAKE-ASSET/L0/001")
