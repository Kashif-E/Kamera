package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset3840x2160
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVCaptureSessionPresetPhoto

/** A session preset and the frame it delivers, long side first; the Photo preset is the sensor's full size. */
private class Preset(val name: String, val long: Int, val short: Int)

private val photo = Preset(AVCaptureSessionPresetPhoto!!, Int.MAX_VALUE, Int.MAX_VALUE)

/** 4:3, largest first. 1:1 uses it too: its stills are a centred square of the 4:3 photo. */
private val fourThree = listOf(photo, Preset(AVCaptureSessionPreset640x480!!, 640, 480))

/** 16:9, largest first. */
private val sixteenNine = listOf(
    Preset(AVCaptureSessionPreset3840x2160!!, 3840, 2160),
    Preset(AVCaptureSessionPreset1920x1080!!, 1920, 1080),
    Preset(AVCaptureSessionPreset1280x720!!, 1280, 720),
)

/**
 * The session presets setup tries, best first (w6 decision G1, #161). Only presets of [aspectRatio]
 * are listed: the aspect ratio wins over [target], which only picks where in that ratio's list to
 * start. With a target (its long and short sides, so 1080x1920 means 1920x1080) the list starts at the
 * smallest preset that reaches it, at the largest when none does, and goes down from there.
 *
 * Without a target every ratio runs the Photo preset, the sensor's full resolution: a 16:9 still is
 * then a centred 16:9 crop of that photo, so it keeps the full width, and a 1:1 still a centred
 * square. 16:9 falls back to its own presets when the session cannot take Photo.
 */
internal fun presetCandidates(aspectRatio: AspectRatio, target: Pair<Int, Int>?): List<String> {
    val ladder = when (aspectRatio) {
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> sixteenNine
        AspectRatio.RATIO_4_3, AspectRatio.RATIO_1_1 -> fourThree
    }
    if (target == null) return (listOf(photo) + ladder).map { it.name }.distinct()
    val long = maxOf(target.first, target.second)
    val short = minOf(target.first, target.second)
    val start = ladder.indexOfLast { it.long >= long && it.short >= short }.coerceAtLeast(0)
    return ladder.drop(start).map { it.name }
}
