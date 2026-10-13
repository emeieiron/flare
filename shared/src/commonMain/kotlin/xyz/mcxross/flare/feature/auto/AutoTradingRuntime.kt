package xyz.mcxross.flare.feature.auto

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSetupGate
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.security.JevCredentialMetadata
import xyz.mcxross.flare.security.JevCredentials
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.strategies.AutoAmounts
import xyz.mcxross.flare.strategies.AutoRisk
import xyz.mcxross.flare.strategies.AutoRunConfig
import xyz.mcxross.flare.strategies.AutoRunMode
import xyz.mcxross.flare.strategies.ConfluenceEvaluation
import xyz.mcxross.flare.strategies.DecisionModel
import xyz.mcxross.flare.strategies.JevConfluence
import xyz.mcxross.flare.strategies.PaperSimulator
import xyz.mcxross.flare.strategies.PaperState
import xyz.mcxross.flare.strategies.StrategyBook
import xyz.mcxross.flare.strategies.StrategyDirection
import xyz.mcxross.flare.strategies.StrategyEngine
import xyz.mcxross.flare.strategies.StrategyMarketFeed
import xyz.mcxross.flare.strategies.StrategyMarketKey
import xyz.mcxross.flare.strategies.StrategyMarketSnapshot
import xyz.mcxross.flare.strategies.closedCandles
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.model.ActionKind
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarginMode
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderBookLevel
import xyz.mcxross.flare.venue.model.OrderCheck
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TradeAction

/**
 * Runs one strategy on one market while the app is in the foreground, acting at most once per newly
 * closed candle. A run's counters, reservations and candle history survive Pause; only Reset clears
 * them, and Reset waits until no submission is pending.
 */
