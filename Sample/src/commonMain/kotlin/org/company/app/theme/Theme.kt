package org.company.app.theme

import androidx.compose.runtime.Composable

/** Sets the system bars' icons dark, for the light screens, or light. */
@Composable
internal expect fun SystemAppearance(darkIcons: Boolean)
