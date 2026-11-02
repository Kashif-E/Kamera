package com.kashif.cameraK.utils

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The name of a new capture file: `<prefix>_<epoch milliseconds>_<8 random hex digits>.<extension>`.
 * Up to three captures can be in flight at once, so the name is unique without relying on the clock;
 * a platform that creates the file still claims it atomically and retries on the rare collision.
 */
@InternalCameraKApi
@OptIn(ExperimentalTime::class)
fun captureFileName(prefix: String, extension: String, random: Random = Random.Default): String {
    val millis = Clock.System.now().toEpochMilliseconds()
    val suffix = random.nextInt().toUInt().toString(16).padStart(8, '0')
    return "${prefix}_${millis}_$suffix.$extension"
}
