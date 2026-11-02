package com.kashif.cameraK.capture

import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.CameraKLogger

internal const val HEIC_CAPTURE_UNSUPPORTED = "HEIC capture is not supported yet"

/**
 * What a 1.x controller returns for a capture configured as HEIC, before it writes anything: no
 * platform writes HEIC yet, and a file named `.heic` holding JPEG or PNG bytes would mislead every
 * reader. Logged once per refused capture. A3', I3' and D3' replace it.
 */
internal fun heicCaptureUnsupported(tag: String): ImageCaptureResult.Error {
    CameraKLogger.w(tag, HEIC_CAPTURE_UNSUPPORTED)
    return ImageCaptureResult.Error(UnsupportedOperationException(HEIC_CAPTURE_UNSUPPORTED))
}
