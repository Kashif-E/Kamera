package org.company.app.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** How a [Sheet] looks: its colour, padding, the gap between its children, and whether it has an edge line. */
@Immutable
internal data class SheetStyle(
    val container: Color,
    val contentPadding: PaddingValues,
    val spacing: Dp,
    val edge: Boolean,
)

internal object SheetDefaults {
    /** The white sheet that shows a scan's or an analysis's result. */
    @Composable
    fun result(): SheetStyle {
        val spacing = SampleTheme.spacing
        return SheetStyle(
            container = SampleTheme.colors.surface,
            contentPadding = PaddingValues(
                start = spacing.xl,
                top = spacing.xl,
                end = spacing.xl,
                bottom = spacing.controlsEdge,
            ),
            spacing = spacing.md,
            edge = true,
        )
    }

    /** The off-white panel over a dimmed camera, such as the settings. */
    @Composable
    fun panel(): SheetStyle {
        val spacing = SampleTheme.spacing
        return SheetStyle(
            container = SampleTheme.colors.background,
            contentPadding = PaddingValues(
                start = spacing.xl,
                top = spacing.lg,
                end = spacing.xl,
                bottom = spacing.xxxl,
            ),
            spacing = spacing.lg,
            edge = false,
        )
    }
}

/** A bottom sheet: rounded top corners, a handle, and [content] in a column. */
@Composable
internal fun Sheet(
    modifier: Modifier = Modifier,
    style: SheetStyle = SheetDefaults.result(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = SampleTheme.shapes.sheet
    // The edge colour shows only above the sheet: a hairline along its top and round its corners.
    val edged = if (style.edge) {
        Modifier.clip(shape).background(SampleTheme.colors.sheetEdge).padding(top = SampleTheme.sizes.hairline)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .then(edged)
            .clip(shape)
            .background(style.container)
            .padding(style.contentPadding),
        verticalArrangement = Arrangement.spacedBy(style.spacing),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(SampleTheme.sizes.handleWidth, SampleTheme.sizes.handleHeight)
                .clip(SampleTheme.shapes.handle)
                .background(SampleTheme.colors.outline),
        )
        content()
    }
}

/** How a [SheetHeader] sets its title. */
internal enum class SheetHeaderEmphasis {
    /** A small indigo label with its [SheetMeta] beside it, as "QR CODE · just now". */
    Eyebrow,

    /** A sheet heading with its trailing part at the far end, as "Text Detected … 4 lines". */
    Title,

    /** A panel's heading, as the settings' "Settings" with its close button. */
    Heading,
}

/** A sheet's first line: [title], and [trailing] beside it (a [SheetMeta], a close button) when given. */
@Composable
internal fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    emphasis: SheetHeaderEmphasis = SheetHeaderEmphasis.Title,
    trailing: (@Composable () -> Unit)? = null,
) {
    val eyebrow = emphasis == SheetHeaderEmphasis.Eyebrow
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (eyebrow) Arrangement.spacedBy(SampleTheme.spacing.sm) else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (emphasis) {
            SheetHeaderEmphasis.Eyebrow -> Eyebrow(text = title, color = SampleTheme.colors.primary)
            SheetHeaderEmphasis.Title ->
                Text(text = title, style = SampleTheme.typography.sheetTitle, color = SampleTheme.colors.ink)
            SheetHeaderEmphasis.Heading ->
                Text(text = title, style = SampleTheme.typography.title, color = SampleTheme.colors.ink)
        }
        trailing?.invoke()
    }
}

/** The quiet detail beside a sheet's title, as "4 lines". */
@Composable
internal fun SheetMeta(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = SampleTheme.typography.caption,
        color = SampleTheme.colors.inkSecondary,
    )
}
