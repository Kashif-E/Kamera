package com.kashif.cameraK.utils

/**
 * Marks declarations that expose a platform SDK type (a CameraX `ImageProxy`, an Android `Uri`, a
 * Photos local identifier, a JavaCV `FrameGrabber`). Code that uses them is tied to one platform.
 */
@RequiresOptIn(
    message = "Exposes a platform SDK type; ties the caller to one platform.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class CameraKPlatformApi
