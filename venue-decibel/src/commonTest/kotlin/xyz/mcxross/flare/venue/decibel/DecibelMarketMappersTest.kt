package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.DecibelNetwork
import xyz.mcxross.flare.decibel.api.MarketDepthUpdate
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.CandleInterval
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.model.Candle
import xyz.mcxross.flare.venue.model.ChartTimeframe
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.MarketTrade
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderBookLevel
import xyz.mcxross.flare.decibel.model.Candle as DecibelCandle
import xyz.mcxross.flare.decibel.model.Market as DecibelMarket
import xyz.mcxross.flare.decibel.model.MarketTrade as DecibelMarketTrade
import xyz.mcxross.flare.decibel.model.OrderBook as DecibelOrderBook

class DecibelMarketMappersTest {
  private val json = DecibelClient.DefaultJson

  @Test
  fun networksKeepTheirNames() {
    Network.entries.forEach { network ->
      assertEquals(network.name, network.toDecibelNetwork().name)
    }
    assertEquals(DecibelNetwork.entries.size, Network.entries.size)
  }

  @Test
  fun assetTypesBecomeMarketKinds() {
    assertEquals(MarketKind.PERP, AssetType.PERP.toMarketKind())
    assertEquals(MarketKind.SPOT, AssetType.SPOT.toMarketKind())
  }

  @Test
  fun aDocumentedMarketKeepsItsIdPrecisionAndLimits() {
    val market = json.decodeFromString<DecibelMarket>(MARKET_JSON).toMarket()

    assertEquals(
      Market(
        id = "0x1",
        symbol = "BTC",
        displayName = "BTC/USD",
        kind = MarketKind.PERP,
        category = "crypto",
        priceDecimals = 9,
        sizeDecimals = 9,
        maxLeverage = 50,
        isolatedOnly = false,
        tickSize = 1_000_000uL,
        lotSize = 100_000_000uL,
        minSize = 100_000_000uL,
        minPrice = 1uL,
        maxPrice = 90_000_000_000_000uL,
      ),
      market,
    )
  }

  @Test
  fun aSpotMarketNamedWithADashKeepsItsTicker() {
    val market =
      json
        .decodeFromString<DecibelMarket>(
          MARKET_JSON.replace("\"perp\"", "\"spot\"")
            .replace("BTC/USD", "apt-usdc")
            .replace("\"is_isolated_only\":false", "\"is_isolated_only\":true")
        )
        .toMarket()

    assertEquals("APT", market.symbol)
    assertEquals("apt-usdc", market.displayName)
    assertEquals(MarketKind.SPOT, market.kind)
    assertTrue(market.isolatedOnly)
  }

  @Test
  fun everyTimeframeHasItsOwnDecibelInterval() {
    assertEquals(
      listOf("1m", "5m", "15m", "30m", "1h", "2h", "4h", "8h", "12h", "1d", "1w", "1mo"),
      ChartTimeframe.entries.map { it.toCandleInterval().wireValue },
    )
    assertEquals(CandleInterval.entries.toSet(), ChartTimeframe.entries.map { it.toCandleInterval() }.toSet())
    ChartTimeframe.entries.forEach { timeframe ->
      assertEquals(timeframe, chartTimeframeOf(timeframe.toCandleInterval().wireValue))
    }
    assertNull(chartTimeframeOf("1M"))
    assertNull(chartTimeframeOf("3m"))
  }

  @Test
  fun aStreamedCandleKeepsItsPricesAndTimeframe() {
    val candle =
      json
        .decodeFromString<DecibelCandle>(
          """{"t":1790518620000,"T":1790518679999,"o":340.35,"h":340.35,"l":340.14,"c":340.14,"v":0.12,"i":"1m"}"""
        )
        .toCandle()

    assertEquals(
      Candle(
        openTimeMs = 1_790_518_620_000L,
        closeTimeMs = 1_790_518_679_999L,
        open = 340.35,
        high = 340.35,
        low = 340.14,
        close = 340.14,
        volume = 0.12,
        timeframe = ChartTimeframe.ONE_MINUTE,
      ),
      candle,
    )
  }

  @Test
  fun aCandleOfAnUnknownIntervalHasNoTimeframe() {
    val candle =
      json
        .decodeFromString<DecibelCandle>(
          """{"t":1761588000000,"T":1761591599999,"o":100.0,"h":102.0,"l":98.0,"c":101.0,"v":1000.0,"i":"3h"}"""
        )
        .toCandle()

    assertNull(candle.timeframe)
    assertEquals(101.0, candle.close)
  }

  @Test
  fun cachedCandlesKeepDecibelsWireInterval() {
    val decibel =
      json.decodeFromString<DecibelCandle>(
        """{"t":1761588000000,"T":1761591599999,"o":100.0,"h":102.0,"l":98.0,"c":101.0,"v":1000.0,"i":"1mo"}"""
      )
    val entity = decibel.toCandleEntity("0x1", CandleInterval.ONE_MONTH)

    assertEquals("0x1", entity.marketAddress)
    assertEquals("1mo", entity.interval)
    assertEquals(decibel.toCandle(), entity.toCandle())
    assertEquals(ChartTimeframe.ONE_MONTH, entity.toCandle().timeframe)
  }

