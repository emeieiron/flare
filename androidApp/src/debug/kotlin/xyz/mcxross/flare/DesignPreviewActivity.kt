package xyz.mcxross.flare

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlin.math.sin
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.decibel.model.*
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.design.*
import xyz.mcxross.flare.feature.markets.*
import xyz.mcxross.flare.feature.onboarding.*
import xyz.mcxross.flare.feature.orders.*
import xyz.mcxross.flare.feature.portfolio.*
import xyz.mcxross.flare.feature.settings.*
import xyz.mcxross.flare.feature.trade.*
import xyz.mcxross.flare.store.FlarePreferences
import xyz.mcxross.flare.store.WithdrawalContinuation

/** Offline design harness. Debug source set only; no repositories, keys, or transactions. */
class DesignPreviewActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val initialScreen = intent.getStringExtra("screen") ?: "welcome"
    // Captures for the website and README hide the harness label: --ez label false
    val showLabel = intent.getBooleanExtra("label", true)
    // Previews show secret screens with sample words, so they stay capturable for design review.
    setContent { FlareTheme { CompositionLocalProvider(LocalProtectSecrets provides false) { PreviewScreens(initialScreen, showLabel) } } }
  }
}

@Composable
private fun PreviewScreens(initialScreen: String, showLabel: Boolean) {
  var screen by remember { mutableStateOf(initialScreen) }
  var onboarding by remember { mutableStateOf(previewOnboardingState(initialScreen)) }
  var quotes by remember { mutableStateOf(previewQuotes()) }
  var query by remember { mutableStateOf("") }
  var favorites by remember { mutableStateOf(false) }
  var searching by remember { mutableStateOf(false) }
  var trade by remember {
    mutableStateOf(
      TradeUiState(
        quote = quotes.first(),
        marketDetails = previewBook(quotes.first()),
        candles = previewCandles(),
        timeframe = ChartTimeframe.ONE_MINUTE,
        stale = false,
        chartStyle = if (initialScreen == "candles") ChartStyle.CANDLESTICK else ChartStyle.LINE,
        tradingKeyAddress = "preview",
        tradingEnabled = initialScreen != "trade-offline",
        quoteBalance = 10000.0,
        sizeInput = if (initialScreen.startsWith("trade-")) "0.01" else "",
      )
    )
  }
  var section by remember { mutableStateOf(OrdersSection.OPEN) }
  var settings by remember { mutableStateOf(previewSettingsState(initialScreen)) }
  var portfolio by remember { mutableStateOf(previewPortfolioState(initialScreen)) }
  var previewPosition by remember { mutableStateOf<String?>(null) }
  Column(Modifier.fillMaxSize().background(FlareColors.Canvas).safeDrawingPadding()) {
    if (showLabel) {
      Text(
        "DESIGN PREVIEW · SAMPLE DATA",
        Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = FlareColors.TextTertiary,
      )
    }
    Box(Modifier.weight(1f)) {
      when (screen) {
        "splash" -> FlareSplashScreen()
        "welcome", "import", "import-phrase", "import-key", "backup", "backup-revealed", "confirm",
        "accounts", "create-account", "finding", "enable", "enable-working", "setup-error",
        "fee-can-pay", "fee-needs-funds", "fee-unchecked", "open-account", "open-account-working",
        "open-account-opened", "open-account-sponsor", "open-account-stalled" ->
          OnboardingScreen(onboarding, { onboarding = previewOnboardingIntent(onboarding, it) })
        "markets" ->
          MarketsScreen(
            MarketsUiState(
              loading = false,
              stale = false,
              query = query,
              favoritesOnly = favorites,
              quotes = quotes.filter { !favorites || it.favorite },
              searching = searching,
              searchResults = if (searching) searchMarkets(quotes, emptyMap(), query) else MarketSearchResults(),
              pages = mapOf(
                MarketInstrumentFilter.PERPETUALS to MarketPage(
                  favoritesOnly = favorites,
                  categories = listOf("commodity", "crypto", "equity"),
                  quotes = quotes.filter { it.market.assetType == AssetType.PERP && (!favorites || it.favorite) },
                ),
                MarketInstrumentFilter.SPOT to MarketPage(
                  favoritesOnly = false,
                  categories = listOf("crypto"),
                  quotes = quotes.filter { it.market.assetType == AssetType.SPOT },
                ),
              ),
              pageQuotes = marketPageQuotes(quotes, emptyMap()),
            ),
            { intent ->
              when (intent) {
                is MarketsIntent.Search -> query = intent.value
                MarketsIntent.OpenSearch -> { searching = true; query = "" }
                MarketsIntent.CloseSearch -> { searching = false; query = "" }
                is MarketsIntent.SetFavoritesOnly -> favorites = intent.enabled
                is MarketsIntent.ToggleFavorite -> quotes = quotes.map {
                    if (it.market.address == intent.marketAddress) it.copy(favorite = !it.favorite)
                    else it
                  }
                else -> Unit
              }
            },
            { address ->
              val quote = quotes.first { it.market.address == address }
              trade = trade.copy(quote = quote, marketDetails = previewBook(quote))
              screen = "chart"
            },
          )
        "chart",
        "candles",
        "ticket", "trade-success", "trade-failure", "trade-busy", "trade-offline", "trade-selfpay" ->
          TradeScreen(
            trade,
            { intent ->
              trade =
                when (intent) {
                  is TradeIntent.SelectChartStyle -> trade.copy(chartStyle = intent.style)
                  is TradeIntent.SelectTimeframe -> trade.copy(timeframe = intent.timeframe)
                  TradeIntent.ToggleRsi -> trade.copy(showRsi = !trade.showRsi)
                  TradeIntent.ToggleMacd -> trade.copy(showMacd = !trade.showMacd)
                  is TradeIntent.SetSize -> trade.copy(sizeInput = intent.value)
                  is TradeIntent.SetLimitPrice -> trade.copy(limitPriceInput = intent.value)
                  is TradeIntent.SetTakeProfit -> trade.copy(takeProfitInput = intent.value)
                  is TradeIntent.SetStopLoss -> trade.copy(stopLossInput = intent.value)
                  is TradeIntent.SetLeverage -> trade.copy(leverage = intent.value)
                  is TradeIntent.SetOrderType -> trade.copy(orderType = intent.type)
                  is TradeIntent.SetTwapDurationMinutes -> trade.copy(twapDurationMinutesInput = intent.value)
                  is TradeIntent.SetTwapFrequencyMinutes -> trade.copy(twapFrequencyMinutesInput = intent.value)
                  TradeIntent.ConfirmSelfPay -> trade.copy(transaction = TransactionState.Committed("preview-only"))
                  TradeIntent.DismissOrderReceipt -> trade.copy(transaction = null, sizeInput = "", orderError = null)
                  is TradeIntent.Submit -> when (initialScreen) {
                    "trade-success" -> trade.copy(transaction = TransactionState.Committed("preview-only"))
                    "trade-busy" -> trade.copy(orderBusy = true)
                    "trade-selfpay" -> trade.copy(
                      transaction = TransactionState.Failed(
                        "Sponsorship unavailable",
                        selfPayEstimateOctas = 2_400uL,
                      ),
                      apiWalletNeedsTopUp = true,
                      suggestedTopUpOctas = 50_000uL,
                    )
                    else -> trade.copy(orderError = "Preview only. No order was placed.")
                  }
                  else -> trade
                }
            },
            onBack = { screen = "markets" },
            onOpenActivity = { screen = "activity" },
          )
        "portfolio", "portfolio-loading", "portfolio-empty", "portfolio-stale", "portfolio-unavailable", "portfolio-many",
        "portfolio-showcase" ->
          previewPosition?.let { market ->
            PositionScreen(portfolio, market, { portfolio = previewPortfolioIntent(portfolio, it) }, { previewPosition = null })
          } ?: PortfolioScreen(
            portfolio,
            { portfolio = previewPortfolioIntent(portfolio, it) },
            { screen = "welcome" },
            onPositionClick = { previewPosition = it },
          )
        "withdraw-form", "withdraw-invalid", "withdraw-over", "withdraw-review", "withdraw-working",
        "withdraw-sending", "withdraw-selfpay", "withdraw-resume", "withdraw-sent", "withdraw-failed",
        "withdraw-uncertain" -> {
          var withdraw by remember { mutableStateOf(previewWithdrawState(screen)) }
          Box(Modifier.fillMaxSize())
          WithdrawSheet(
            withdraw,
            { intent ->
              withdraw = when (intent) {
                is SettingsIntent.ChangeWithdrawDestination -> withdraw.copy(withdrawDestination = intent.address)
                is SettingsIntent.ChangeWithdrawAmount -> withdraw.copy(withdrawAmount = intent.amount)
                SettingsIntent.EditWithdraw -> withdraw.copy(withdrawTransaction = null, withdrawError = null)
                else -> withdraw
              }
            },
            {},
            startInReview = screen !in setOf("withdraw-form", "withdraw-invalid", "withdraw-over"),
          )
        }
        "qr" ->
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FlareQrCode(
              "0xe05e75a9f25b254fcc354d5a68d7d15f7b97ac6ee56922fac3c1c27009d0c25b",
              "QR code preview",
              Modifier.size(282.dp),
            )
          }
        "activity" ->
          OrdersScreen(
            OrdersUiState(section = section),
            { intent ->
              if (intent is OrdersIntent.SelectSection) section = intent.section
            },
            { screen = "welcome" },
          )
        "account", "account-owner" ->
          SettingsScreen(
            settings,
            { intent ->
              val prefs = settings.preferences
              settings =
                when (intent) {
                  is SettingsIntent.SetSlippage ->
                    settings.copy(preferences = prefs.copy(slippageBps = intent.basisPoints))
                  is SettingsIntent.SetBuilderFeeBps ->
                    settings.copy(
                      preferences =
                        if (intent.product == AssetType.PERP)
                          prefs.copy(
                            builderFeeBps = intent.basisPoints,
                            builderApproved = prefs.builderApproved || intent.basisPoints > 0,
                          )
                        else
                          prefs.copy(
                            spotBuilderFeeBps = intent.basisPoints,
                            spotBuilderApproved = prefs.spotBuilderApproved || intent.basisPoints > 0,
                          )
                    )
                  is SettingsIntent.RevokeBuilderFee ->
                    settings.copy(
                      preferences =
                        if (intent.product == AssetType.PERP) prefs.copy(builderApproved = false)
                        else prefs.copy(spotBuilderApproved = false)
                    )
                  else -> settings
                }
            },
            { screen = "welcome" },
          )
      }
    }
  }
}

