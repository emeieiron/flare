package xyz.mcxross.flare.hyperliquid.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlDecimalTest {
  @Test
  fun parsesAndNormalizesPlainNotation() {
    val cases =
      listOf(
        "1.500" to "1.5",
        "-0" to "0",
        "-0.000" to "0",
        ".5" to "0.5",
        "-.5" to "-0.5",
        "5." to "5",
        "007.10" to "7.1",
        "0.0012" to "0.0012",
        "-83108.0" to "-83108",
        "100" to "100",
        "0" to "0",
        "123456789012345678" to "123456789012345678",
        "0.123456789012345678" to "0.123456789012345678",
        "0.000000000000000001" to "0.000000000000000001",
        "1.0000000000000000000000" to "1",
      )
    cases.forEach { (text, expected) -> assertEquals(expected, text.toHlDecimal().toString(), text) }
  }

  @Test
  fun keepsNormalizedMantissaAndScale() {
    val value = "1.2500".toHlDecimal()
    assertEquals(125L, value.unscaled)
    assertEquals(2, value.scale)
    assertEquals(HlDecimal.ZERO, "-0.0".toHlDecimal())
    assertEquals("2".toHlDecimal(), "2.000".toHlDecimal())
    assertEquals("2".toHlDecimal().hashCode(), "2.000".toHlDecimal().hashCode())
    assertEquals(HlDecimal.of(-15, 1), "-1.5".toHlDecimal())
    assertEquals("1200", HlDecimal.of(12, -2).toString())
  }

  @Test
  fun rejectsAnythingButPlainDecimals() {
    listOf(
        "1e5",
        "1E5",
        "+1",
        "1,000",
        "--1",
        "-",
        ".",
        "",
        "1.2.3",
        "abc",
        "0x10",
        "1 000",
        "NaN",
        "Infinity",
        "１",
        // 19 significant digits, 19 decimal places.
        "1234567890123456789",
        "0.0000000000000000001",
      )
      .forEach { text ->
        assertNull(HlDecimal.parseOrNull(text), text)
        assertFailsWith<IllegalArgumentException>(text) { HlDecimal.parse(text) }
      }
  }

  @Test
  fun comparesAcrossScalesAndSigns() {
    assertTrue("1.5".toHlDecimal() > "1.25".toHlDecimal())
    assertTrue("-1.5".toHlDecimal() < "-1.25".toHlDecimal())
    assertTrue("-0.5".toHlDecimal() < "0.3".toHlDecimal())
    assertTrue("-1".toHlDecimal() < "-0.999".toHlDecimal())
    assertTrue("100".toHlDecimal() > "99.9999999999999999".toHlDecimal())
    assertTrue("0.000000000000000001".toHlDecimal() > HlDecimal.ZERO)
    assertTrue("-0.000000000000000001".toHlDecimal() < HlDecimal.ZERO)
    assertEquals(0, "2".toHlDecimal().compareTo("2.000".toHlDecimal()))
    // Rescaling either side to the other's scale would overflow; comparing must not.
    assertTrue("999999999999999999".toHlDecimal() > "0.999999999999999999".toHlDecimal())
    assertTrue("-999999999999999999".toHlDecimal() < "-0.999999999999999999".toHlDecimal())
    val sorted = listOf("3", "-2.5", "0.01", "-2.49", "0", "2.999").map(String::toHlDecimal).sorted()
    assertEquals(listOf("-2.5", "-2.49", "0", "0.01", "2.999", "3"), sorted.map { it.toString() })
  }

  @Test
  fun addsSubtractsAndMultipliesExactly() {
    assertEquals("0.3", ("0.1".toHlDecimal() + "0.2".toHlDecimal()).toString())
    assertEquals("-0.75", ("1.5".toHlDecimal() - "2.25".toHlDecimal()).toString())
    assertEquals("-0.5", ("1.25".toHlDecimal() * "-0.4".toHlDecimal()).toString())
    assertEquals("0", ("2.5".toHlDecimal() - "2.50".toHlDecimal()).toString())
    assertEquals(
      "0.000000000000000001",
      ("0.000000001".toHlDecimal() * "0.000000001".toHlDecimal()).toString(),
    )
    assertEquals("1000", ("0.1".toHlDecimal() * "10000".toHlDecimal()).toString())
    assertEquals("1.5", "-1.5".toHlDecimal().abs().toString())
    assertEquals("-1.5", (-"1.5".toHlDecimal()).toString())
  }

  @Test
  fun arithmeticOverflowThrows() {
    val large = "999999999999999999".toHlDecimal()
    assertFailsWith<ArithmeticException> { large * "10".toHlDecimal() }
    assertFailsWith<ArithmeticException> { HlDecimal.of(Long.MAX_VALUE) + HlDecimal.ONE }
    assertFailsWith<ArithmeticException> { HlDecimal.of(Long.MIN_VALUE) - HlDecimal.ONE }
    assertFailsWith<ArithmeticException> { -HlDecimal.of(Long.MIN_VALUE) }
    // Rescaling the integer part to 18 places cannot fit.
    assertFailsWith<ArithmeticException> { large + "0.000000000000000001".toHlDecimal() }
    // 19 decimal places.
    assertFailsWith<ArithmeticException> {
      "0.000000001".toHlDecimal() * "0.0000000001".toHlDecimal()
    }
  }

  @Test
  fun everyRoundingModeToUnits() {
    // value to DOWN, UP, FLOOR, CEILING, HALF_UP, HALF_EVEN
    val table =
      mapOf(
        "2.5" to listOf("2", "3", "2", "3", "3", "2"),
        "1.5" to listOf("1", "2", "1", "2", "2", "2"),
        "2.4" to listOf("2", "3", "2", "3", "2", "2"),
        "2.6" to listOf("2", "3", "2", "3", "3", "3"),
        "0.5" to listOf("0", "1", "0", "1", "1", "0"),
        "-2.5" to listOf("-2", "-3", "-3", "-2", "-3", "-2"),
        "-1.5" to listOf("-1", "-2", "-2", "-1", "-2", "-2"),
        "-2.4" to listOf("-2", "-3", "-3", "-2", "-2", "-2"),
        "-2.6" to listOf("-2", "-3", "-3", "-2", "-3", "-3"),
        "-0.5" to listOf("0", "-1", "-1", "0", "-1", "0"),
        "2" to listOf("2", "2", "2", "2", "2", "2"),
        "-2" to listOf("-2", "-2", "-2", "-2", "-2", "-2"),
      )
    val modes =
      listOf(
        HlRounding.DOWN,
        HlRounding.UP,
        HlRounding.FLOOR,
        HlRounding.CEILING,
        HlRounding.HALF_UP,
        HlRounding.HALF_EVEN,
      )
    table.forEach { (value, expected) ->
      modes.zip(expected).forEach { (mode, result) ->
        assertEquals(result, value.toHlDecimal().roundToDecimals(0, mode).toString(), "$value $mode")
      }
    }
  }

  @Test
  fun roundsToDecimalPlaces() {
    assertEquals("1.01", "1.005".toHlDecimal().roundToDecimals(2, HlRounding.HALF_UP).toString())
    assertEquals("1", "1.005".toHlDecimal().roundToDecimals(2, HlRounding.HALF_EVEN).toString())
    assertEquals("1.02", "1.015".toHlDecimal().roundToDecimals(2, HlRounding.HALF_EVEN).toString())
    assertEquals("1.005", "1.00505".toHlDecimal().roundToDecimals(4, HlRounding.HALF_EVEN).toString())
    assertEquals("-1.01", "-1.0051".toHlDecimal().roundToDecimals(2, HlRounding.HALF_EVEN).toString())
    assertEquals("1.23", "1.23".toHlDecimal().roundToDecimals(5, HlRounding.UP).toString())
    // Just below and above a tie.
    assertEquals("0", "0.4999999".toHlDecimal().roundToDecimals(0, HlRounding.HALF_UP).toString())
    assertEquals("1", "0.5000001".toHlDecimal().roundToDecimals(0, HlRounding.HALF_EVEN).toString())
    assertEquals(
      "1",
      "0.999999999999999999".toHlDecimal().roundToDecimals(17, HlRounding.HALF_UP).toString(),
    )
  }

  @Test
  fun roundsToTensAndBeyond() {
    assertEquals("12300", "12345".toHlDecimal().roundToDecimals(-2, HlRounding.HALF_UP).toString())
    assertEquals("12400", "12350".toHlDecimal().roundToDecimals(-2, HlRounding.HALF_EVEN).toString())
    assertEquals("12200", "12250".toHlDecimal().roundToDecimals(-2, HlRounding.HALF_EVEN).toString())
    assertEquals("12400", "12301".toHlDecimal().roundToDecimals(-2, HlRounding.CEILING).toString())
    assertEquals("-12400", "-12301".toHlDecimal().roundToDecimals(-2, HlRounding.FLOOR).toString())
    // Past 10^18 the divisor no longer fits a Long.
    assertEquals("0", "5".toHlDecimal().roundToDecimals(-19, HlRounding.DOWN).toString())
    assertEquals("0", "5".toHlDecimal().roundToDecimals(-19, HlRounding.HALF_UP).toString())
    assertEquals("0", "5.5".toHlDecimal().roundToDecimals(-25, HlRounding.HALF_EVEN).toString())
    assertFailsWith<ArithmeticException> {
      "5".toHlDecimal().roundToDecimals(-19, HlRounding.UP)
    }
    // 9 × 10^18 is past half of 10^19, so it rounds up to 10^19, which does not fit.
    assertFailsWith<ArithmeticException> {
      HlDecimal.of(9_000_000_000_000_000_000L).roundToDecimals(-19, HlRounding.HALF_UP)
    }
    assertEquals(
      "0",
      HlDecimal.of(4_000_000_000_000_000_000L).roundToDecimals(-19, HlRounding.HALF_UP).toString(),
    )
  }

  @Test
  fun roundsToSignificantFigures() {
    fun sf(value: String, figures: Int, rounding: HlRounding = HlRounding.HALF_UP) =
      value.toHlDecimal().roundToSignificantFigures(figures, rounding).toString()

    assertEquals("100000", sf("99999.5", 5))
    assertEquals("99999", sf("99999.4", 5))
    assertEquals("99999", sf("99999.5", 5, HlRounding.DOWN))
    assertEquals("1", sf("0.99999", 3))
    assertEquals("123460000", sf("123456789", 5))
    assertEquals("123450000", sf("123456789", 5, HlRounding.DOWN))
    assertEquals("1000000000000000000", sf("999999999999999999", 5))
    assertEquals("0.00012346", sf("0.000123456", 5))
    assertEquals("-1.2346", sf("-1.23456", 5))
    assertEquals("-1.2346", sf("-1.23451", 5, HlRounding.FLOOR))
    assertEquals("-1.2345", sf("-1.23459", 5, HlRounding.CEILING))
    assertEquals("12.3", sf("12.3", 5))
    assertEquals("83108", sf("83108", 5))
    assertEquals("0", sf("0", 5))
    assertEquals("2", sf("2.5", 1, HlRounding.HALF_EVEN))
    assertFailsWith<IllegalArgumentException> { sf("1.5", 0) }
  }

  @Test
  fun countsSignificantFiguresAndPlaces() {
    assertEquals(3, "123000".toHlDecimal().significantFigures)
    assertEquals(2, "0.0012".toHlDecimal().significantFigures)
    assertEquals(2, "1.50".toHlDecimal().significantFigures)
    assertEquals(4, "100.5".toHlDecimal().significantFigures)
    assertEquals(0, "0".toHlDecimal().significantFigures)
    assertEquals(4, "0.0012".toHlDecimal().decimalPlaces)
    assertTrue("12.000".toHlDecimal().isInteger)
  }

  @Test
  fun writesWireForm() {
    assertEquals("1.5", "1.50000000".toHlDecimal().toWire())
    assertEquals("100", "100".toHlDecimal().toWire())
    assertEquals("0.00000001", "0.00000001".toHlDecimal().toWire())
    assertEquals("0", "-0.0".toHlDecimal().toWire())
    assertEquals("-2529.2", "-2529.20".toHlDecimal().toWire())
    assertFailsWith<IllegalArgumentException> { "0.000000001".toHlDecimal().toWire() }
    assertFailsWith<IllegalArgumentException> { "1.123456789".toHlDecimal().toWire() }
  }

  /**
   * The official Python SDK's `float_to_wire` formats with `"%.8f"`, then strips trailing zeros
   * (`Decimal.normalize`, written back in plain notation) and turns `-0` into `0`.
   */
  @Test
  fun matchesPythonSdkFloatToWire() {
    val cases =
      listOf(
        "0.10000000" to "0.1",
        "1.00000000" to "1",
        "100.00000000" to "100",
        "-0.00000000" to "0",
        "1234.50000000" to "1234.5",
        "0.00001234" to "0.00001234",
        "0.00000001" to "0.00000001",
        "83108.00000000" to "83108",
        "-2529.20000000" to "-2529.2",
        "123456.00000000" to "123456",
        "0.01000000" to "0.01",
      )
    cases.forEach { (formatted, wire) ->
      assertEquals(wire, formatted.toHlDecimal().toWire(), formatted)
    }
  }

  @Test
  fun writesJsonNumbersInPlainNotation() {
    assertEquals("0.0000006645107064126772", plainNumberText("6.645107064126772e-7"))
    assertEquals("0.0000006645107064126772", plainNumberText("6.645107064126772E-07"))
    assertEquals("100", plainNumberText("1E+2"))
    assertEquals("-0.00125", plainNumberText("-1.25E-3"))
    assertEquals("15", plainNumberText("1.5e1"))
    assertEquals("1.2", plainNumberText("12e-1"))
    assertEquals("0.05069748912465818", plainNumberText("0.05069748912465818"))
    assertEquals("0.0", plainNumberText("0.0"))
    assertEquals("0.00000012", plainNumberText("1.2e-7").toHlDecimal().toString())
  }
}
