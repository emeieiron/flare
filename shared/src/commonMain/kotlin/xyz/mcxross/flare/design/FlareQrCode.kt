package xyz.mcxross.flare.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.options.QrBallShape
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.options.QrFrameShape
import io.github.alexzhirkevich.qrose.options.QrLogoPadding
import io.github.alexzhirkevich.qrose.options.QrLogoShape
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.QrOptions
import io.github.alexzhirkevich.qrose.options.circle
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.QrCodePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A QR code in Flare's style: soft modules and eyes on a light card, with the Flare mark at its
 * centre. Dark on light, because every camera reads it; the light card is also the quiet zone.
 * High error correction leaves room for the mark without costing a scan.
 */
@Composable
fun FlareQrCode(data: String, contentDescription: String?, modifier: Modifier = Modifier) {
  val mark = remember { FlareBadgePainter(badge = FlareColors.Canvas, mark = FlareColors.Positive) }
  val painter = remember(data, mark) { QrCodePainter(data, flareQrOptions(mark)) }
  FlareQrCode(painter, contentDescription, modifier)
}

/** Kept by the account screen so closing Receive does not discard Qrose's size-specific draw cache. */
@Composable
internal fun rememberPreparedFlareQrCodePainter(data: String): Painter? {
  val mark = remember { FlareBadgePainter(badge = FlareColors.Canvas, mark = FlareColors.Positive) }
  val options = remember(mark) { flareQrOptions(mark) }
  // A new address gets a new state holder, never the previous wallet's painter for one frame.
  return key(data) {
    val painter by produceState<Painter?>(null) {
      if (data.isNotBlank()) value = withContext(Dispatchers.Default) { QrCodePainter(data, options) }
    }
    painter
  }
}

@Composable
internal fun FlareQrCode(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier) {
  Box(
    modifier.clip(RoundedCornerShape(28.dp)).background(FlareColors.TextPrimary).padding(20.dp)
  ) {
    Image(painter, contentDescription, Modifier.fillMaxSize())
  }
}

private fun flareQrOptions(mark: Painter) = QrOptions {
  errorCorrectionLevel = QrErrorCorrectionLevel.High
  shapes {
    darkPixel = QrPixelShape.roundCorners(0.5f)
    // Rounder eyes lose the square corners strict decoders look for; 0.1 still reads as soft.
    frame = QrFrameShape.roundCorners(0.1f)
    ball = QrBallShape.roundCorners(0.3f)
  }
  colors { dark = QrBrush.solid(FlareColors.Canvas) }
  logo {
    painter = mark
    size = 0.22f
    padding = QrLogoPadding.Natural(0.1f)
    shape = QrLogoShape.circle()
  }
}

/** The Flare mark on a round badge, for places that take a [Painter]. */
private class FlareBadgePainter(private val badge: Color, private val mark: Color) : Painter() {
  override val intrinsicSize: Size = Size.Unspecified

  override fun DrawScope.onDraw() {
    drawCircle(badge)
    inset(size.minDimension * 0.24f) { drawFlareMark(mark) }
  }
}
