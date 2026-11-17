package dev.kashifmehmood.camerak.qrscanner

import dev.kashifmehmood.camerak.frames.FrameFormat
import dev.kashifmehmood.camerak.state.CameraConfiguration
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.google.zxing.BarcodeFormat as ZxingFormat

/**
 * The plugin with its real decoder: ZXing reads the luma `copyLuma` writes, from either session format.
 * One read reports a code, so one frame shows the decoding.
 */
class QRScannerPluginZxingTest {
    @Test
    fun rgbaSessionDecodesThroughCopyLuma() = runTest {
        val plugin = QRScannerPlugin(QRScannerConfig { confirmations = 1 })
        val results = collectResults(plugin)
        val s = qrSession(plugin, CameraConfiguration { analysisFormat = FrameFormat.RGBA_8888 })
        startReady(s)

        s.fake.fakeFrames.emit(codeScene("HELLO", 320, 240).rgbaFrame())

        assertEquals(listOf("HELLO"), results.map { it.rawValue })
    }

    @Test
    fun yuvSessionDecodesThroughCopyLuma() = runTest {
        val plugin = QRScannerPlugin(QRScannerConfig { confirmations = 1 })
        val results = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(codeScene("HELLO", 320, 240).yuvFrame())

        assertEquals(listOf("HELLO"), results.map { it.rawValue })
    }

    // #188, through the plugin: a session's frames reach the decoder with the configured formats.
    @Test
    fun aConfiguredDataMatrixIsScannedFromTheSessionsFrames() = runTest {
        val plugin = QRScannerPlugin(
            QRScannerConfig {
                formats = setOf(BarcodeFormat.DATA_MATRIX)
                confirmations = 1
            },
        )
        val results = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(codeScene("CAMERAK-DM-188", 320, 240, format = ZxingFormat.DATA_MATRIX).yuvFrame())

        assertEquals(listOf("CAMERAK-DM-188"), results.map { it.rawValue })
        assertEquals(listOf(BarcodeFormat.DATA_MATRIX), results.map { it.format })
    }
}
