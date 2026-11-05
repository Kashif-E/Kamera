@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.qrscannerplugin

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreGraphics.CGRectMake
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_OneComponent8
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSError
import platform.ImageIO.CGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyOrientationDown
import platform.ImageIO.kCGImagePropertyOrientationLeft
import platform.ImageIO.kCGImagePropertyOrientationRight
import platform.ImageIO.kCGImagePropertyOrientationUp
import platform.Vision.VNBarcodeObservation
import platform.Vision.VNBarcodeSymbologyAztec
import platform.Vision.VNBarcodeSymbologyCodabar
import platform.Vision.VNBarcodeSymbologyCode128
import platform.Vision.VNBarcodeSymbologyCode39
import platform.Vision.VNBarcodeSymbologyCode93
import platform.Vision.VNBarcodeSymbologyDataMatrix
import platform.Vision.VNBarcodeSymbologyEAN13
import platform.Vision.VNBarcodeSymbologyEAN8
import platform.Vision.VNBarcodeSymbologyI2of5
import platform.Vision.VNBarcodeSymbologyITF14
import platform.Vision.VNBarcodeSymbologyPDF417
import platform.Vision.VNBarcodeSymbologyQR
import platform.Vision.VNBarcodeSymbologyUPCE
import platform.Vision.VNDetectBarcodesRequest
import platform.Vision.VNImageRequestHandler
import platform.posix.memcpy

internal actual fun createBarcodeDecoder(formats: Set<BarcodeFormat>): BarcodeDecoder = VisionBarcodeDecoder(formats)

/**
 * Vision over the plugin's luma, on iOS: one 8-bit, one-plane pixel buffer per decoded frame, built
 * from the plugin's copy, so the frame's own sample buffer is never held. Vision reads only the
 * configured symbologies, inside the image's area (its region of interest). iOS reads UPC-A codes
 * as EAN-13, so [BarcodeFormat.UPC_A] is ignored, and logged once.
 */
