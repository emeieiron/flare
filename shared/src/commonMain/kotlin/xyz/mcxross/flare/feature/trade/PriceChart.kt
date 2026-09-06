package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.candlestickModel
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberCandlestickCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import kotlin.math.roundToInt
import xyz.mcxross.flare.data.formatPrice
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
  var widthPx by remember { mutableIntStateOf(0) }
  var selectedIndex by
    remember(candles) {
      mutableStateOf(candles.lastIndex.takeIf { it >= 0 })
    }
  val inspectModifier =
    Modifier.onSizeChanged { widthPx = it.width }
      .pointerInput(candles.size, widthPx) {
        if (candles.isEmpty() || widthPx == 0) return@pointerInput
        awaitEachGesture {
          fun select(x: Float) {
            val fraction = (x / widthPx).coerceIn(0f, 1f)
            selectedIndex = (fraction * candles.lastIndex).roundToInt()
          }
          val down = awaitFirstDown(requireUnconsumed = false)
          select(down.position.x)
          do {
            val event = awaitPointerEvent()
            event.changes.firstOrNull()?.let { select(it.position.x) }
          } while (event.changes.any { it.pressed })
        }
      }
      .semantics {
        contentDescription =
          selectedIndex?.let { "Selected candle ${it + 1} of ${candles.size}" } ?: "Market chart"
      }

  Box(modifier.then(inspectModifier)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      FlarePriceChart(
        candles = candles,
        style = style,
        modifier = Modifier.fillMaxWidth().height(300.dp),
      )
      FlareIndicatorCharts(
        candles = candles,
        showRsi = showRsi,
        showMacd = showMacd,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    selectedIndex
      ?.takeIf { it in candles.indices }
      ?.let { index ->
        Canvas(Modifier.fillMaxSize()) {
          val x = if (candles.size == 1) size.width / 2f else size.width * index / candles.lastIndex
          drawLine(
            color = FlareColors.BorderStrong,
            start = androidx.compose.ui.geometry.Offset(x, 0f),
            end = androidx.compose.ui.geometry.Offset(x, size.height),
            strokeWidth = 1.dp.toPx(),
          )
        }
        val candle = candles[index]
        Text(
          text =
            "O ${formatPrice(candle.open)}  H ${formatPrice(candle.high)}  " +
              "L ${formatPrice(candle.low)}  C ${formatPrice(candle.close)}",
          modifier =
            Modifier.align(Alignment.TopCenter)
              .background(FlareColors.Elevated, MaterialTheme.shapes.extraSmall)
              .padding(horizontal = 8.dp, vertical = 5.dp),
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.labelSmall,
        )
      }
  }
}

@Composable
fun FlarePriceChart(
  candles: List<Candle>,
  style: ChartStyle,
  modifier: Modifier = Modifier,
) {
  if (candles.isEmpty()) {
    ChartPlaceholder("No candle data", modifier)
    return
  }

  val producer = remember { CartesianChartModelProducer() }
  LaunchedEffect(candles, style) {
    producer.runTransaction {
      when (style) {
        ChartStyle.LINE ->
          lineModel {
            series(
              x = candles.indices.toList(),
              y = candles.map(Candle::close),
            )
          }
        ChartStyle.CANDLESTICK ->
          candlestickModel(
            x = candles.indices.toList(),
            opening = candles.map(Candle::open),
            closing = candles.map(Candle::close),
            low = candles.map(Candle::low),
            high = candles.map(Candle::high),
          )
      }
    }
  }

  val layer =
    when (style) {
      ChartStyle.LINE -> flareLineLayer(listOf(FlareColors.Positive))
      ChartStyle.CANDLESTICK -> rememberCandlestickCartesianLayer()
    }
  CartesianChartHost(
    chart =
      rememberCartesianChart(
        layer,
        endAxis = compactEndAxis(),
        bottomAxis = hiddenBottomAxis(),
      ),
    modelProducer = producer,
    modifier =
      modifier.fillMaxSize().semantics {
        contentDescription =
          if (style == ChartStyle.LINE) "Market price line chart" else "Market candlestick chart"
      },
    scrollState = rememberVicoScrollState(scrollEnabled = false),
    initialAnimationSpec = null,
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
private fun flareLineLayer(colors: List<Color>) =
  rememberLineCartesianLayer(
    lineProvider =
      LineCartesianLayer.LineProvider.series(
        colors.map { color ->
          LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(color)))
        }
      )
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
