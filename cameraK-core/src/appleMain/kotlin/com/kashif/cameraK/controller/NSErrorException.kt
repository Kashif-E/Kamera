package com.kashif.cameraK.controller

import platform.Foundation.NSError

/**
 * An [NSError] carried as a Throwable, so an error CameraK reports keeps what the platform said:
 * the domain, code and description, and the NSError itself in [error]. Its message is the
 * platform's, which may name files or devices, so it travels as a cause, never as CameraK's own
 * message.
 */
internal class NSErrorException(val error: NSError) :
    Exception("${error.domain} ${error.code}: ${error.localizedDescription}")

/** "domain code" for a message CameraK writes; the description stays in the [NSErrorException] cause. */
internal val NSError.summary: String get() = "$domain $code"
