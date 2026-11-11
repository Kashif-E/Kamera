@file:OptIn(ExperimentalCoroutinesApi::class)

package org.company.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The camera screen on a phone held upright and turned on its side. Turned, the screen is as short as
 * it was narrow, and the photo controls stacked under the preview left it a strip: on a Pixel 7, about
 * 90 pixels of a 1080-pixel-tall screen. Either way the preview keeps at least half the height, and
 * every control is on the screen and off the preview.
 */
class CameraScreenLayoutTest {
    private val controls = listOf("Take photo", "Flip Camera", "Settings", "PHOTO", "VIDEO", "ANALYZE")

    @Test
    fun uprightThePreviewKeepsHalfTheHeightAndTheControlsStayOffIt() = runTest {
        checkLayout(this, width = SampleScene.PHONE_WIDTH, height = SampleScene.PHONE_HEIGHT)
    }

    @Test
    fun turnedOnItsSideThePreviewKeepsHalfTheHeightAndTheControlsStayOffIt() = runTest {
        checkLayout(this, width = SampleScene.PHONE_HEIGHT, height = SampleScene.PHONE_WIDTH)
    }

    private fun checkLayout(scope: TestScope, width: Int, height: Int) {
        val holder = scope.fakeCameraKStateHolder(fake = FakeCameraEngine())
        holder.start()
        scope.runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)

        SampleScene(scope, width = width, height = height) {
            CameraScreen(
                holder = holder,
                qrScanner = rememberQrScannerFeature(),
                ocr = rememberOcrFeature(),
                analyzer = rememberAnalyzerFeature(),
                snackbarHostState = remember { SnackbarHostState() },
                qrCodes = emptyList(),
                recognizedText = null,
                settings = CameraSettings(),
                onSettingsChange = {},
                options = SampleOptions(
                    saveToGallery = false,
                    onSaveToGalleryChange = {},
                    ocrAccuracy = OcrAccuracy.Fast,
                    onOcrAccuracyChange = {},
                ),
            )
        }.use { scene ->
            val previews = scene.nodes().filter { it.testTag() == PREVIEW_TAG }
            assertEquals(1, previews.size, "nodes tagged $PREVIEW_TAG")
            val preview = previews.single().boundsInRoot
            assertTrue(
                preview.height >= height / 2f,
                "the preview is ${preview.height} of a $width by $height screen's height",
            )
            for (label in controls) {
                val control = scene.node(label).boundsInRoot
                assertTrue(
                    control.left >= 0f && control.top >= 0f && control.right <= width && control.bottom <= height,
                    "\"$label\" at $control is off the $width by $height screen",
                )
                assertTrue(!control.overlaps(preview), "\"$label\" at $control is over the preview at $preview")
            }
        }
        holder.close()
    }

    private fun SemanticsNode.testTag(): String? = config.getOrNull(SemanticsProperties.TestTag)
}
