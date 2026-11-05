package com.kashif.cameraK.engine

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One analysis frame as CameraX hands it to an ImageAnalysis analyzer, with what the frame's metadata
 * states: its size, rotation, crop rect and timestamp. Its plane buffers are shared by every reader,
 * as CameraX's are, and [closes] counts calls to the real proxy's close().
 */
internal class FakeImageProxy private constructor(
    val width: Int,
    val height: Int,
    private val format: Int,
    private val rotationDegrees: Int,
    private val cropRect: Rect,
    private val timestampNanos: Long,
    private val planeBytes: List<PlaneBytes>,
) {
    class PlaneBytes(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int)

    var closes = 0
        private set

    /** Plane 0's bytes: the luma of a YUV_420 frame, the pixels of an RGBA_8888 one. */
    val firstPlane: ByteArray get() = planeBytes.first().bytes.copyOf()

    private val planes: Array<ImageProxy.PlaneProxy> = planeBytes.map { plane ->
        object : ImageProxy.PlaneProxy {
            private val buffer = ByteBuffer.wrap(plane.bytes).order(ByteOrder.LITTLE_ENDIAN)

            override fun getRowStride() = plane.rowStride

            override fun getPixelStride() = plane.pixelStride

            override fun getBuffer(): ByteBuffer = buffer
        }
    }.toTypedArray()

    private val info: ImageInfo = stub { name ->
        when (name) {
            "getRotationDegrees" -> rotationDegrees
            "getTimestamp" -> timestampNanos
            else -> null
        }
    }

    val proxy: ImageProxy = stub { name ->
        when (name) {
            "close" -> {
                closes++
                Unit
            }
            "getWidth" -> width
            "getHeight" -> height
            "getFormat" -> format
            "getPlanes" -> planes
            "getImageInfo" -> info
            "getCropRect" -> Rect(cropRect)
            else -> null
        }
    }

    companion object {
        /**
         * A YUV_420_888 frame whose luma is [seed], [seed] + 1, [seed] + 2… and whose chroma is neutral
         * grey. Frames given different seeds hold different bytes.
         */
        fun yuv(
            width: Int = 8,
            height: Int = 4,
            rotationDegrees: Int = 0,
            cropRect: Rect = Rect(0, 0, width, height),
            timestampNanos: Long = 0,
            seed: Int = 0,
        ): FakeImageProxy {
            val chroma = (width / 2) * (height / 2)
            return FakeImageProxy(
                width,
                height,
                ImageFormat.YUV_420_888,
                rotationDegrees,
                cropRect,
                timestampNanos,
                listOf(
                    PlaneBytes(ByteArray(width * height) { (it + seed).toByte() }, width, 1),
                    PlaneBytes(ByteArray(chroma) { 128.toByte() }, width / 2, 1),
                    PlaneBytes(ByteArray(chroma) { 128.toByte() }, width / 2, 1),
                ),
            )
        }

        /** An RGBA_8888 frame: one plane, four bytes per pixel. */
        fun rgba(width: Int = 8, height: Int = 4): FakeImageProxy = FakeImageProxy(
            width,
            height,
            PixelFormat.RGBA_8888,
            0,
            Rect(0, 0, width, height),
            0,
            listOf(PlaneBytes(ByteArray(width * height * 4) { it.toByte() }, width * 4, 4)),
        )
    }
}

private inline fun <reified T> stub(crossinline answer: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.firstOrNull()
            "toString" -> "Fake${T::class.java.simpleName}"
            else -> answer(method.name)
        }
    } as T
