@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)

package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.company.app.designsystem.SampleTheme
import org.jetbrains.skia.Image
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * [content] in the Sample's theme on an [ImageComposeScene] of [width] by [height] dp that recomposes
 * on the test's scheduler: the screens' and atoms' tests read its semantics and tap it.
 */
internal class SampleScene(
    private val scope: TestScope,
    width: Int = PHONE_WIDTH,
    height: Int = PHONE_HEIGHT,
    density: Float = 1f,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val scene = ImageComposeScene(
        width = (width * density).toInt(),
        height = (height * density).toInt(),
        density = Density(density),
        coroutineContext = StandardTestDispatcher(scope.testScheduler),
    ) { SampleTheme(content) }
    private var frameTime = Duration.ZERO

    init {
        settle()
    }

    /** Renders [frames] frames 16 ms apart, running what each schedules; returns the last image. */
    fun settle(frames: Int = SETTLE_FRAMES): Image {
        var image: Image? = null
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            image = scene.render(frameTime.inWholeNanoseconds)
            scope.runCurrent()
            scope.advanceTimeBy(FRAME)
            frameTime += FRAME
        }
        return checkNotNull(image)
    }

    /** The merged semantics nodes, a tap target and its labels in one, in tree order. */
    fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.rootSemanticsNode.withDescendants() }

    /** The node whose text or content description is [label]. */
    fun node(label: String): SemanticsNode {
        val matches = nodes().filter { label in it.labels() }
        return matches.singleOrNull() ?: fail("expected one node reading \"$label\", found ${matches.size}")
    }

    /** Whether any node reads [label]. */
    fun shows(label: String): Boolean = nodes().any { label in it.labels() }

    /** Taps the middle of [node], then settles. */
    fun tap(node: SemanticsNode) {
        val center = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, center)
        scene.sendPointerEvent(PointerEventType.Release, center)
        settle()
    }

    /** Taps [position], in the scene's pixels, then settles. */
    fun tapAt(position: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, position)
        scene.sendPointerEvent(PointerEventType.Release, position)
        settle()
    }

    /** Presses the middle of [node], moves the pointer by [by] pixels in [steps] moves, releases, then settles. */
    fun drag(node: SemanticsNode, by: Offset, steps: Int = DRAG_STEPS) {
        val start = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, start)
        settle(frames = 1)
        for (step in 1..steps) {
            scene.sendPointerEvent(PointerEventType.Move, start + by * (step.toFloat() / steps))
            settle(frames = 1)
        }
        scene.sendPointerEvent(PointerEventType.Release, start + by)
        settle()
    }

    override fun close() = scene.close()

    private fun SemanticsNode.withDescendants(): List<SemanticsNode> =
        listOf(this) + children.flatMap { it.withDescendants() }

    companion object {
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844
        private const val SETTLE_FRAMES = 5
        private const val DRAG_STEPS = 8
        private val FRAME = 16.milliseconds
    }
}

/** A node's texts and content descriptions. */
internal fun SemanticsNode.labels(): List<String> = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

/** Whether a node is marked selected; null when it has no selected state. */
internal fun SemanticsNode.selected(): Boolean? = config.getOrNull(SemanticsProperties.Selected)
