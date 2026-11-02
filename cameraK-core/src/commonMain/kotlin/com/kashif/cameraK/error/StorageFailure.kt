package com.kashif.cameraK.error

import kotlin.jvm.JvmInline

/**
 * Open: new values can be added in a minor release, so a `when` over it needs an `else` branch.
 * What went wrong writing a capture or recording, in [CameraKError.Storage].
 */
@JvmInline
public value class StorageFailure private constructor(public val name: String) {
    override fun toString(): String = TODO("S1' stub")

    public companion object {
        public val FULL: StorageFailure = StorageFailure("FULL")
        public val NOT_WRITABLE: StorageFailure = StorageFailure("NOT_WRITABLE")
        public val NOT_FOUND: StorageFailure = StorageFailure("NOT_FOUND")
        public val GALLERY_IMPORT_FAILED: StorageFailure = StorageFailure("GALLERY_IMPORT_FAILED")
    }
}
