package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CandlestickCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.CandlestickCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.absolute
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberCandlestickCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import kotlin.math.abs
import xyz.mcxross.flare.decibel.model.Candle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.domain.MacdPoint
import xyz.mcxross.flare.domain.TradingIndicators

@Composable
fun FlareChartStack(
  candles: List<Candle>,
  style: ChartStyle,
  showRsi: Boolean,
  showMacd: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    FlarePriceChart(candles, style, Modifier.fillMaxWidth().height(280.dp))
    FlareIndicatorCharts(candles, showRsi, showMacd, Modifier.fillMaxWidth())
  }
}

@Composable
fun FlarePriceChart(
  candles: List<Candle>,
  style: ChartStyle,
  modifier: Modifier = Modifier,
) {
  if (candles.isEmpty()) {
    ChartPlaceholder("Price history is unavailable", modifier)
    return
  }

  // Build once per price snapshot. This host also recomputes ranges when the viewport changes,
  // without waiting for another network update to rescale a panned candle chart.
  val model =
    remember(candles, style) {
      CartesianChartModel(
        when (style) {
          ChartStyle.LINE ->
            LineCartesianLayerModel.build {
              series(
                x = candles.indices.toList(),
                y = candles.map(Candle::close),
              )
            }
          ChartStyle.CANDLESTICK ->
            CandlestickCartesianLayerModel.build(
              x = candles.indices.toList(),
              opening = candles.map(Candle::open),
              closing = candles.map(Candle::close),
              low = candles.map(Candle::low),
              high = candles.map(Candle::high),
            )
        }
      )
    }

  var visibleIndices by
    remember(candles.size, style) {
      mutableStateOf((candles.size - 60).coerceAtLeast(0)..candles.lastIndex)
    }
  val viewportObserver =
    remember(candles.size, style) {
      object : Decoration {
        override fun drawUnderLayers(context: CartesianDrawingContext) {
          // Use the measured plot, including candle padding, after Vico applies pan and zoom.
          val spacing = context.layerDimensions.xSpacing
          if (spacing <= 0f) return
          val firstX =
            context.ranges.minX +
              (abs(context.scroll) - context.layerDimensions.startPadding) / spacing *
                context.ranges.xStep
          val lastX = firstX + context.layerBounds.width / spacing * context.ranges.xStep
          visibleIndices = visibleCandleIndices(firstX, lastX, candles.size)
        }
      }
    }
  val bounds =
    remember(candles, style, visibleIndices) {
      val visibleCandles = if (style == ChartStyle.LINE) candles else candles.slice(visibleIndices)
      priceBounds(
        visibleCandles.flatMap {
          if (style == ChartStyle.LINE) listOf(it.close) else listOf(it.low, it.high)
        }
      )
    }
  val rangeProvider =
    remember(bounds) {
      CartesianLayerRangeProvider.fixed(minY = bounds.start, maxY = bounds.endInclusive)
    }
  val marker =
    rememberDefaultCartesianMarker(
      label =
        rememberTextComponent(
          style = MaterialTheme.typography.bodySmall.copy(color = FlareColors.TextPrimary)
        ),
      guideline = rememberLineComponent(fill = Fill(FlareColors.BorderStrong), thickness = 1.dp),
    )
  val layer =
    when (style) {
      ChartStyle.LINE ->
        flareLineLayer(
          listOf(
            if (candles.last().close >= candles.first().close) FlareColors.Positive
            else FlareColors.Negative
          ),
          rangeProvider,
        )
      ChartStyle.CANDLESTICK ->
        rememberCandlestickCartesianLayer(
          candleProvider =
            CandlestickCartesianLayer.CandleProvider.absolute(
              bullish =
                CandlestickCartesianLayer.Candle(
                  rememberLineComponent(Fill(FlareColors.Positive), 6.dp)
                ),
              neutral =
                CandlestickCartesianLayer.Candle(
                  rememberLineComponent(Fill(FlareColors.TextSecondary), 6.dp)
                ),
              bearish =
                CandlestickCartesianLayer.Candle(
                  rememberLineComponent(Fill(FlareColors.Negative), 6.dp)
                ),
            ),
          rangeProvider = rangeProvider,
        )
    }
  CartesianChartHost(
    chart =
      rememberCartesianChart(
        layer,
        marker = marker,
        decorations =
          if (style == ChartStyle.CANDLESTICK) listOf(viewportObserver) else emptyList(),
      ),
    model = model,
    modifier =
      modifier.fillMaxSize().semantics {
        contentDescription =
          if (style == ChartStyle.LINE) "Market price line chart" else "Market candlestick chart"
      },
    scrollState =
      rememberVicoScrollState(
        scrollEnabled = style == ChartStyle.CANDLESTICK,
        initialScroll = Scroll.Absolute.End,
      ),
    zoomState =
      rememberVicoZoomState(
        zoomEnabled = style == ChartStyle.CANDLESTICK,
        initialZoom =
          if (style == ChartStyle.LINE) Zoom.Content else Zoom.max(Zoom.Content, Zoom.x(60.0)),
        minZoom = Zoom.Content,
        maxZoom = Zoom.max(Zoom.Content, Zoom.x(12.0)),
      ),
  )
}

