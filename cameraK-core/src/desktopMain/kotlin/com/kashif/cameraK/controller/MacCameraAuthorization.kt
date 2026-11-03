package com.kashif.cameraK.controller

import com.kashif.cameraK.permissions.PermissionStatus

/** The camera's authorization status as the OS reports it. */
internal fun interface CameraAuthorization {
    fun cameraStatus(): PermissionStatus
}

/** The Objective-C runtime calls the macOS authorization read needs. Pointers are raw addresses; 0 is nil. */
internal interface ObjCRuntime {
    fun getClass(name: String): Long

    fun selector(name: String): Long

    /** `objc_msgSend(receiver, selector, argument)` for a method that returns an NSInteger. */
    fun sendLong(receiver: Long, selector: Long, argument: Long): Long

    /** The value of the pointer constant [symbol] exported by [library]. */
    fun pointerConstant(library: String, symbol: String): Long
}

/** macOS camera authorization, read through the Objective-C runtime over JNA (Decision 15). */
internal class MacCameraAuthorization(private val runtime: () -> ObjCRuntime) : CameraAuthorization {
    override fun cameraStatus(): PermissionStatus = TODO("D1'")
}
