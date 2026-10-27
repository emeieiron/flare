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
      val input =
        L1Signing.signingInput(
          case.action,
          case.nonce,
          HyperliquidDeployment.Mainnet,
          case.vaultAddress,
          case.expiresAfter,
        )
      assertSignature(case.vector.mainnet, signer.signTypedData(input), case.name)
    }
  }

  @Test
  fun userSignedSignaturesMatch() = runTest {
    val mainnet = HyperliquidDeployment.Mainnet
    for (case in VectorCases.user) {
      val input =
        UserSigning.signingInput(case.action.message(mainnet, case.nonce), mainnet.signatureChainId)
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
    assertEquals(27, SdkVectors.DUMMY.mainnet.v)
    assertEquals(
      "0xe302c107c0048633293299b066317048f69f685aa4f536fe56c050efaf04996c",
      SdkVectors.PHANTOM_AGENT.mainnet.r,
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
