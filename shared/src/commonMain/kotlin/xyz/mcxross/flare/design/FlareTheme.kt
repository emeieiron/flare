package xyz.mcxross.flare.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import flare.shared.generated.resources.Res
import flare.shared.generated.resources.inter_medium
import flare.shared.generated.resources.inter_regular
import flare.shared.generated.resources.inter_semibold
import org.jetbrains.compose.resources.Font

object FlareColors {
  val Canvas = Color(0xFF000000)
  val Surface = Color(0xFF101210)
  val Elevated = Color(0xFF191C19)
  val Hover = Color(0xFF202020)
  val BorderSubtle = Color(0xFF242424)
  val BorderDefault = Color(0xFF343434)
  val BorderStrong = Color(0xFF4A4A4A)
  val TextPrimary = Color(0xFFF5F5F5)
  val TextSecondary = Color(0xFFA3A3A3)
  val TextTertiary = Color(0xFF8B8D89)
  val TextDisabled = Color(0xFF4F4F4F)
  val Positive = Color(0xFFC4F564)
  val PositiveMuted = Color(0xFF202B14)
  val Negative = Color(0xFFFF766B)
  val NegativeMuted = Color(0xFF431409)
  val Warning = Color(0xFFF5B700)
  val Info = Color(0xFF0A84FF)
  val IndicatorCyan = Color(0xFF64D9E7)
  val IndicatorOrange = Color(0xFFFF8A00)
}

@Immutable
data class FlareTradingColors(
  val positive: Color,
  val positiveMuted: Color,
  val negative: Color,
  val negativeMuted: Color,
  val warning: Color,
  val info: Color,
  val indicatorCyan: Color,
  val indicatorOrange: Color,
  val borderSubtle: Color,
  val borderDefault: Color,
  val borderStrong: Color,
  val textSecondary: Color,
  val textTertiary: Color,
)

@Immutable
data class FlareSpacing(
  val xs: Dp = 4.dp,
  val sm: Dp = 8.dp,
  val md: Dp = 12.dp,
  val lg: Dp = 16.dp,
  val xl: Dp = 20.dp,
  val xxl: Dp = 24.dp,
  val section: Dp = 32.dp,
  val touch: Dp = 48.dp,
)

@Immutable
data class FlareElevation(
  val flat: Dp = 0.dp,
  val raised: Dp = 2.dp,
  val overlay: Dp = 6.dp,
)

@Immutable
data class FlareMotion(
  val microInteractionMs: Int = 120,
  val panelTransitionMs: Int = 200,
  val chartTransitionMs: Int = 240,
  val numericFlashMs: Int = 140,
)

val LocalFlareTradingColors = staticCompositionLocalOf {
  FlareTradingColors(
    positive = FlareColors.Positive,
    positiveMuted = FlareColors.PositiveMuted,
    negative = FlareColors.Negative,
    negativeMuted = FlareColors.NegativeMuted,
    warning = FlareColors.Warning,
    info = FlareColors.Info,
    indicatorCyan = FlareColors.IndicatorCyan,
    indicatorOrange = FlareColors.IndicatorOrange,
    borderSubtle = FlareColors.BorderSubtle,
    borderDefault = FlareColors.BorderDefault,
    borderStrong = FlareColors.BorderStrong,
    textSecondary = FlareColors.TextSecondary,
    textTertiary = FlareColors.TextTertiary,
  )
}

val LocalFlareSpacing = staticCompositionLocalOf { FlareSpacing() }
val LocalFlareElevation = staticCompositionLocalOf { FlareElevation() }
val LocalFlareMotion = staticCompositionLocalOf { FlareMotion() }

private val FlareColorScheme =
  darkColorScheme(
    primary = FlareColors.Positive,
    onPrimary = FlareColors.Canvas,
    primaryContainer = FlareColors.PositiveMuted,
    onPrimaryContainer = FlareColors.Positive,
    secondary = FlareColors.TextPrimary,
    onSecondary = FlareColors.Canvas,
    background = FlareColors.Canvas,
    onBackground = FlareColors.TextPrimary,
    surface = FlareColors.Surface,
    onSurface = FlareColors.TextPrimary,
    surfaceVariant = FlareColors.Elevated,
    onSurfaceVariant = FlareColors.TextSecondary,
    outline = FlareColors.BorderDefault,
    outlineVariant = FlareColors.BorderSubtle,
    error = FlareColors.Negative,
    onError = Color.White,
  )

private fun textStyle(
  size: Int,
  lineHeight: Int,
  weight: FontWeight,
  fontFamily: FontFamily,
): TextStyle =
  TextStyle(
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    fontFamily = fontFamily,
    fontFeatureSettings = "tnum",
  )

@Composable
private fun flareTypography(): Typography {
  val inter =
    FontFamily(
      Font(Res.font.inter_regular, FontWeight.Normal),
      Font(Res.font.inter_medium, FontWeight.Medium),
      Font(Res.font.inter_semibold, FontWeight.SemiBold),
    )
  return Typography(
    displayLarge = textStyle(56, 60, FontWeight.Medium, inter),
    displayMedium = textStyle(48, 54, FontWeight.Medium, inter),
    displaySmall = textStyle(40, 46, FontWeight.Medium, inter),
    headlineLarge = textStyle(32, 38, FontWeight.Medium, inter),
    headlineMedium = textStyle(28, 34, FontWeight.Medium, inter),
    headlineSmall = textStyle(24, 30, FontWeight.Medium, inter),
    titleLarge = textStyle(22, 28, FontWeight.Medium, inter),
    titleMedium = textStyle(16, 22, FontWeight.Medium, inter),
    titleSmall = textStyle(14, 20, FontWeight.Medium, inter),
    bodyLarge = textStyle(16, 24, FontWeight.Normal, inter),
    bodyMedium = textStyle(14, 22, FontWeight.Normal, inter),
    bodySmall = textStyle(12, 18, FontWeight.Normal, inter),
    labelLarge = textStyle(15, 20, FontWeight.SemiBold, inter),
    labelMedium = textStyle(13, 18, FontWeight.Medium, inter),
    labelSmall = textStyle(11, 16, FontWeight.Medium, inter),
  )
}

private val FlareShapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
  )

@Composable
fun FlareTheme(content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalFlareTradingColors provides LocalFlareTradingColors.current,
    LocalFlareSpacing provides FlareSpacing(),
    LocalFlareElevation provides FlareElevation(),
    LocalFlareMotion provides FlareMotion(),
  ) {
    MaterialTheme(
      colorScheme = FlareColorScheme,
      typography = flareTypography(),
      shapes = FlareShapes,
      content = {
        CompositionLocalProvider(
          LocalContentColor provides FlareColors.TextPrimary,
          content = content,
        )
      },
    )
  }
}
