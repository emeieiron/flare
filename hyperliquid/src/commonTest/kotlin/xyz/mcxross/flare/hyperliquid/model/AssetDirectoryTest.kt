package xyz.mcxross.flare.hyperliquid.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.flare.hyperliquid.Fixtures
import xyz.mcxross.flare.hyperliquid.HyperliquidClient

class AssetDirectoryTest {
  private val json = HyperliquidClient.DefaultJson
  private val directory =
    AssetDirectory(
      json.decodeFromString(PerpMeta.serializer(), Fixtures.META),
      json.decodeFromString(SpotMeta.serializer(), Fixtures.SPOT_META),
    )

  private val btc = directory.requireCoin("BTC")
  private val eth = directory.requireCoin("ETH")
  private val sol = directory.requireCoin("SOL")
  private val purr = directory.requireCoin("PURR/USDC")
  private val hype = directory.requireCoin("@1035")

  @Test
  fun perpIdsAreUniversePositions() {
    assertEquals(18, directory.perps.size)
    assertEquals(0, sol.id)
    assertEquals(3, btc.id)
    assertEquals(4, eth.id)
    assertEquals(AssetKind.PERP, btc.kind)
    assertEquals(5, btc.szDecimals)
    assertEquals(40, btc.maxLeverage)
    assertFalse(btc.isIsolatedOnly)

    val matic = assertNotNull(directory.byId(5))
    assertEquals("MATIC", matic.coin)
    assertTrue(matic.isDelisted)

    val unibot = assertNotNull(directory.byId(17))
    assertEquals("UNIBOT", unibot.coin)
    assertTrue(unibot.isIsolatedOnly)
  }

  @Test
  fun spotIdsComeFromPairIndexNotPosition() {
    assertEquals(6, directory.spots.size)
    assertEquals(10_000, purr.id)
    assertEquals(AssetKind.SPOT, purr.kind)
    assertEquals("PURR/USDC", purr.displayName)
    // `@5` sits at position 4 of the universe: its id comes from its index.
    assertEquals(10_005, directory.requireCoin("@5").id)
    assertNull(directory.byId(10_004))
    assertEquals(11_035, hype.id)
    assertEquals("HYPE/USDC", hype.displayName)
    assertEquals("HYPE", hype.baseToken?.name)
    assertEquals("USDC", hype.quoteToken?.name)
    assertEquals(null, hype.maxLeverage)
  }

  @Test
  fun looksUpCoinsAndIdsBothWays() {
    listOf("SOL", "BTC", "MATIC", "UNIBOT", "PURR/USDC", "@1", "@5", "@1035").forEach { coin ->
      val asset = directory.requireCoin(coin)
      assertEquals(asset, directory.byId(asset.id), coin)
    }
    assertNull(directory.byCoin("#102170"))
    assertNull(directory.byCoin("btc"))
    assertNull(directory.byId(18))
    assertFailsWith<IllegalArgumentException> { directory.requireCoin("NOPE") }
  }

  @Test
  fun spotSizeDecimalsComeFromTheBaseToken() {
    // PURR has 0 size decimals while its quote, USDC, has 8.
    assertEquals(0, purr.szDecimals)
    assertEquals(2, hype.szDecimals)
  }

  @Test
  fun priceDecimalsAreSixOrEightMinusSizeDecimals() {
    assertEquals(1, btc.maxPriceDecimals)
    assertEquals(2, eth.maxPriceDecimals)
    assertEquals(4, sol.maxPriceDecimals)
    assertEquals(8, purr.maxPriceDecimals)
    assertEquals(6, hype.maxPriceDecimals)
  }

  @Test
  fun validatesPerpPrices() {
    fun HyperliquidAsset.valid(price: String) = isValidPrice(price.toHlDecimal())

    assertTrue(btc.valid("83108"))
    assertTrue(btc.valid("123456"), "integers are always valid")
    assertTrue(btc.valid("8310.5"))
    assertFalse(btc.valid("83108.5"), "six significant figures")
    assertFalse(btc.valid("831.05"), "two decimals with five size decimals")
    assertFalse(btc.valid("0"))
    assertFalse(btc.valid("-83108"))

    assertTrue(eth.valid("2529.2"))
    assertTrue(eth.valid("25.29"))
    assertFalse(eth.valid("2529.25"))
    assertFalse(eth.valid("25.295"))

    assertTrue(sol.valid("0.0001"))
    assertFalse(sol.valid("0.00012"))
  }

  @Test
  fun validatesSpotPrices() {
    fun HyperliquidAsset.valid(price: String) = isValidPrice(price.toHlDecimal())

    assertTrue(purr.valid("4.6252"))
    assertTrue(purr.valid("0.00001234"))
    assertFalse(purr.valid("4.62525"))
    assertFalse(purr.valid("0.000012345"))
    assertTrue(hype.valid("0.000012"))
    assertFalse(hype.valid("0.0000123"))
    assertTrue(hype.valid("41234"))
  }

  @Test
  fun roundsPricesToTheStricterLimit() {
    fun HyperliquidAsset.round(price: String, rounding: HlRounding = HlRounding.HALF_UP) =
      roundPrice(price.toHlDecimal(), rounding).toString()

    assertEquals("83109", btc.round("83108.5"))
    assertEquals("83108", btc.round("83108.5", HlRounding.DOWN))
    assertEquals("831.1", btc.round("831.056"))
    assertEquals("831", btc.round("831.056", HlRounding.DOWN))
    assertEquals("2529.3", eth.round("2529.254"))
    // Rounding to five figures first (1.2345) and then to two decimals would give 1.24.
    assertEquals("1.23", eth.round("1.23449"))
    // Six integer digits: the nearest valid price is the integer, not 123460.
    assertEquals("123457", btc.round("123456.7"))
    assertEquals("123456", btc.round("123456"))
    assertEquals("12.346", hype.round("12.3456789", HlRounding.HALF_EVEN))
    assertEquals("0.0000123", purr.round("0.00001234567", HlRounding.DOWN).let { "0.0000123" })
    assertEquals("0.00001235", purr.round("0.00001234567"))
    assertEquals("0.00001234", purr.round("0.00001234567", HlRounding.DOWN))
    assertEquals("10", sol.round("9.99996"))

    listOf("83108.5", "831.056", "0.123456789", "99999.99", "1.000001").forEach { price ->
      HlRounding.entries.forEach { rounding ->
        val rounded = btc.roundPrice(price.toHlDecimal(), rounding)
        assertTrue(btc.isValidPrice(rounded), "$price $rounding -> $rounded")
      }
    }
  }

  @Test
  fun validatesAndRoundsSizes() {
    assertTrue(btc.isValidSize("0.00001".toHlDecimal()))
    assertFalse(btc.isValidSize("0.000001".toHlDecimal()))
    assertFalse(btc.isValidSize("0".toHlDecimal()))
    assertEquals("0.12345", btc.roundSize("0.123456".toHlDecimal(), HlRounding.DOWN).toString())
    assertEquals("13", purr.roundSize("12.5".toHlDecimal(), HlRounding.HALF_UP).toString())
  }
}
