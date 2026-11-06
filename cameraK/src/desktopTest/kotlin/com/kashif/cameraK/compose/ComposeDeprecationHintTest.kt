@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kashif.cameraK.state.CameraKState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two 1.x composables 2.0 keeps as hints (design §10.4): ERROR level with a ReplaceWith, read
 * from their file facade's method, and composing one anyway, from code compiled against 1.x, throws
 * UnsupportedOperationException. A reflective call cannot show the throw, because the Compose
 * compiler adds a Composer parameter, so each is composed.
 */
class ComposeDeprecationHintTest {
    @Test
    fun everyHintIsErrorLevelWithReplaceWithAndThrows() = runTest {
        val wrong = mutableListOf<String>()
        HINTS.forEach { (facade, name) ->
            val hints = Class.forName(facade).methods.filter { it.name == name }
                .mapNotNull { it.getAnnotation(Deprecated::class.java) }
            when {
                hints.isEmpty() -> wrong += "$name in $facade is not deprecated"
                hints.any { it.level != DeprecationLevel.ERROR } ->
                    wrong +=
                        "$name is ${hints.map { it.level }}, not ERROR"
                hints.any { it.replaceWith.expression.isBlank() } -> wrong += "$name has no ReplaceWith"
            }
        }

        @Suppress("DEPRECATION", "DEPRECATION_ERROR")
        val compositions: List<Pair<String, @Composable () -> Unit>> = listOf(
            "rememberCameraKState" to { rememberCameraKState() },
            "CameraKScreen(cameraState)" to { CameraKScreen(cameraState = CameraKState.Idle) { } },
        )
        compositions.forEach { (name, content) ->
            val thrown = compose(content)
            if (thrown !is UnsupportedOperationException) {
                wrong += "composing $name threw $thrown, not UnsupportedOperationException"
            }
        }

        assertTrue(wrong.isEmpty(), "hints that break their rule:\n${wrong.joinToString("\n")}")
    }

    // Composes [content] under a host that never starts, so no camera opens; returns what composing threw.
    private fun kotlinx.coroutines.test.TestScope.compose(content: @Composable () -> Unit): Throwable? {
        val host = NeverStartedHost()
        var scene: ImageComposeScene? = null
        return try {
            scene =
                ImageComposeScene(width = 10, height = 10, coroutineContext = StandardTestDispatcher(testScheduler)) {
                    CompositionLocalProvider(LocalLifecycleOwner provides host) { content() }
                }
            scene.render()
            runCurrent()
            null
        } catch (e: Throwable) {
            generateSequence(e) { it.cause }.firstOrNull { it is UnsupportedOperationException } ?: e
        } finally {
            // A scene whose composition threw may throw again while closing; the first throw is the answer.
            runCatching { scene?.close() }.exceptionOrNull()?.let { println("Closing the scene threw: $it") }
        }
    }

    private class NeverStartedHost : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.CREATED
        }
    }

    private companion object {
        val HINTS = listOf(
            "com.kashif.cameraK.compose.RememberCameraKStateKt" to "rememberCameraKState",
            "com.kashif.cameraK.compose.CameraKScreenKt" to "CameraKScreen",
        )
    }
}
