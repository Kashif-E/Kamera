package org.company.app.designsystem

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A vertical exposure slider showing [index] in [range], each step [step] EV; dragging it, or tapping
 * its track, calls [onChange] with the index under the finger.
 */
@Composable
internal fun ExposureSlider(
    index: Int,
    range: IntRange,
    step: Float,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
}

/** [index] steps of [step] EV as the slider reads it out, to one decimal: "+1.0 EV". */
internal fun exposureLabel(index: Int, step: Float): String = ""
