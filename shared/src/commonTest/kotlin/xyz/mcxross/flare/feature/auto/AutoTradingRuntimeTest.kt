package xyz.mcxross.flare.feature.auto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.data.AccountSetupGate
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.ExactAssetBalance
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.ReconciliationResult
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.JevCredentials
import xyz.mcxross.flare.security.UnavailableWalletVault
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.strategies.AutoRunConfig
import xyz.mcxross.flare.strategies.AutoRunMode
import xyz.mcxross.flare.strategies.DecisionModel
import xyz.mcxross.flare.strategies.StrategyBook
import xyz.mcxross.flare.strategies.StrategyBookLevel
import xyz.mcxross.flare.strategies.StrategyCandle
import xyz.mcxross.flare.strategies.StrategyDirection
import xyz.mcxross.flare.strategies.StrategyKind
import xyz.mcxross.flare.strategies.StrategyMarketFeed
import xyz.mcxross.flare.strategies.StrategyMarketSnapshot
import xyz.mcxross.flare.testVenue
import xyz.mcxross.flare.testing.MemoryPreferences
import xyz.mcxross.flare.testing.SetupAccounts
import xyz.mcxross.flare.testing.SetupTrading
import xyz.mcxross.flare.testing.SetupWallets
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderCheck
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderRules
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.TradeAction

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoTradingRuntimeTest {
  @Test
  fun simulationActsOncePerCandleAndKeepsCountersAcrossPause() = runTest {
    val f = fixture()
    f.runtime.onIntent(AutoTradingIntent.ReviewStart)
    runCurrent()
    assertEquals(1, f.state.orders)
    assertEquals(0, f.wallet.authorizations)
    advanceTimeBy(3 * POLL)
    runCurrent()
    assertEquals(1, f.state.orders)
    val consumed = f.state.consumedNotional
    f.runtime.pause()
    runCurrent()
    f.runtime.onIntent(AutoTradingIntent.ReviewStart)
    runCurrent()
    assertEquals(1, f.state.orders, "The same candle is never decided twice")
    assertEquals(consumed, f.state.consumedNotional)
    advanceTimeBy(TIMEFRAME)
    runCurrent()
    assertEquals(2, f.state.orders, "Resume continues the run's counters")
    assertNotEquals(consumed, f.state.consumedNotional)
    assertTrue(f.trading.sent.isEmpty())
    f.runtime.close()
  }

  @Test
  fun candleStillOpenWhenRequestedIsExcludedEvenIfTheAnswerArrivesAfterItsClose() = runTest {
    // The first request begins 300 ms before a close and answers 400 ms after it, including the
    // candle that closed in between with partial data.
    val f = fixture(startOffset = TIMEFRAME - 300, requestMs = 700)
    f.runtime.onIntent(AutoTradingIntent.ReviewStart)
    runCurrent()
    val partial = BASE + TIMEFRAME
    assertTrue(f.decided.none { it == partial })
    advanceTimeBy(2 * POLL)
    runCurrent()
    assertEquals(1, f.decided.count { it == partial })
    advanceTimeBy(3 * POLL)
    runCurrent()
    assertEquals(1, f.decided.count { it == partial })
    f.runtime.close()
  }

  @Test
  fun realRunTurnsConfirmationsOffAndRestoresEitherOriginalValue() = runTest {
    for (original in listOf(true, false)) {
      val f = fixture(real = true, confirm = original)
      f.runtime.onIntent(AutoTradingIntent.ConfirmStart)
      runCurrent()
      assertEquals(0, f.wallet.authorizations, "Real runs need a reviewed start")
      f.runtime.onIntent(AutoTradingIntent.ReviewStart)
      f.runtime.onIntent(AutoTradingIntent.ConfirmStart)
      runCurrent()
      assertEquals(1, f.wallet.authorizations)
      assertTrue(f.state.active)
      assertFalse(f.preferences.values.first().confirmTransactions)
      assertTrue(f.preferences.values.first().autoTradingActive)
      assertTrue(f.state.notice!!.contains("Confirm transactions is off"))
      f.runtime.pause("Paused in background")
      assertEquals(original, f.preferences.values.first().confirmTransactions)
      assertFalse(f.preferences.values.first().autoTradingActive)
      runCurrent()
      assertEquals(original, AppPreferences(f.store).values.first().confirmTransactions)
      f.runtime.close()
    }
  }

  @Test
  fun confirmationRestoresAfterProcessDeathMidRun() = runTest {
    for (original in listOf(true, false)) {
      val store = MemoryPreferences()
      val before = AppPreferences(store)
      before.setConfirmTransactions(original)
      before.beginRealAutoTrading()
      assertFalse(before.values.first().confirmTransactions)
      // A new process: nothing owns the saved run.
      val after = AppPreferences(store)
      assertEquals(original, after.values.first().confirmTransactions)
      assertFalse(after.values.first().autoTradingActive)
      assertFailsWith<IllegalStateException> { after.setConfirmTransactions(!original) }
      after.recoverRealAutoTrading()
      assertEquals(original, AppPreferences(store).values.first().confirmTransactions)
      after.setConfirmTransactions(!original)
      assertEquals(!original, AppPreferences(store).values.first().confirmTransactions)
    }
  }

  @Test
  fun uncertainOrderStaysReservedAcrossPauseAndIsNeverResent() = runTest {
    val f = fixture(real = true)
    f.trading.outcome = ActionState.Pending("uncertain")
    start(f)
    assertEquals(1, f.trading.sent.size)
    assertEquals(1, f.state.orders)
    val reserved = f.state.consumedNotional
    assertEquals("100", reserved)
    assertFalse(f.state.active)
    assertFalse(f.state.needsReset, "A pending order pauses the run without ending it")
    assertTrue(f.preferences.values.first().confirmTransactions)
    f.trading.unresolved = 1
    start(f)
    assertFalse(f.state.active)
    assertEquals(1, f.wallet.authorizations, "Resume is refused before authorization while pending")
    f.trading.unresolved = 0
    f.trading.outcome = ActionState.Committed("confirmed")
    start(f)
    assertTrue(f.state.active)
    assertEquals(1, f.trading.sent.size, "The pending order's candle is not decided again")
    assertEquals(1, f.state.orders)
    assertEquals(reserved, f.state.consumedNotional)
    advanceTimeBy(TIMEFRAME)
    runCurrent()
    assertEquals(2, f.trading.sent.size)
    assertEquals(2, f.state.orders)
    assertEquals("200", f.state.consumedNotional)
    f.runtime.close()
  }

  @Test
  fun realLimitsReserveWorstCaseValueAndEndTheRunAtTheAttemptLimit() = runTest {
    val budget = fixture(real = true) { it.copy(maxTotalNotional = "150") }
    start(budget)
    assertEquals(1, budget.trading.sent.size)
    assertEquals("100", budget.state.consumedNotional)
    advanceTimeBy(TIMEFRAME)
    runCurrent()
    assertEquals(1, budget.trading.sent.size, "The budget can't cover a second order")
    assertTrue(budget.state.active, "A limit that blocks one order skips it without stopping")
    assertEquals(AutoOutcome.SKIPPED, budget.state.decision?.outcome)
    budget.runtime.close()

    val attempts = fixture(real = true) { it.copy(maxOrders = 1) }
    start(attempts)
    assertEquals(1, attempts.trading.sent.size)
    assertFalse(attempts.state.active)
    assertTrue(attempts.state.needsReset)
    assertTrue(attempts.state.error!!.contains("attempt limit"))
    assertTrue(attempts.preferences.values.first().confirmTransactions)
    start(attempts)
    assertEquals(1, attempts.trading.sent.size)
  }

  @Test
  fun unavailableJevVetoesWithoutStoppingTheRun() = runTest {
    val f = fixture(model = DecisionModel { error("Jev is down") })
    f.preferences.setJevKeyConfigured(true)
    f.credentials.setEnabled(true)
    runCurrent()
    f.runtime.onIntent(AutoTradingIntent.ReviewStart)
    runCurrent()
    assertEquals(0, f.state.orders)
    assertEquals(1, f.state.modelCalls)
    assertEquals(AutoOutcome.VETOED, f.state.decision?.outcome)
    assertTrue(f.state.active)
    f.runtime.close()
  }

  @Test
  fun anOppositeSignalOnlyReducesTheOpenPosition() {
    assertEquals("0.5", exitSize("0.5", StrategyDirection.SELL, "1", perp = true))
    assertEquals("1", exitSize("2", StrategyDirection.SELL, "1", perp = true))
    assertEquals("0.25", exitSize("-0.25", StrategyDirection.BUY, "1", perp = true))
    assertEquals("1", exitSize("0.5", StrategyDirection.BUY, "1", perp = true))
    assertEquals("1", exitSize("0.5", StrategyDirection.SELL, "1", perp = false))
  }

  private suspend fun TestScope.fixture(
    real: Boolean = false,
    confirm: Boolean = true,
    startOffset: Long = 50,
    requestMs: Long = 0,
    model: DecisionModel = DecisionModel { error("Not enabled") },
    limits: (AutoRunConfig) -> AutoRunConfig = { it },
  ): Fixture {
    val store = MemoryPreferences()
    val preferences = AppPreferences(store)
    if (real) preferences.registerProfile(AccountProfile("profile", apiWalletAddress = "api", selectedSubaccount = "account", onboardingComplete = true))
    preferences.setConfirmTransactions(confirm)
    val wallet = TestWallet(real)
    val accounts = object : SetupAccounts() {
      override val snapshot = MutableStateFlow(AccountSnapshot(account = "account", stale = false,
        positions = listOf(Position("BTC", "0", 1, 100.0, false))))
    }
    val trading = TestTrading()
    val credentials = JevCredentials(ForegroundWalletVault(UnavailableWalletVault()), preferences)
    val config = limits(AutoRunConfig(StrategyKind.MACD_CROSSOVER, if (real) AutoRunMode.REAL else AutoRunMode.SIMULATION,
      "1", "1000", "10000", 10, 30, 0, 50, 1))
    val f = Fixture(store, preferences, wallet, trading, credentials)
    val now = { BASE + startOffset + testScheduler.currentTime }
    val feed = StrategyMarketFeed { key ->
      val fetched = now()
      delay(requestMs)
      // The exchange answers with every candle open at answer time, the last one still forming.
      val answered = now()
      val last = answered / TIMEFRAME
      val candles = (last - 59..last).map { index ->
        val close = 100.0 + if (index % 2 == 0L) 5.0 else -5.0
        StrategyCandle(index * TIMEFRAME, (index + 1) * TIMEFRAME, close, close + 1, close - 1, close, 10.0)
      }
      StrategyMarketSnapshot(key, fetched, candles,
        StrategyBook(listOf(StrategyBookLevel("99", "100")), listOf(StrategyBookLevel("100", "100")), answered))
    }
    f.runtime = AutoTradingRuntime(feed, preferences, credentials, wallet, accounts, trading, model, testVenue(),
      AccountSetupGate(preferences, accounts), CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job])), POLL, now)
    runCurrent()
    val market = Market("BTC", "BTC", "BTC/USDC", MarketKind.PERP, "crypto", 2, 2, 20, false, TestRules)
    f.runtime.onIntent(AutoTradingIntent.Bind(market, TIMEFRAME, if (real) "account" else null))
    f.runtime.onIntent(AutoTradingIntent.Configure(config))
    runCurrent()
    return f
  }

  private class Fixture(
    val store: MemoryPreferences,
    val preferences: AppPreferences,
    val wallet: TestWallet,
    val trading: TestTrading,
    val credentials: JevCredentials,
  ) {
    lateinit var runtime: AutoTradingRuntime
    val state get() = runtime.uiState.value
    val decided get() = state.activity.map { it.closedAtMs }
  }

  private fun TestScope.start(f: Fixture) {
    f.runtime.onIntent(AutoTradingIntent.ReviewStart)
    f.runtime.onIntent(AutoTradingIntent.ConfirmStart)
    runCurrent()
  }

  private class TestWallet(real: Boolean) : WalletRepository by SetupWallets() {
    override val profile = MutableStateFlow(WalletProfile(apiWalletAddress = if (real) "api" else null))
    var authorizations = 0
    override val authorizationGeneration = 0L
    override fun requireAuthorization(generation: Long) { check(generation == 0L) }
    override suspend fun confirmIdentity(prompt: VaultPrompt) { authorizations++ }
  }

  private class TestTrading : TradingRepository by SetupTrading {
    val sent = mutableListOf<TradeAction>()
    var unresolved = 0
    var outcome: ActionState = ActionState.Committed("confirmed")
    override suspend fun reconcilePending() = ReconciliationResult(0, unresolved)
    override suspend fun assetBalanceDecimal(accountAddress: String, symbol: String) = ExactAssetBalance("100000", "100000")
    override fun execute(action: TradeAction, prompt: VaultPrompt, feePayment: FeePayment, onPrepared: suspend (String) -> Unit): Flow<ActionState> = flow {
      sent += action
      emit(outcome)
    }
  }

  private object TestRules : OrderRules {
    override val minimumSize = "0.01"
    override fun check(order: OrderRequest, orderBook: OrderBook?) = OrderCheck.Valid(order.copy(price = orderBook!!.bestAsk))
    override fun checkExitPrices(takeProfit: String?, stopLoss: String?) = null
  }

  private companion object {
    const val TIMEFRAME = 60_000L
    const val POLL = 5_000L
    const val BASE = 6_000_000L
  }
}