/** "account-owner" signs in with an owner key, which unlocks the builder approvals. */
private fun previewSettingsState(screen: String): SettingsUiState =
  if (screen != "account-owner") SettingsUiState()
  else
    SettingsUiState(
      profile = WalletProfile(ownerAddress = "0x7a3f0c21e98d4b65a1f2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6a7b8"),
      defaultBuilderAddress = "0x51c0de0b7e2a4f6c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e",
      preferences = FlarePreferences(builderApproved = true, builderFeeBps = 5),
    )

/** Each stage of a withdrawal, with sample data. */
private fun previewWithdrawState(screen: String): SettingsUiState {
  val address = "0xe05e75a9f25b254fcc354d5a68d7d15f7b97ac6ee56922fac3c1c27009d0c25b"
  val filled = SettingsUiState(withdrawDestination = address, withdrawAmount = "25", withdrawable = 998.51)
  return when (screen) {
    "withdraw-form" -> SettingsUiState(withdrawable = 998.51)
    "withdraw-invalid" -> filled.copy(withdrawDestination = "0xe05e75a9-not-an-address")
    "withdraw-over" -> filled.copy(withdrawAmount = "1200")
    "withdraw-working" -> filled.copy(withdrawing = true, withdrawTransaction = TransactionState.Submitting)
    "withdraw-sending" ->
      filled.copy(
        withdrawing = true,
        withdrawTransaction = TransactionState.Submitting,
        pendingWithdrawal = WithdrawalContinuation("0x1", address, "25", "0xabc", withdrawalCommitted = true),
      )
    "withdraw-selfpay" ->
      filled.copy(withdrawTransaction = TransactionState.Failed("Sponsor unavailable", selfPayEstimateOctas = 12_400uL))
    "withdraw-resume" ->
      filled.copy(pendingWithdrawal = WithdrawalContinuation("0x1", address, "25", "0xabc", withdrawalCommitted = true))
    "withdraw-sent" -> filled.copy(withdrawTransaction = TransactionState.Committed("0xfeed"))
    "withdraw-failed" ->
      filled.copy(
        withdrawTransaction = TransactionState.Failed("Move abort: INSUFFICIENT_BALANCE"),
        withdrawError = "Move abort: INSUFFICIENT_BALANCE",
      )
    "withdraw-uncertain" ->
      filled.copy(
        withdrawTransaction = TransactionState.Failed("timed out"),
        withdrawError = "timed out",
        pendingWithdrawal = WithdrawalContinuation("0x1", address, "25", "0xabc"),
      )
    else -> filled
  }
}

