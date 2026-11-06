package com.kashif.mediasaverplugin

import com.kashif.cameraK.capture.GalleryAsset
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.result.CameraKResult

/** A still or a recording. */
internal enum class MediaKind { IMAGE, VIDEO }

/**
 * One platform's gallery: MediaStore on Android, the Photos library on iOS, a folder under the user's
 * home on desktop. The saver reads the permission and builds the saved values; a store only writes
 * and reads. Its failures are values carrying the platform's exception as their cause, and it never
 * deletes the file it copies. A failed add is logged by the saver, once, so a store does not log it.
 */
internal interface GalleryStore {
    /** What adding needs at this API level, or null when nothing; the permission is read only when non-null. */
    val galleryPermission: CameraKPermission?

    /** Whether an add itself raises the platform's prompt, so a NotDetermined status may proceed (iOS). */
    val addPrompts: Boolean get() = false

    /**
     * Adds a copy of the app-owned still at [path] as [name] plus [format]'s extension, or a name of
     * the store's own when [name] is null.
     */
    suspend fun addImage(path: String, format: ImageFormat, name: String?): CameraKResult<GalleryAsset>

    /** Adds the recording at [path] as [name] plus its own extension, or a name of the store's own. */
    suspend fun addVideo(path: String, name: String?): CameraKResult<GalleryAsset>

    /** The bytes of a still this store added. */
    suspend fun read(asset: GalleryAsset): CameraKResult<ByteArray>
}

/** The saver's read of the `GALLERY_ADD` status. Tests pass their own; it is read on every save. */
internal fun interface GalleryPermissionRead {
    fun status(): PermissionStatus
}

/** The platform's store for [config]. */
internal expect fun platformGalleryStore(context: CameraKPlatformContext, config: MediaSaverConfig): GalleryStore

/** Core's `GALLERY_ADD` status, the rule `:cameraK`'s permission state reads too. */
internal expect fun platformGalleryPermissionRead(context: CameraKPlatformContext): GalleryPermissionRead
