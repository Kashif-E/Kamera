@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.testing

import android.view.Display
import androidx.camera.core.Preview
import dev.kashifmehmood.camerak.engine.PlatformPreviewSource
import dev.kashifmehmood.camerak.engine.PreviewAttachment
import dev.kashifmehmood.camerak.utils.CameraKEngineApi

internal actual fun fakePreviewSource(): PlatformPreviewSource = object : PlatformPreviewSource {
    override fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment =
        object : PreviewAttachment {
            override fun close() = Unit
        }
}
