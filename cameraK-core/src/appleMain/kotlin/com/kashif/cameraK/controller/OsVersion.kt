package com.kashif.cameraK.controller

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo

/** Whether the running OS is at least iOS [major]; APIs newer than the deployment target are gated on it. */
@OptIn(ExperimentalForeignApi::class)
internal fun isOsAtLeast(major: Int): Boolean = NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
    cValue<NSOperatingSystemVersion> {
        majorVersion = major.toLong()
        minorVersion = 0
        patchVersion = 0
    },
)
