package xyz.mcxross.flare.hyperliquid.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MsgPackTest {
  @Test
  fun matchesThePythonSdkForEveryVectorAction() {
    for (case in VectorCases.l1) {
      assertEquals(case.vector.msgpack, MsgPack.encode(case.action).toHexDigits(), case.name)
    }
  }

  @Test
  fun keepsMapInsertionOrder() {
    val action = buildJsonObject {
      put("z", 1)
      put("a", 2)
    }
    assertEquals("82a17a01a16102", hex(action))
  }

  @Test
  fun encodesNonNegativeIntegersInTheirSmallestUnsignedForm() {
    assertEquals("00", hex(number(0)))
    assertEquals("7f", hex(number(127)))
    assertEquals("cc80", hex(number(128)))
    assertEquals("ccff", hex(number(255)))
    assertEquals("cd0100", hex(number(256)))
    assertEquals("cdffff", hex(number(65_535)))
    assertEquals("ce00010000", hex(number(65_536)))
    assertEquals("ceffffffff", hex(number(4_294_967_295L)))
    assertEquals("cf0000000100000000", hex(number(4_294_967_296L)))
    assertEquals("cf7fffffffffffffff", hex(number(Long.MAX_VALUE)))
    assertEquals("cfffffffffffffffff", hex(literal("18446744073709551615")))
  }

  @Test
  fun encodesNegativeIntegersInTheirSmallestSignedForm() {
    assertEquals("ff", hex(number(-1)))
    assertEquals("e0", hex(number(-32)))
    assertEquals("d0df", hex(number(-33)))
    assertEquals("d080", hex(number(-128)))
    assertEquals("d1ff7f", hex(number(-129)))
    assertEquals("d18000", hex(number(-32_768)))
    assertEquals("d2ffff7fff", hex(number(-32_769)))
    assertEquals("d280000000", hex(number(Int.MIN_VALUE.toLong())))
    assertEquals("d3ffffffff7fffffff", hex(number(Int.MIN_VALUE.toLong() - 1)))
    assertEquals("d38000000000000000", hex(number(Long.MIN_VALUE)))
  }

  @Test
  fun encodesStringsByUtf8Length() {
    assertEquals("a0", hex(JsonPrimitive("")))
    assertEquals("bf" + "61".repeat(31), hex(JsonPrimitive("a".repeat(31))))
    assertEquals("d920" + "61".repeat(32), hex(JsonPrimitive("a".repeat(32))))
    assertEquals("d9ff" + "61".repeat(255), hex(JsonPrimitive("a".repeat(255))))
    assertEquals("da0100" + "61".repeat(256), hex(JsonPrimitive("a".repeat(256))))
    assertEquals("a3e282ac", hex(JsonPrimitive("€")))
    // Digits stay strings: prices and sizes are never numbers on the wire.
    assertEquals("a3313030", hex(JsonPrimitive("100")))
  }

  @Test
  fun encodesBooleansNilArraysAndLargeContainers() {
    assertEquals("c3", hex(JsonPrimitive(true)))
    assertEquals("c2", hex(JsonPrimitive(false)))
    assertEquals("c0", hex(JsonNull))
    assertEquals("90", hex(JsonArray(emptyList())))
    assertEquals("80", hex(JsonObject(emptyMap())))
    val sixteen = JsonArray(List(16) { JsonPrimitive(1) })
    assertEquals("dc0010" + "01".repeat(16), hex(sixteen))
    val bigMap = JsonObject((0 until 16).associate { "k${it.toString(16)}" to JsonPrimitive(0) })
    assertEquals(
      "de0010" +
        (0 until 16).joinToString("") { "a26b" + it.toString(16)[0].code.toString(16) + "00" },
      hex(bigMap),
    )
  }

  @Test
  fun rejectsFractionalAndExponentNumbers() {
    assertFailsWith<IllegalArgumentException> { MsgPack.encode(JsonPrimitive(1.5)) }
    assertFailsWith<IllegalArgumentException> { MsgPack.encode(literal("1e3")) }
  }

  private fun hex(value: JsonElement): String = MsgPack.encode(value).toHexDigits()

  private fun number(value: Long): JsonElement = JsonPrimitive(value)

  private fun number(value: Int): JsonElement = JsonPrimitive(value)

  @OptIn(ExperimentalSerializationApi::class)
  private fun literal(text: String): JsonElement = JsonUnquotedLiteral(text)
}
