package com.kashif.cameraK.utils

/**
 * Marks the engine SPI and the constructors of library-produced value types. They are public so
 * camerak-testing and alternative backends can build fakes; they carry no ABI promise and can change
 * in any release.
 */
@RequiresOptIn(
    message = "Engine SPI for fakes and alternative backends. It may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
// RUNTIME, as InternalCameraKApi is, so a test can read it with reflection.
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class CameraKEngineApi