/** Enough of the portfolio's behaviour to walk through a position and its close sheet. */
private fun previewPortfolioIntent(state: PortfolioUiState, intent: PortfolioIntent): PortfolioUiState =
  when (intent) {
    is PortfolioIntent.ManagePosition -> state.copy(managedPositionMarket = intent.market)
    PortfolioIntent.DismissPositionManagement -> state.copy(managedPositionMarket = null)
    is PortfolioIntent.ChangeTakeProfit -> state.copy(takeProfitInput = intent.value)
    is PortfolioIntent.ChangeStopLoss -> state.copy(stopLossInput = intent.value)
    is PortfolioIntent.ClosePosition -> state.copy(closedMarket = intent.market)
    PortfolioIntent.CloseHandled -> state.copy(closedMarket = null)
    else -> state
  }

private fun previewQuotes(): List<MarketQuote> =
  listOf(
      Triple("BTC", 67432.18, 2.34),
      Triple("ETH", 3521.64, 1.82),
      Triple("SOL", 148.92, -0.67),
      Triple("APT", 8.42, 3.16),
      Triple("SUI", 1.23, -1.42),
      Triple("DOGE", 0.1428, 4.28),
      Triple("AVAX", 32.18, 0.94),
    )
    .mapIndexed { index, (symbol, price, change) ->
      MarketQuote(
        Market(
          AssetType.PERP,
          "0x${index + 100}",
          "$symbol/USD",
          4,
          20,
          1u,
          1u,
          1u,
          1000000.0,
          2,
          "open",
          0,
          "crypto",
          1u,
          100000000u,
          false,
        ),
        price,
        change,
        128400000.0,
        48200000.0,
        index < 2,
      )
    }

