package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The scanner's tunables live in its config (magic-numbers), with the 1.x behaviour as defaults. */
class QRScannerConfigTest {
    @Test
    fun defaultsAreQrCodesOnlyTheWholeFrameAOneSecondWindowAndFiveFramesASecond() {
        val config = QRScannerConfig()

        assertEquals(setOf(BarcodeFormat.QR_CODE), config.formats)
        assertNull(config.region)
        assertEquals(1.seconds, config.dedupeWindow)
        assertEquals(5f, config.maxFps)
    }

    @Test
    fun comparesByValueAndCopies() {
        val config = QRScannerConfig {
            formats = setOf(BarcodeFormat.EAN_13)
            region = FrameRect(0, 0, 10, 10)
        }

        assertEquals(
            config,
            QRScannerConfig {
                formats = setOf(BarcodeFormat.EAN_13)
                region = FrameRect(0, 0, 10, 10)
            },
        )
        assertEquals(
            config.hashCode(),
            QRScannerConfig {
                formats = setOf(BarcodeFormat.EAN_13)
                region =
                    FrameRect(0, 0, 10, 10)
            }.hashCode(),
        )
        val copy = config.copy { dedupeWindow = 300.milliseconds }
        assertEquals(300.milliseconds, copy.dedupeWindow)
        assertEquals(setOf(BarcodeFormat.EAN_13), copy.formats)
        assertNotEquals(config, copy)
    }

    @Test
    fun anInvalidFieldIsRejectedWhenTheConfigIsBuilt() {
        assertFailsWith<IllegalArgumentException>("no formats") { QRScannerConfig { formats = emptySet() } }
        assertFailsWith<IllegalArgumentException>("maxFps 0") { QRScannerConfig { maxFps = 0f } }
        assertFailsWith<IllegalArgumentException>("maxFps NaN") { QRScannerConfig { maxFps = Float.NaN } }
        assertFailsWith<IllegalArgumentException>("maxFps infinite") {
            QRScannerConfig {
                maxFps =
                    Float.POSITIVE_INFINITY
            }
        }
        assertFailsWith<IllegalArgumentException>("a negative window") {
            QRScannerConfig { dedupeWindow = (-1).milliseconds }
        }
        assertFailsWith<IllegalArgumentException>("an empty region") {
            QRScannerConfig { region = FrameRect(4, 4, 4, 8) }
        }
        assertFailsWith<IllegalArgumentException>("a region left of the frame") {
            QRScannerConfig { region = FrameRect(-1, 0, 4, 4) }
        }
        QRScannerConfig { dedupeWindow = Duration.ZERO }
    }

    @Test
    fun toStringNamesEveryField() {
        assertEquals(
            "QRScannerConfig(formats=[QR_CODE], region=[1, 2, 3, 4], dedupeWindow=1s, maxFps=5.0)",
            QRScannerConfig { region = FrameRect(1, 2, 3, 4) }.toString(),
        )
    }
}
