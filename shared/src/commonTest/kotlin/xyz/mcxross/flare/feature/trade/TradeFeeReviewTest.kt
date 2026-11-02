package xyz.mcxross.flare.feature.trade

import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.testVenue
import xyz.mcxross.flare.testing.*
import xyz.mcxross.flare.venue.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class TradeFeeReviewTest {
  @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

  @AfterTest fun tearDown() = Dispatchers.resetMain()

  @Test
  fun reviewingIsUnsignedAndFeesAreAutomaticWithinOneConfirmation() = runTest {
    val f = fixture()
    try {
      f.prepareFees()
      val review = assertNotNull(f.vm.uiState.value.feeReview, f.vm.uiState.value.toString())
      assertEquals(2, review.actions.size)
      assertEquals(10_000uL, review.maximumTotalOctas)
      assertEquals(0, f.trading.confirmations)
      assertTrue(f.trading.executed.isEmpty())
      f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
      assertEquals(1, f.trading.confirmations)
      assertEquals(listOf(true), f.trading.consents)
      assertEquals(review.actions, f.trading.executed)
      assertIs<ActionState.Committed>(f.vm.uiState.value.transaction)
      assertFalse(f.vm.uiState.value.feeReviewPrepared)
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  @Test
  fun insufficientReserveAndUnknownBalanceKeepSponsorshipWithoutImplicitFeeConsent() = runTest {
    for (balance in listOf(1_000uL, null)) {
      val f = fixture()
      try {
        f.trading.balance = balance
        f.prepareFees()
        val review = assertNotNull(f.vm.uiState.value.feeReview, f.vm.uiState.value.toString())
        assertFalse(review.canSelfPay)
        assertEquals(balance?.let { 9_000uL }, review.fundingDeficit)
        f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
        assertEquals(1, f.trading.confirmations)
        assertEquals(listOf(false), f.trading.consents)
      } finally {
        f.vm.viewModelScope.cancel()
      }
    }
  }

  @Test
  fun changingOrderOrAccountRequiresNewReviewAndClearsConsent() = runTest {
    val f = fixture()
    try {
      f.prepareFees()
      f.vm.onIntent(TradeIntent.SetSize("0.002"))
      assertNull(f.vm.uiState.value.feeReview)
      f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
      assertEquals(0, f.trading.confirmations)
      f.awaitFees()
      assertNotNull(f.vm.uiState.value.feeReview, f.vm.uiState.value.toString())
      f.preferences.setSelectedSubaccount("0xother")
      assertNull(f.vm.uiState.value.feeReview)
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  @Test
  fun unresolvedLeverageNeverSubmitsEntryOrRetriesAutomatically() = runTest {
    val f = fixture()
    try {
      f.trading.terminal =
        ActionState.Failed("Sponsor response was lost", reference = "sponsor:unknown")
      f.prepareFees()
      f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
      assertEquals(1, f.trading.confirmations)
      assertEquals(1, f.trading.executed.size)
      assertIs<TradeAction.ConfigureMarket>(f.trading.executed.single())
      assertNotNull(f.vm.uiState.value.orderError)
      assertFalse(f.vm.uiState.value.feeReviewPrepared)
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  @Test
  fun knownUnavailableSponsorshipUsesFundedFeesWithinOneConfirmation() = runTest {
    val f = fixture()
    try {
      f.trading.sponsorshipAvailable = false
      f.prepareFees()
      assertFalse(f.vm.uiState.value.orderFeePaymentBlocked)
      f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
      assertEquals(1, f.trading.confirmations)
      assertEquals(listOf(true), f.trading.consents)
      assertEquals(2, f.trading.executed.size)
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  @Test
  fun ownerFeePayerNeedsNoTradingWalletTopUpOrExtraConfirmation() = runTest {
    val f = fixture()
    try {
      f.trading.sponsorshipAvailable = false
      f.trading.balance = 0uL
      f.trading.ownerBalance = 2_000uL
      f.trading.requiredReserve = 1_000uL
      f.prepareFees()
      val review = assertNotNull(f.vm.uiState.value.feeReview)
      assertTrue(review.ownerPaysFees)
      assertEquals(0uL, review.tradingKeyBalance)
      assertEquals(2_000uL, review.feePayerBalance)
      assertTrue(review.canSelfPay)
      assertFalse(f.vm.uiState.value.orderFeePaymentBlocked)
      f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
      assertEquals(1, f.trading.confirmations)
      assertEquals(listOf(true), f.trading.consents)
      assertEquals(review.actions, f.trading.executed)
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  @Test
  fun aFailedPayerFeeReviewCanRetryAfterExternalFundingWithoutAuthorization() = runTest {
    val f = fixture()
    try {
      f.trading.sponsorshipAvailable = false
      f.trading.ownerBalance = 500uL
      f.trading.requiredReserve = 1_000uL
      f.trading.localFeeReady = false
      f.prepareFees()
      assertTrue(f.vm.uiState.value.orderFeesNeedRetry)
      assertNotNull(f.vm.uiState.value.orderFeePaymentError)
      assertEquals(0, f.trading.confirmations)
      f.trading.ownerBalance = 2_000uL
      f.trading.localFeeReady = true
      f.prepareFees()
      assertFalse(f.vm.uiState.value.orderFeesNeedRetry)
      assertEquals(2_000uL, f.vm.uiState.value.feeReview?.feePayerBalance)
      assertEquals(0, f.trading.confirmations)
      f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
      assertEquals(1, f.trading.confirmations)
      assertEquals(2, f.trading.executed.size)
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  @Test
  fun anUnvalidatedLocalFeeRouteNeverAuthorizesEvenWithEnoughReportedBalance() = runTest {
    val f = fixture()
    try {
      f.trading.sponsorshipAvailable = false
      f.trading.localFeeReady = false
      f.prepareFees()
      assertTrue(f.vm.uiState.value.orderFeePaymentBlocked)
      f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
      assertEquals(0, f.trading.confirmations)
      assertTrue(f.trading.executed.isEmpty())
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  @Test
  fun theFeesRowUsesEstimatesAndAggregatedEntryFeesWithoutDoubleCounting() = runTest {
    val f = fixture()
    try {
      f.trading.sponsorshipAvailable = false
      f.prepareFees()
      val state = f.vm.uiState.value
      val review = assertNotNull(state.feeReview)
      val chain = testVenue().chain
      assertEquals("Up to 0.0001 APT", state.orderNetworkFeeText(chain))
      assertEquals(
        "≈ 0.000007 APT",
        state.copy(feeReview = review.copy(estimatedFeeOctas = 700uL)).orderNetworkFeeText(chain),
      )
      val singleEntry =
        review.copy(
          actions = review.actions.filterIsInstance<TradeAction.PlaceOrder>(),
          estimatedFeeOctas = 400uL,
        )
      assertEquals("≈ 0.000004 APT", state.copy(feeReview = singleEntry).orderNetworkFeeText(chain))
      assertEquals(
        "≈ 0.000007 APT",
        state.copy(entryNetworkFee = NetworkFee(700uL, 1_000uL)).orderNetworkFeeText(chain),
      )
      assertEquals(
        "Covered by Flare",
        state.copy(feeReview = review.copy(sponsorshipAvailable = true)).orderNetworkFeeText(chain),
      )
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  @Test
  fun knownUnavailableSponsorshipCannotAuthorizeWithInsufficientOrUnknownTradingApt() = runTest {
    for (balance in listOf(0uL, 1_000uL, null)) {
      val f = fixture(ownerAvailable = false)
      try {
        f.trading.sponsorshipAvailable = false
        f.trading.balance = balance
        f.prepareFees()
        val review = assertNotNull(f.vm.uiState.value.feeReview)
        assertEquals(balance, review.tradingKeyBalance)
        assertEquals("0xapi", review.tradingKeyAddress)
        assertTrue(f.vm.uiState.value.orderFeePaymentBlocked)
        f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
        assertEquals(0, f.trading.confirmations)
        assertTrue(f.trading.executed.isEmpty())
      } finally {
        f.vm.viewModelScope.cancel()
      }
    }
  }

  @Test
  fun newPositionKeepsRequestedLeverageAcrossRefreshButRejectsANewExternalPosition() = runTest {
    for (externalPosition in listOf(false, true)) {
      val f = fixture()
      try {
        f.vm.onIntent(TradeIntent.SetLeverage(2))
        f.prepareFees()
        val review = assertNotNull(f.vm.uiState.value.feeReview)
        assertEquals(
          2,
          review.actions.filterIsInstance<TradeAction.ConfigureMarket>().single().leverage,
        )
        val gate = CompletableDeferred<Unit>()
        f.accounts.refreshGate = gate
        f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
        assertEquals(0, f.trading.confirmations)
        if (externalPosition)
          f.accounts.snapshot.value =
            f.accounts.snapshot.value.copy(
              positions = listOf(Position("0xmarket", "0.001", 3, 70_000.0, isolated = false)),
            )
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, f.vm.uiState.value.leverage)
        if (externalPosition) {
          assertEquals(0, f.trading.confirmations)
          assertTrue(f.trading.executed.isEmpty())
          assertTrue(
            assertNotNull(f.vm.uiState.value.orderError).contains("Check network fees again"),
          )
        } else {
          assertEquals(1, f.trading.confirmations)
          assertEquals(review.actions, f.trading.executed)
          assertNull(f.vm.uiState.value.orderError)
        }
      } finally {
        f.vm.viewModelScope.cancel()
      }
    }
  }

  @Test
  fun positionChangesDuringAccountRefreshRequireReviewBeforeAuthorization() = runTest {
    for (position in
      listOf(
        Position("0xmarket", "0.001", 2, 70_000.0, isolated = false),
        Position("0xmarket", "0.001", 1, 70_000.0, isolated = true),
      )) {
      val f = fixture()
      try {
        f.accounts.snapshot.value =
          f.accounts.snapshot.value.copy(
            positions = listOf(Position("0xmarket", "0.001", 1, 70_000.0, isolated = false)),
          )
        f.prepareFees()
        val gate = CompletableDeferred<Unit>()
        f.accounts.refreshGate = gate
        f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
        assertEquals(0, f.trading.confirmations)
        f.accounts.snapshot.value = f.accounts.snapshot.value.copy(positions = listOf(position))
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, f.trading.confirmations)
        assertTrue(f.trading.executed.isEmpty())
        assertTrue(
          assertNotNull(f.vm.uiState.value.orderError).contains("Check network fees again"),
        )
        assertFalse(f.vm.uiState.value.feeReviewPrepared)
      } finally {
        f.vm.viewModelScope.cancel()
      }
    }
  }

  @Test
  fun aBookBecomingStaleDuringAccountRefreshBlocksMarketOrderAuthorization() = runTest {
    val f = fixture()
    try {
      f.details.value =
        MarketDetails(
          stale = false,
          orderBook =
            OrderBook(
              "0xmarket",
              listOf(OrderBookLevel("70000", "1")),
              listOf(OrderBookLevel("70010", "1")),
            ),
        )
      f.vm.onIntent(TradeIntent.SetOrderType(OrderType.MARKET))
      f.vm.onIntent(TradeIntent.SetLimitPrice(""))
      f.prepareFees()
      val gate = CompletableDeferred<Unit>()
      f.accounts.refreshGate = gate
      f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
      f.details.value = f.details.value.copy(stale = true)
      gate.complete(Unit)
      runCurrent()
      assertEquals(0, f.trading.confirmations)
      assertTrue(f.trading.executed.isEmpty())
      assertTrue(assertNotNull(f.vm.uiState.value.orderError).contains("live order book"))
      assertFalse(f.vm.uiState.value.feeReviewPrepared)
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  @Test
  fun marketBookTicksKeepTheReviewedSlippageBoundAndOneConfirmation() = runTest {
    val f = fixture(SlippageBoundRules)
    try {
      f.vm.onIntent(TradeIntent.SetOrderType(OrderType.MARKET))
      f.vm.onIntent(TradeIntent.SetLimitPrice(""))
      fun book(ask: String) =
        MarketDetails(
          stale = false,
          orderBook =
            OrderBook(
              "0xmarket",
              listOf(OrderBookLevel("70000", "1")),
              listOf(OrderBookLevel(ask, "1")),
            ),
        )
      f.details.value = book("70010")
      f.prepareFees()
      val reviewed = assertNotNull(f.vm.uiState.value.feeReview)
      assertEquals(
        "70361",
        reviewed.actions.filterIsInstance<TradeAction.PlaceOrder>().single().order.price,
      )
      f.details.value = book("70011")
      assertEquals("70362", f.vm.uiState.value.checkedOrder(OrderSide.BUY).price)
      f.vm.onIntent(TradeIntent.Submit(OrderSide.BUY))
      assertEquals(1, f.trading.confirmations)
      assertEquals(reviewed.actions, f.trading.executed)
      assertNull(f.vm.uiState.value.orderError)
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  /** Resolves market IOC bounds from the live book, rounding outward to a whole-dollar tick. */
  private object SlippageBoundRules : OrderRules by AcceptingOrderRules {
    override fun check(order: OrderRequest, orderBook: OrderBook?): OrderCheck {
      if (order.type != OrderType.MARKET) return OrderCheck.Valid(order)
      val ask = checkNotNull(orderBook?.bestAsk).toULong()
      val bound = (ask * (10_000 + order.slippageBps).toULong() + 9_999uL) / 10_000uL
      return OrderCheck.Valid(
        order.copy(price = bound.toString(), timeInForce = TimeInForce.IMMEDIATE_OR_CANCEL),
      )
    }
  }

  @Test
  fun receiptFeesUseOnlyActualCommittedTotals() {
    val chain = testVenue().chain
    fun receipt(fee: NetworkFee) =
      TradeUiState(transaction = ActionState.Committed("hash", networkFee = fee))
    assertEquals(
      "0.00000123 APT",
      receipt(NetworkFee(900uL, 9_000uL, actual = 123uL)).orderReceiptFeeText(chain),
    )
    assertEquals(
      "Covered by Flare",
      receipt(NetworkFee(900uL, 9_000uL, covered = true, actual = 123uL))
        .orderReceiptFeeText(chain),
    )
    assertEquals("—", receipt(NetworkFee(900uL, 9_000uL)).orderReceiptFeeText(chain))
  }

  @Test
  fun preparationCoalescesTapsIgnoresBookTicksAndCancelsObsoleteInputs() = runTest {
    val f = fixture()
    try {
      val gate = CompletableDeferred<Unit>()
      f.trading.preparationGate = gate
      f.vm.onIntent(TradeIntent.PrepareOrderFees(OrderSide.BUY))
      f.trading.preparationStarted.await()
      assertTrue(f.vm.uiState.value.feeReviewLoading)
      repeat(3) {
        f.vm.onIntent(TradeIntent.PrepareOrderFees(OrderSide.BUY))
        f.details.value =
          MarketDetails(
            stale = false,
            orderBook =
              OrderBook(
                "0xmarket",
                listOf(OrderBookLevel("70000", "1")),
                listOf(OrderBookLevel((70010 + it).toString(), "1")),
              ),
          )
      }
      assertEquals(1, f.trading.feePreparations)
      f.vm.onIntent(TradeIntent.SetSize("0.002"))
      f.trading.preparationCancelled.await()
      assertFalse(f.vm.uiState.value.feeReviewLoading)
      assertNull(f.vm.uiState.value.feeReview)
      f.trading.preparationGate = null
      f.prepareFees()
      gate.complete(Unit)
      val entry =
        assertNotNull(f.vm.uiState.value.feeReview)
          .actions
          .filterIsInstance<TradeAction.PlaceOrder>()
          .single()
      assertEquals("0.002", entry.order.size)
      assertEquals(2, f.trading.feePreparations)
      assertEquals(0, f.trading.confirmations)
    } finally {
      f.vm.viewModelScope.cancel()
    }
  }

  @Test
  fun rawApiFailuresNeverAppearAsUserInstructions() {
    assertEquals(
      "Your order wasn’t placed.",
      actionFailure(
        "Api(statusCode=404, message=Not Found)",
        "Your order wasn’t placed.",
      ),
    )
  }

  private suspend fun fixture(
    rules: OrderRules = AcceptingOrderRules,
    ownerAvailable: Boolean = true,
  ): Fixture {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(
      AccountProfile(
        "test",
        ownerAddress = "0xowner",
        apiWalletAddress = "0xapi",
        selectedSubaccount = "0xaccount",
      ),
    )
    val wallets =
      SetupWallets().apply {
        profile.value =
          WalletProfile(
            ownerAddress = if (ownerAvailable) "0xowner" else null,
            apiWalletAddress = "0xapi",
          )
      }
    val accounts = RefreshingAccounts()
    val trading = ReviewedTrading()
    val market =
      Market(
        "0xmarket",
        "BTC",
        "BTC/USD",
        MarketKind.PERP,
        "crypto",
        2,
        8,
        40,
        false,
        rules,
      )
    val markets =
      object : MarketsRepository {
        override val catalog =
          MutableStateFlow(
            MarketCatalog(
              loading = false,
              quotes = listOf(MarketQuote(market, 70_000.0, 0.0, 0.0, 0.0)),
            ),
          )

        override suspend fun refresh() {}

        override suspend fun connectLive() {}

        override suspend fun toggleFavorite(marketAddress: String) {}
      }
    val charts =
      object : ChartRepository {
        override suspend fun candles(market: String, timeframe: ChartTimeframe) =
          ChartSnapshot(emptyList(), false)

        override suspend fun candlesBefore(
          market: String,
          timeframe: ChartTimeframe,
          beforeMs: Long,
        ) = emptyList<Candle>()

        override fun liveCandles(market: String, timeframe: ChartTimeframe) =
          emptyFlow<LiveCandles>()
      }
    val details =
      object : MarketDetailsRepository {
        override val details = MutableStateFlow(MarketDetails(stale = false))

        override suspend fun refresh(market: String) {}

        override suspend fun connectLive(market: String) {}
      }
    val vm =
      TradeViewModel(charts, markets, details, trading, accounts, wallets, preferences, testVenue())
    vm.onIntent(TradeIntent.SetOrderType(OrderType.LIMIT))
    vm.onIntent(TradeIntent.SetSize("0.001"))
    vm.onIntent(TradeIntent.SetLimitPrice("70000"))
    return Fixture(vm, trading, preferences, details.details, accounts)
  }

  private suspend fun Fixture.prepareFees() {
    vm.onIntent(TradeIntent.PrepareOrderFees(OrderSide.BUY))
    awaitFees()
  }

  private suspend fun Fixture.awaitFees() {
    vm.uiState.first { !it.feeReviewLoading && (it.feeReviewPrepared || it.feeReviewError != null) }
  }

  private data class Fixture(
    val vm: TradeViewModel,
    val trading: ReviewedTrading,
    val preferences: AppPreferences,
    val details: MutableStateFlow<MarketDetails>,
    val accounts: RefreshingAccounts,
  )

  private class RefreshingAccounts : SetupAccounts() {
    override val snapshot = MutableStateFlow(AccountSnapshot(account = "0xaccount", stale = false))
    var refreshGate: CompletableDeferred<Unit>? = null

    override suspend fun refresh() {
      refreshGate?.await()
    }
  }

  private class ReviewedTrading : TradingRepository by SetupTrading {
    var balance: ULong? = 20_000uL
    var sponsorshipAvailable: Boolean? = null
    var ownerBalance: ULong? = null
    var requiredReserve: ULong? = null
    var localFeeReady: Boolean? = null
    var feePreparations = 0
    var preparationGate: CompletableDeferred<Unit>? = null
    val preparationStarted = CompletableDeferred<Unit>()
    val preparationCancelled = CompletableDeferred<Unit>()
    var confirmations = 0
    val consents = mutableListOf<Boolean>()
    val executed = mutableListOf<TradeAction>()
    var terminal: ActionState = ActionState.Committed("hash")

    override suspend fun prepareOrderFees(actions: List<TradeAction>): OrderFeeReview {
      feePreparations++
      preparationStarted.complete(Unit)
      try {
        preparationGate?.await()
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        preparationCancelled.complete(Unit)
        throw cancelled
      }
      return OrderFeeReview(
        "review",
        actions,
        5_000uL,
        5_000uL * actions.size.toULong(),
        balance,
        "0xapi",
        sponsorshipAvailable,
        feePayerAddress = if (ownerBalance != null) "0xowner" else "0xapi",
        feePayerBalance = ownerBalance ?: balance,
        ownerPaysFees = ownerBalance != null,
        requiredReserveOctas = requiredReserve,
        localFeeReady = localFeeReady,
      )
    }

    override suspend fun <T> confirmedOrderFees(
      review: OrderFeeReview,
      allowSelfPay: Boolean,
      prompt: VaultPrompt,
      block: suspend () -> T,
    ): T {
      confirmations++
      consents += allowSelfPay
      return block()
    }

    override fun execute(
      action: TradeAction,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      onPrepared: suspend (String) -> Unit,
    ): Flow<ActionState> {
      executed += action
      assertEquals(FeePayment.SPONSORED, feePayment)
      return flowOf(terminal)
    }
  }
}
