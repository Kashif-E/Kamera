package com.kashif.cameraK.state

import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 1.x named-argument factories stay only as ERROR-level hints whose ReplaceWith writes the DSL
 * call (Decision 1, design §6.1): a 1.x call with any subset of named arguments lands on the hint and
 * gets the rewrite, and none of them compiles.
 */
class DeprecationHintTest {
    // The deprecated top-level functions named [name] in the file facade [facade].
    private fun hints(facade: String, name: String): List<Pair<Method, Deprecated>> = Class.forName(facade).methods
        .filter { it.name == name }
        .mapNotNull { method -> method.getAnnotation(Deprecated::class.java)?.let { method to it } }

    @Test
    fun namedArgumentFactoryIsErrorLevelWithReplaceWith() {
        val hint = hints("com.kashif.cameraK.state.CameraConfigurationKt", "CameraConfiguration")
        assertEquals(1, hint.size, "named-argument CameraConfiguration hints: ${hint.map { it.first }}")
        val (method, deprecation) = hint.single()

        assertEquals(DeprecationLevel.ERROR, deprecation.level)
        assertTrue(
            deprecation.replaceWith.expression.startsWith("CameraConfiguration {"),
            "the rewrite: ${deprecation.replaceWith.expression}",
        )
        // The fifteen 1.x fields, directory and the Pair resolution among them.
        assertEquals(15, method.parameterCount, "the hint's parameters")
    }

    @Test
    fun namedArgumentVideoFactoryIsErrorLevelWithReplaceWith() {
        val hint = hints("com.kashif.cameraK.video.VideoConfigurationKt", "VideoConfiguration")
        assertEquals(1, hint.size, "named-argument VideoConfiguration hints: ${hint.map { it.first }}")
        val (_, deprecation) = hint.single()

        assertEquals(DeprecationLevel.ERROR, deprecation.level)
        assertTrue(
            deprecation.replaceWith.expression.startsWith("VideoConfiguration {"),
            "the rewrite: ${deprecation.replaceWith.expression}",
        )
    }
}