@Composable
fun FlareIndicatorCharts(
  candles: List<Candle>,
  showRsi: Boolean,
  showMacd: Boolean,
  modifier: Modifier = Modifier,
) {
  if (!showRsi && !showMacd) return
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (showRsi) {
      val rsi = remember(candles) { TradingIndicators.rsi(candles.map(Candle::close)) }
      val points = rsi.mapIndexedNotNull { index, value -> value?.let { index to it } }
      IndicatorPanel(
        title = "RSI · 14",
        value = rsi.lastOrNull()?.let(::fixedIndicator) ?: "—",
        description = "Relative strength index chart",
      ) {
        if (points.isEmpty()) {
          ChartPlaceholder("More candles required", Modifier.fillMaxSize())
        } else {
          LineIndicatorChart(points, FlareColors.IndicatorCyan, Modifier.fillMaxSize())
        }
      }
    }
    if (showMacd) {
      val macd = remember(candles) { TradingIndicators.macd(candles.map(Candle::close)) }
      IndicatorPanel(
        title = "MACD · 12 26 9",
        value = macd.lastOrNull()?.histogram?.let(::fixedIndicator) ?: "—",
        description = "Moving average convergence divergence chart",
      ) {
        if (macd.isEmpty()) {
          ChartPlaceholder("More candles required", Modifier.fillMaxSize())
        } else {
          MacdIndicatorChart(macd, Modifier.fillMaxSize())
        }
      }
    }
  }
}

@Composable
private fun IndicatorPanel(
  title: String,
  value: String,
  description: String,
  content: @Composable () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth()
      .background(FlareColors.Surface, MaterialTheme.shapes.small)
      .padding(top = 10.dp)
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(title, style = MaterialTheme.typography.labelMedium)
      Text(
        value,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
      )
    }
    Box(Modifier.fillMaxWidth().height(116.dp).semantics { contentDescription = description }) {
      content()
    }
  }
}

@Composable
private fun LineIndicatorChart(
  points: List<Pair<Int, Double>>,
  color: Color,
  modifier: Modifier,
) {
  val producer = remember { CartesianChartModelProducer() }
  LaunchedEffect(points) {
    producer.runTransaction {
      lineModel {
        series(
          x = points.map { it.first },
          y = points.map { it.second },
        )
      }
    }
  }
  CartesianChartHost(
    chart =
      rememberCartesianChart(
        flareLineLayer(listOf(color)),
        endAxis = compactEndAxis(),
        bottomAxis = hiddenBottomAxis(),
      ),
    modelProducer = producer,
    modifier = modifier,
    scrollState = rememberVicoScrollState(scrollEnabled = false),
    initialAnimationSpec = null,
  )
}

@Composable
private fun MacdIndicatorChart(
  points: List<MacdPoint>,
  modifier: Modifier,
) {
  val producer = remember { CartesianChartModelProducer() }
  val x = points.indices.toList()
  LaunchedEffect(points) {
    producer.runTransaction {
      columnModel { series(x = x, y = points.map { it.histogram }) }
      lineModel {
        series(x = x, y = points.map { it.macd })
        series(x = x, y = points.map { it.signal })
      }
    }
  }
  val columns =
    rememberColumnCartesianLayer(
      columnProvider =
        ColumnCartesianLayer.ColumnProvider.series(
          rememberLineComponent(fill = Fill(FlareColors.IndicatorOrange), thickness = 3.dp)
        )
    )
  CartesianChartHost(
    chart =
      rememberCartesianChart(
        columns,
        flareLineLayer(listOf(FlareColors.IndicatorCyan, FlareColors.TextSecondary)),
        endAxis = compactEndAxis(),
        bottomAxis = hiddenBottomAxis(),
      ),
    modelProducer = producer,
    modifier = modifier,
    scrollState = rememberVicoScrollState(scrollEnabled = false),
    initialAnimationSpec = null,
  )
}

@Composable
private fun flareLineLayer(
  colors: List<Color>,
  rangeProvider: CartesianLayerRangeProvider = CartesianLayerRangeProvider.Intrinsic,
) =
  rememberLineCartesianLayer(
    rangeProvider = rangeProvider,
    lineProvider =
      LineCartesianLayer.LineProvider.series(
        colors.map { color ->
          LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(color)))
        }
      ),
  )

@Composable
private fun compactEndAxis() =
  VerticalAxis.rememberEnd(
    label = null,
    tick = null,
    guideline = null,
  )

@Composable
private fun hiddenBottomAxis() =
  HorizontalAxis.rememberBottom(
    label = null,
    tick = null,
    guideline = null,
  )

@Composable
private fun ChartPlaceholder(text: String, modifier: Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Text(text, color = FlareColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
  }
}

private fun fixedIndicator(value: Double): String {
  val scaled = kotlin.math.round(value * 100.0).toLong()
  val whole = scaled / 100
  val fraction = kotlin.math.abs(scaled % 100).toString().padStart(2, '0')
  return "$whole.$fraction"
}
