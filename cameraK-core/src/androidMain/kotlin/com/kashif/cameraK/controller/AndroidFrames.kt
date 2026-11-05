package com.kashif.cameraK.controller

import androidx.camera.core.ImageProxy
import com.kashif.cameraK.frames.AnalyzerLease
import com.kashif.cameraK.frames.AnalyzerRetain
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FramePlane
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.RetainedFrame
import java.nio.ByteBuffer

/** What one analysis frame's metadata states, read from its ImageProxy before anyone sees it. */
internal class FrameMeta(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampNanos: Long,
    val format: FrameFormat,
    val cropRect: FrameRect,
) {
    companion object {
        /** [proxy]'s size, rotation, timestamp and crop rect, in the session's [format]. */
        fun of(proxy: ImageProxy, format: FrameFormat): FrameMeta {
            val info = proxy.imageInfo
            val crop = proxy.cropRect
            return FrameMeta(
                width = proxy.width,
                height = proxy.height,
                rotationDegrees = info.rotationDegrees,
                timestampNanos = info.timestamp,
                format = format,
                cropRect = FrameRect(crop.left, crop.top, crop.right, crop.bottom),
            )
        }
    }
}

/**
 * The proxy a synchronous registration gets: CameraX's own, through read-only planes of its own, so
 * one registration reading or trying to write a plane cannot move the position or change the pixels
 * another reads. Closing it does nothing: the multiplexer closes the real proxy before `analyze`
 * returns. Its `image` is still CameraX's shared image: read it, never write to it.
 */
internal class SynchronousImageProxy(private val delegate: ImageProxy) : ImageProxy by delegate {
    private val ownPlanes: Array<ImageProxy.PlaneProxy> by lazy {
        delegate.planes.map { plane -> ReadOnlyPlane(plane) as ImageProxy.PlaneProxy }.toTypedArray()
    }

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> = ownPlanes

    override fun close() = Unit
}

/**
 * A frame delivered to a synchronous registration, over [proxy] itself: zero copy, valid until the
 * registration's `analyze` returns. [retain] copies the planes into a frame of its own, because the
 * proxy closes when `analyze` returns.
 */
internal class ProxyFrame(
    val proxy: SynchronousImageProxy,
    private val meta: FrameMeta,
    private val lease: AnalyzerLease,
) : CameraFrame {
    override val width: Int get() = meta.width
    override val height: Int get() = meta.height
    override val rotationDegrees: Int get() = meta.rotationDegrees
    override val timestampNanos: Long get() = meta.timestampNanos
    override val format: FrameFormat get() = meta.format
    override val cropRect: FrameRect get() = meta.cropRect
    override val planes: List<FramePlane> by lazy {
        proxy.planes.map { BufferPlane(it.buffer, it.rowStride, it.pixelStride) }
    }

    override fun retain(): RetainedFrame {
        val copy = FrameSlot().apply { fill(proxy, meta) }
        return RetainedSlotFrame(copy, lease, lease.retain())
    }
}

/**
 * A plane over a buffer of its own (a read-only duplicate of CameraX's), read from its position
 * at construction. Not thread-safe: it belongs to one registration's frame.
 */
private class BufferPlane(buffer: ByteBuffer, override val rowStride: Int, override val pixelStride: Int) : FramePlane {
    private val view: ByteBuffer = buffer.duplicate()
    private val start = view.position()
    override val size: Int = view.remaining()

    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
        copyTo(destination, destinationOffset, 0, size)
        return size
    }

    override fun copyTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int, length: Int) {
        view.position(start + sourceOffset)
        view.get(destination, destinationOffset, length)
    }
}

/**
 * One copy of a frame's planes: a pooled slot shared by the asynchronous registrations a frame went
 * to, or a synchronous frame's retained copy. Its arrays are reused across fills while they are big
 * enough, so a pooled slot allocates only when a frame outgrows it.
 */
internal class FrameSlot {
    private var bytes: Array<ByteArray> = emptyArray()
    private var sizes = IntArray(0)
    private var rowStrides = IntArray(0)
    private var pixelStrides = IntArray(0)
    lateinit var meta: FrameMeta
        private set

    /** Copies [proxy]'s planes, each once, and keeps [meta]. */
    fun fill(proxy: ImageProxy, meta: FrameMeta) {
        val source = proxy.planes
        if (bytes.size != source.size) {
            bytes = Array(source.size) { ByteArray(0) }
            sizes = IntArray(source.size)
            rowStrides = IntArray(source.size)
            pixelStrides = IntArray(source.size)
        }
        for (index in source.indices) {
            val plane = source[index]
            val buffer = plane.buffer.duplicate()
            val size = buffer.remaining()
            if (bytes[index].size < size) bytes[index] = ByteArray(size)
            buffer.get(bytes[index], 0, size)
            sizes[index] = size
            rowStrides[index] = plane.rowStride
            pixelStrides[index] = plane.pixelStride
        }
        this.meta = meta
    }

    val planes: List<FramePlane> get() = List(bytes.size) {
        SlotPlane(bytes[it], sizes[it], rowStrides[it], pixelStrides[it])
    }
}

private class SlotPlane(
    private val bytes: ByteArray,
    override val size: Int,
    override val rowStride: Int,
    override val pixelStride: Int,
) : FramePlane {
    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
        bytes.copyInto(destination, destinationOffset, 0, size)
        return size
    }

    override fun copyTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int, length: Int) {
        require(sourceOffset >= 0 && length >= 0 && sourceOffset + length <= size) {
            "copying $length bytes from $sourceOffset of a $size-byte plane"
        }
        bytes.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + length)
    }
}

/** A frame delivered to an asynchronous registration: [slot]'s copy, held under [lease]. */
internal open class SlotFrame(private val slot: FrameSlot, private val lease: AnalyzerLease) : CameraFrame {
    override val width: Int get() = slot.meta.width
    override val height: Int get() = slot.meta.height
    override val rotationDegrees: Int get() = slot.meta.rotationDegrees
    override val timestampNanos: Long get() = slot.meta.timestampNanos
    override val format: FrameFormat get() = slot.meta.format
    override val cropRect: FrameRect get() = slot.meta.cropRect
    override val planes: List<FramePlane> by lazy { slot.planes }

    override fun retain(): RetainedFrame = RetainedSlotFrame(slot, lease, lease.retain())
}

/** A frame kept past `analyze`: [slot] stays this registration's until [close] releases [handle]. */
private class RetainedSlotFrame(slot: FrameSlot, lease: AnalyzerLease, private val handle: AnalyzerRetain) :
    SlotFrame(slot, lease),
    RetainedFrame {
    override fun close() = handle.release()
}
