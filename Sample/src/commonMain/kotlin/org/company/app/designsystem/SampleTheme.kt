package org.company.app.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cameracompose.sample.generated.resources.Res
import cameracompose.sample.generated.resources.inter_variable
import cameracompose.sample.generated.resources.space_grotesk_variable
import org.company.app.theme.SystemAppearance
import org.jetbrains.compose.resources.Font

/** The Sample's colours: one light palette, off-white ground, indigo primary, red for recording. */
@Immutable
internal data class SampleColors(
    val background: Color = Color(0xFFF7F6F2),
    val surface: Color = Color(0xFFFFFFFF),
    val control: Color = Color(0xFFE7E7EB),
    val ink: Color = Color(0xFF1A1B1F),
    val inkSecondary: Color = Color(0xFF5F6168),
    val inkBody: Color = Color(0xFF4A4C52),
    val inkOnPreview: Color = Color(0xFF3F4147),
    val disabled: Color = Color(0xFFA3A5AB),
    val outline: Color = Color(0xFFD4D4DA),
    val sheetEdge: Color = Color(0xFFE2E2E6),
    val primary: Color = Color(0xFF4F46E5),
    val onPrimary: Color = Color(0xFFFFFFFF),
    val recording: Color = Color(0xFFD93025),
    val onRecording: Color = Color(0xFFFFFFFF),
    val previewPlaceholder: Color = Color(0xFFCFD0D6),
    val thumbPlaceholder: Color = Color(0xFFBFC0C7),
    /** Marks drawn straight on the camera image: the focus reticle and the exposure slider. */
    val onCamera: Color = Color(0xFFFFFFFF),
) {
    /** The dimming behind a sheet that covers the camera. */
    val scrim: Color get() = ink.copy(alpha = 0.35f)

    /** Hairlines drawn on the preview, such as the thirds grid. */
    val previewLine: Color get() = ink.copy(alpha = 0.14f)
}

/** The Sample's text styles: Space Grotesk for headings and the timer, Inter for everything else. */
@Immutable
internal data class SampleTypography(
    val display: TextStyle,
    val title: TextStyle,
    val screenTitle: TextStyle,
    val sheetTitle: TextStyle,
    val timer: TextStyle,
    val bodyLarge: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val value: TextStyle,
    val label: TextStyle,
    val buttonLarge: TextStyle,
    val button: TextStyle,
    val option: TextStyle,
    val optionSelected: TextStyle,
    val chip: TextStyle,
    val chipQuiet: TextStyle,
    val mode: TextStyle,
    val eyebrow: TextStyle,
    val caption: TextStyle,
    val note: TextStyle,
) {
    companion object {
        fun of(heading: FontFamily, text: FontFamily) = SampleTypography(
            display = style(heading, FontWeight.SemiBold, size = 30, lineHeight = 34),
            title = style(heading, FontWeight.SemiBold, size = 20, lineHeight = 26),
            screenTitle = style(heading, FontWeight.SemiBold, size = 18, lineHeight = 24),
            sheetTitle = style(heading, FontWeight.SemiBold, size = 17, lineHeight = 22),
            timer = style(heading, FontWeight.SemiBold, size = 15, lineHeight = 20),
            bodyLarge = style(text, FontWeight.Normal, size = 16, lineHeight = 24),
            body = style(text, FontWeight.Normal, size = 15, lineHeight = 22),
            bodySmall = style(text, FontWeight.Normal, size = 14, lineHeight = 20),
            value = style(text, FontWeight.Medium, size = 16, lineHeight = 22),
            label = style(text, FontWeight.Medium, size = 15, lineHeight = 20),
            buttonLarge = style(text, FontWeight.SemiBold, size = 16, lineHeight = 20),
            button = style(text, FontWeight.SemiBold, size = 15, lineHeight = 20),
            option = style(text, FontWeight.Medium, size = 14, lineHeight = 18),
            optionSelected = style(text, FontWeight.SemiBold, size = 14, lineHeight = 18),
            chip = style(text, FontWeight.SemiBold, size = 13, lineHeight = 16),
            chipQuiet = style(text, FontWeight.Medium, size = 13, lineHeight = 16),
            mode = style(text, FontWeight.SemiBold, size = 13, lineHeight = 16, tracking = TRACKING),
            eyebrow = style(text, FontWeight.SemiBold, size = 12, lineHeight = 16, tracking = TRACKING),
            caption = style(text, FontWeight.Normal, size = 12, lineHeight = 16),
            note = style(text, FontWeight.Normal, size = 13, lineHeight = 18),
        )

        // The uppercase labels (modes, eyebrows) are tracked out by 0.06 em.
        private const val TRACKING = 0.06f

        private fun style(family: FontFamily, weight: FontWeight, size: Int, lineHeight: Int, tracking: Float = 0f) =
            TextStyle(
                fontFamily = family,
                fontWeight = weight,
                fontSize = size.sp,
                lineHeight = lineHeight.sp,
                letterSpacing = tracking.em,
            )
    }
}

