package com.adbhost.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
  primary = PrimaryBlue,
  onPrimary = OnPrimary,
  primaryContainer = PrimaryContainer,
  onPrimaryContainer = OnPrimaryContainer,
  secondary = Secondary,
  onSecondary = OnSecondary,
  secondaryContainer = SecondaryContainer,
  onSecondaryContainer = OnSecondaryContainer,
  tertiary = Tertiary,
  onTertiary = OnTertiary,
  tertiaryContainer = TertiaryContainer,
  onTertiaryContainer = OnTertiaryContainer,
  error = ErrorRed,
  onError = OnError,
  errorContainer = ErrorContainer,
  onErrorContainer = OnErrorContainer,
  background = AppSurface,
  onBackground = OnSurface,
  surface = AppSurface,
  onSurface = OnSurface,
  surfaceVariant = SurfaceVariant,
  onSurfaceVariant = OnSurfaceVariant,
  outline = OutlineSoft,
  outlineVariant = OutlineSoft,
  scrim = ScrimDark,
  inverseSurface = InverseSurfaceDark,
  inverseOnSurface = InverseOnSurface,
  inversePrimary = InversePrimary,
  surfaceDim = SurfaceDim,
  surfaceBright = SurfaceBright,
  surfaceContainerLowest = CardSurface,
  surfaceContainerLow = CardSurface,
  surfaceContainer = CardSurface,
  surfaceContainerHigh = CardSurface,
  surfaceContainerHighest = SurfaceVariant
)

@Composable
fun AdbHostTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = LightColors,
    typography = Typography,
    content = content
  )
}
