@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.InternalCameraKApi
import com.kashif.cameraK.utils.toNSData
import platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH
import platform.darwin.dispatch_apply
import platform.darwin.dispatch_get_global_queue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Captures that AVFoundation finishes at the same moment are saved on several threads at once, and
 * each must land in a file of its own: two captures sharing a name overwrite each other's photo.
 */
class ConcurrentCaptureFilesTest {
    private val rig = CaptureRig()

    @AfterTest
    fun tearDown() = rig.tearDown()

    @Test
    fun capturesFinishedTogetherAreSavedToFilesOfTheirOwn() {
        rig.start()
        val paths = mutableListOf<String>()
        repeat(10) { round ->
            val captures = List(3) { rig.capture() }
            dispatch_apply(3u, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) { i ->
                val request = captures[i.toInt()].second
                request.callbacks.photoProcessed(photo(round, i.toInt()).toNSData(), null)
                request.callbacks.captureFinished(null)
            }
            captures.forEachIndexed { i, (capture, _) ->
                val result = assertIs<CameraKResult.Success<CapturedImage>>(capture.awaitResult()).value
                assertContentEquals(
                    photo(round, i),
                    fileBytes(result.path),
                    "round $round: capture $i lost its photo",
                )
                paths += result.path
            }
        }
        assertEquals(
            paths.size,
            paths.toSet().size,
            "captures shared a file: ${paths.groupBy {
                it
            }.filter { it.value.size > 1 }.keys}",
        )
    }

    private fun photo(round: Int, index: Int) = ByteArray(40) { (round * 7 + index * 3 + it).toByte() }
}
