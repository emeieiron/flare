package xyz.mcxross.flare.hyperliquid.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FastKryptoSignerTest {
  @Test
  fun neverShowsTheKey() {
    val signer = FastKryptoSigner.fromHex(SdkVectors.PRIVATE_KEY)

    assertEquals("FastKryptoSigner(${SdkVectors.ADDRESS})", signer.toString())
    assertFalse(signer.toString().contains("0123456789012345"))
  }

  @Test
  fun rejectsBadKeysWithoutEchoingThem() {
    val tooShort = assertFailsWith<IllegalArgumentException> { FastKryptoSigner.fromHex("0x1234") }
    assertFalse(tooShort.message.orEmpty().contains("1234"))
    val notHex =
      assertFailsWith<IllegalArgumentException> { FastKryptoSigner.fromHex("zz".repeat(32)) }
    assertFalse(notHex.message.orEmpty().contains("zz"))
    // Zero is not a valid secp256k1 scalar.
    assertFailsWith<IllegalArgumentException> { FastKryptoSigner(ByteArray(32)) }
  }

  @Test
  fun signsOnlyEip712SigningInput() = runTest {
    val signer = FastKryptoSigner.fromHex(SdkVectors.PRIVATE_KEY)

    assertFailsWith<IllegalArgumentException> { signer.signTypedData(ByteArray(32)) }
    assertFailsWith<IllegalArgumentException> { signer.signTypedData(ByteArray(66)) }
  }

  @Test
  fun signaturesRecoverTheSigner() = runTest {
    val signer = FastKryptoSigner(keccak256("flare".encodeToByteArray()))
    val input = L1Signing.signingInput(ByteArray(32) { it.toByte() }, "b")

    val signature = signer.signTypedData(input)

    assertTrue(signature.v == 27 || signature.v == 28)
    val recoverable =
      decodeHex(signature.r) + decodeHex(signature.s) + byteArrayOf((signature.v - 27).toByte())
    assertEquals(
      signer.address,
      EvmAddress.fromPublicKey(
        xyz.mcxross.fastkrypto.secp256k1RecoverPublicKeyKeccak256(input, recoverable)
      ),
    )
    assertEquals("EvmSignature(<redacted>)", signature.toString())
  }

  @Test
  fun matchesTheEip712MailExample() = runTest {
    // EIP-712's Mail example: key keccak256("cow"), signer 0xCD2a…D826.
    val signer = FastKryptoSigner(keccak256("cow".encodeToByteArray()))
    val input =
      decodeHex(
        "1901f2cee375fa42b42143804025fc449deafd50cc031ca257e0b194a650a912090f" +
          "c52c0ee5d84264471806290a3f2c4cecfc5490626bf912d01f240d7a274b371e"
      )

    val signature = signer.signTypedData(input)

    assertEquals("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826", EvmAddress.checksum(signer.address))
    assertEquals("0x4355c47d63924e8a72e509b65029052eb6c299d53a04e167c5775fd466751c9d", signature.r)
    assertEquals("0x07299936d304c153f6443dfa05f40ff007d72911b6f72307f996231605b91562", signature.s)
    assertEquals(28, signature.v)
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
}
