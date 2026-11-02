package com.kashif.cameraK.platform

/**
 * What a platform needs to create a camera and reach its storage: the application `Context` on
 * Android, nothing on iOS, and on desktop an optional grabber factory. CameraK's own type, so no
 * third-party type appears in a public signature.
 */
public expect class CameraKPlatformContext
