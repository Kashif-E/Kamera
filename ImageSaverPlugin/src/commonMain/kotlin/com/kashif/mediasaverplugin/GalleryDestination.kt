package com.kashif.mediasaverplugin

/**
 * Closed: where the saver publishes. A new destination waits for a major release.
 *
 * - Android: [PICTURES] is `MediaStore.Images` under `Pictures/<folder>` for stills and
 *   `MediaStore.Video` under `Movies/<folder>` for videos; [DCIM] is `DCIM/<folder>` in both;
 *   [DOCUMENTS] is `MediaStore.Files` under `Documents/<folder>`.
 * - iOS: every destination is the Photos library, which has no folders.
 * - Desktop: [PICTURES] and [DCIM] are `~/Pictures/<folder>`, [DOCUMENTS] is `~/Documents/<folder>`.
 *
 * `<folder>` is [MediaSaverConfig.folderName], or `CameraK` when it is null.
 */
public enum class GalleryDestination { PICTURES, DCIM, DOCUMENTS }
