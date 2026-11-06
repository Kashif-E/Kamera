@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.controller.CameraGrabber
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.reflect.jvm.kotlinFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 1.x named-argument factories stay only as ERROR-level hints whose ReplaceWith writes the DSL
 * call (Decision 1, design §6.1): a 1.x call with any subset of named arguments lands on the hint and
 * gets the rewrite, and none of them compiles.
 *
 * Every hint core keeps in 2.0 (design §10.4) follows the rule for its kind: a function is ERROR
 * level with a ReplaceWith, and a call that reaches it anyway, from code compiled against 1.x,
 * throws UnsupportedOperationException; a type is ERROR level with a ReplaceWith naming its 2.0
 * result and has nothing to throw.
 */
class DeprecationHintTest {
    // The deprecated top-level functions named [name] in the file facade [facade]. A function that takes
    // an inline class, such as a Duration, has its JVM name mangled to "name-<hash>".
    private fun hints(facade: String, name: String): List<Pair<Method, Deprecated>> = Class.forName(facade).methods
        .filter { it.name == name || it.name.startsWith("$name-") }
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

    @Test
    fun everyHintIsErrorLevelWithReplaceWithAndThrows() = runTest {
        val wrong = mutableListOf<String>()
        fun checkAnnotation(name: String, deprecation: Deprecated?) {
            when {
                deprecation == null -> wrong += "$name is not deprecated"
                deprecation.level != DeprecationLevel.ERROR -> wrong += "$name is ${deprecation.level}, not ERROR"
                deprecation.replaceWith.expression.isBlank() -> wrong += "$name has no ReplaceWith"
            }
        }
        fun checkThrows(name: String, call: () -> Unit) {
            val thrown = try {
                call()
                null
            } catch (e: Throwable) {
                if (e is InvocationTargetException) e.cause else e
            }
            if (thrown !is UnsupportedOperationException) {
                wrong +=
                    "$name threw $thrown, not UnsupportedOperationException"
            }
        }

        // The two named-argument factories, called with their defaults.
        listOf(
            "com.kashif.cameraK.state.CameraConfigurationKt" to "CameraConfiguration",
            "com.kashif.cameraK.video.VideoConfigurationKt" to "VideoConfiguration",
        ).forEach { (facade, name) ->
            hints(facade, name).forEach { (method, deprecation) ->
                checkAnnotation(name, deprecation)
                checkThrows(name) { checkNotNull(method.kotlinFunction).callBy(emptyMap()) }
            }
        }

        val holder =
            CameraKStateHolder(CameraConfiguration(), { _, _ ->
                FakeCameraEngine()
            }, emptyList(), cameraKTestEnvironment())
        HOLDER_HINTS.forEach { name ->
            val method = CameraKStateHolder::class.java.methods.singleOrNull {
                it.name == name && it.parameterCount == 0
            }
            if (method == null) {
                wrong += "CameraKStateHolder.$name is missing"
            } else {
                checkAnnotation("CameraKStateHolder.$name", method.getAnnotation(Deprecated::class.java))
                checkThrows("CameraKStateHolder.$name") { method.invoke(holder) }
            }
        }

        val anyGrabber = CameraGrabber()
        val grabFrame = CameraGrabber::class.java.methods.single { it.name == "grabCurrentFrame" }
        checkAnnotation("CameraGrabber.grabCurrentFrame", grabFrame.getAnnotation(Deprecated::class.java))
        checkThrows("CameraGrabber.grabCurrentFrame") { grabFrame.invoke(anyGrabber) }

        mapOf(
            "com.kashif.cameraK.result.ImageCaptureResult" to "CameraKResult<CapturedImage>",
            "com.kashif.cameraK.video.VideoCaptureResult" to "CameraKResult<RecordedVideo>",
        ).forEach { (type, replacement) ->
            val deprecation = Class.forName(type).getAnnotation(Deprecated::class.java)
            checkAnnotation(type, deprecation)
            if (deprecation != null && replacement !in deprecation.replaceWith.expression) {
                wrong += "$type's ReplaceWith '${deprecation.replaceWith.expression}' does not name $replacement"
            }
        }

        holder.close()
        assertTrue(wrong.isEmpty(), "hints that break their rule:\n${wrong.joinToString("\n")}")
    }

    private companion object {
        val HOLDER_HINTS = listOf(
            "captureImage",
            "toggleFlashMode",
            "toggleTorchMode",
            "toggleCameraLens",
            "shutdown",
            "getCameraCapabilities",
        )
    }
}
