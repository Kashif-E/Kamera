@file:OptIn(CameraKEngineApi::class)

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
import platform.AVFoundation.AVErrorUnknown
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSPOSIXErrorDomain
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Design §4.1, iOS, outside a recording: every AVFoundation interruption reason and session error has
 * one row, and an error keeps AVFoundation's NSError (its domain and code) as its cause.
 */
class AVErrorMapperTest {
    @Test
    fun mapsEveryRowAndKeepsDomainAndCode() {
        val reasons = listOf(
            AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground to
                InterruptionReason.APP_IN_BACKGROUND,
            AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableWithMultipleForegroundApps to
                InterruptionReason.MULTITASKING,
            AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient to
                InterruptionReason.IN_USE_BY_ANOTHER_CLIENT,
            AVCaptureSessionInterruptionReasonAudioDeviceInUseByAnotherClient to InterruptionReason.AUDIO_IN_USE,
            AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableDueToSystemPressure to
                InterruptionReason.SYSTEM_PRESSURE,
            99L to InterruptionReason.OTHER,
        )
        for ((code, reason) in reasons) assertEquals(reason, interruptionReasonFor(code), "interruption reason $code")
        assertEquals(InterruptionReason.OTHER, interruptionReasonFor(null), "an interruption without a reason")

        assertEquals(
            EngineState.Interrupted(InterruptionReason.MEDIA_SERVICES_RESET),
            sessionErrorState(av(AVErrorMediaServicesWereReset)),
            "media services reset",
        )

        val errors: List<Pair<NSError, KClass<out CameraKError>>> = listOf(
            av(AVErrorDeviceAlreadyUsedByAnotherSession) to CameraKError.CameraInUse::class,
            av(AVErrorDeviceNotConnected) to CameraKError.Disconnected::class,
            av(AVErrorDeviceWasDisconnected) to CameraKError.Disconnected::class,
            av(AVErrorApplicationIsNotAuthorizedToUseDevice) to CameraKError.Permission::class,
            av(AVErrorUnknown) to CameraKError.Hardware::class,
            NSError.errorWithDomain(NSPOSIXErrorDomain!!, 5, null) to CameraKError.Hardware::class,
        )
        for ((error, type) in errors) {
            val mapped = cameraKErrorFor(error)
            assertEquals(type, mapped::class, "${error.domain} ${error.code}")
            val cause = assertIs<NSErrorException>(mapped.cause, "${error.domain} ${error.code} lost its NSError")
            assertSame(error, cause.error, "${error.domain} ${error.code}: the cause is another NSError")
            val failed = assertIs<EngineState.Failed>(sessionErrorState(error), "${error.domain} ${error.code}")
            assertEquals(type, failed.error::class, "${error.domain} ${error.code} as a session error")
        }

        val denied =
            assertIs<CameraKError.Permission>(cameraKErrorFor(av(AVErrorApplicationIsNotAuthorizedToUseDevice)))
        assertEquals(CameraKPermission.CAMERA, denied.permission)
        assertEquals(PermissionStatus.PermanentlyDenied, denied.status)

        assertEquals(
            AVErrorUnknown.toInt(),
            assertIs<CameraKError.Hardware>(cameraKErrorFor(av(AVErrorUnknown))).platformCode,
            "Hardware keeps AVFoundation's code",
        )
        assertEquals(5, assertIs<CameraKError.Hardware>(cameraKErrorFor(errors[5].first)).platformCode)

        assertSame(CameraKError.NotReady, cameraKErrorFor(av(AVErrorSessionNotRunning)), "session not running")
        assertNotNull(AVFoundationErrorDomain)
    }

    private fun av(code: Long): NSError = FakeSessionOps.avError(code)
}
