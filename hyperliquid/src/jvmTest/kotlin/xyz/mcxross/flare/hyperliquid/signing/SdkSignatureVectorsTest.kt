package xyz.mcxross.flare.hyperliquid.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment

/**
 * The final r/s/v of every [SdkVectors] case, signed by [FastKryptoSigner] with the SDK tests'
 * key: `tests/signing_test.py`'s own values, plus signatures generated with
 * hyperliquid-python-sdk 0.24.0 (commit 2fdb18f) for the actions its tests don't cover.
 */
class SdkSignatureVectorsTest {
  private val signer = FastKryptoSigner.fromHex(SdkVectors.PRIVATE_KEY)

  @Test
  fun derivesTheSdkTestAddress() {
    assertEquals(SdkVectors.ADDRESS, signer.address)
  }

  @Test
  fun l1SignaturesMatch() = runTest {
    for (case in VectorCases.l1) {
      for ((deployment, expected) in
        listOf(
          HyperliquidDeployment.Mainnet to case.vector.mainnet,
          HyperliquidDeployment.Testnet to case.vector.testnet,
        )) {
        val input =
          L1Signing.signingInput(
            case.action,
            case.nonce,
            deployment,
            case.vaultAddress,
            case.expiresAfter,
          )
        assertSignature(expected, signer.signTypedData(input), "${case.name} ${deployment.network}")
      }
    }
  }

  @Test
  fun userSignedSignaturesMatch() = runTest {
    val testnet = HyperliquidDeployment.Testnet
    for (case in VectorCases.user) {
      val input =
        UserSigning.signingInput(case.action.message(testnet, case.nonce), testnet.signatureChainId)
      assertSignature(case.vector.signature, signer.signTypedData(input), case.name)
    }
  }

  @Test
  fun vectorsCarrySigningTestPyValuesVerbatim() {
    // Spot checks against literals in signing_test.py.
    assertEquals(
      "0x53749d5b30552aeb2fca34b530185976545bb22d0b3ce6f62e31be961a59298",
      SdkVectors.DUMMY.mainnet.r,
    )
    assertEquals(28, SdkVectors.DUMMY.testnet.v)
    assertEquals(
      "0x637b37dd731507cdd24f46532ca8ba6eec616952c56218baeff04144e4a77073",
      SdkVectors.USD_SEND.signature.r,
    )
    assertEquals(
      "0x8363524c799e90ce9bc41022f7c39b4e9bdba786e5f9c72b20e43e1462c37cf9",
      SdkVectors.WITHDRAW3.signature.r,
    )
  }

  /**
   * The SDK prints r and s with `to_hex(int)`, dropping leading zeros; Flare always sends 32
   * bytes. Same number, so compare without the leading zeros.
   */
  private fun assertSignature(expected: EvmSignatureVector, actual: EvmSignature, what: String) {
    assertEquals(66, actual.r.length, "$what r width")
    assertEquals(66, actual.s.length, "$what s width")
    assertEquals(expected.r.stripWord(), actual.r.stripWord(), "$what r")
    assertEquals(expected.s.stripWord(), actual.s.stripWord(), "$what s")
    assertEquals(expected.v, actual.v, "$what v")
  }

  private fun String.stripWord(): String = removePrefix("0x").trimStart('0')
}
