package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.captureFileName
import java.io.File
import java.io.IOException

private const val MAX_NAME_ATTEMPTS = 16

/** The name of a new capture, unique by construction (see [captureFileName]). */
internal fun newCaptureFileName(prefix: String, extension: String): String = captureFileName(prefix, extension)

/**
 * Creates the file a new capture is written to, in the existing directory [dir]. The file is created
 * here with createNewFile, which is atomic, so no other capture can be given it and no existing file
 * is ever overwritten.
 *
 * @throws IOException when the file cannot be created.
 */
internal fun newCaptureFile(dir: File, prefix: String, extension: String): File {
    repeat(MAX_NAME_ATTEMPTS) {
        val candidate = File(dir, newCaptureFileName(prefix, extension))
        if (candidate.createNewFile()) return candidate
    }
    throw IOException("No free name for a new capture file")
}
