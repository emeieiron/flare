package xyz.mcxross.flare.feature.auto

import androidx.lifecycle.ViewModel
import xyz.mcxross.flare.strategies.AutoRunConfig
import xyz.mcxross.flare.strategies.AutoRunMode
import xyz.mcxross.flare.strategies.ConfluenceEvaluation
import xyz.mcxross.flare.strategies.PaperState
import xyz.mcxross.flare.strategies.StrategyCandle
import xyz.mcxross.flare.strategies.StrategyDirection
import xyz.mcxross.flare.strategies.StrategyEvaluation
import xyz.mcxross.flare.strategies.StrategyKind
import xyz.mcxross.flare.venue.model.Market

enum class AutoOutcome { ACTED, WAITING, VETOED, LATE, SKIPPED }

data class AutoActivity(
  val atMs: Long,
  val closedAtMs: Long,
  val message: String,
  val direction: StrategyDirection,
  val outcome: AutoOutcome,
)

/** The decision for the latest closed candle. Confidence is the model's, not a chance of profit. */
data class AutoDecision(
  val closedAtMs: Long,
  val direction: StrategyDirection,
  val outcome: AutoOutcome,
  val confidence: Double? = null,
  val latencyMs: Long? = null,
)

/** A fill on the candle that closed at [closedAtMs]. */
data class AutoMarker(val closedAtMs: Long, val direction: StrategyDirection)

data class AutoTradingUiState(
  val config: AutoRunConfig = AutoRunConfig(StrategyKind.MACD_CROSSOVER, AutoRunMode.SIMULATION, "0.001", "0.01", "100", 10, 30, 30, 50, 1),
  val market: Market? = null,
  val timeframeMs: Long = 60_000,
  val account: String? = null,
  val active: Boolean = false,
  val starting: Boolean = false,
  val reviewOpen: Boolean = false,
  val status: String = "Ready",
  val notice: String? = null,
  val error: String? = null,
  val candles: List<StrategyCandle> = emptyList(),
  val markPrice: String? = null,
  val baseline: StrategyEvaluation? = null,
  val confluence: ConfluenceEvaluation? = null,
  val decision: AutoDecision? = null,
  val markers: List<AutoMarker> = emptyList(),
  val useJev: Boolean = false,
  val keyConfigured: Boolean = false,
  val modelCalls: Int = 0,
  val modelLatencyMs: Long? = null,
  val orders: Int = 0,
  val consumedNotional: String = "0",
  val positionSize: String = "0",
  val realizedPnl: String? = "0",
  val unrealizedPnl: String? = "0",
  val paper: PaperState? = null,
  val activity: List<AutoActivity> = emptyList(),
  val startedAtMs: Long? = null,
  val needsReset: Boolean = false,
  val realUnavailableReason: String? = "Finish trading setup before starting real auto trading.",
)

sealed interface AutoTradingIntent {
  data class Bind(val market: Market, val timeframeMs: Long, val account: String?) : AutoTradingIntent
  data class Configure(val config: AutoRunConfig) : AutoTradingIntent
  data object ReviewStart : AutoTradingIntent
  data object ConfirmStart : AutoTradingIntent
  data object DismissReview : AutoTradingIntent
  data object Pause : AutoTradingIntent
  data object Reset : AutoTradingIntent
}

class AutoTradingViewModel(private val runtime: AutoTradingRuntime) : ViewModel() {
  val uiState = runtime.uiState
  fun onIntent(intent: AutoTradingIntent) = runtime.onIntent(intent)
}
