package com.kashif.imagesaverplugin

import android.content.Context
import com.kashif.cameraK.permissions.PermissionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** A saver over MediaStore for [config] whose permission reads Granted, for the tests that are not about the gate. */
internal fun androidSaver(
    context: Context,
    config: MediaSaverConfig = MediaSaverConfig(),
    io: CoroutineDispatcher = Dispatchers.IO,
    permission: GalleryPermissionRead = GalleryPermissionRead { PermissionStatus.Granted },
): ImageSaverPlugin = ImageSaverPlugin(config, AndroidGalleryStore(context, config, io), permission)
