package org.company.app.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** The last photo, small and square beside the shutter; a grey tile until there is one. */
@Composable
internal fun PhotoThumb(image: ImageBitmap?, modifier: Modifier = Modifier) {
    val shape = SampleTheme.shapes.thumb
    Box(
        modifier = modifier
            .size(SampleTheme.sizes.controlButton)
            .clip(shape)
            .background(SampleTheme.colors.thumbPlaceholder)
            .border(SampleTheme.sizes.stroke, SampleTheme.colors.outline, shape)
            .semantics { contentDescription = if (image == null) "No photo yet" else "Last photo" },
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
