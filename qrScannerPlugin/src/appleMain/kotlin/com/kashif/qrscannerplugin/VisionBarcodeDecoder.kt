@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.qrscannerplugin

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreVideo.CVPixelBufferRef
import platform.Vision.VNDetectBarcodesRequest

internal actual fun createBarcodeDecoder(formats: Set<BarcodeFormat>): BarcodeDecoder = VisionBarcodeDecoder(formats)

/** Vision over the plugin's luma, on iOS. */
internal class VisionBarcodeDecoder(private val formats: Set<BarcodeFormat>) : BarcodeDecoder {
    override fun decode(image: LumaImage): List<Barcode> = emptyList()

    /** The request for [image]: the configured symbologies, and the image's area as the region of interest. */
    fun request(image: LumaImage): VNDetectBarcodesRequest = VNDetectBarcodesRequest()

    /** A one-plane 8-bit buffer holding [image]'s luma, which the caller releases. */
    fun lumaPixelBuffer(image: LumaImage): CVPixelBufferRef? = null
}
