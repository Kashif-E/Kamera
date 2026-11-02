package com.kashif.cameraK.utils

/**
 * Marks declarations that are public only so the first-party CameraK plugins can share them. They
 * are not part of the supported API and can change or disappear in any release.
 */
@RequiresOptIn(
    message = "This is CameraK's internal API for its own plugins and can change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class InternalCameraKApi
