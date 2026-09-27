package xyz.mcxross.flare.feature.trade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.AutoScrollCondition
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
import com.patrykandpatrick.vico.compose.cartesian.layer.CartesianLayerPadding
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
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import xyz.mcxross.flare.data.ChartTimeframe
import xyz.mcxross.flare.decibel.model.Candle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareIcons
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.fullBleed
import xyz.mcxross.flare.domain.MacdPoint
import xyz.mcxross.flare.domain.TradingIndicators

/** The stretch of history the price chart shows, in candle slots, so other panels can follow it. */
@Stable
class ChartWindow {
  var slots: LongRange? by mutableStateOf(null)
    internal set
}

/**
 * Opens on the latest candles. Dragging right reveals older ones, dragging left returns to the
 * latest, and pinching changes how many fit. While the latest candle is in view, the chart follows
 * it as new candles arrive. The line takes its colour from [rising], the market's day, so panning
 * never recolours it.
 */
@Composable
fun FlarePriceChart(
  candles: List<Candle>,
  style: ChartStyle,
  rising: Boolean,
  window: ChartWindow,
  modifier: Modifier = Modifier,
  onReachHistoryStart: () -> Unit = {},
) {
  if (candles.isEmpty()) {
    ChartPlaceholder("Price history is unavailable", modifier)
    return
  }

  // Slots stay put as history is added at either end, so the view holds still while it loads.
  val slots = remember(candles) { candleSlots(candles) }
  val model =
    remember(candles, style, slots) {
      CartesianChartModel(
        when (style) {
          ChartStyle.LINE ->
            LineCartesianLayerModel.build { series(x = slots, y = candles.map(Candle::close)) }
          ChartStyle.CANDLESTICK ->
            CandlestickCartesianLayerModel.build(
              x = slots,
              opening = candles.map(Candle::open),
              closing = candles.map(Candle::close),
              low = candles.map(Candle::low),
              high = candles.map(Candle::high),
            )
        }
      )
    }
  val visibleIndices =
    remember(slots, window.slots) { visibleCandleIndices(slots, window.slots, INITIAL_CANDLES) }
  LaunchedEffect(visibleIndices.first, slots.first()) {
    if (visibleIndices.first < HISTORY_PREFETCH_CANDLES) onReachHistoryStart()
  }
  val bounds =
    remember(candles, style, visibleIndices) {
      priceBounds(
        candles.slice(visibleIndices).flatMap {
          if (style == ChartStyle.LINE) listOf(it.close) else listOf(it.low, it.high)
        }
      )
    }
  // A short history keeps its candles at their usual width, sitting at the latest end, rather than
  // stretching to fill the chart.
  val minX = minOf(slots.first(), slots.last() - (INITIAL_CANDLES - 1)).toDouble()
  val rangeProvider =
    remember(bounds, minX) {
      CartesianLayerRangeProvider.fixed(
        minX = minX,
        minY = bounds.start,
        maxY = bounds.endInclusive,
      )
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
          listOf(if (rising) FlareColors.Positive else FlareColors.Negative),
          rangeProvider,
          pointSpacing = CANDLE_SPACING,
        )
      ChartStyle.CANDLESTICK ->
        rememberCandlestickCartesianLayer(
          candleProvider =
            CandlestickCartesianLayer.CandleProvider.absolute(
              bullish =
                CandlestickCartesianLayer.Candle(
                  rememberLineComponent(Fill(FlareColors.Positive), CANDLE_WIDTH)
                ),
              neutral =
                CandlestickCartesianLayer.Candle(
                  rememberLineComponent(Fill(FlareColors.TextSecondary), CANDLE_WIDTH)
                ),
              bearish =
                CandlestickCartesianLayer.Candle(
                  rememberLineComponent(Fill(FlareColors.Negative), CANDLE_WIDTH)
                ),
            ),
          candleSpacing = CANDLE_SPACING - CANDLE_WIDTH,
          rangeProvider = rangeProvider,
        )
    }
  // A new market or timeframe starts over at its latest candles.
  key(window) {
    val follow = remember { FollowLatest() }
    val viewportObserver =
      remember(window) {
        object : Decoration {
          override fun drawUnderLayers(context: CartesianDrawingContext) {
            // The measured plot, after Vico applies pan and zoom.
            val spacing = context.layerDimensions.xSpacing
            if (spacing <= 0f) return
            val xStep = context.ranges.xStep
            val firstX =
              context.ranges.minX +
                (abs(context.scroll) - context.layerDimensions.startPadding) / spacing * xStep
            val lastX = firstX + context.layerBounds.width / spacing * xStep
            follow.atLatest = lastX >= context.ranges.maxX - xStep / 2
            val visible = floor(firstX).toLong()..ceil(lastX).toLong()
            if (window.slots != visible) window.slots = visible
          }
        }
      }
    val followCondition =
      remember(follow) {
        AutoScrollCondition { _, newModel ->
          val latestX = newModel.models.maxOf { it.maxX }
          val grew = latestX > follow.latestX
          follow.latestX = latestX
          grew && follow.atLatest
        }
      }
    CartesianChartHost(
      chart =
        rememberCartesianChart(
          layer,
          marker = marker,
          layerPadding = ChartLayerPadding,
          decorations = listOf(viewportObserver),
        ),
      model = model,
      modifier =
        modifier.fillMaxSize().semantics {
          contentDescription =
            if (style == ChartStyle.LINE) "Market price line chart" else "Market candlestick chart"
        },
      scrollState =
        rememberVicoScrollState(
          initialScroll = Scroll.Absolute.End,
          autoScroll = Scroll.Absolute.End,
          autoScrollCondition = followCondition,
        ),
      zoomState =
        rememberVicoZoomState(
          initialZoom = InitialZoom,
          minZoom = WidestZoom,
          maxZoom = ClosestZoom,
        ),
    )
  }
}