private fun previewCandles(): List<Candle> =
  List(180) { index ->
    val close = 65890.0 + index * 8.4 + sin(index * 0.19) * 175 + sin(index * 0.71) * 45
    Candle(
      index * 60_000L,
      (index + 1) * 60_000L,
      close - sin(index.toDouble()) * 55,
      close + 72,
      close - 90,
      close,
      100.0,
      "1m",
    )
  }

private fun previewBook(quote: MarketQuote) =
  MarketDetails(
    market = quote.market.address,
    orderBook =
      OrderBook(
        market = quote.market.address,
        timestamp = "0",
        bids = listOf(listOf(formatQuantity(quote.markPrice, 2), "10")),
        asks = listOf(listOf(formatQuantity(quote.markPrice, 2), "10")),
      ),
    stale = false,
  )

private fun previewPortfolio() =
  PortfolioUiState(
    profile = WalletProfile(ownerAddress = "preview"),
    spotHoldings =
      listOf(
        SpotHolding(
          symbol = "USDC",
          name = "USD Coin",
          marketAddress = null,
          quantity = 8240.32,
          markPrice = 1.0,
          valueUsd = 8240.32,
          isCollateral = true,
          badge = "CASH",
        ),
        SpotHolding(
          symbol = "APT",
          name = "Aptos",
          marketAddress = "0x26f",
          quantity = 100.0,
          markPrice = 10.50,
          valueUsd = 1050.0,
          isCollateral = false,
          badge = "SPOT",
        ),
      ),
    account =
      AccountSnapshot(
        overview =
          AccountOverview(
            12480.65,
            unrealizedPnl = 284.16,
            unrealizedFundingCost = 0.0,
            crossMarginRatio = 0.18,
            maintenanceMargin = 120.0,
            totalMargin = 1800.0,
            crossWithdrawableBalance = 8240.32,
            isolatedWithdrawableBalance = 0.0,
            availableToTrade = 8240.32,
          ),
        stale = false,
      ),
  )

