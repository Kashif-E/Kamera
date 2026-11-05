package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.state.CameraConfiguration
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The plugin with its real decoder: ZXing reads the luma `copyLuma` writes, from either session format. */
class QRScannerPluginZxingTest {
    @Test
    fun rgbaSessionDecodesThroughCopyLuma() = runTest {
        val plugin = QRScannerPlugin()
        val results = collectResults(plugin)
        val s = qrSession(plugin, CameraConfiguration { analysisFormat = FrameFormat.RGBA_8888 })
        startReady(s)

        s.fake.fakeFrames.emit(codeScene("HELLO", 320, 240).rgbaFrame())

        assertEquals(listOf("HELLO"), results.map { it.rawValue })
    }

    @Test
    fun yuvSessionDecodesThroughCopyLuma() = runTest {
        val plugin = QRScannerPlugin()
        val results = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(codeScene("HELLO", 320, 240).yuvFrame())

        assertEquals(listOf("HELLO"), results.map { it.rawValue })
    }
}
