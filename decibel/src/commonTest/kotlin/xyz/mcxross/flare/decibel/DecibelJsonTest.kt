package xyz.mcxross.flare.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Candle
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.decibel.model.FundingPayment
import xyz.mcxross.flare.decibel.model.Market
import xyz.mcxross.flare.decibel.model.Page
import xyz.mcxross.flare.decibel.model.Position
import xyz.mcxross.flare.decibel.model.Subaccount

class DecibelJsonTest {
  @Test
  fun decodesDocumentedMarketShape() {
    val market =
      DecibelClient.DefaultJson.decodeFromString<Market>(
        """{"asset_type":"perp","market_addr":"0x1","market_name":"BTC/USD","sz_decimals":9,"max_leverage":50,"tick_size":1000000,"min_size":100000000,"lot_size":100000000,"max_open_interest":1000000.0,"px_decimals":9,"mode":"Open","unrealized_pnl_haircut_bps":1000,"category":"crypto","min_price":1,"max_price":90000000000000,"is_isolated_only":false}"""
      )

    assertEquals(AssetType.PERP, market.assetType)
    assertEquals("BTC", market.symbol)
    assertEquals(1_000_000uL, market.tickSize)
  }

  @Test
  fun decodesDocumentedCandleShape() {
    val candle =
      DecibelClient.DefaultJson.decodeFromString<Candle>(
        """{"t":1761588000000,"T":1761591599999,"o":100.0,"h":102.0,"l":98.0,"c":101.0,"v":1000.0,"i":"1h"}"""
      )

    assertEquals(101.0, candle.close)
    assertEquals("1h", candle.interval)
  }

  @Test
  fun preservesPositionSizeDecimalText() {
    val position =
      DecibelClient.DefaultJson.decodeFromString<Position>(
        """{"market":"0x1","user":"0x2","size":-0.000000001,"user_leverage":10,"entry_price":100.0,"is_isolated":false,"is_deleted":false,"unrealized_funding":0.0,"estimated_liquidation_price":50.0,"transaction_version":1,"has_fixed_sized_tpsls":false}"""
      )

    assertEquals("-0.000000001", position.size)
  }

  @Test
  fun decodesDocumentedFundingHistoryPage() {
    val page =
      DecibelClient.DefaultJson.decodeFromString<Page<FundingPayment>>(
        """{"items":[{"market":"0x1","action":"Close Long","size":1.0,"realized_funding_amount":-15.5,"is_rebate":false,"fee_amount":0.01,"transaction_unix_ms":1735758000000}],"total_count":7}"""
      )

    assertEquals(7, page.totalCount)
    assertEquals(-15.5, page.items.single().realizedFundingAmount)
  }

  @Test
  fun decodesCurrentSubaccountShapeWithoutLosingAddress() {
    val subaccount =
      DecibelClient.DefaultJson.decodeFromString<Subaccount>(
        """{"subaccount_address":"0x11","primary_account_address":"0x22","custom_label":null,"is_primary":true,"is_active":true}"""
      )

    assertEquals("0x11", subaccount.address)
    assertEquals("0x22", subaccount.owner)
    assertEquals("", subaccount.name)
    assertEquals(true, subaccount.isPrimary)
  }

  @Test
  fun decodesCurrentDelegationPermissionAndExpiry() {
    val delegation =
      DecibelClient.DefaultJson.decodeFromString<Delegation>(
        """{"delegated_account":"0x33","expiration_time_s":1736326800,"permission_type":"TradePerpsAllMarkets","permission_market":null}"""
      )

    assertEquals("0x33", delegation.delegate)
    assertEquals(1_736_326_800L, delegation.expirationTimeSeconds)
    assertEquals(true, delegation.canTradeAllPerpMarkets)
  }
}
