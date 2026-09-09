package xyz.mcxross.flare

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlin.math.sin
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.decibel.model.*
import xyz.mcxross.flare.design.*
import xyz.mcxross.flare.feature.markets.*
import xyz.mcxross.flare.feature.onboarding.*
import xyz.mcxross.flare.feature.orders.*
import xyz.mcxross.flare.feature.portfolio.*
import xyz.mcxross.flare.feature.settings.*
import xyz.mcxross.flare.feature.trade.*

/** Offline design harness. Debug source set only; no repositories, keys, or transactions. */
class DesignPreviewActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val initialScreen = intent.getStringExtra("screen") ?: "welcome"
    setContent { FlareTheme { PreviewScreens(initialScreen) } }
  }
}

@Composable
private fun PreviewScreens(initialScreen: String) {
  var screen by remember { mutableStateOf(initialScreen) }
  var onboarding by remember {
    mutableStateOf(
      OnboardingUiState(
        step = if (initialScreen == "import") OnboardingStep.IMPORT else OnboardingStep.WELCOME
      )
    )
  }
  var quotes by remember { mutableStateOf(previewQuotes()) }
  var query by remember { mutableStateOf("") }
  var favorites by remember { mutableStateOf(false) }
  var trade by remember {
    mutableStateOf(
      TradeUiState(
        quote = quotes.first(),
        marketDetails = previewBook(quotes.first()),
        candles = previewCandles(),
        stale = false,
        chartStyle = if (initialScreen == "candles") ChartStyle.CANDLESTICK else ChartStyle.LINE,
        expectedSignerAddress = "preview",
        sessionSignerAddress = "preview",
        tradingEnabled = true,
      )
    )
  }
  var section by remember { mutableStateOf(OrdersSection.OPEN) }
  var settings by remember { mutableStateOf(SettingsUiState()) }
  Column(Modifier.fillMaxSize().background(FlareColors.Canvas).safeDrawingPadding()) {
    Text(
      "DESIGN PREVIEW · SAMPLE DATA",
      Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
      style = MaterialTheme.typography.labelSmall,
      color = FlareColors.TextTertiary,
    )
    Box(Modifier.weight(1f)) {
      when (screen) {
        "welcome",
        "import" ->
          OnboardingScreen(
            onboarding,
            { intent ->
              onboarding =
                when (intent) {
                  OnboardingIntent.ShowImport -> onboarding.copy(step = OnboardingStep.IMPORT)
                  is OnboardingIntent.ChangeInput -> onboarding.copy(input = intent.value)
                  is OnboardingIntent.SetApiImport -> onboarding.copy(apiImport = intent.enabled)
                  OnboardingIntent.Back -> OnboardingUiState()
                  else ->
                    onboarding.copy(
                      error = "Preview only. Account creation and import are disabled."
                    )
                }
            },
          )
        "markets" ->
          MarketsScreen(
            MarketsUiState(
              loading = false,
              stale = false,
              query = query,
              favoritesOnly = favorites,
              quotes =
                quotes.filter {
                  (!favorites || it.favorite) && it.market.name.contains(query, ignoreCase = true)
                },
            ),
            { intent ->
              when (intent) {
                is MarketsIntent.Search -> query = intent.value
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
        "ticket" ->
          TradeScreen(
            trade,
            { intent ->
              trade =
                when (intent) {
                  is TradeIntent.SelectChartStyle -> trade.copy(chartStyle = intent.style)
                  is TradeIntent.SelectRange -> trade.copy(range = intent.range)
                  TradeIntent.ToggleRsi -> trade.copy(showRsi = !trade.showRsi)
                  TradeIntent.ToggleMacd -> trade.copy(showMacd = !trade.showMacd)
                  is TradeIntent.SetSize -> trade.copy(sizeInput = intent.value)
                  is TradeIntent.SetLimitPrice -> trade.copy(limitPriceInput = intent.value)
                  is TradeIntent.SetTakeProfit -> trade.copy(takeProfitInput = intent.value)
                  is TradeIntent.SetStopLoss -> trade.copy(stopLossInput = intent.value)
                  is TradeIntent.SetLeverage -> trade.copy(leverage = intent.value)
                  is TradeIntent.SetOrderType -> trade.copy(orderType = intent.type)
                  is TradeIntent.Submit ->
                    trade.copy(orderError = "Preview only. No order was placed.")
                  else -> trade
                }
            },
            onBack = { screen = "markets" },
          )
        "portfolio" -> PortfolioScreen(previewPortfolio(), {}, { screen = "welcome" })
        "activity" ->
          OrdersScreen(
            OrdersUiState(section = section),
            { intent ->
              if (intent is OrdersIntent.SelectSection) section = intent.section
            },
            { screen = "welcome" },
          )
        "account" ->
          SettingsScreen(
            settings,
            { intent ->
              if (intent is SettingsIntent.SetSlippage)
                settings =
                  settings.copy(
                    preferences = settings.preferences.copy(slippageBps = intent.basisPoints)
                  )
            },
            { screen = "welcome" },
          )
      }
    }
  }
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
