package xyz.mcxross.flare.feature.auto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.data.formatSignedBalance
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSegmentedControl
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.LocalVenue
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.design.fullBleed
import xyz.mcxross.flare.design.shortAddress
import xyz.mcxross.flare.strategies.AutoAmounts
import xyz.mcxross.flare.strategies.AutoRunMode
import xyz.mcxross.flare.strategies.StrategyCandle
import xyz.mcxross.flare.strategies.StrategyDirection
import xyz.mcxross.flare.strategies.StrategyKind
import xyz.mcxross.flare.venue.model.ChartTimeframe
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind

@Composable
fun AutoTradingRoute(
  market: Market,
  timeframeMs: Long,
  account: String?,
  onDismiss: () -> Unit,
  onOpenSetup: (() -> Unit)? = null,
  viewModel: AutoTradingViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(market, timeframeMs, account) { viewModel.onIntent(AutoTradingIntent.Bind(market, timeframeMs, account)) }
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(AutoTradingIntent.Pause) }
  DisposableEffect(viewModel) { onDispose { viewModel.onIntent(AutoTradingIntent.Pause) } }
  AutoTradingScreen(state, viewModel::onIntent, onDismiss, onOpenSetup)
}

@Composable
fun AutoTradingScreen(state: AutoTradingUiState, onIntent: (AutoTradingIntent) -> Unit, onDismiss: () -> Unit, onOpenSetup: (() -> Unit)? = null) {
  var limitsOpen by remember { mutableStateOf(false) }
  val market = state.market
  val symbol = market?.symbol ?: "Market"
  val quote = market?.quoteAsset ?: "USDC"
  val locked = state.active || state.starting
  val editable = !locked && state.startedAtMs == null && !state.needsReset
  val simulation = state.config.mode == AutoRunMode.SIMULATION
  val timeframe = ChartTimeframe.entries.firstOrNull { it.durationMs == state.timeframeMs }
  if (!limitsOpen && !state.reviewOpen) FlareSheet("Auto trading · $symbol", onDismiss, heightFraction = 0.96f) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (editable) {
        FlareSegmentedControl(AutoRunMode.entries, state.config.mode, { mode ->
          onIntent(AutoTradingIntent.Configure(state.config.copy(mode = mode,
            leverage = if (mode == AutoRunMode.SIMULATION) 1 else state.config.leverage)))
        }, { it.label() })
        FlareSegmentedControl(StrategyKind.entries, state.config.strategy,
          { onIntent(AutoTradingIntent.Configure(state.config.copy(strategy = it))) }, { it.shortLabel() })
      } else {
        RunSummary(state, timeframe)
      }
      Hero(state, symbol, quote)
      AutoChart(state.candles, state.markers, Modifier.fullBleed().height(180.dp))
      Text("${timeframe?.title?.replaceFirstChar(Char::uppercase) ?: "Chart timeframe"} candles · one decision per closed candle",
        color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
      state.confluence?.let { confluence ->
        StrategyDirection.entries.forEach { direction -> ProbabilityRow(direction, confluence.probabilities[direction] ?: 0.0) }
        Text("Jev choice probabilities. Confidence describes the model’s answer, not a chance of profit.",
          color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
      } ?: Text(
        when {
          !state.useJev -> "Independent strategy · Jev confluence off"
          else -> "Jev is asked only when the strategy proposes a trade; an unavailable or low-confidence answer vetoes it."
        },
        color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
      state.notice?.let { ActionNotice(it) }
      state.error?.let { ActionNotice(it) }
      if (!simulation && !locked) {
        state.realUnavailableReason?.let { reason ->
          Text(reason, style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
          if (reason.startsWith("Finish trading setup") && onOpenSetup != null) {
            ActionRow("Finish trading setup", "Simulation remains available", onClick = onOpenSetup)
          }
        }
      }
      Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Metric("Attempts", "${state.orders}/${state.config.maxOrders}", Modifier.weight(1f))
        Metric("Model calls", state.modelCalls.toString(), Modifier.weight(1f))
        Metric("Last latency", state.modelLatencyMs?.let { "$it ms" } ?: "—", Modifier.weight(1f))
      }
      DetailRow(if (simulation) "Filled order value" else "Reserved order value",
        "${amount(state.consumedNotional)} / ${state.config.maxTotalNotional} $quote")
      ActionRow("Run limits", "${state.config.orderSize} $symbol per order · ${state.config.durationMinutes} min · ${state.config.cooldownSeconds}s cooldown",
        enabled = editable, onClick = { limitsOpen = true })
      if (!locked && (state.startedAtMs != null || state.needsReset)) {
        ActionRow("Reset for a new run", "Clear counters, fills and decisions so the mode and limits can change",
          onClick = { onIntent(AutoTradingIntent.Reset) })
      }
      SectionLabel("Recent decisions")
      if (state.activity.isEmpty()) Text("Each closed candle adds a decision here once the run starts.",
        color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
      state.activity.take(20).forEach { event -> ActivityRow(event) }
      Text(
        if (simulation) "Simulation fills against the live order book at 1× leverage with a 5 bps fee. Funding, network fees and liquidation are excluded."
        else "Real trading sends orders with your funds. An opposite signal first reduces the open position with a reduce-only order.",
        color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
      Text("The run pauses when this screen closes or Flare leaves the foreground. Pause keeps positions open.",
        Modifier.padding(bottom = 12.dp), color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
    val action = when {
      locked -> "Pause"
      state.needsReset -> "Reset run"
      !simulation -> if (state.startedAtMs == null) "Review real trading" else "Review and resume"
      state.startedAtMs != null -> "Resume simulation"
      else -> "Start simulation"
    }
    FlareButton(action,
      { onIntent(when { locked -> AutoTradingIntent.Pause; state.needsReset -> AutoTradingIntent.Reset; else -> AutoTradingIntent.ReviewStart }) },
      Modifier.fillMaxWidth().padding(top = 12.dp),
      enabled = market != null && (state.active || (!state.starting && (state.needsReset || simulation || state.realUnavailableReason == null))),
      working = state.starting && !state.active)
  }
  if (limitsOpen && !locked) AutoLimitsSheet(state.config, symbol, quote,
    onSave = { onIntent(AutoTradingIntent.Configure(it)); limitsOpen = false }, onDismiss = { limitsOpen = false })
  if (state.reviewOpen) AutoRealReview(state, onIntent)
}

@Composable
private fun RunSummary(state: AutoTradingUiState, timeframe: ChartTimeframe?) {
  val ends = state.startedAtMs?.let { it + state.config.durationMinutes * 60_000L }
  Column(Modifier.fillMaxWidth().background(FlareColors.Surface, RoundedCornerShape(18.dp)).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("${state.config.mode.label()} · ${state.config.strategy.label} · ${timeframe?.label ?: "—"}",
      style = MaterialTheme.typography.titleMedium)
    Text(listOfNotNull(state.status, ends?.let { "ends ${clock(it, seconds = false)}" }).joinToString(" · "),
      style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
  }
}

@Composable
private fun Hero(state: AutoTradingUiState, symbol: String, quote: String) {
  val decision = state.decision
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Column(Modifier.weight(1f)) {
      Text(state.markPrice?.toDoubleOrNull()?.let(::formatPrice) ?: "—", style = MaterialTheme.typography.headlineLarge)
      Text(positionLabel(state.positionSize, symbol, state.market?.kind), style = MaterialTheme.typography.bodyMedium)
      val pnl = totalPnl(state.realizedPnl, state.unrealizedPnl)
      Text(
        "P&L ${pnl?.let { signedMoney(it, quote) } ?: "—"}",
        style = MaterialTheme.typography.bodyMedium,
        color = when {
          pnl == null || kotlin.math.abs(pnl) < 0.005 -> FlareColors.TextSecondary
          pnl > 0 -> FlareColors.Positive
          else -> FlareColors.Negative
        },
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(decision?.headline() ?: if (state.active) "Watching" else "Ready",
        style = MaterialTheme.typography.headlineLarge, color = decision?.color() ?: FlareColors.TextPrimary)
      val detail = listOfNotNull(
        decision?.latencyMs?.let { "$it ms" },
        decision?.confidence?.let { "conf ${formatQuantity(it, 2)}" },
        decision?.closedAtMs?.let { "candle ${clock(it, seconds = false)}" },
      ).joinToString(" · ")
      if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary, textAlign = TextAlign.End)
    }
  }
}

/** Closes of the latest candles, with a dot on each candle the run traded. */
@Composable
private fun AutoChart(candles: List<StrategyCandle>, markers: List<AutoMarker>, modifier: Modifier) {
  val visible = candles.takeLast(VISIBLE_CANDLES)
  if (visible.size < 2) {
    Box(modifier, contentAlignment = Alignment.Center) {
      Text("Waiting for candles", color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
    return
  }
  val rising = visible.last().close >= visible.first().close
  val line = if (rising) FlareColors.Positive else FlareColors.Negative
  val canvas = FlareColors.Canvas
  val positive = FlareColors.Positive
  val negative = FlareColors.Negative
  val buys = markers.count { it.direction == StrategyDirection.BUY }
  val sells = markers.count { it.direction == StrategyDirection.SELL }
  Canvas(modifier.padding(vertical = 12.dp).semantics {
    contentDescription = "Price line, $buys buys and $sells sells marked"
  }) {
    val low = visible.minOf { it.close }
    val high = visible.maxOf { it.close }
    val span = (high - low).takeIf { it > 0 } ?: (high * 0.001).coerceAtLeast(1e-9)
    val padded = span * 0.12
    val min = low - padded
    val max = high + padded
    val step = size.width / (VISIBLE_CANDLES - 1)
    val start = size.width - step * (visible.size - 1) - 8.dp.toPx()
    fun point(index: Int) = Offset(start + index * step, (size.height * (1 - (visible[index].close - min) / (max - min))).toFloat())
    val path = Path().apply {
      visible.indices.forEach { if (it == 0) moveTo(point(it).x, point(it).y) else lineTo(point(it).x, point(it).y) }
    }
    drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    val index = visible.withIndex().associate { it.value.closeTimeMs to it.index }
    markers.forEach { marker ->
      val at = index[marker.closedAtMs] ?: return@forEach
      val center = point(at)
      drawCircle(canvas, 6.dp.toPx(), center)
      drawCircle(if (marker.direction == StrategyDirection.BUY) positive else negative, 4.dp.toPx(), center)
    }
    drawCircle(line, 4.dp.toPx(), point(visible.lastIndex))
  }
}

@Composable
private fun ActivityRow(event: AutoActivity) {
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(clock(event.atMs, seconds = true), color = FlareColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
    Text(event.tag(), Modifier.width(44.dp), color = event.color(), style = MaterialTheme.typography.labelSmall)
    Text(event.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
  }
}

@Composable
private fun AutoRealReview(state: AutoTradingUiState, onIntent: (AutoTradingIntent) -> Unit) {
  val venue = LocalVenue.current
  val config = state.config
  val symbol = state.market?.symbol ?: "—"
  val quote = state.market?.quoteAsset ?: "USDC"
  FlareSheet("Enable real auto trading", { onIntent(AutoTradingIntent.DismissReview) }, heightFraction = 0.96f) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      ActionNotice("Orders will be sent with your funds without asking each time. Confirm transactions in Settings turns off for this run and comes back when it pauses, ends or Flare leaves the foreground.")
      DetailRow("Market", state.market?.displayName ?: symbol)
      DetailRow("Network", "${venue.displayName} ${venue.network.name.lowercase()}")
      DetailRow("Account", state.account?.let(::shortAddress) ?: "Unavailable")
      DetailRow("Strategy", config.strategy.label)
      DetailRow("Candle timeframe", ChartTimeframe.entries.firstOrNull { it.durationMs == state.timeframeMs }?.title ?: "Unavailable")
      DetailRow("Order size", "${config.orderSize} $symbol")
      DetailRow("Maximum position", "${config.maxPositionSize} $symbol")
      DetailRow("Order-value budget", "${config.maxTotalNotional} $quote")
      DetailRow("Maximum attempts", config.maxOrders.toString())
      DetailRow("Duration", "${config.durationMinutes} minutes")
      DetailRow("Cooldown", "${config.cooldownSeconds} seconds")
      DetailRow("Slippage", "${config.slippageBps} bps")
      if (state.market?.kind != MarketKind.SPOT) DetailRow("Leverage", "${config.leverage}×")
      DetailRow("Jev confluence", if (state.useJev) "On · minimum ${formatQuantity(config.minConfidence * 100, 1)}% confidence" else "Off · independent strategy")
      Text("Each attempt reserves its worst-case order value before it’s sent. A pending or uncertain order stays counted and is never resent. Pause stops new orders and leaves positions open.",
        color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
      state.error?.let { ActionNotice(it) }
    }
    FlareButton("Authorize and start", { onIntent(AutoTradingIntent.ConfirmStart) },
      Modifier.fillMaxWidth().padding(top = 12.dp), enabled = state.account != null && state.realUnavailableReason == null, working = state.starting)
  }
}

@Composable
private fun ProbabilityRow(direction: StrategyDirection, probability: Double) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(direction.label(), Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, color = direction.color())
    Box(Modifier.weight(1f).height(8.dp).background(FlareColors.Surface, CircleShape)) {
      Box(Modifier.fillMaxWidth(probability.toFloat().coerceIn(0f, 1f)).height(8.dp).background(direction.color(), CircleShape))
    }
    Text("${formatQuantity(probability * 100, 0)}%", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
  }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
  Column(modifier) {
    Text(value, style = MaterialTheme.typography.titleMedium)
    Text(label, color = FlareColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
  }
}

private const val VISIBLE_CANDLES = 60

private fun positionLabel(size: String, symbol: String, kind: MarketKind?): String {
  val value = size.toDoubleOrNull() ?: return "Position —"
  val amount = size.trim().removePrefix("-")
  return when {
    value == 0.0 -> "Flat"
    kind == MarketKind.SPOT -> "Holding $amount $symbol"
    value > 0 -> "Long $amount $symbol"
    else -> "Short $amount $symbol"
  }
}

private fun totalPnl(realized: String?, unrealized: String?): Double? {
  if (realized == null || unrealized == null) return null
  return runCatching { AutoAmounts.add(realized, unrealized).toDouble() }.getOrNull()
    ?: (realized.toDoubleOrNull() ?: return null) + (unrealized.toDoubleOrNull() ?: return null)
}

private fun signedMoney(value: Double, quote: String): String =
  if (quote == "USDC" || quote == "USD") formatSignedBalance(value)
  else (if (value > 0) "+" else if (value < 0) "−" else "") + "${formatQuantity(kotlin.math.abs(value), 4)} $quote"

private fun amount(value: String) = value.toDoubleOrNull()?.let { formatQuantity(it, 2) } ?: value

private fun clock(ms: Long, seconds: Boolean): String {
  val local = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
  fun two(value: Int) = value.toString().padStart(2, '0')
  return "${two(local.hour)}:${two(local.minute)}" + if (seconds) ":${two(local.second)}" else ""
}

private fun AutoDecision.headline() = when (outcome) {
  AutoOutcome.ACTED -> if (direction == StrategyDirection.BUY) "Bought" else "Sold"
  AutoOutcome.WAITING -> "Waiting"
  AutoOutcome.VETOED -> "Vetoed"
  AutoOutcome.LATE -> "Late"
  AutoOutcome.SKIPPED -> "Skipped"
}

private fun AutoDecision.color(): Color = when (outcome) {
  AutoOutcome.ACTED -> direction.color()
  AutoOutcome.LATE -> FlareColors.Warning
  else -> FlareColors.TextSecondary
}

private fun AutoActivity.tag() = when (outcome) {
  AutoOutcome.ACTED -> direction.label().uppercase()
  AutoOutcome.WAITING -> "WAIT"
  AutoOutcome.VETOED -> "VETO"
  AutoOutcome.LATE -> "LATE"
  AutoOutcome.SKIPPED -> "SKIP"
}

private fun AutoActivity.color(): Color = when (outcome) {
  AutoOutcome.ACTED -> direction.color()
  AutoOutcome.LATE -> FlareColors.Warning
  else -> FlareColors.TextTertiary
}

private fun AutoRunMode.label() = if (this == AutoRunMode.SIMULATION) "Simulation" else "Real"
private fun StrategyKind.shortLabel() = when (this) { StrategyKind.MACD_CROSSOVER -> "MACD"; StrategyKind.RSI_REVERSAL -> "RSI"; StrategyKind.MOMENTUM_PULLBACK -> "Pullback" }
private fun StrategyDirection.label() = when (this) { StrategyDirection.BUY -> "Buy"; StrategyDirection.SELL -> "Sell"; StrategyDirection.WAIT -> "Wait" }
private fun StrategyDirection.color(): Color = when (this) { StrategyDirection.BUY -> FlareColors.Positive; StrategyDirection.SELL -> FlareColors.Negative; StrategyDirection.WAIT -> FlareColors.TextSecondary }
