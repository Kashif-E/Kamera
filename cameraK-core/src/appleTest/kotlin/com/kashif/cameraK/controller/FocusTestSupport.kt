package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.test.fail

/** The label of every engine's session queue, which device calls are recorded with. */
internal const val SESSION_QUEUE_LABEL = "com.kashif.cameraK.session"

/** The device calls focus, metering and the subject-area reset make. */
internal val FOCUS_OPS = setOf(
    "setFocusPointOfInterest",
    "setFocusMode",
    "setExposurePointOfInterest",
    "setExposureMode",
    "setSubjectAreaChangeMonitoringEnabled",
)

internal fun failure(result: CameraKResult<*>): CameraKError =
    (result as? CameraKResult.Failure)?.error ?: fail("expected a failure, was $result")

/** Seconds since 1970, for the waits AVFoundation's timing is measured in. */
internal fun now(): Double = NSDate().timeIntervalSince1970

/**
 * Settles the driver until [condition] holds, for up to about four seconds: a focus or bias
 * completion arrives on the session queue in real time, which no scheduler can advance.
 */
internal suspend fun AppleEngineDriver.settleUntil(what: String, condition: () -> Boolean) {
    repeat(200) {
        if (condition()) return
        settle()
        spinUntil(0.01) { false }
    }
    if (!condition()) fail("timed out waiting until $what")
}