internal class VisionBarcodeDecoder(
    formats: Set<BarcodeFormat>,
    // Null is Vision's default, its newest revision. On the simulator, revisions 3 and 4 find no
    // code and report no error, so the simulator tests that run a real detection pin revision 2.
    private val pinnedRevision: ULong? = null,
) : BarcodeDecoder {
    private val requested: List<Any?> = formats.flatMap { SYMBOLOGIES[it].orEmpty() }

    init {
        val ignored = formats.filterNot { it in SYMBOLOGIES }
        if (ignored.isNotEmpty()) CameraKLogger.w(TAG, "Vision cannot read $ignored; those formats are ignored")
    }

    override fun decode(image: LumaImage): List<Barcode> {
        if (requested.isEmpty()) return emptyList()
        val buffer = lumaPixelBuffer(image) ?: error("Vision barcode decoding could not allocate its luma buffer")
        try {
            val request = request(image)
            val handler = VNImageRequestHandler(buffer, orientation(image), emptyMap<Any?, Any?>())
            memScoped {
                val nsError = alloc<ObjCObjectVar<NSError?>>()
                if (!handler.performRequests(listOf(request), nsError.ptr)) {
                    val failure = nsError.value
                    error("Vision barcode request failed (${failure?.domain} ${failure?.code})")
                }
            }
            return request.results.orEmpty().filterIsInstance<VNBarcodeObservation>().mapNotNull { it.toBarcode(image) }
        } finally {
            CVPixelBufferRelease(buffer)
        }
    }

    /**
     * The request for [image]: the configured symbologies, and the image's area as the region of
     * interest. Vision reads the image upright ([orientation]), so the region is the area turned
     * upright, normalised, with its origin at the bottom left.
     */
    fun request(image: LumaImage): VNDetectBarcodesRequest = VNDetectBarcodesRequest().apply {
        pinnedRevision?.let { setRevision(it) }
        setSymbologies(requested)
        val geometry = UprightGeometry(image.width, image.height, image.rotationDegrees)
        val area = geometry.toUpright(image.area)
        val width = geometry.uprightWidth.toDouble()
        val height = geometry.uprightHeight.toDouble()
        setRegionOfInterest(
            CGRectMake(
                area.left / width,
                (geometry.uprightHeight - area.bottom) / height,
                (area.right - area.left) / width,
                (area.bottom - area.top) / height,
            ),
        )
    }

    /** The orientation Vision reads [image] in. */
    fun orientation(image: LumaImage): CGImagePropertyOrientation =
        when (UprightGeometry(image.width, image.height, image.rotationDegrees).quarterTurns) {
            1 -> kCGImagePropertyOrientationRight
            2 -> kCGImagePropertyOrientationDown
            3 -> kCGImagePropertyOrientationLeft
            else -> kCGImagePropertyOrientationUp
        }

    /** A one-plane 8-bit buffer holding [image]'s luma, which the caller releases; null when it cannot be made. */
    fun lumaPixelBuffer(image: LumaImage): CVPixelBufferRef? = memScoped {
        val out = alloc<CVPixelBufferRefVar>()
        val status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            image.width.convert(),
            image.height.convert(),
            kCVPixelFormatType_OneComponent8,
            null,
            out.ptr,
        )
        val buffer = out.value
        if (status != kCVReturnSuccess || buffer == null) {
            CameraKLogger.e(TAG, "Creating the luma pixel buffer for Vision failed (CVReturn $status)")
            return@memScoped null
        }
        CVPixelBufferLockBaseAddress(buffer, 0uL)
        val base = CVPixelBufferGetBaseAddress(buffer)?.reinterpret<ByteVar>()
        val rowBytes = CVPixelBufferGetBytesPerRow(buffer).toLong()
        if (base != null) {
            image.luma.usePinned { pinned ->
                for (y in 0 until image.height) {
                    memcpy(base + y * rowBytes, pinned.addressOf(y * image.width), image.width.convert())
                }
            }
        }
        CVPixelBufferUnlockBaseAddress(buffer, 0uL)
        if (base == null) {
            CVPixelBufferRelease(buffer)
            CameraKLogger.e(TAG, "The luma pixel buffer for Vision had no base address")
            return@memScoped null
        }
        buffer
    }

    // Vision reports the box in the upright image, normalised to the region of interest, origin bottom left.
    private fun VNBarcodeObservation.toBarcode(image: LumaImage): Barcode? {
        val format = FORMATS_BY_SYMBOLOGY[symbology] ?: return null
        val geometry = UprightGeometry(image.width, image.height, image.rotationDegrees)
        val area = geometry.toUpright(image.area)
        val areaWidth = (area.right - area.left).toDouble()
        val areaHeight = (area.bottom - area.top).toDouble()
        val corners = boundingBox.useContents {
            floatArrayOf(
                (area.left + origin.x * areaWidth).toFloat(),
                (area.top + (1 - origin.y - size.height) * areaHeight).toFloat(),
                (area.left + (origin.x + size.width) * areaWidth).toFloat(),
                (area.top + (1 - origin.y) * areaHeight).toFloat(),
            )
        }
        return Barcode(payloadStringValue, null, format, geometry.toImage(corners))
    }

    private companion object {
        const val TAG = "QRScanner"

        val SYMBOLOGIES: Map<BarcodeFormat, List<Any?>> = mapOf(
            BarcodeFormat.QR_CODE to listOf(VNBarcodeSymbologyQR),
            BarcodeFormat.AZTEC to listOf(VNBarcodeSymbologyAztec),
            BarcodeFormat.DATA_MATRIX to listOf(VNBarcodeSymbologyDataMatrix),
            BarcodeFormat.PDF_417 to listOf(VNBarcodeSymbologyPDF417),
            BarcodeFormat.EAN_13 to listOf(VNBarcodeSymbologyEAN13),
            BarcodeFormat.EAN_8 to listOf(VNBarcodeSymbologyEAN8),
            BarcodeFormat.UPC_E to listOf(VNBarcodeSymbologyUPCE),
            BarcodeFormat.CODE_128 to listOf(VNBarcodeSymbologyCode128),
            BarcodeFormat.CODE_39 to listOf(VNBarcodeSymbologyCode39),
            BarcodeFormat.CODE_93 to listOf(VNBarcodeSymbologyCode93),
            BarcodeFormat.CODABAR to listOf(VNBarcodeSymbologyCodabar),
            BarcodeFormat.ITF to listOf(VNBarcodeSymbologyI2of5, VNBarcodeSymbologyITF14),
        )
        val FORMATS_BY_SYMBOLOGY: Map<Any?, BarcodeFormat> =
            SYMBOLOGIES.entries.flatMap { (format, names) -> names.map { it to format } }.toMap()
    }
}