/** Deterministic read states for visual checks; this activity never submits transactions. */
private fun previewPortfolioState(screen: String): PortfolioUiState {
  val loaded = previewPortfolio().copy(vaultsLoaded = true, chartLoaded = true, rewardsLoaded = true)
  return when (screen) {
    "portfolio-many" -> {
      val positions = (1..24).map { index ->
        xyz.mcxross.flare.decibel.model.Position("0xposition$index", "preview", "$index", 2, 10.0,
          false, false, 0.0, 5.0, index.toLong(), false)
      }
      val vaults = (1..12).map { index ->
        xyz.mcxross.flare.decibel.model.VaultInfo(address = "0xvault$index", name = "Vault $index",
          totalAum = index * 100_000.0)
      }
      loaded.copy(
        account = loaded.account.copy(positions = positions),
        marketSymbols = positions.mapIndexed { index, position -> position.market to "Market ${index + 1}" }.toMap(),
        markPrices = positions.associate { it.market to 12.0 },
        spotHoldings = loaded.spotHoldings.take(1) + (1..47).map { index ->
          SpotHolding("ASSET$index", "Asset $index", quantity = index.toDouble(), markPrice = 1.0, valueUsd = index.toDouble())
        },
        vaults = vaults,
        accountVaults = vaults.take(3).map {
          xyz.mcxross.flare.decibel.model.AccountVaultPerformance(vault = it, currentNumShares = 10.0, currentValue = 10.0)
        },
        amps = xyz.mcxross.flare.decibel.model.AmpsBreakdown(totalAmps = 2400.0, tradingAmps = 2200.0, vaultAmps = 200.0),
        tier = xyz.mcxross.flare.decibel.model.TierInfo(tier = "Silver"),
      )
    }
    // A believable account for product imagery: three positions in major markets, a vault, rewards.
    "portfolio-showcase" -> {
      val positions = listOf(
        xyz.mcxross.flare.decibel.model.Position("0xbtc", "preview", "0.12", 10, 81_740.0, false, false, 0.0, 74_210.0, 1, false),
        xyz.mcxross.flare.decibel.model.Position("0xeth", "preview", "-1.8", 5, 3_412.5, false, false, 0.0, 3_980.0, 2, false),
        xyz.mcxross.flare.decibel.model.Position("0xsol", "preview", "24", 3, 188.2, false, false, 0.0, 131.0, 3, false),
      )
      val vault = xyz.mcxross.flare.decibel.model.VaultInfo(address = "0xdlp", name = "DLP", totalAum = 12_400_000.0)
      loaded.copy(
        account = loaded.account.copy(
          positions = positions,
          overview = loaded.account.overview!!.copy(
            equityBalance = 12_480.65,
            unrealizedPnl = 446.46,
            freeVaultEquity = 1_214.6,
            spot = xyz.mcxross.flare.decibel.model.SpotOverview(totalUsd = 1_050.0),
          ),
        ),
        marketSymbols = mapOf("0xbtc" to "BTC", "0xeth" to "ETH", "0xsol" to "SOL"),
        markPrices = mapOf("0xbtc" to 83_909.0, "0xeth" to 3_356.4, "0xsol" to 191.75),
        accountVaults = listOf(
          xyz.mcxross.flare.decibel.model.AccountVaultPerformance(vault = vault, currentNumShares = 1_150.0, currentValue = 1_214.6)
        ),
        amps = xyz.mcxross.flare.decibel.model.AmpsBreakdown(totalAmps = 18_420.0, tradingAmps = 16_900.0, vaultAmps = 1_520.0),
        tier = xyz.mcxross.flare.decibel.model.TierInfo(tier = "Gold"),
      )
    }
    "portfolio-loading" -> loaded.copy(
      spotHoldings = emptyList(), account = AccountSnapshot(account = "preview", loading = true),
      vaultsLoaded = false, chartLoaded = false, rewardsLoaded = false,
    )
    "portfolio-empty" -> loaded.copy(
      spotHoldings = emptyList(), account = loaded.account.copy(overview = loaded.account.overview!!.copy(
        equityBalance = 0.0, unrealizedPnl = 0.0, availableToTrade = 0.0,
        crossWithdrawableBalance = 0.0, crossUsdcBalance = 0.0, freeVaultEquity = 0.0,
        spot = xyz.mcxross.flare.decibel.model.SpotOverview(),
      )),
    )
    "portfolio-stale" -> loaded.copy(
      account = loaded.account.copy(stale = true, error = "Offline"),
      vaultsError = "Offline", chartError = "Offline", rewardsError = true,
    )
    "portfolio-unavailable" -> loaded.copy(
      spotHoldings = emptyList(), account = AccountSnapshot(account = "preview", error = "Offline"),
      vaultsError = "Offline", chartError = "Offline", rewardsError = true,
    )
    else -> loaded
  }
}

