package xyz.mcxross.flare.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.valentinilk.shimmer.Shimmer
import com.valentinilk.shimmer.ShimmerBounds
import com.valentinilk.shimmer.defaultShimmerTheme
import com.valentinilk.shimmer.rememberShimmer
import com.valentinilk.shimmer.shimmer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import xyz.mcxross.flare.feature.trade.ChartStyle

/**
 * Standard shimmer configuration tailored for Flare's dark theme.
 * Uses a smooth 1300ms linear sweep with alpha modulation against dark surfaces.
 */
@Composable
fun rememberFlareShimmer(): Shimmer =
  rememberShimmer(
    shimmerBounds = ShimmerBounds.View,
    theme =
      defaultShimmerTheme.copy(
        animationSpec =
          infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
          ),
        shaderColors =
          listOf(
            Color.White.copy(alpha = 0.20f),
            Color.White.copy(alpha = 1.00f),
            Color.White.copy(alpha = 0.20f),
          ),
      ),
  )

/**
 * Base building block for skeleton screens.
 */
@Composable
fun FlareSkeletonBox(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(4.dp),
  color: Color = FlareColors.BorderStrong,
) {
  Box(modifier.background(color, shape))
}

/**
 * A single row skeleton matching [MarketListRow] 1:1.
 */
@Composable
fun MarketListRowSkeleton(
  modifier: Modifier = Modifier,
  itemColor: Color = FlareColors.BorderStrong,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FlareSkeletonBox(
      modifier = Modifier.size(40.dp),
      shape = CircleShape,
      color = itemColor,
    )
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      FlareSkeletonBox(
        modifier = Modifier.height(16.dp).width(72.dp),
        shape = RoundedCornerShape(4.dp),
        color = itemColor,
      )
      FlareSkeletonBox(
        modifier = Modifier.height(12.dp).width(120.dp),
        shape = RoundedCornerShape(4.dp),
        color = itemColor,
      )
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      FlareSkeletonBox(
        modifier = Modifier.height(16.dp).width(68.dp),
        shape = RoundedCornerShape(4.dp),
        color = itemColor,
      )
      FlareSkeletonBox(
        modifier = Modifier.height(12.dp).width(52.dp),
        shape = RoundedCornerShape(4.dp),
        color = itemColor,
      )
    }
    Spacer(Modifier.width(8.dp))
    FlareSkeletonBox(
      modifier = Modifier.size(20.dp),
      shape = CircleShape,
      color = itemColor,
    )
  }
}

/**
 * Reusable list skeleton for market discovery and watchlist loading.
 */
@Composable
fun MarketListSkeleton(
  modifier: Modifier = Modifier,
  itemCount: Int = 8,
) {
  val shimmer = rememberFlareShimmer()
  Column(
    modifier = modifier.fillMaxWidth().shimmer(shimmer),
  ) {
    repeat(itemCount) {
      MarketListRowSkeleton()
    }
  }
}

private data class SkeletonPoint(val x: Float, val y: Float)

private val LineChartSkeletonPoints =
  listOf(
    SkeletonPoint(0.00f, 0.70f),
    SkeletonPoint(0.08f, 0.65f),
    SkeletonPoint(0.16f, 0.72f),
    SkeletonPoint(0.25f, 0.54f),
    SkeletonPoint(0.35f, 0.58f),
    SkeletonPoint(0.45f, 0.42f),
    SkeletonPoint(0.55f, 0.48f),
    SkeletonPoint(0.65f, 0.35f),
    SkeletonPoint(0.75f, 0.38f),
    SkeletonPoint(0.85f, 0.22f),
    SkeletonPoint(0.93f, 0.28f),
    SkeletonPoint(1.00f, 0.16f),
  )

private data class SkeletonCandle(
  val high: Float,
  val low: Float,
  val open: Float,
  val close: Float,
)

