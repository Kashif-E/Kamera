package com.kashif.mediasaverplugin

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.uri
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCaptures
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.assertIs

/** A saver over MediaStore for [config] whose permission reads Granted, for the tests that are not about the gate. */
internal fun androidSaver(
    context: Context,
    config: MediaSaverConfig = MediaSaverConfig(),
    io: CoroutineDispatcher = Dispatchers.IO,
    permission: GalleryPermissionRead = GalleryPermissionRead { PermissionStatus.Granted },
): MediaSaverPlugin = MediaSaverPlugin(config, AndroidGalleryStore(context, config, io), permission)

/** A JPEG capture whose app-owned file holds [bytes], as CameraK's own captures are before a saver copies them. */
internal fun captured(bytes: ByteArray): CapturedImage {
    val context: Context = ApplicationProvider.getApplicationContext()
    val folder = File(context.filesDir, "captures").apply { mkdirs() }
    val file = File.createTempFile("IMG_", ".jpg", folder).apply { writeBytes(bytes) }
    return FakeCaptures.image(path = file.path)
}

/** The gallery row a save returned. */
internal fun savedUri(result: CameraKResult<SavedImage>): Uri =
    assertIs<CameraKResult.Success<SavedImage>>(result, "save result: $result").value.asset.uri