private val PreviewWords =
  listOf("orbit", "velvet", "canyon", "ember", "mosaic", "harbor", "lunar", "quartz", "willow", "summit", "prism", "cobalt")

private val PreviewSubaccounts =
  listOf(
    Subaccount(address = "0x7a3f9c21e4b85d06a1f3c92b7e4d815a6c09b3e27f1d4a8c5e6b09f2d3a17c48", owner = "0x1", customLabel = "Main", isPrimary = true),
    Subaccount(address = "0x2e91b47c0d8a35f6e1c94b27a0d6f3e85c1b49a7d2e06f3c8b5a1d94e7f20c36", owner = "0x1", isPrimary = false),
  )

private fun previewOnboardingState(screen: String): OnboardingUiState {
  val owner = WalletProfile(ownerAddress = "0x1c4e8f2a9b7d3c6e5f0a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5a6")
  val backup =
    OnboardingUiState(
      step = OnboardingStep.SHOW_BACKUP,
      profile = owner,
      backupWords = PreviewWords,
      confirmationIndices = listOf(2, 6, 10),
      confirmationOptions = mapOf(
        2 to listOf("summit", "canyon", "ember"),
        6 to listOf("lunar", "prism", "velvet"),
        10 to listOf("orbit", "harbor", "prism"),
      ),
    )
  return when (screen) {
    "import" -> OnboardingUiState(step = OnboardingStep.IMPORT)
    "import-phrase" -> OnboardingUiState(step = OnboardingStep.IMPORT, input = PreviewWords.joinToString(" "))
    "import-key" -> OnboardingUiState(step = OnboardingStep.IMPORT, input = "ed25519-priv-0x" + "5f".repeat(32))
    "backup" -> backup
    "backup-revealed" -> backup.copy(backupRevealed = true)
    "confirm" -> backup.copy(step = OnboardingStep.CONFIRM_BACKUP, backupRevealed = true, confirmations = mapOf(2 to "canyon"))
    "accounts" -> OnboardingUiState(step = OnboardingStep.SUBACCOUNT, profile = owner, subaccounts = PreviewSubaccounts, subaccountsLoaded = true, selectedSubaccount = PreviewSubaccounts[0].address)
    "create-account" -> OnboardingUiState(step = OnboardingStep.SUBACCOUNT, profile = owner, subaccountsLoaded = true)
    "finding" -> OnboardingUiState(step = OnboardingStep.SUBACCOUNT, profile = owner, busy = true)
    "enable" -> OnboardingUiState(step = OnboardingStep.ENABLE_TRADING, profile = owner, selectedSubaccount = PreviewSubaccounts[0].address)
    "enable-working" -> OnboardingUiState(step = OnboardingStep.ENABLE_TRADING, profile = owner, selectedSubaccount = PreviewSubaccounts[0].address, busy = true)
    "fee-can-pay", "fee-needs-funds", "fee-unchecked" ->
      OnboardingUiState(
        step = OnboardingStep.ENABLE_TRADING,
        profile = owner,
        selectedSubaccount = PreviewSubaccounts[0].address,
        setupOperation = SetupOperation.ENABLE_TRADING,
        setupFee =
          when (screen) {
            "fee-can-pay" -> SetupFee.WalletCanPay(estimateOctas = 104_300uL, balanceOctas = 48_210_000uL)
            "fee-needs-funds" ->
              SetupFee.WalletNeedsFunds(104_300uL, reserveOctas = 5_000_000uL, balanceOctas = 0uL, address = owner.ownerAddress!!)
            else -> SetupFee.Unchecked(104_300uL)
          },
      )
    "open-account", "open-account-working", "open-account-opened", "open-account-sponsor", "open-account-stalled" ->
      OnboardingUiState(
        step = OnboardingStep.OPEN_ACCOUNT,
        profile = owner,
        accountOpened = screen == "open-account-opened" || screen == "open-account-stalled",
        busy = screen == "open-account-working" || screen == "open-account-opened",
        setupOperation =
          if (screen == "open-account-stalled") SetupOperation.ENABLE_TRADING else SetupOperation.CREATE_ACCOUNT,
        setupFee = if (screen == "open-account-sponsor") SetupFee.AwaitingSponsor(104_300uL) else null,
        error =
          if (screen == "open-account-stalled") "Trading wasn’t enabled on this device. Flare couldn’t reach the network."
          else null,
      )
    "setup-error" -> OnboardingUiState(step = OnboardingStep.ENABLE_TRADING, profile = owner, selectedSubaccount = PreviewSubaccounts[0].address, error = "Trading wasn’t enabled on this device. The network didn’t respond.")
    else -> OnboardingUiState()
  }
}

