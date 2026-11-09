package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureSessionInterruptionReasonAudioDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableDueToSystemPressure
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableWithMultipleForegroundApps
import platform.AVFoundation.AVErrorApplicationIsNotAuthorizedToUseDevice
import platform.AVFoundation.AVErrorDeviceAlreadyUsedByAnotherSession
import platform.AVFoundation.AVErrorDeviceNotConnected
import platform.AVFoundation.AVErrorDeviceWasDisconnected
import platform.AVFoundation.AVErrorMediaServicesWereReset
import platform.AVFoundation.AVErrorSessionNotRunning
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSError

/*
 * Design §4.1, iOS, outside a recording: the one place AVFoundation's interruption reasons and
 * session errors become engine states and CameraKErrors. Each is a pure function with a table test.
 */

/** The engine's interruption reason for AVFoundation's AVCaptureSessionInterruptionReason [code]. */
internal fun interruptionReasonFor(code: Long?): InterruptionReason = when (code) {
    AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground -> InterruptionReason.APP_IN_BACKGROUND
    AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableWithMultipleForegroundApps ->
        InterruptionReason.MULTITASKING
    AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient -> InterruptionReason.IN_USE_BY_ANOTHER_CLIENT
    AVCaptureSessionInterruptionReasonAudioDeviceInUseByAnotherClient -> InterruptionReason.AUDIO_IN_USE
    AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableDueToSystemPressure -> InterruptionReason.SYSTEM_PRESSURE
    else -> InterruptionReason.OTHER
}

/**
 * What a session runtime error does to the engine: a media-services reset interrupts the session,
 * which the engine restarts once; any other error fails it.
 */
@OptIn(CameraKEngineApi::class)
internal fun sessionErrorState(error: NSError): EngineState = if (error.isAVFoundation(AVErrorMediaServicesWereReset)) {
    EngineState.Interrupted(InterruptionReason.MEDIA_SERVICES_RESET)
} else {
    EngineState.Failed(cameraKErrorFor(error))
}

/**
 * The CameraKError for an NSError outside a recording. The NSError travels as the cause, so its
 * domain, code and description survive; an error without a row is Hardware with its code.
 */
internal fun cameraKErrorFor(error: NSError): CameraKError {
    val cause = NSErrorException(error)
    if (error.domain != AVFoundationErrorDomain) return CameraKError.Hardware(error.code.toInt(), cause)
    return when (error.code) {
        AVErrorDeviceAlreadyUsedByAnotherSession -> CameraKError.CameraInUse(cause)
        AVErrorDeviceNotConnected, AVErrorDeviceWasDisconnected -> CameraKError.Disconnected(cause)
        AVErrorApplicationIsNotAuthorizedToUseDevice ->
            CameraKError.Permission(CameraKPermission.CAMERA, PermissionStatus.PermanentlyDenied, cause)
        AVErrorSessionNotRunning -> CameraKError.NotReady
        else -> CameraKError.Hardware(error.code.toInt(), cause)
    }
}

/** Whether this is AVFoundation's error [code]. */
internal fun NSError.isAVFoundation(code: Long): Boolean = domain == AVFoundationErrorDomain && this.code == code