private val CandlestickSkeletonData =
  listOf(
    SkeletonCandle(high = 0.62f, low = 0.80f, open = 0.76f, close = 0.66f),
    SkeletonCandle(high = 0.58f, low = 0.74f, open = 0.66f, close = 0.72f),
    SkeletonCandle(high = 0.54f, low = 0.73f, open = 0.71f, close = 0.58f),
    SkeletonCandle(high = 0.48f, low = 0.68f, open = 0.59f, close = 0.51f),
    SkeletonCandle(high = 0.46f, low = 0.65f, open = 0.51f, close = 0.61f),
    SkeletonCandle(high = 0.40f, low = 0.60f, open = 0.58f, close = 0.44f),
    SkeletonCandle(high = 0.38f, low = 0.55f, open = 0.45f, close = 0.52f),
    SkeletonCandle(high = 0.30f, low = 0.52f, open = 0.50f, close = 0.34f),
    SkeletonCandle(high = 0.28f, low = 0.46f, open = 0.35f, close = 0.42f),
    SkeletonCandle(high = 0.22f, low = 0.44f, open = 0.41f, close = 0.26f),
    SkeletonCandle(high = 0.20f, low = 0.38f, open = 0.27f, close = 0.34f),
    SkeletonCandle(high = 0.16f, low = 0.36f, open = 0.33f, close = 0.20f),
    SkeletonCandle(high = 0.14f, low = 0.30f, open = 0.21f, close = 0.27f),
    SkeletonCandle(high = 0.10f, low = 0.26f, open = 0.26f, close = 0.14f),
  )

@Composable
private fun LineChartSkeletonCanvas(
  modifier: Modifier = Modifier,
  color: Color = FlareColors.BorderStrong,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height

    // Grid lines
    val gridCount = 4
    for (i in 0..gridCount) {
      val y = h * (i.toFloat() / gridCount)
      drawLine(
        color = FlareColors.BorderDefault.copy(alpha = 0.4f),
        start = Offset(0f, y),
        end = Offset(w, y),
        strokeWidth = 1.dp.toPx(),
      )
    }

    // Line path & area gradient fill
    val linePath = Path()
    val areaPath = Path()

    val first = LineChartSkeletonPoints.first()
    val startX = first.x * w
    val startY = first.y * h

    linePath.moveTo(startX, startY)
    areaPath.moveTo(startX, startY)

    for (i in 1 until LineChartSkeletonPoints.size) {
      val prev = LineChartSkeletonPoints[i - 1]
      val curr = LineChartSkeletonPoints[i]
      val prevX = prev.x * w
      val prevY = prev.y * h
      val currX = curr.x * w
      val currY = curr.y * h
      val midX = (prevX + currX) / 2f

      linePath.cubicTo(midX, prevY, midX, currY, currX, currY)
      areaPath.cubicTo(midX, prevY, midX, currY, currX, currY)
    }

    areaPath.lineTo(w, h)
    areaPath.lineTo(0f, h)
    areaPath.close()

    drawPath(
      path = areaPath,
      brush =
        Brush.verticalGradient(
          colors = listOf(color.copy(alpha = 0.35f), Color.Transparent),
          startY = 0f,
          endY = h,
        ),
    )

    drawPath(
      path = linePath,
      color = color,
      style =
        Stroke(
          width = 2.5.dp.toPx(),
          cap = StrokeCap.Round,
          join = StrokeJoin.Round,
        ),
    )
  }
}

@Composable
private fun CandlestickSkeletonCanvas(
  modifier: Modifier = Modifier,
  color: Color = FlareColors.BorderStrong,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height

    // Grid lines
    val gridCount = 4
    for (i in 0..gridCount) {
      val y = h * (i.toFloat() / gridCount)
      drawLine(
        color = FlareColors.BorderDefault.copy(alpha = 0.4f),
        start = Offset(0f, y),
        end = Offset(w, y),
        strokeWidth = 1.dp.toPx(),
      )
    }

    // Candlesticks
    val candleCount = CandlestickSkeletonData.size
    val step = w / candleCount
    val bodyWidth = (step * 0.55f).coerceIn(6.dp.toPx(), 14.dp.toPx())
    val cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())

    CandlestickSkeletonData.forEachIndexed { index, candle ->
      val centerX = (index + 0.5f) * step
      val highY = candle.high * h
      val lowY = candle.low * h
      val openY = candle.open * h
      val closeY = candle.close * h

      // Wick
      drawLine(
        color = color.copy(alpha = 0.7f),
        start = Offset(centerX, highY),
        end = Offset(centerX, lowY),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round,
      )

      // Body
      val bodyTop = min(openY, closeY)
      val bodyHeight = max(abs(closeY - openY), 4.dp.toPx())
      val bodyLeft = centerX - (bodyWidth / 2f)

      drawRoundRect(
        color = color,
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = cornerRadius,
      )
    }
  }
}

