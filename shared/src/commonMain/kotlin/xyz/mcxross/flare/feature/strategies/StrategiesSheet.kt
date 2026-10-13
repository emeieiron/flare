package xyz.mcxross.flare.feature.strategies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.time.Instant
import kotlinx.coroutines.awaitCancellation
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.SwitchRow
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.strategies.JevConfluence
import xyz.mcxross.flare.strategies.StrategyKind
import xyz.mcxross.flare.strategies.StrategyDirection

@Composable
fun StrategiesRoute(
  marketId: String,
  symbol: String,
  timeframeMs: Long,
  account: String?,
  onDismiss: () -> Unit,
  onOpenAuto: (() -> Unit)? = null,
  viewModel: StrategiesViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current
  LaunchedEffect(marketId, timeframeMs, account) {
    viewModel.onIntent(StrategiesIntent.Bind(marketId, timeframeMs, account))
  }
  LaunchedEffect(lifecycle, viewModel) {
    lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      viewModel.onIntent(StrategiesIntent.Resume)
      try { awaitCancellation() } finally { viewModel.onIntent(StrategiesIntent.Pause) }
    }
  }
  StrategiesSheet(symbol, state, viewModel::onIntent, onDismiss, onOpenAuto)
}

@Composable
fun StrategiesSheet(
  symbol: String,
  state: StrategiesUiState,
  onIntent: (StrategiesIntent) -> Unit,
  onDismiss: () -> Unit,
  onOpenAuto: (() -> Unit)? = null,
) {
  FlareSheet("Strategies · $symbol", onDismiss, heightFraction = 0.85f) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StrategyKind.entries.forEach { kind ->
          FlareChip(kind.shortLabel(), kind == state.selected,
            { onIntent(StrategiesIntent.Select(kind)) }, Modifier.weight(1f))
        }
      }
      DetailRow(state.selected.label, state.signal?.direction?.label() ?: "Not evaluated")
      DetailRow("Jev confluence", when {
        !state.useJev -> "Off"
        state.confluence != null -> "${state.confluence.direction.label()} · ${formatQuantity(state.confluence.confidence * 100, 1)}%"
        state.working && state.baseline != null -> "Assessing…"
        state.baseline == null -> "Not evaluated"
        else -> "Unavailable"
      })
      if (state.useJev && state.signal != null && state.confluence != null) {
        DetailRow("With confluence", JevConfluence.applyGate(state.signal!!, state.confluence, enabled = true).label())
      }
      Text("Closed-candle signals for the current chart timeframe. You decide whether to trade.",
        color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
      SwitchRow("Use Jev confluence",
        if (state.keyConfigured) "Optional agreement across indicators" else "Add your API key in Settings to enable",
        state.useJev, { onIntent(StrategiesIntent.UseJev(it)) },
        enabled = state.keyConfigured || state.useJev)
      if (state.useJev) {
        Text("Indicator requests go directly to Jev and may incur charges on your account.",
          color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
      }
      onOpenAuto?.let { ActionRow("Auto trading", "Simulation or real trading with run limits", onClick = it) }
      state.error?.let { ActionNotice(it) }
      state.baseline?.let { baseline ->
        SectionLabel("Strategy signals")
        baseline.closedAtMs?.let {
          DetailRow("Last candle close", Instant.fromEpochMilliseconds(it).toString().replace('T', ' ').substringBefore('.').removeSuffix("Z") + " UTC")
        }
        baseline.rejection?.let { ActionNotice(it) }
        baseline.signals.forEach { signal ->
          DetailRow(signal.kind.label, signal.direction.label())
          Text(signal.reason, color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        SectionLabel("Indicator evidence")
        val evidence = mapOf("rsi" to "RSI (14)", "macd" to "MACD", "macdSignal" to "MACD signal",
          "macdHistogram" to "MACD histogram", "sma20" to "20-candle trend", "volume" to "Candle volume")
        evidence.forEach { (name, label) -> baseline.features[name]?.let { DetailRow(label, formatQuantity(it, 4)) } }
        baseline.features["return1"]?.let { DetailRow("Latest return", "${formatQuantity(it * 100, 2)}%") }
        DetailRow("Order book", if (baseline.features["bookFresh"] == 1.0) "Available" else "Unavailable")
        baseline.features["spreadFraction"]?.let { DetailRow("Spread", "${formatQuantity(it * 100, 3)}%") }
        baseline.features["depthImbalance10"]?.let { DetailRow("Book imbalance", formatQuantity(it, 3)) }
        if (state.useJev) {
          SectionLabel("Jev confluence")
          state.confluence?.let { model ->
            model.probabilities.forEach { (direction, probability) ->
              DetailRow("${direction.label()} choice", "${formatQuantity(probability * 100, 1)}%")
            }
            Text("Agreement requires matching direction and at least 60% confidence. Choice probabilities describe model output, not trade success rates.",
              color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
          } ?: Text(state.confluenceError ?: if (state.working) "Assessing confluence…" else "Confluence unavailable. Strategy signals have no model approval.",
            color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
          Text("Jev is off. These signals use deterministic indicators without a model or API key.",
            color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
      }
      Text("Signals do not place orders. Use the market's Trade button to enter an amount and review an order.",
        Modifier.padding(vertical = 16.dp), color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
    FlareButton(if (state.baseline == null) "Evaluate signals" else "Refresh signals",
      { onIntent(StrategiesIntent.Evaluate) }, Modifier.fillMaxWidth().padding(top = 12.dp),
      enabled = state.active && state.key != null && !state.working, working = state.working)
  }
}

private fun StrategyDirection.label(): String = when (this) {
  StrategyDirection.BUY -> "Buy"
  StrategyDirection.SELL -> "Sell"
  StrategyDirection.WAIT -> "Wait"
}

private fun StrategyKind.shortLabel(): String = when (this) {
  StrategyKind.MACD_CROSSOVER -> "MACD"
  StrategyKind.RSI_REVERSAL -> "RSI"
  StrategyKind.MOMENTUM_PULLBACK -> "Pullback"
}