/** Whether the latest candle is in view, updated as the chart draws. */
private class FollowLatest {
  var atLatest = true
  var latestX = Double.NEGATIVE_INFINITY
}

private const val INITIAL_CANDLES = 60
private const val HISTORY_PREFETCH_CANDLES = 40
private val CANDLE_WIDTH = 6.dp
private val CANDLE_SPACING = 10.dp

// Zoom and padding are keys of the chart's saved state, so each must stay the same instance.
private val InitialZoom = Zoom.max(Zoom.Content, Zoom.x(INITIAL_CANDLES.toDouble()))
private val WidestZoom = Zoom.max(Zoom.Content, Zoom.x(240.0))
private val ClosestZoom = Zoom.max(Zoom.Content, Zoom.x(12.0))
private val ChartLayerPadding: (ExtraStore) -> CartesianLayerPadding = {
  CartesianLayerPadding(unscalableEnd = 16.dp)
}

/**
 * Flips the price chart between line and candles in one tap. The icon shows the style a tap
 * switches to, so it reads as an action rather than a state.
 */
@Composable
fun ChartStyleToggle(style: ChartStyle, onToggle: () -> Unit, modifier: Modifier = Modifier) {
  val next = style.flipped()
  Box(
    modifier
      .size(32.dp)
      .clip(CircleShape)
      .background(FlareColors.Surface)
      .clickable(
        role = Role.Button,
        onClickLabel = if (next == ChartStyle.LINE) "Show line chart" else "Show candles",
        onClick = onToggle,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Crossfade(next, label = "chartStyleToggle") { target ->
      Icon(
        if (target == ChartStyle.LINE) FlareIcons.ChartLine
        else FlareIcons.ChartCandles,
        contentDescription = if (target == ChartStyle.LINE) "Show line chart" else "Show candles",
        modifier = Modifier.size(18.dp),
        tint = FlareColors.TextPrimary,
      )
    }
  }
}

/** Shows the chart's timeframe and opens the full list. */
@Composable
fun ChartTimeframeButton(timeframe: ChartTimeframe, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier
      .height(32.dp)
      .clip(CircleShape)
      .background(FlareColors.Surface)
      .clickable(role = Role.Button, onClickLabel = "Change timeframe", onClick = onClick)
      .semantics { contentDescription = "Timeframe, ${timeframe.title}" }
      .padding(start = 12.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(timeframe.label, style = MaterialTheme.typography.labelMedium)
    Icon(
      FlareIcons.ChevronDown,
      contentDescription = null,
      modifier = Modifier.padding(start = 2.dp).size(18.dp),
      tint = FlareColors.TextSecondary,
    )
  }
}

/**
 * Every timeframe as a grid of pills, four to a row, shortest first. Picking one applies it and
 * closes the sheet.
 */
@Composable
fun ChartTimeframeSheet(
  selected: ChartTimeframe,
  onSelect: (ChartTimeframe) -> Unit,
  onDismiss: () -> Unit,
) {
  FlareSheet("Timeframe", onDismiss) {
    Column(verticalArrangement = Arrangement.spacedBy(TimeframeGap)) {
      ChartTimeframe.entries.chunked(TIMEFRAME_COLUMNS).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(TimeframeGap)) {
          row.forEach { timeframe ->
            TimeframePill(timeframe, timeframe == selected, { onSelect(timeframe) }, Modifier.weight(1f))
          }
          repeat(TIMEFRAME_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

/** The indicator chips' pill: a hairline when off, filled lighter when chosen. */
@Composable
private fun TimeframePill(
  timeframe: ChartTimeframe,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val background by animateColorAsState(if (selected) FlareColors.Elevated else Color.Transparent)
  val border by animateColorAsState(if (selected) FlareColors.Elevated else FlareColors.BorderDefault)
  val foreground by animateColorAsState(if (selected) FlareColors.TextPrimary else FlareColors.TextSecondary)
  Box(
    modifier
      .height(48.dp)
      .clip(CircleShape)
      .background(background)
      .border(1.dp, border, CircleShape)
      .selectable(selected, role = Role.RadioButton, onClick = onClick)
      .semantics { contentDescription = timeframe.title },
    contentAlignment = Alignment.Center,
  ) {
    Text(timeframe.label, color = foreground, style = MaterialTheme.typography.labelLarge)
  }
}

private const val TIMEFRAME_COLUMNS = 4
private val TimeframeGap = 8.dp

/**
 * The panels for the indicators that are on, each labelled with its latest reading. They show the
 * stretch of history the price chart does, computed over all of it so the values stay right.
 */
@Composable
fun FlareIndicators(
  candles: List<Candle>,
  window: ChartWindow,
  showRsi: Boolean,
  showMacd: Boolean,
  modifier: Modifier = Modifier,
) {
  val closes = remember(candles) { candles.map(Candle::close) }
  val rsi = remember(closes) { TradingIndicators.rsi(closes) }
  val macd = remember(closes) { TradingIndicators.macd(closes) }
  val slots = remember(candles) { candleSlots(candles) }
  val visible = remember(slots, window.slots) {
    visibleCandleIndices(slots, window.slots, INITIAL_CANDLES)
  }
  Column(modifier.fillMaxWidth()) {
    IndicatorPanel(
      visible = showRsi,
      title = "RSI 14",
      reading = rsi.lastOrNull()?.let(::indicatorReading),
      description = "Relative strength index chart",
    ) {
      val points = visible.mapNotNull { index -> rsi.getOrNull(index)?.let { index to it } }
      if (points.isEmpty()) ChartPlaceholder("More candles required", Modifier.fillMaxSize())
      else LineIndicatorChart(points, FlareColors.IndicatorCyan, Modifier.fillMaxSize())
    }
    IndicatorPanel(
      visible = showMacd,
      title = "MACD 12 26 9",
      reading = macd.lastOrNull()?.histogram?.let(::indicatorReading),
      description = "Moving average convergence divergence chart",
    ) {
      if (macd.isEmpty()) ChartPlaceholder("More candles required", Modifier.fillMaxSize())
      else MacdIndicatorChart(macd.slice(visible), Modifier.fillMaxSize())
    }
  }
}

@Composable
private fun IndicatorPanel(
  visible: Boolean,
  title: String,
  reading: String?,
  description: String,
  chart: @Composable () -> Unit,
) {
  AnimatedVisibility(
    visible,
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut(),
  ) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = FlareColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
        Text(reading ?: "—", color = FlareColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
      }
      Box(
        Modifier.fullBleed().height(96.dp).padding(top = 4.dp).semantics {
          contentDescription = description
        }
      ) {
        chart()
      }
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
    animationSpec = null,
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
    animationSpec = null,
  )
}

@Composable
private fun flareLineLayer(
  colors: List<Color>,
  rangeProvider: CartesianLayerRangeProvider = CartesianLayerRangeProvider.Intrinsic,
  pointSpacing: Dp = 32.dp,
) =
  rememberLineCartesianLayer(
    rangeProvider = rangeProvider,
    pointSpacing = pointSpacing,
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
    line = null,
  )

@Composable
private fun hiddenBottomAxis() =
  HorizontalAxis.rememberBottom(
    label = null,
    tick = null,
    guideline = null,
    line = null,
  )

@Composable
private fun ChartPlaceholder(text: String, modifier: Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Text(text, color = FlareColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
  }
}

/** Two decimals for readings of 1 or more, four below that, so small MACD values don't read as 0. */
private fun indicatorReading(value: Double): String {
  val decimals = if (abs(value) >= 1.0) 2 else 4
  val factor = 10.0.pow(decimals).toLong()
  val scaled = kotlin.math.round(abs(value) * factor).toLong()
  val sign = if (value < 0 && scaled != 0L) "-" else ""
  return "$sign${scaled / factor}." + (scaled % factor).toString().padStart(decimals, '0')
}