/**
 * Shimmer placeholder matching the 280 dp chart area in [TradeScreen].
 * Adapts to [ChartStyle.LINE] and [ChartStyle.CANDLESTICK].
 */
@Composable
fun PriceChartSkeleton(
  modifier: Modifier = Modifier,
  chartStyle: ChartStyle = ChartStyle.LINE,
  hasShimmer: Boolean = true,
  shimmer: Shimmer? = null,
) {
  val actualShimmer = shimmer ?: rememberFlareShimmer()
  val shimmerModifier = if (hasShimmer) Modifier.shimmer(actualShimmer) else Modifier
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .height(280.dp)
        .then(shimmerModifier)
        .background(FlareColors.Surface, RoundedCornerShape(12.dp))
        .padding(16.dp),
  ) {
    when (chartStyle) {
      ChartStyle.LINE -> LineChartSkeletonCanvas(Modifier.fillMaxSize())
      ChartStyle.CANDLESTICK -> CandlestickSkeletonCanvas(Modifier.fillMaxSize())
    }
  }
}

/**
 * Full-screen trade view skeleton used while loading market details.
 */
@Composable
fun TradeScreenSkeleton(
  modifier: Modifier = Modifier,
  chartStyle: ChartStyle = ChartStyle.LINE,
) {
  val shimmer = rememberFlareShimmer()
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .padding(horizontal = 24.dp)
        .shimmer(shimmer),
  ) {
    Spacer(Modifier.height(16.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FlareSkeletonBox(Modifier.size(48.dp), CircleShape)
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlareSkeletonBox(Modifier.height(20.dp).width(100.dp), RoundedCornerShape(4.dp))
        FlareSkeletonBox(Modifier.height(14.dp).width(60.dp), RoundedCornerShape(4.dp))
      }
    }
    Spacer(Modifier.height(16.dp))
    FlareSkeletonBox(Modifier.height(36.dp).width(160.dp), RoundedCornerShape(6.dp))
    Spacer(Modifier.height(8.dp))
    FlareSkeletonBox(Modifier.height(14.dp).width(120.dp), RoundedCornerShape(4.dp))
    Spacer(Modifier.height(24.dp))
    PriceChartSkeleton(chartStyle = chartStyle, hasShimmer = false)
    Spacer(Modifier.height(16.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      repeat(5) {
        FlareSkeletonBox(Modifier.height(28.dp).width(44.dp), RoundedCornerShape(8.dp))
      }
    }
    Spacer(Modifier.height(24.dp))
    repeat(3) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        FlareSkeletonBox(Modifier.height(14.dp).width(90.dp), RoundedCornerShape(4.dp))
        FlareSkeletonBox(Modifier.height(14.dp).width(70.dp), RoundedCornerShape(4.dp))
      }
    }
  }
}

/**
 * Clean startup skeleton displayed on cold launch before preferences/profile resolve.
 */
@Composable
fun AppStartupSkeleton(
  modifier: Modifier = Modifier,
) {
  val shimmer = rememberFlareShimmer()
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(FlareColors.Canvas)
        .padding(horizontal = 24.dp)
        .shimmer(shimmer),
  ) {
    Spacer(Modifier.height(24.dp))
    FlareSkeletonBox(Modifier.height(36.dp).width(130.dp), RoundedCornerShape(6.dp))
    Spacer(Modifier.height(20.dp))
    FlareSkeletonBox(Modifier.fillMaxWidth().height(52.dp), RoundedCornerShape(12.dp))
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      repeat(4) {
        FlareSkeletonBox(Modifier.height(36.dp).width(76.dp), CircleShape)
      }
    }
    Spacer(Modifier.height(16.dp))
    repeat(7) {
      MarketListRowSkeleton()
    }
  }
}
