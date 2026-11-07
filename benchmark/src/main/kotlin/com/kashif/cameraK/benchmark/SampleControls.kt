package com.kashif.cameraK.benchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlin.math.abs

/** The Sample's benchmark build, which every benchmark here drives. */
internal const val SAMPLE_PACKAGE = "org.company.app.androidApp"

// The trace sections the benchmarks read. CameraK's come from the Android engine's AndroidTracer.
internal const val CAMERAK_FIRST_FRAME = "CameraK.firstFrame"
internal const val CAMERAK_CAPTURE = "CameraK.capture"
internal const val CAMERAK_VIDEO_BIND = "CameraK.videoBind"
internal const val BASELINE_FIRST_FRAME = "Baseline.firstFrame"

/** Every ProcessCameraProvider bind, whichever overload the caller uses, runs this CameraX 1.5 section once. */
internal const val CAMERAX_BIND = "CX:bindToLifecycle-internal"

internal const val STARTUP_ITERATIONS = 10
internal const val ITERATIONS = 5

private const val BARE_CAMERAX_ACTIVITY = "org.company.app.BareCameraXActivity"
private const val UI_TIMEOUT_MS = 15_000L

// No control shows the first preview frame or a saved file, so an iteration waits this long after
// the last thing it can see, for the trace section it reads to close inside the trace.
private const val FRAME_SETTLE_MS = 1_500L
private const val CAPTURE_SETTLE_MS = 3_000L
private const val ANALYZER_SETTLE_MS = 1_000L

// The Sample controls the benchmarks use, by what the Sample labels them.
private const val FLIP_CAMERA = "Flip Camera"
private const val SETTINGS = "Settings"
private const val QR_SCANNER = "QR Scanner"
private const val VIDEO_MODE = "VIDEO"

// The shutter's description says what a tap does.
private const val TAKE_PHOTO = "Take photo"
private const val START_RECORDING = "Start recording"
private const val STOP_RECORDING = "Stop recording"

/** The top bar's aspect-ratio chip, in the order a tap cycles it. The Sample does not offer the deprecated 9:16. */
private val ASPECT_RATIOS = listOf("4:3", "16:9", "1:1")

/** Recording needs the microphone too: the Sample records with audio. */
internal fun MacrobenchmarkScope.grantSamplePermissions() {
    for (permission in listOf("CAMERA", "RECORD_AUDIO")) {
        device.executeShellCommand("pm grant $SAMPLE_PACKAGE android.permission.$permission")
    }
}

internal fun bareCameraXIntent(): Intent = Intent().setClassName(SAMPLE_PACKAGE, BARE_CAMERAX_ACTIVITY)

/** CameraKScreen shows its content, Flip Camera among it, only once the holder is Ready. */
internal fun MacrobenchmarkScope.awaitCameraReady() {
    requireObject(By.desc(FLIP_CAMERA), "the camera screen's Flip Camera button")
}

internal fun MacrobenchmarkScope.awaitFirstFrame() {
    device.waitForIdle()
    Thread.sleep(FRAME_SETTLE_MS)
}

internal fun MacrobenchmarkScope.openCameraScreen() {
    startActivityAndWait()
    awaitCameraReady()
    awaitFirstFrame()
}

/** Takes a photo with the photo mode's shutter. */
internal fun MacrobenchmarkScope.pressShutter() {
    requireObject(By.desc(TAKE_PHOTO), "the shutter").click()
}

/** Starts a recording with the video mode's shutter. */
internal fun MacrobenchmarkScope.startRecording() {
    requireObject(By.desc(START_RECORDING), "the shutter").click()
}

/** Stops the recording with the shutter, which shows a stop button while the Sample records. */
internal fun MacrobenchmarkScope.stopRecording() {
    requireObject(By.desc(STOP_RECORDING), "the shutter").click()
}

internal fun MacrobenchmarkScope.awaitCaptureSaved() {
    Thread.sleep(CAPTURE_SETTLE_MS)
}

/** Taps the top bar's aspect-ratio chip until it reads [label]; each change re-initializes the camera. */
internal fun MacrobenchmarkScope.selectAspectRatio(label: String) {
    repeat(ASPECT_RATIOS.size) {
        val current = ASPECT_RATIOS.firstOrNull { device.hasObject(By.desc(it)) }
            ?: error("the Sample shows no aspect-ratio chip")
        if (current == label) return
        val next = ASPECT_RATIOS[(ASPECT_RATIOS.indexOf(current) + 1) % ASPECT_RATIOS.size]
        requireObject(By.desc(current), "the $current chip").click()
        requireObject(By.desc(next), "the $next chip")
        awaitCameraReady()
    }
    check(device.hasObject(By.desc(label))) { "the aspect-ratio chip never read $label" }
}

internal fun MacrobenchmarkScope.selectVideoMode() {
    requireObject(By.text(VIDEO_MODE), "the VIDEO mode").click()
    device.waitForIdle()
}

/** The top bar's chips give way to the recording timer while the Sample records. */
internal fun MacrobenchmarkScope.awaitRecording(recording: Boolean) {
    val chip = By.desc(ASPECT_RATIOS.first())
    val reached = device.wait(if (recording) Until.gone(chip) else Until.hasObject(chip), UI_TIMEOUT_MS)
    check(reached == true) { if (recording) "the Sample did not start recording" else "the recording did not end" }
}

internal fun MacrobenchmarkScope.openSettings() {
    requireObject(By.desc(SETTINGS), "the Settings button").click()
    requireObject(By.text(QR_SCANNER), "the QR Scanner setting")
}

/**
 * Flips the settings panel's QR Scanner switch: off unregisters the QR plugin's frame analyzer, on
 * registers it again. Waits for the switch to change, then long enough for a rebind to reach the trace.
 */
internal fun MacrobenchmarkScope.toggleQrScanner() {
    val label = requireObject(By.text(QR_SCANNER), "the QR Scanner setting").visibleCenter.y
    val switch = device.findObjects(By.checkable(true)).minByOrNull { abs(it.visibleCenter.y - label) }
        ?: error("no switch beside the QR Scanner setting")
    val wasChecked = switch.isChecked
    switch.click()
    check(switch.wait(Until.checked(!wasChecked), UI_TIMEOUT_MS) == true) { "the QR Scanner switch did not flip" }
    device.waitForIdle()
    Thread.sleep(ANALYZER_SETTLE_MS)
}

private fun MacrobenchmarkScope.requireObject(selector: BySelector, what: String): UiObject2 =
    device.wait(Until.findObject(selector), UI_TIMEOUT_MS) ?: error("$what did not appear within $UI_TIMEOUT_MS ms")
