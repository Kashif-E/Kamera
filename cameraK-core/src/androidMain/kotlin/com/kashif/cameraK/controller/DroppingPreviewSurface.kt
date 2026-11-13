package com.kashif.cameraK.controller

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest

/**
 * The Preview's surface once no preview shows the camera: a SurfaceTexture nothing reads, of the size
 * CameraX asks for, the kind of surface CameraX's own MeteringRepeating stream draws into.
 *
 * A Preview left without a surface mid-session keeps no repeating request in CameraX 1.5.3, which adds
 * MeteringRepeating only when it resets the session, and going inactive does not reset it. With no
 * repeating request no capture result arrives, so a photo or an exposure change never completes
 * (Galaxy S23).
 */
internal object DroppingPreviewSurface : Preview.SurfaceProvider {
    override fun onSurfaceRequested(request: SurfaceRequest) {
        val size = request.resolution
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(size.width, size.height) }
        val surface = Surface(texture)
        request.provideSurface(surface, Runnable::run) {
            surface.release()
            texture.release()
        }
    }
}
