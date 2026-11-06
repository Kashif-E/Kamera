package com.kashif.mediasaverplugin

import android.net.Uri
import com.kashif.cameraK.capture.GalleryAsset
import com.kashif.cameraK.utils.InternalCameraKApi
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The common tests build gallery handles over a Uri, which only Robolectric's Android implements.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
actual abstract class SaverTest actual constructor()

@OptIn(InternalCameraKApi::class)
internal actual fun fakeGalleryAsset(displayName: String): GalleryAsset =
    GalleryAsset(displayName, Uri.parse("content://media/external/images/media/1"))
