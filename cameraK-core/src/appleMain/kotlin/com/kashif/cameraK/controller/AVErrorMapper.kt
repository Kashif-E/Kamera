package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.Foundation.NSError

/** The engine's interruption reason for AVFoundation's AVCaptureSessionInterruptionReason [code]. */
internal fun interruptionReasonFor(code: Long?): InterruptionReason = TODO("I1'")

/** What a session runtime error does to the engine: a media-services reset interrupts it, anything else fails it. */
@OptIn(CameraKEngineApi::class)
internal fun sessionErrorState(error: NSError): EngineState = TODO("I1'")

/** The CameraKError for an NSError outside a recording, keeping the NSError as its cause. */
internal fun cameraKErrorFor(error: NSError): CameraKError = TODO("I1'")
