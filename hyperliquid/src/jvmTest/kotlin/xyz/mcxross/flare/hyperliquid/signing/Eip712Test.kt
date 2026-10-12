package xyz.mcxross.flare.hyperliquid.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Eip712Test {
  @Test
  fun encodesFlatStructsPerEip712() {
    // EIP-712's Mail example, flattened: Person(string name,address wallet).
    val person =
      Eip712Struct(
        "Person",
        listOf(
          "name" to Eip712Value.Str("Cow"),
          "wallet" to Eip712Value.Address("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"),
        ),
      )
    assertEquals("Person(string name,address wallet)", person.encodedType)
    assertEquals(
      "0xb9d8c78acf9b987311de6c7b45bb6a9c8e1bf361fa7fd3467a2163f994c79500",
      person.typeHash().toHex0x(),
    )
    assertEquals(
      "0xfc71e5fa27ff56c350aa531bc129ebdf613b772b6604664f5d8dbe21b85eb0c8",
      person.hashStruct().toHex0x(),
    )
    assertEquals(
      "0xf2cee375fa42b42143804025fc449deafd50cc031ca257e0b194a650a912090f",
      Eip712.domain("Ether Mail", "1", 1L, "0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC")
        .hashStruct()
        .toHex0x(),
    )
  }

  @Test
  fun buildsTheSigningInput() {
    val input = L1Signing.signingInput(ByteArray(32), "a")

    assertEquals(66, input.size)
    assertEquals("0x1901", input.copyOfRange(0, 2).toHex0x())
    assertEquals(L1Signing.domain.hashStruct().toHex0x(), input.copyOfRange(2, 34).toHex0x())
  }

  @Test
  fun rejectsValuesOutOfRange() {
    assertFailsWith<IllegalArgumentException> { Eip712Value.Uint64(-1L) }
    assertFailsWith<IllegalArgumentException> { Eip712Value.Bytes32(ByteArray(31)) }
    assertFailsWith<IllegalArgumentException> { Eip712Value.Address("0x1234") }
  }

  @Test
  fun checksumsAddressesPerEip55() {
    // Test cases from EIP-55.
    listOf(
        "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        "0x52908400098527886E0F7030069857D2E4169EE7",
        "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
        "0xde709f2102306220921060314715629080e2fb77",
        "0x27b1fdb04752bbc536007a920d24acb045561c26",
      )
      .forEach { address ->
        assertEquals(address, EvmAddress.checksum(address.lowercase()))
        assertTrue(EvmAddress.isValidChecksum(address))
      }
    assertFalse(EvmAddress.isValidChecksum("0x5aaEb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
    assertTrue(EvmAddress.isValidChecksum("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"))
  }

  @Test
  fun buildsSignaturesFromRecoverableBytes() {
    val bytes = ByteArray(65) { if (it < 32) 0x11 else 0x22 }
    bytes[64] = 1

    val signature = EvmSignature.fromRecoverable(bytes)

    assertEquals("0x" + "11".repeat(32), signature.r)
    assertEquals("0x" + "22".repeat(32), signature.s)
    assertEquals(28, signature.v)
    assertEquals("EvmSignature(<redacted>)", signature.toString())
    assertFailsWith<IllegalArgumentException> { EvmSignature.fromRecoverable(ByteArray(64)) }
    assertFailsWith<IllegalArgumentException> {
      EvmSignature.fromRecoverable(ByteArray(65).also { it[64] = 2 })
    }
  }
}
