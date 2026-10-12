package xyz.mcxross.flare.feature.strategies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.security.JevCredentials
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.strategies.ConfluenceEvaluation
import xyz.mcxross.flare.strategies.DecisionModel
import xyz.mcxross.flare.strategies.JevConfluence
import xyz.mcxross.flare.strategies.StrategyEngine
import xyz.mcxross.flare.strategies.StrategyEvaluation
import xyz.mcxross.flare.strategies.StrategyKind
import xyz.mcxross.flare.strategies.StrategyMarketFeed
import xyz.mcxross.flare.strategies.StrategyMarketKey
import xyz.mcxross.flare.strategies.StrategySignal
import xyz.mcxross.flare.venue.VenueDescriptor

data class StrategiesUiState(
  val key: StrategyMarketKey? = null,
  val selected: StrategyKind = StrategyKind.MACD_CROSSOVER,
  val active: Boolean = false,
  val working: Boolean = false,
  val useJev: Boolean = false,
  val keyConfigured: Boolean = false,
  val baseline: StrategyEvaluation? = null,
  val confluence: ConfluenceEvaluation? = null,
  val confluenceError: String? = null,
  val error: String? = null,
) {
  val signal: StrategySignal? get() = baseline?.signals?.firstOrNull { it.kind == selected }
}

sealed interface StrategiesIntent {
  data class Bind(val marketId: String, val timeframeMs: Long, val account: String?) : StrategiesIntent
  data class Select(val strategy: StrategyKind) : StrategiesIntent
  data class UseJev(val enabled: Boolean) : StrategiesIntent
  data object Resume : StrategiesIntent
  data object Pause : StrategiesIntent
  data object Evaluate : StrategiesIntent
}

/** Read-only signals. No trading repository or signing authority crosses this boundary. */
class StrategiesViewModel(
  private val feed: StrategyMarketFeed,
  private val preferences: AppPreferences,
  private val credentials: JevCredentials,
  wallets: WalletRepository,
  private val model: DecisionModel,
  private val descriptor: VenueDescriptor,
  private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {
  private val mutableState = MutableStateFlow(StrategiesUiState())
  val uiState = mutableState.asStateFlow()
  private var generation = 0L
  private var request: Job? = null
  private var expiry: Job? = null
  private var account: String? = null
  private val engine = StrategyEngine()

  init {
    viewModelScope.launch {
      preferences.values.map { it.selectedStrategy }.distinctUntilChanged().collectLatest { name ->
        val strategy = StrategyKind.entries.firstOrNull { it.name == name } ?: StrategyKind.MACD_CROSSOVER
        mutableState.update { it.copy(selected = strategy) }
      }
    }
    viewModelScope.launch {
      credentials.metadata.distinctUntilChanged().collectLatest { metadata ->
        invalidate()
        mutableState.update { it.copy(useJev = metadata.enabled, keyConfigured = metadata.configured) }
      }
    }
    viewModelScope.launch { wallets.profile.distinctUntilChanged().collectLatest { invalidate() } }
    viewModelScope.launch {
      preferences.values.map { it.activeProfileId to it.selectedSubaccount }.distinctUntilChanged().collectLatest { invalidate() }
    }
  }

  fun onIntent(intent: StrategiesIntent) {
    when (intent) {
      is StrategiesIntent.Bind -> {
        val key = StrategyMarketKey(descriptor.id, descriptor.network.name.lowercase(), intent.marketId, intent.timeframeMs)
        if (key != uiState.value.key || account != intent.account) {
          invalidate()
          account = intent.account
          mutableState.update { it.copy(key = key) }
        }
      }
      is StrategiesIntent.Select -> viewModelScope.launch { preferences.setSelectedStrategy(intent.strategy.name) }
      is StrategiesIntent.UseJev -> viewModelScope.launch { credentials.setEnabled(intent.enabled) }
      StrategiesIntent.Resume -> mutableState.update { it.copy(active = true) }
      StrategiesIntent.Pause -> { invalidate(); mutableState.update { it.copy(active = false) } }
      StrategiesIntent.Evaluate -> evaluate()
    }
  }

  private fun invalidate() {
    generation++
    request?.cancel()
    request = null
    expiry?.cancel()
    expiry = null
    mutableState.update { it.copy(working = false, baseline = null, confluence = null, confluenceError = null, error = null) }
  }

  private fun evaluate() {
    val initial = uiState.value
    val key = initial.key ?: return
    if (!initial.active || initial.working) return
    invalidate()
    val expected = generation
    mutableState.update { it.copy(working = true) }
    request = viewModelScope.launch {
      val result = runSuspendCatching {
        val snapshot = feed.snapshot(key)
        require(snapshot.key == key) { "Market snapshot does not match this request" }
        snapshot to engine.evaluate(snapshot, nowMs())
      }
      if (expected != generation || !uiState.value.active) return@launch
      val (snapshot, baseline) = result.getOrNull() ?: run {
        mutableState.update { it.copy(working = false, error = "Market data unavailable. Refresh to try again.") }
        return@launch
      }
      mutableState.update { it.copy(baseline = baseline) }
      if (baseline.rejection == null) {
        val expiresAt = minOf(snapshot.fetchedAtMs + 60_000, baseline.closedAtMs!! + key.timeframeMs)
        expiry = viewModelScope.launch {
          delay((expiresAt - nowMs() + 1).coerceAtLeast(1))
          if (expected == generation) {
            invalidate()
            mutableState.update { it.copy(error = "Signal data expired. Refresh to evaluate again.") }
          }
        }
      }
      if (initial.useJev && baseline.rejection == null) {
        val confluence = runSuspendCatching { JevConfluence.evaluate(baseline, model) }
        if (expected != generation || !uiState.value.active) return@launch
        // The model request may outlive the snapshot's freshness window.
        if (nowMs() - snapshot.fetchedAtMs > 60_000 || nowMs() - baseline.closedAtMs!! > key.timeframeMs) {
          mutableState.update { it.copy(working = false, baseline = null, error = "Signal data expired. Refresh to evaluate again.") }
          return@launch
        }
        mutableState.update {
          it.copy(confluence = confluence.getOrNull(),
            confluenceError = if (confluence.isFailure) "Jev unavailable. Baseline signals remain independent." else null)
        }
      }
      mutableState.update { it.copy(working = false) }
    }
  }
}