class AutoTradingRuntime(
  private val feed: StrategyMarketFeed,
  private val preferences: AppPreferences,
  private val credentials: JevCredentials,
  private val wallets: WalletRepository,
  private val accounts: AccountRepository,
  private val trading: TradingRepository,
  private val model: DecisionModel,
  private val descriptor: VenueDescriptor,
  private val setup: AccountSetupGate,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
  private val pollIntervalMs: Long = 5_000,
  private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
  private val mutable = MutableStateFlow(AutoTradingUiState())
  val uiState = mutable.asStateFlow()
  private val engine = StrategyEngine()
  private val simulator = PaperSimulator()
  private var run: Job? = null
  private var generation = 0L
  private var lastClosed: Long? = null
  private var lastAttemptAt: Long? = null
  private var reviewedGeneration: Long? = null
  private var metadata: JevCredentialMetadata? = null
  private var boundProfile: Pair<String, String?>? = null
  private var overrideToken: String? = null

  init {
    require(pollIntervalMs > 0)
    scope.launch {
      credentials.metadata.distinctUntilChanged().collect { value ->
        if (metadata != null && value != metadata) invalidate("Jev settings changed")
        metadata = value
        mutable.update { it.copy(useJev = value.enabled, keyConfigured = value.configured) }
      }
    }
    scope.launch {
      preferences.values.map { it.activeProfileId to it.selectedSubaccount }.distinctUntilChanged().collect {
        if (boundProfile != null && boundProfile != it) invalidate("Account changed")
        boundProfile = it
      }
    }
    scope.launch {
      combine(preferences.values, wallets.profile, setup.need, accounts.snapshot) { config, wallet, need, account ->
        when {
          config.selectedSubaccount == null || wallet.apiWalletAddress == null || need != null ->
            "Finish trading setup before starting real auto trading."
          account.stale || account.account != config.selectedSubaccount ->
            "Reconnect your trading account before starting."
          else -> descriptor.capabilities.unavailableReason(ActionKind.TRADING)
        }
      }.distinctUntilChanged().collect { reason -> mutable.update { it.copy(realUnavailableReason = reason) } }
    }
    scope.launch {
      var previous = wallets.profile.first()
      wallets.profile.distinctUntilChanged().collect {
        if (it != previous) invalidate("Wallet changed")
        previous = it
      }
    }
  }

  fun onIntent(intent: AutoTradingIntent) {
    when (intent) {
      is AutoTradingIntent.Bind -> bind(intent.market, intent.timeframeMs, intent.account)
      is AutoTradingIntent.Configure -> configure(intent.config)
      AutoTradingIntent.ReviewStart -> {
        if (busy()) return
        val validation = validateStart()
        if (validation != null) mutable.update { it.copy(error = validation) }
        else if (mutable.value.config.mode == AutoRunMode.REAL) {
          reviewedGeneration = generation
          mutable.update { it.copy(reviewOpen = true, error = null) }
        } else start()
      }
      AutoTradingIntent.ConfirmStart -> if (mutable.value.reviewOpen && reviewedGeneration == generation) start()
      AutoTradingIntent.DismissReview -> {
        reviewedGeneration = null
        mutable.update { it.copy(reviewOpen = false) }
      }
      AutoTradingIntent.Pause -> pause()
      AutoTradingIntent.Reset -> reset()
    }
  }

  /** Stops new orders. Positions, submitted actions and the run's counters stay as they are. */
  fun pause(reason: String = "Paused") {
    generation++
    reviewedGeneration = null
    val token = overrideToken
    token?.let(preferences::releaseRealAutoTrading)
    if (run?.isCompleted != false) {
      mutable.update { it.copy(reviewOpen = false) }
      return
    }
    run?.cancel()
    mutable.update {
      it.copy(active = false, starting = true, reviewOpen = false, status = reason,
        notice = if (token != null) CONFIRMATION_RESTORED else it.notice)
    }
  }

  fun close() {
    pause("Closed")
    scope.cancel()
  }

  private fun busy() = mutable.value.active || mutable.value.starting || run?.isCompleted == false

  private fun hasRun() = mutable.value.startedAtMs != null

  private fun bind(market: Market, timeframeMs: Long, account: String?) {
    val state = mutable.value
    val changed = state.market?.id != market.id || state.timeframeMs != timeframeMs ||
      (state.config.mode == AutoRunMode.REAL && state.account != account)
    if (changed) {
      if (hasRun() || busy()) invalidate("Market or account changed")
      mutable.update {
        it.copy(market = market, timeframeMs = timeframeMs, account = account, candles = emptyList(),
          markPrice = null, baseline = null, confluence = null, decision = null, markers = emptyList())
      }
    } else mutable.update { it.copy(market = market, account = account) }
  }

  private fun configure(config: AutoRunConfig) {
    if (busy()) return
    if (hasRun()) {
      mutable.update { it.copy(error = "Reset the run before changing its mode, strategy or limits.") }
      return
    }
    generation++
    reviewedGeneration = null
    val real = config.mode == AutoRunMode.REAL
    mutable.update {
      it.copy(config = config, reviewOpen = false, error = null,
        realizedPnl = if (real) null else "0", unrealizedPnl = if (real) null else "0")
    }
  }

  private fun reset() {
    if (busy()) return
    generation++
    val expected = generation
    reviewedGeneration = null
    mutable.update { it.copy(starting = true, status = "Checking previous run") }
    run = scope.launch {
      try {
        if (mutable.value.config.mode == AutoRunMode.REAL) {
          stopRequire(trading.reconcilePending().unresolved == 0) { PENDING_RESET }
        }
        ensure(expected)
        lastClosed = null
        lastAttemptAt = null
        mutable.update {
          AutoTradingUiState(config = it.config, market = it.market, timeframeMs = it.timeframeMs,
            account = it.account, useJev = it.useJev, keyConfigured = it.keyConfigured,
            realUnavailableReason = it.realUnavailableReason, candles = it.candles, markPrice = it.markPrice,
            realizedPnl = if (it.config.mode == AutoRunMode.REAL) null else "0",
            unrealizedPnl = if (it.config.mode == AutoRunMode.REAL) null else "0")
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (generation == expected) mutable.update { it.copy(status = "Ended", error = PENDING_RESET) }
      } finally {
        mutable.update { it.copy(starting = false) }
      }
    }
  }

  private fun invalidate(reason: String) {
    val hadRun = hasRun()
    pause(reason)
    if (hadRun) mutable.update { it.copy(needsReset = true, status = reason) }
  }

  private fun validateStart(): String? {
    val state = mutable.value
    if (state.market == null || state.timeframeMs <= 0) return "Select a market and timeframe."
    if (state.needsReset) return "This run has ended. Reset to start a new one."
    AutoRisk.validate(state.config)?.let { return it }
    if (state.config.leverage > state.market.maxLeverage) return "Leverage exceeds this market’s limit."
    if (state.config.mode == AutoRunMode.REAL && state.account.isNullOrBlank()) return "Complete trading account setup first."
    return null
  }

  private class RunContext(
    val expected: Long,
    val authorization: Long,
    val profile: Pair<String, String?>,
    val revision: Long,
    val real: Boolean,
    val key: StrategyMarketKey,
    val market: Market,
    val config: AutoRunConfig,
    val useJev: Boolean,
  )

  private fun start() {
    if (busy()) return
    validateStart()?.let { error -> mutable.update { it.copy(error = error) }; return }
    generation++
    val expected = generation
    val initial = mutable.value
    val market = initial.market!!
    val config = initial.config
    val real = config.mode == AutoRunMode.REAL
    val key = StrategyMarketKey(descriptor.id, descriptor.network.name.lowercase(), market.id, initial.timeframeMs)
    mutable.update { it.copy(starting = true, reviewOpen = false, error = null, notice = null, status = "Starting") }
    run = scope.launch {
      var token: String? = null
      try {
        val jev = credentials.metadata.first()
        val profile = preferences.values.first().let { it.activeProfileId to it.selectedSubaccount }
        val authorization = wallets.authorizationGeneration
        if (real) {
          stopRequire(descriptor.capabilities.unavailableReason(ActionKind.TRADING) == null) { "Orders are unavailable here." }
          stopRequire(wallets.profile.first().apiWalletAddress != null) { "Finish trading setup on this device." }
          stopRequire(setup.need.first() == null) { "Complete account setup before starting." }
          stopRequire(profile.second == initial.account) { "The selected account changed." }
          stopRequire(trading.reconcilePending().unresolved == 0) { PENDING_START }
          preflight(market, config, key)
          ensure(expected)
          wallets.confirmIdentity(VaultPrompt("Start real auto trading", "Authorize this foreground run", requireFreshAuthorization = true))
          ensure(expected)
          wallets.requireAuthorization(authorization)
          preferences.recoverRealAutoTrading()
          token = preferences.beginRealAutoTrading()
          overrideToken = token
          ensure(expected)
        }
        val context = RunContext(expected, authorization, profile, jev.revision, real, key, market, config, jev.enabled)
        val started = initial.startedAtMs ?: nowMs()
        val remaining = started + config.durationMinutes * 60_000L - nowMs()
        stopRequire(remaining > 0, endsRun = true) { DURATION_REACHED }
        mutable.update {
          it.copy(active = true, starting = false, startedAtMs = started, status = "Running",
            notice = if (real) CONFIRMATION_OFF else null,
            paper = if (real) null else it.paper ?: PaperState())
        }
        withTimeout(remaining) {
          while (true) {
            poll(context)
            if (mutable.value.orders >= config.maxOrders) throw AutoStop(ATTEMPTS_REACHED, endsRun = true)
            delay(pollIntervalMs)
          }
        }
      } catch (cancelled: CancellationException) {
        if (generation == expected) {
          mutable.update { it.copy(status = "Ended", error = if (cancelled is TimeoutCancellationException) DURATION_REACHED else null, needsReset = true) }
        }
        if (cancelled !is TimeoutCancellationException) throw cancelled
      } catch (error: Exception) {
        if (generation == expected) {
          val stop = error as? AutoStop
          mutable.update {
            it.copy(status = if (stop?.endsRun == true) "Ended" else "Stopped",
              error = stop?.message ?: "Run stopped. Check your connection and transaction status, then resume.",
              needsReset = it.needsReset || stop?.endsRun == true)
          }
        }
      } finally {
        withContext(NonCancellable) {
          try {
            token?.let {
              preferences.endRealAutoTrading(it)
              if (generation == expected) mutable.update { state -> state.copy(notice = CONFIRMATION_RESTORED) }
            }
          } catch (_: Exception) {
            // The saved run marker is restored on the next launch; confirmations already read as restored.
          } finally {
            if (overrideToken == token) overrideToken = null
            mutable.update { it.copy(active = if (generation == expected) false else it.active, starting = false) }
          }
        }
      }
    }
  }

  /** Real orders must pass the market's rules before the run asks for authorization. */
  private suspend fun preflight(market: Market, config: AutoRunConfig, key: StrategyMarketKey) {
    val snapshot = try {
      feed.snapshot(key)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      throw AutoStop("Market data is unavailable. Try again in a moment.")
    }
    val book = snapshot.book ?: throw AutoStop("A live order book is required for real auto trading.")
    for (side in OrderSide.entries) {
      val check = market.rules.check(OrderRequest(market.id, side, OrderType.MARKET, config.orderSize, slippageBps = config.slippageBps), book.toOrderBook(market.id))
      if (check is OrderCheck.Invalid) throw AutoStop("Order size isn’t accepted: ${check.errors.firstOrNull() ?: "check size and slippage"}.")
    }
  }

  private suspend fun poll(context: RunContext) {
    ensureBoundary(context)
    val snapshot = try {
      feed.snapshot(context.key)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      mutable.update { it.copy(status = "Waiting for market data") }
      return
    }
    ensureBoundary(context)
    stopRequire(snapshot.key == context.key) { "Market data does not match this run." }
    updateMarket(snapshot, context.expected)
    val latestClosed = snapshot.closedCandles().maxOfOrNull { it.closeTimeMs }
    if (latestClosed == null || lastClosed?.let { latestClosed <= it } == true) {
      mutable.update { it.copy(status = "Running") }
      return
    }
    // Claimed before any work, so a candle is decided at most once, even across Pause and Resume.
    lastClosed = latestClosed
    val baseline = engine.evaluate(snapshot, nowMs())
    ensure(context.expected)
    mutable.update { it.copy(baseline = baseline, confluence = null, status = "Running") }
    if (baseline.rejection != null || baseline.closedAtMs != latestClosed) {
      decide(latestClosed, StrategyDirection.WAIT, AutoOutcome.SKIPPED, "Skipped · ${baseline.rejection ?: "candle data changed"}")
      return
    }
    val signal = baseline.signals.first { it.kind == context.config.strategy }
    var direction = signal.direction
    var confluence: ConfluenceEvaluation? = null
    var latency: Long? = null
    if (context.useJev && direction != StrategyDirection.WAIT) {
      val began = nowMs()
      mutable.update { it.copy(modelCalls = it.modelCalls + 1) }
      confluence = try {
        JevConfluence.evaluate(baseline, model)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }
      latency = nowMs() - began
      ensureBoundary(context)
      mutable.update { it.copy(confluence = confluence, modelLatencyMs = latency) }
      direction = JevConfluence.applyGate(signal, confluence, true, context.config.minConfidence)
      if (direction == StrategyDirection.WAIT) {
        val why = confluence?.let { "Jev ${it.direction.name.lowercase()} at ${percent(it.confidence)} confidence" } ?: "Jev unavailable"
        decide(latestClosed, signal.direction, AutoOutcome.VETOED, "Vetoed ${signal.direction.word()} · $why", confluence?.confidence, latency)
        return
      }
    }
    if (nowMs() - latestClosed >= context.key.timeframeMs) {
      decide(latestClosed, direction, AutoOutcome.LATE, "Late · the next candle closed before the decision", confluence?.confidence, latency)
      return
    }
    if (direction == StrategyDirection.WAIT) {
      decide(latestClosed, direction, AutoOutcome.WAITING, "Wait · no new ${context.config.strategy.label} signal")
      return
    }
    val cooldownMs = context.config.cooldownSeconds * 1_000L
    if (lastAttemptAt?.let { nowMs() - it < cooldownMs } == true) {
      decide(latestClosed, direction, AutoOutcome.SKIPPED, "Skipped ${direction.word()} · cooldown", confluence?.confidence, latency)
      return
    }
    val (outcome, message) = try {
      if (context.real) realOrder(context, latestClosed, direction) else paperOrder(context, latestClosed, direction)
    } catch (skip: AutoSkip) {
      AutoOutcome.SKIPPED to "Skipped ${direction.word()} · ${skip.message}"
    }
    decide(latestClosed, direction, outcome, message, confluence?.confidence, latency)
  }

  private suspend fun paperOrder(context: RunContext, closed: Long, direction: StrategyDirection): Pair<AutoOutcome, String> {
    val fresh = fetch(context.key)
    ensureBoundary(context)
    if (!isCurrent(fresh, closed)) return AutoOutcome.LATE to LATE_ORDER
    val paper = mutable.value.paper ?: PaperState()
    val perp = context.market.kind == MarketKind.PERP
    val size = exitSize(paper.positionSize, direction, context.config.orderSize, perp)
    val result = simulator.fill(context.config.copy(orderSize = size), paper, fresh, direction, nowMs(), perp)
    result.rejection?.let { throw AutoSkip(it.lowercase()) }
    val fill = result.fill ?: throw AutoSkip("no paper fill")
    lastAttemptAt = nowMs()
    mutable.update {
      it.copy(paper = result.state, orders = result.state.attempts, consumedNotional = result.state.filledNotional,
        positionSize = result.state.positionSize, realizedPnl = result.state.realizedPnl,
        markers = (it.markers + AutoMarker(closed, direction)).takeLast(MAX_MARKERS))
    }
    return AutoOutcome.ACTED to "Paper ${direction.word()} · ${fill.size} ${context.market.symbol} at ${fill.price}"
  }

  private suspend fun realOrder(context: RunContext, closed: Long, direction: StrategyDirection): Pair<AutoOutcome, String> {
    val market = context.market
    val config = context.config
    val account = mutable.value.account!!
    val perp = market.kind == MarketKind.PERP
    ensureBoundary(context)
    stopRequire(skipping { trading.reconcilePending() }.unresolved == 0) { PENDING_RUN }
    skipping { accounts.refresh() }
    ensureBoundary(context)
    val snapshot = accounts.snapshot.value
    skipRequire(!snapshot.stale && snapshot.account == account) { "account data is reconnecting" }
    // Reduce-only exits can only shrink the position; anything else could grow it behind the run's back.
    skipRequire(snapshot.openOrders.none { it.marketId == market.id && !it.reduceOnly && !it.isPositionTpSl }) {
      "another open order in this market could change the position"
    }
    skipRequire(skipping { accounts.activeTwaps() }.none { it.marketId == market.id }) { "a TWAP is running in this market" }
    val position = snapshot.positions.firstOrNull { it.marketId == market.id }
    val base = if (perp) null else skipping { trading.assetBalanceDecimal(account, market.symbol) }
    val held = base?.total ?: position?.size?.trim() ?: "0"
    val size = exitSize(held, direction, config.orderSize, perp)
    val reduceOnly = perp && opposes(held, direction)
    val fresh = fetch(context.key)
    ensureBoundary(context)
    if (!isCurrent(fresh, closed)) return AutoOutcome.LATE to LATE_ORDER
    val book = fresh.book!!
    val saved = preferences.values.first()
    val builderApproved = if (perp) saved.builderApproved else saved.spotBuilderApproved
    val request = OrderRequest(
      market.id, if (direction == StrategyDirection.BUY) OrderSide.BUY else OrderSide.SELL, OrderType.MARKET, size,
      reduceOnly = reduceOnly, slippageBps = config.slippageBps,
      builderAddress = saved.builderAddress.takeIf { builderApproved },
      builderFeeBps = (if (perp) saved.builderFeeBps else saved.spotBuilderFeeBps).takeIf { builderApproved },
    )
    val checked = when (val check = market.rules.check(request, book.toOrderBook(market.id))) {
      is OrderCheck.Valid -> check.order
      is OrderCheck.Invalid -> throw AutoSkip(check.errors.firstOrNull()?.lowercase() ?: "order rejected by market rules")
    }
    stopRequire(AutoAmounts.compare(checked.size, size) == 0 && checked.reduceOnly == reduceOnly) { "Market rules changed the order. Reset and review the order size." }
    val limit = checked.price ?: throw AutoSkip("no price bound")
    // The most an immediate-or-cancel order can be worth: a buy at its limit, a sell at the best bid.
    val worst = if (direction == StrategyDirection.BUY) limit
      else book.bids.first().price.let { if (AutoAmounts.compare(it, limit) > 0) it else limit }
    val state = mutable.value
    val risk = AutoRisk.check(config, held, state.orders, state.consumedNotional, direction, worst, allowShort = perp, size = size)
    if (!risk.allowed) {
      stopRequire(state.orders < config.maxOrders, endsRun = true) { ATTEMPTS_REACHED }
      throw AutoSkip((risk.reason ?: "risk limit").lowercase())
    }
    val reservation = risk.reservation!!
    if (!perp) {
      if (direction == StrategyDirection.SELL) skipRequire(AutoAmounts.compare(base!!.available, size) >= 0) { "not enough ${market.symbol} available" }
      else {
        val quote = skipping { trading.assetBalanceDecimal(account, market.quoteAsset ?: "USDC") }
        skipRequire(AutoAmounts.compare(quote.available, AutoAmounts.multiply(reservation.notional, "1.01")) >= 0) {
          "not enough ${market.quoteAsset ?: "USDC"} available, including fees"
        }
      }
    }
    if (perp && !reduceOnly && position?.leverage != config.leverage) {
      val configure = TradeAction.ConfigureMarket(account, market.id,
        if (position?.isolated ?: market.isolatedOnly) MarginMode.ISOLATED else MarginMode.CROSS, config.leverage)
      when (val outcome = send(context, configure)) {
        is ActionState.Committed -> Unit
        is ActionState.Failed -> if (outcome.definite) throw AutoSkip("leverage not applied: ${outcome.message.lowercase()}")
          else throw AutoStop(UNCERTAIN)
        else -> throw AutoStop(UNCERTAIN)
      }
      ensureBoundary(context)
      if (!isCurrent(fresh, closed)) return AutoOutcome.LATE to LATE_ORDER
    }
    // Reserved before submission; the reservation stays counted whatever the outcome, so an uncertain
    // order still counts against the limits and is never sent again.
    lastAttemptAt = nowMs()
    mutable.update { it.copy(orders = reservation.nextAttempts, consumedNotional = reservation.nextConsumedNotional) }
    val terminal = send(context, TradeAction.PlaceOrder(account, market, checked))
    val side = direction.word()
    val label = "${if (reduceOnly) "reduce-only " else ""}$side ${checked.size} ${market.symbol}, limit $limit"
    return when {
      terminal is ActionState.Committed -> {
        refreshPosition(context, account, reservation.projectedPosition)
        mutable.update { it.copy(markers = (it.markers + AutoMarker(closed, direction)).takeLast(MAX_MARKERS)) }
        AutoOutcome.ACTED to "Real $label${terminal.detail?.let { " · $it" } ?: ""}"
      }
      terminal is ActionState.Failed && terminal.definite -> {
        refreshPosition(context, account, null)
        AutoOutcome.SKIPPED to "Not filled · $label · ${terminal.message}"
      }
      else -> throw AutoStop(UNCERTAIN)
    }
  }

  private suspend fun send(context: RunContext, action: TradeAction): ActionState {
    ensureBoundary(context)
    var terminal: ActionState? = null
    try {
      trading.execute(action, VaultPrompt("Auto trading", "Foreground authorization"), FeePayment.SPONSORED).collect { terminal = it }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      throw AutoStop(UNCERTAIN)
    }
    return terminal ?: throw AutoStop(UNCERTAIN)
  }

  private suspend fun refreshPosition(context: RunContext, account: String, fallback: String?) {
    try {
      accounts.refresh()
      accounts.refreshHistory()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
    }
    ensure(context.expected)
    val snapshot = accounts.snapshot.value
    val size = if (context.market.kind == MarketKind.SPOT) fallback
      else if (!snapshot.stale && snapshot.account == account) snapshot.positions.firstOrNull { it.marketId == context.market.id }?.size ?: "0"
      else fallback
    size?.let { value -> mutable.update { it.copy(positionSize = value) } }
  }

  private suspend fun fetch(key: StrategyMarketKey): StrategyMarketSnapshot = skipping { feed.snapshot(key) }

  private fun isCurrent(snapshot: StrategyMarketSnapshot, closed: Long): Boolean {
    val now = nowMs()
    if (snapshot.fetchedAtMs > now || now - snapshot.fetchedAtMs > FRESH_MS) return false
    if (snapshot.closedCandles().maxOfOrNull { it.closeTimeMs } != closed || now - closed >= snapshot.key.timeframeMs) return false
    val book = snapshot.book ?: throw AutoSkip("no live order book")
    if (book.bids.isEmpty() || book.asks.isEmpty()) throw AutoSkip("no live order book")
    if (book.observedAtMs < 0 || book.observedAtMs > now || now - book.observedAtMs > FRESH_MS) throw AutoSkip("order book is stale")
    return true
  }

  private suspend fun ensureBoundary(context: RunContext) {
    ensure(context.expected)
    val current = preferences.values.first()
    stopRequire((current.activeProfileId to current.selectedSubaccount) == context.profile) { "Account changed." }
    stopRequire(credentials.metadata.first().revision == context.revision) { "Jev settings changed." }
    if (context.real) {
      wallets.requireAuthorization(context.authorization)
      stopRequire(current.autoTradingActive && !current.confirmTransactions) { "Real auto trading authorization ended." }
    }
    mutable.value.startedAtMs?.let { stopRequire(nowMs() - it < context.config.durationMinutes * 60_000L, endsRun = true) { DURATION_REACHED } }
    ensure(context.expected)
  }

  private suspend fun ensure(expected: Long) {
    currentCoroutineContext().ensureActive()
    check(generation == expected) { "Run changed." }
  }

  private fun decide(closed: Long, direction: StrategyDirection, outcome: AutoOutcome, message: String, confidence: Double? = null, latencyMs: Long? = null) {
    val decision = AutoDecision(closed, direction, outcome, confidence, latencyMs)
    mutable.update {
      it.copy(decision = decision,
        activity = (listOf(AutoActivity(nowMs(), closed, message, direction, outcome)) + it.activity).take(100))
    }
  }

  private fun updateMarket(snapshot: StrategyMarketSnapshot, expected: Long) {
    if (generation != expected) return
    val bid = snapshot.book?.bids?.firstOrNull()?.price
    val ask = snapshot.book?.asks?.firstOrNull()?.price
    val mark = if (bid != null && ask != null) runCatching { AutoAmounts.multiply(AutoAmounts.add(bid, ask), "0.5") }.getOrNull() else ask ?: bid
    mutable.update {
      it.copy(candles = snapshot.candles.takeLast(MAX_CANDLES), markPrice = mark ?: it.markPrice,
        unrealizedPnl = if (it.paper != null && mark != null) runCatching { simulator.unrealizedPnl(it.paper, mark) }.getOrNull()
          else if (it.config.mode == AutoRunMode.REAL) realUnrealized(mark) else it.unrealizedPnl,
        realizedPnl = if (it.config.mode == AutoRunMode.REAL) realRealized() else it.realizedPnl)
    }
  }

  private fun realUnrealized(mark: String?): String? {
    val snapshot = accounts.snapshot.value
    if (snapshot.stale || snapshot.account != mutable.value.account || mutable.value.market?.kind != MarketKind.PERP) return null
    val position = snapshot.positions.firstOrNull { it.marketId == mutable.value.market?.id } ?: return "0"
    val size = position.size.toDoubleOrNull() ?: return null
    val price = mark?.toDoubleOrNull() ?: return null
    return (size * (price - position.entryPrice)).takeIf { it.isFinite() }?.toString()
  }

  private fun realRealized(): String? {
    val history = accounts.history.value
    val started = mutable.value.startedAtMs ?: return null
    if (history.stale || history.account != mutable.value.account || history.tradesHasMore) return null
    return history.trades.filter { it.marketId == mutable.value.market?.id && it.timeMs >= started }
      .sumOf { it.realizedPnl - it.fee }.takeIf { it.isFinite() }?.toString()
  }

  private class AutoStop(message: String, val endsRun: Boolean = false) : IllegalStateException(message)

  /** Skips this candle's action; the run keeps going. */
  private class AutoSkip(message: String) : IllegalStateException(message)

  private inline fun stopRequire(condition: Boolean, endsRun: Boolean = false, message: () -> String) {
    if (!condition) throw AutoStop(message(), endsRun)
  }

  private inline fun skipRequire(condition: Boolean, message: () -> String) {
    if (!condition) throw AutoSkip(message())
  }

  private suspend fun <T> skipping(block: suspend () -> T): T =
    try {
      block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (stop: AutoStop) {
      throw stop
    } catch (skip: AutoSkip) {
      throw skip
    } catch (_: Exception) {
      throw AutoSkip("couldn’t refresh market or account data")
    }

  private companion object {
    const val FRESH_MS = 5_000L
    const val MAX_CANDLES = 120
    const val MAX_MARKERS = 200
    const val CONFIRMATION_OFF = "Real auto trading is on. Confirm transactions is off for this run and comes back when it pauses or ends."
    const val CONFIRMATION_RESTORED = "Confirm transactions is back to your setting."
    const val DURATION_REACHED = "Run duration reached. Reset to start a new run."
    const val ATTEMPTS_REACHED = "Order attempt limit reached. Reset to start a new run."
    const val PENDING_START = "An earlier transaction is still pending. Wait for it to settle, then start."
    const val PENDING_RUN = "An earlier transaction is still pending. Resume once it settles."
    const val PENDING_RESET = "A transaction from this run is still pending. Reset once it settles."
    const val UNCERTAIN = "The last order’s outcome isn’t known yet. It stays pending, counts against the limits and is never resent. Resume once it settles."
    const val LATE_ORDER = "Late · the candle expired before the order"
  }
}

private val ActionState.Failed.definite: Boolean
  get() = committed || definitelyNotSubmitted

/** An order against an open position only closes it, never flips it in one step. */
internal fun exitSize(held: String, direction: StrategyDirection, orderSize: String, perp: Boolean): String {
  if (!perp || !opposes(held, direction)) return orderSize
  val absolute = held.trim().removePrefix("-")
  return if (AutoAmounts.compare(absolute, orderSize) < 0) AutoAmounts.normalize(absolute) else orderSize
}

private fun opposes(held: String, direction: StrategyDirection): Boolean {
  val sign = AutoAmounts.compare(held.trim(), "0")
  return sign != 0 && (sign > 0) == (direction == StrategyDirection.SELL)
}

private fun StrategyBook.toOrderBook(marketId: String) =
  OrderBook(marketId, bids.map { OrderBookLevel(it.price, it.size) }, asks.map { OrderBookLevel(it.price, it.size) })

private fun StrategyDirection.word() = name.lowercase()

private fun percent(value: Double) = "${(value * 100).toInt()}%"