/** Walks the preview through setup without keys, network or transactions. */
private fun previewOnboardingIntent(state: OnboardingUiState, intent: OnboardingIntent): OnboardingUiState =
  when (intent) {
    OnboardingIntent.ShowImport -> OnboardingUiState(step = OnboardingStep.IMPORT)
    OnboardingIntent.CreateOwner -> previewOnboardingState("backup")
    is OnboardingIntent.ChangeInput -> state.copy(input = intent.value)
    is OnboardingIntent.SetApiImport -> state.copy(apiImport = intent.enabled)
    is OnboardingIntent.ChangeTradingAccount -> state.copy(tradingAccountInput = intent.value)
    OnboardingIntent.ImportCredential -> previewOnboardingState("accounts")
    OnboardingIntent.RevealBackup -> state.copy(backupRevealed = true)
    OnboardingIntent.ReviewBackup -> state.copy(step = OnboardingStep.CONFIRM_BACKUP)
    is OnboardingIntent.ChangeConfirmation -> state.copy(confirmations = state.confirmations + (intent.index to intent.value))
    OnboardingIntent.ConfirmBackup -> previewOnboardingState("open-account")
    OnboardingIntent.OpenAccount -> state.copy(busy = true, error = null, setupFee = null)
    OnboardingIntent.CreateSubaccount, OnboardingIntent.DiscoverSubaccounts -> previewOnboardingState("accounts")
    is OnboardingIntent.SelectSubaccount -> state.copy(selectedSubaccount = intent.address)
    OnboardingIntent.ContinueSubaccount -> previewOnboardingState("enable")
    is OnboardingIntent.SetBuilderOptIn -> state.copy(builderOptIn = intent.enabled)
    OnboardingIntent.EnableTrading, OnboardingIntent.ConfirmSetupSelfPay, OnboardingIntent.RetrySetup ->
      state.copy(busy = true)
    OnboardingIntent.Back ->
      when (state.step) {
        OnboardingStep.CONFIRM_BACKUP -> state.copy(step = OnboardingStep.SHOW_BACKUP)
        OnboardingStep.ENABLE_TRADING -> previewOnboardingState("accounts")
        else -> OnboardingUiState()
      }
    else -> state
  }

