@file:OptIn(CameraKPlatformApi::class)

package com.kashif.mediasaverplugin

import com.kashif.cameraK.capture.path
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCaptures
import com.kashif.cameraK.utils.CameraKPlatformApi
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop has no gallery: the destination is a folder under the user's home, chosen by the
 * destination alone, and the saved asset's path is the copy there. The capture is copied, not moved.
 */
class GalleryAssetPathTest {
    private val home: File = Files.createTempDirectory("camerak-gallery-home").toFile()
    private val capture = File(home, "app/IMG_0001.jpg").apply {
        parentFile.mkdirs()
        writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))
    }

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private suspend fun savedUnder(config: MediaSaverConfig): File {
        val sut = MediaSaverPlugin(config, DesktopGalleryStore(config, home), { error("never read") })
        val saved = assertIs<CameraKResult.Success<SavedImage>>(
            sut.save(FakeCaptures.image(path = capture.path), "shot"),
        ).value
        val file = File(saved.asset.path)
        assertContentEquals(capture.readBytes(), file.readBytes(), "the saved file is not the capture")
        assertTrue(capture.isFile, "the capture was moved, not copied")
        assertEquals("shot.jpg", saved.displayName)
        return file.canonicalFile.parentFile
    }

    @Test
    fun defaultFolderIsPicturesCameraK() = runTest {
        assertEquals(File(home, "Pictures/CameraK").canonicalFile, savedUnder(MediaSaverConfig()))
    }

    @Test
    fun dcimIsPicturesCameraK() = runTest {
        assertEquals(
            File(home, "Pictures/CameraK").canonicalFile,
            savedUnder(MediaSaverConfig { destination = GalleryDestination.DCIM }),
        )
    }

    @Test
    fun documentsIsDocumentsCameraK() = runTest {
        assertEquals(
            File(home, "Documents/CameraK").canonicalFile,
            savedUnder(MediaSaverConfig { destination = GalleryDestination.DOCUMENTS }),
        )
    }

    @Test
    fun folderNameReplacesCameraK() = runTest {
        assertEquals(
            File(home, "Documents/Receipts").canonicalFile,
            savedUnder(
                MediaSaverConfig {
                    destination = GalleryDestination.DOCUMENTS
                    folderName = "Receipts"
                },
            ),
        )
    }

    @Test
    fun galleryPermissionIsNullAndNeverRead() = runTest {
        val config = MediaSaverConfig()
        var reads = 0
        val sut = MediaSaverPlugin(config, DesktopGalleryStore(config, home), {
            reads++
            error("the desktop saver read a permission")
        })

        val result = sut.save(FakeCaptures.image(path = capture.path))

        assertNull(sut.galleryPermission)
        assertIs<CameraKResult.Success<SavedImage>>(result)
        assertEquals(0, reads)
    }

    @Test
    fun aVideoIsCopiedWithItsOwnExtension() = runTest {
        val video = File(home, "app/VID_0001.mp4").apply { writeBytes(byteArrayOf(0, 0, 0, 24)) }
        val config = MediaSaverConfig()
        val sut = MediaSaverPlugin(config, DesktopGalleryStore(config, home), { error("never read") })

        val saved = assertIs<CameraKResult.Success<SavedVideo>>(
            sut.save(FakeCaptures.video(path = video.path), "clip"),
        ).value

        assertEquals(File(home, "Pictures/CameraK/clip.mp4").canonicalFile, File(saved.asset.path).canonicalFile)
        assertContentEquals(video.readBytes(), File(saved.asset.path).readBytes())
    }

    @Test
    fun aSavedImageReadsBack() = runTest {
        val config = MediaSaverConfig()
        val sut = MediaSaverPlugin(config, DesktopGalleryStore(config, home), { error("never read") })
        val saved = assertIs<CameraKResult.Success<SavedImage>>(sut.save(FakeCaptures.image(path = capture.path))).value

        assertContentEquals(capture.readBytes(), assertIs<CameraKResult.Success<ByteArray>>(sut.readBytes(saved)).value)
    }
}
