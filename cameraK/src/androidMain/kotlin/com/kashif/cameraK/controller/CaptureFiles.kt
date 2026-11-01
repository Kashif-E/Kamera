package com.kashif.cameraK.controller

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The name of a new capture: `<prefix>_<timestamp>.<extension>`. */
internal fun newCaptureFileName(prefix: String, extension: String): String {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "${prefix}_$timeStamp.$extension"
}

/** The file a new capture is written to, in [dir]. */
internal fun newCaptureFile(dir: File, prefix: String, extension: String): File =
    File(dir, newCaptureFileName(prefix, extension))
