package com.kashif.imagesaverplugin

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class JVMImageSaverPluginTest {
    private val home: File = Files.createTempDirectory("camerak-saver-base").toFile()

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    @Test
    fun readBytes_readsTheFile() = runTest {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 43)
        val plugin = desktopSaver(home)
        val saved = assertIs<CameraKResult.Success<SavedImage>>(plugin.save(capturedImage(bytes), "camerak-read")).value

        assertContentEquals(bytes, assertIs<CameraKResult.Success<ByteArray>>(plugin.readBytes(saved)).value)
    }

    @Test
    fun readBytes_missingFile_failsWithoutThePath() = runTest {
        val plugin = desktopSaver(home)
        val saved = assertIs<CameraKResult.Success<SavedImage>>(
            plugin.save(capturedImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 44)), "camerak-missing"),
        ).value
        home.walkTopDown().filter { it.isFile }.forEach { it.delete() }

        val error = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(plugin.readBytes(saved)).error)
        val cause = assertNotNull(error.cause, "the platform's exception is the cause")
        assertFalse(home.name in cause.message.orEmpty(), "the failure's message contains the path: ${cause.message}")
    }
}