/** The Sample's corner shapes. */
@Immutable
internal data class SampleShapes(
    val circle: Shape = CircleShape,
    val pill: Shape = CircleShape,
    val option: Shape = RoundedCornerShape(12.dp),
    val thumb: Shape = RoundedCornerShape(12.dp),
    val region: Shape = RoundedCornerShape(12.dp),
    val button: Shape = RoundedCornerShape(14.dp),
    val buttonLarge: Shape = RoundedCornerShape(16.dp),
    val badge: Shape = RoundedCornerShape(20.dp),
    val sheet: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    val stopSquare: Shape = RoundedCornerShape(6.dp),
    val handle: Shape = RoundedCornerShape(2.dp),
)

/** The Sample's spacing scale. */
@Immutable
internal data class SampleSpacing(
    val xxs: Dp = 2.dp,
    val inset: Dp = 3.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 28.dp,
    val modeGap: Dp = 22.dp,
    val controlsEdge: Dp = 36.dp,
    val controlsBottom: Dp = 48.dp,
)

/** Component sizes shared by more than one atom; touch targets never drop below [touchTarget]. */
@Immutable
internal data class SampleSizes(
    val touchTarget: Dp = 44.dp,
    val topBar: Dp = 72.dp,
    val circleButton: Dp = 44.dp,
    val controlButton: Dp = 48.dp,
    val shutter: Dp = 78.dp,
    val pill: Dp = 32.dp,
    val option: Dp = 44.dp,
    val button: Dp = 48.dp,
    val buttonLarge: Dp = 52.dp,
    val icon: Dp = 20.dp,
    val iconLarge: Dp = 22.dp,
    val handleWidth: Dp = 40.dp,
    val handleHeight: Dp = 4.dp,
    val hairline: Dp = 1.dp,
    val stroke: Dp = 2.dp,
    val strokeBold: Dp = 4.dp,
    val reticle: Dp = 240.dp,
    val controlsArea: Dp = 252.dp,
    /** The controls' width beside the preview on a screen wider than tall. */
    val sidePanel: Dp = 360.dp,
    val badge: Dp = 64.dp,
    val contentMaxWidth: Dp = 560.dp,
)

private val LocalSampleColors = staticCompositionLocalOf { SampleColors() }
private val LocalSampleTypography = staticCompositionLocalOf<SampleTypography> {
    SampleTypography.of(FontFamily.Default, FontFamily.Default)
}
private val LocalSampleShapes = staticCompositionLocalOf { SampleShapes() }
private val LocalSampleSpacing = staticCompositionLocalOf { SampleSpacing() }
private val LocalSampleSizes = staticCompositionLocalOf { SampleSizes() }

/** The Sample's theme values, read by every atom. */
internal object SampleTheme {
    val colors: SampleColors
        @Composable @ReadOnlyComposable
        get() = LocalSampleColors.current
    val typography: SampleTypography
        @Composable @ReadOnlyComposable
        get() = LocalSampleTypography.current
    val shapes: SampleShapes
        @Composable @ReadOnlyComposable
        get() = LocalSampleShapes.current
    val spacing: SampleSpacing
        @Composable @ReadOnlyComposable
        get() = LocalSampleSpacing.current
    val sizes: SampleSizes
        @Composable @ReadOnlyComposable
        get() = LocalSampleSizes.current
}

/**
 * Provides the Sample's tokens, with Inter and Space Grotesk, and maps them onto MaterialTheme for
 * the Material components the atoms wrap (the switch, the snackbar).
 */
@Composable
internal fun SampleTheme(content: @Composable () -> Unit) {
    val heading = FontFamily(
        Font(Res.font.space_grotesk_variable, FontWeight.Medium),
        Font(Res.font.space_grotesk_variable, FontWeight.SemiBold),
    )
    val text = FontFamily(
        Font(Res.font.inter_variable, FontWeight.Normal),
        Font(Res.font.inter_variable, FontWeight.Medium),
        Font(Res.font.inter_variable, FontWeight.SemiBold),
    )
    val typography = remember(heading, text) { SampleTypography.of(heading, text) }
    val colors = SampleColors()
    if (!LocalInspectionMode.current) SystemAppearance(darkIcons = true)
    CompositionLocalProvider(
        LocalSampleColors provides colors,
        LocalSampleTypography provides typography,
        LocalMinimumInteractiveComponentSize provides SampleSizes().touchTarget,
    ) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = colors.primary,
                onPrimary = colors.onPrimary,
                background = colors.background,
                onBackground = colors.ink,
                surface = colors.surface,
                onSurface = colors.ink,
                surfaceVariant = colors.control,
                onSurfaceVariant = colors.inkSecondary,
                outline = colors.outline,
                inverseSurface = colors.ink,
                inverseOnSurface = colors.background,
                error = colors.recording,
                onError = colors.onRecording,
            ),
            typography = Typography(
                bodyLarge = typography.bodyLarge,
                bodyMedium = typography.bodySmall,
                labelLarge = typography.button,
            ),
            content = content,
        )
    }
}
