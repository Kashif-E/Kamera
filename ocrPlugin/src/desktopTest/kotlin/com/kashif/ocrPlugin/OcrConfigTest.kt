package com.kashif.ocrPlugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * OcrConfig will gain fields (languages, confidence, accuracy), so it is not a data class: adding a
 * field must not change componentN or copy signatures callers compiled against. It keeps value
 * equality, because rememberOcrPlugin keys on it.
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
        assertEquals(OcrConfig("/models"), OcrConfig("/models"))
        assertEquals(OcrConfig("/models").hashCode(), OcrConfig("/models").hashCode())
        assertNotEquals(OcrConfig("/models"), OcrConfig("/other"))
        assertNotEquals(OcrConfig("/models"), OcrConfig())
    }

    @Test
    fun toString_namesTheValues() {
        assertEquals("OcrConfig(tessdataPath=/models, maxFps=2)", OcrConfig("/models").toString())
    }
}
