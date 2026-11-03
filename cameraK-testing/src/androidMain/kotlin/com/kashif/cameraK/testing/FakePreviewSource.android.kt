@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import android.view.Display
import androidx.camera.core.Preview
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.PreviewAttachment
import com.kashif.cameraK.utils.CameraKEngineApi

internal actual fun fakePreviewSource(): PlatformPreviewSource = object : PlatformPreviewSource {
    override fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment =
        object : PreviewAttachment {
            override fun close() = Unit
        }
}
