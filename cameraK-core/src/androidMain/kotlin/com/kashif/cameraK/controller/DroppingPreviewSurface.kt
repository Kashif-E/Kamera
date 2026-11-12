package com.kashif.cameraK.controller

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import com.kashif.cameraK.utils.CameraKLogger

/**
 * The Preview's surface once no preview shows the camera: it takes each frame and lets it go.
 *
 * A Preview left without a surface mid-session keeps no repeating request in CameraX 1.5.3, which adds
 * its MeteringRepeating stream only when it resets the session, and going inactive does not reset it.
 * With no repeating request no capture result arrives, so a photo or an exposure change never completes
 * (Galaxy S23). One thread for the whole process takes the frames.
 */
internal object DroppingPreviewSurface : Preview.SurfaceProvider {
    private const val TAG = "CameraK"
    private const val MAX_IMAGES = 2

    private val frames: Handler by lazy {
        Handler(HandlerThread("CameraK dropped preview frames").apply { start() }.looper)
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val size = request.resolution
        val reader = try {
            ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, MAX_IMAGES)
        } catch (e: RuntimeException) {
            CameraKLogger.e(TAG, "making the frame-dropping preview surface failed; the camera may stop streaming", e)
            request.willNotProvideSurface()
            return
        }
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, frames)
        request.provideSurface(reader.surface, Runnable::run) { reader.close() }
    }
}
