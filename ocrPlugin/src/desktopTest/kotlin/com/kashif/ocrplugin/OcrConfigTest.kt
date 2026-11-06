package com.kashif.ocrplugin

import com.kashif.cameraK.frames.FrameRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * OcrConfig will gain fields (languages, confidence), so it is not a data class: adding a field must
 * not change componentN or copy signatures callers compiled against. It keeps value equality,
 * because rememberOcrPlugin keys on it.
 */
class OcrConfigTest {
    @Test
    fun ocrConfig_hasNoComponentOrCopyFunctions() {
        val generated = OcrConfig::class.java.methods.map { it.name }.filter {
            it.startsWith("component") ||
                it == "copy"
        }

        assertEquals(emptyList(), generated.sorted(), "data-class functions on OcrConfig")
    }

    @Test
    fun configsWithTheSameValues_areEqual() {
        assertEquals(OcrConfig(), OcrConfig())
        assertEquals(OcrConfig { tessdataPath = "/models" }, OcrConfig { tessdataPath = "/models" })
        assertEquals(
            OcrConfig {
                tessdataPath = "/models"
            }.hashCode(),
            OcrConfig { tessdataPath = "/models" }.hashCode(),
        )
        assertNotEquals(OcrConfig { tessdataPath = "/models" }, OcrConfig { tessdataPath = "/other" })
        assertNotEquals(OcrConfig { tessdataPath = "/models" }, OcrConfig())
        // FrameRect compares by identity; two configs with the same region's edges are still equal.
        assertEquals(OcrConfig { region = FrameRect(1, 2, 3, 4) }, OcrConfig { region = FrameRect(1, 2, 3, 4) })
        assertEquals(
            OcrConfig { region = FrameRect(1, 2, 3, 4) }.hashCode(),
            OcrConfig { region = FrameRect(1, 2, 3, 4) }.hashCode(),
        )
        assertNotEquals(OcrConfig { region = FrameRect(1, 2, 3, 4) }, OcrConfig { region = FrameRect(1, 2, 3, 5) })
        assertNotEquals(OcrConfig { level = OcrLevel.ACCURATE }, OcrConfig())
        assertNotEquals(OcrConfig { maxFps = 5f }, OcrConfig())
    }

    @Test
    fun toString_namesTheValues() {
        // The path is a user's file system, so toString says only whether it is set.
        assertEquals(
            "OcrConfig(maxFps=2.0, region=FrameRect(1, 2, 3, 4), level=FAST, tessdataPath set=true)",
            OcrConfig {
                tessdataPath = "/models"
                region = FrameRect(1, 2, 3, 4)
            }.toString(),
        )
    }
}
