@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.previewAspectRatio
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The content rectangle every platform lays the preview's overlay out over: the largest centred box
 * of the content's aspect ratio, with the letterbox or pillarbox bars outside it, or the whole box
 * when there is no aspect ratio to keep.
 */
class ContentRectTest {
    @Test
    fun aWiderBoxPillarboxesTheContent() {
        // A 4:3 frame in a 2:1 box: 133.3 wide, with 33.3 of bar on either side.
        assertRect(Rect(100f / 3f, 0f, 500f / 3f, 100f), contentRect(Size(200f, 100f), 4f / 3f))
    }

    @Test
    fun aTallerBoxLetterboxesTheContent() {
        // A 4:3 frame in a 1:2 box: 75 high, with 62.5 of bar above and below.
        assertRect(Rect(0f, 62.5f, 100f, 137.5f), contentRect(Size(100f, 200f), 4f / 3f))
    }

    @Test
    fun aBoxOfTheContentsShapeIsFilled() {
        assertRect(Rect(0f, 0f, 90f, 160f), contentRect(Size(90f, 160f), 9f / 16f))
    }

    @Test
    fun withoutAnAspectRatioTheContentFillsTheBox() {
        assertRect(Rect(0f, 0f, 200f, 100f), contentRect(Size(200f, 100f), null))
    }

    @Test
    fun everyRatioInEitherOrientationIsTheLargestCentredBoxOfItsPreviewAspect() {
        val boxes = listOf(Size(1080f, 1920f), Size(1920f, 1080f), Size(500f, 500f))
        for (ratio in AspectRatio.entries) {
            for (orientation in listOf(DeviceOrientation.PORTRAIT, DeviceOrientation.LANDSCAPE_LEFT)) {
                val aspect = ratio.previewAspectRatio(orientation)
                for (box in boxes) {
                    val case = "$ratio in $orientation, box $box"
                    val rect = contentRect(box, aspect)
                    assertClose(aspect, rect.width / rect.height, "$case: aspect ratio")
                    assertTrue(rect.left >= -EPSILON && rect.top >= -EPSILON, "$case: $rect starts outside the box")
                    assertTrue(
                        rect.right <= box.width + EPSILON && rect.bottom <= box.height + EPSILON,
                        "$case: $rect ends outside the box",
                    )
                    // Largest: it meets the box's sides on at least one axis.
                    assertTrue(
                        abs(rect.width - box.width) <= EPSILON || abs(rect.height - box.height) <= EPSILON,
                        "$case: $rect is not the largest box of its ratio",
                    )
                    assertClose(rect.left, box.width - rect.right, "$case: horizontal centring")
                    assertClose(rect.top, box.height - rect.bottom, "$case: vertical centring")
                }
            }
        }
    }

    @Test
    fun anEmptyBoxHasAnEmptyContentRectangle() {
        assertRect(Rect.Zero, contentRect(Size.Zero, 4f / 3f))
        assertRect(Rect(0f, 50f, 0f, 50f), contentRect(Size(0f, 100f), 4f / 3f))
    }

    @Test
    fun anAspectRatioThatIsNotAPositiveNumberFillsTheBox() {
        for (aspect in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertRect(Rect(0f, 0f, 200f, 100f), contentRect(Size(200f, 100f), aspect), "aspect $aspect")
        }
    }

    private fun assertRect(expected: Rect, actual: Rect, message: String = "content rectangle") {
        val close = abs(expected.left - actual.left) <= EPSILON &&
            abs(expected.top - actual.top) <= EPSILON &&
            abs(expected.right - actual.right) <= EPSILON &&
            abs(expected.bottom - actual.bottom) <= EPSILON
        assertTrue(close, "$message: expected $expected, was $actual")
    }

    private fun assertClose(expected: Float, actual: Float, message: String) =
        assertEquals(expected, actual, abs(expected) * RELATIVE + EPSILON, message)

    private companion object {
        // Float arithmetic over sides of up to 1920: a few ulps at that magnitude.
        const val EPSILON = 1e-3f

        // Float precision of a quotient of two such sides.
        const val RELATIVE = 1e-5f
    }
}