  @Test
  fun anOrderBookKeepsItsLevelsAsDecimalText() {
    val book =
      json
        .decodeFromString<DecibelOrderBook>(
          """{"ticker_id":"0x1","timestamp":"1761588000000","bids":[["100.0","2"],["99.5","0.000000001"]],"asks":[["100.125","1.25"]]}"""
        )
        .toOrderBook()

    assertEquals(
      OrderBook(
        marketId = "0x1",
        bids = listOf(OrderBookLevel("100.0", "2"), OrderBookLevel("99.5", "0.000000001")),
        asks = listOf(OrderBookLevel("100.125", "1.25")),
      ),
      book,
    )
    assertEquals("100.0", book.bestBid)
    assertEquals("100.125", book.bestAsk)
  }

  @Test
  fun aBookLevelMissingItsPriceOrSizeIsLeftOut() {
    val book =
      DecibelOrderBook("0x1", "0", bids = listOf(listOf("100"), listOf("99", "1")), asks = listOf(emptyList()))
        .toOrderBook()

    assertEquals(listOf(OrderBookLevel("99", "1")), book.bids)
    assertNull(book.bestAsk)
  }

  @Test
  fun aStreamedDepthUpdateBecomesTheSameBook() {
    val update =
      json.decodeFromString<MarketDepthUpdate>(
        """{"market":"0x1","asks":[{"price":100.125,"size":1.25}],"bids":[{"price":100.0,"size":2}],"best_ask":100.125,"best_bid":100.0,"unix_ms":1000,"sequence":7}"""
      )

    val book = update.toOrderBook().toOrderBook()

    assertEquals("0x1", book.marketId)
    assertEquals(update.bids.map { OrderBookLevel(it.price, it.size) }, book.bids)
    assertEquals(listOf(OrderBookLevel("100.125", "1.25")), book.asks)
    assertEquals("2", book.bids.single().size)
  }

  @Test
  fun aTradeKeepsItsPriceSizeAndTime() {
    val trade = json.decodeFromString<DecibelMarketTrade>(TRADE_JSON).toMarketTrade()

    assertEquals(
      MarketTrade(
        marketId = "0x2",
        isBuy = false,
        price = 50_000.0,
        size = 0.25,
        timeMs = 1_735_758_000_000L,
      ),
      trade,
    )
  }

  @Test
  fun onlyABuyActionReadsAsABuy() {
    fun isBuy(action: String) =
      json
        .decodeFromString<DecibelMarketTrade>(TRADE_JSON.replace("OpenLong", action))
        .toMarketTrade()
        .isBuy

    assertTrue(isBuy("buy"))
    assertTrue(isBuy("Buy"))
    assertFalse(isBuy("sell"))
    assertFalse(isBuy("OpenLong"))
  }

  @Test
  fun aMarketCachedByAnEarlierVersionStillLoads() {
    // MarketQuote as earlier versions encoded it, with Decibel's market inside.
    val legacy =
      """{"market":$MARKET_JSON,"markPrice":62500.25,"changePercent24h":-1.25,"volume24h":1.2E7,"openInterest":4000000.0,"fundingRateBps":0.5}"""

    val quote = json.decodeFromString<CachedMarketQuote>(legacy).toMarketQuote()

    assertEquals(
      MarketQuote(
        market = json.decodeFromString<DecibelMarket>(MARKET_JSON).toMarket(),
        markPrice = 62_500.25,
        changePercent24h = -1.25,
        volume24h = 12_000_000.0,
        openInterest = 4_000_000.0,
        favorite = false,
        fundingRateBps = 0.5,
      ),
      quote,
    )
  }

  @Test
  fun cachedMarketQuoteRoundTripsWithoutPersistingFavoriteState() {
    val quote =
      CachedMarketQuote(
        market =
          DecibelMarket(
            assetType = AssetType.PERP,
            address = "0xdec1be1",
            name = "BTC-USD",
            sizeDecimals = 4,
            maxLeverage = 20,
            tickSize = 10u,
            minSize = 100u,
            lotSize = 10u,
            maxOpenInterest = 1_000_000.0,
            priceDecimals = 2,
            mode = "active",
            unrealizedPnlHaircutBps = 100,
            category = "crypto",
            minPrice = 100u,
            maxPrice = 100_000_000u,
            isIsolatedOnly = false,
          ),
        markPrice = 62_500.25,
        changePercent24h = -1.25,
        volume24h = 12_000_000.0,
        openInterest = 4_000_000.0,
        favorite = false,
      )

    val encoded = json.encodeToString(quote)
    val restored = json.decodeFromString<CachedMarketQuote>(encoded)

    assertEquals(quote, restored)
  }

  private companion object {
    const val MARKET_JSON =
      """{"asset_type":"perp","market_addr":"0x1","market_name":"BTC/USD","sz_decimals":9,"max_leverage":50,"tick_size":1000000,"min_size":100000000,"lot_size":100000000,"max_open_interest":1000000.0,"px_decimals":9,"mode":"Open","unrealized_pnl_haircut_bps":1000,"category":"crypto","min_price":1,"max_price":90000000000000,"is_isolated_only":false}"""

    const val TRADE_JSON =
      """{"asset_type":"perp","account":"0x1","market":"0x2","action":"OpenLong","source":"OrderFill","trade_id":"7","size":0.25,"price":50000.0,"is_profit":false,"realized_pnl_amount":0.0,"realized_funding_amount":0.0,"is_rebate":false,"fee_amount":0.1,"order_id":"9","client_order_id":"","transaction_unix_ms":1735758000000,"transaction_version":42,"counter_party_account":"0x3"}"""
  }
}
