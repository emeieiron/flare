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
}
