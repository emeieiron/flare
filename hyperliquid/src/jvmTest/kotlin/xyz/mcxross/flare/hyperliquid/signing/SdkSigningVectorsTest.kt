package xyz.mcxross.flare.hyperliquid.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mcxross.fastkrypto.secp256k1PublicKeyUncompressed
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment

/**
 * Golden tests against the official Python SDK (hyperliquid-python-sdk 0.24.0, commit 2fdb18f):
 * every case of `tests/signing_test.py`, plus vectors generated with the SDK's own functions for
 * the actions its tests don't cover (see [SdkVectors]). They check action hashes and the exact
 * EIP-712 signing input; the signatures themselves are checked by the FastKrypto signer's tests.
 *
 * These run on the JVM only: Keccak-256 comes from FastKrypto's native library, which Android
 * host tests cannot load (the Android artifact ships Android ABIs only).
 */
class SdkSigningVectorsTest {
  @Test
  fun phantomAgentMatchesProduction() {
    // test_phantom_agent_creation_matches_production
    val case = VectorCases.l1.first { it.name == "phantomAgent" }
    assertEquals(
      "0x0fcbeda5ae3c4950a548021552a4fea2226858c4453571bf3f24ba017eac2908",
      L1Signing.actionHash(case.action, case.nonce).toHex0x(),
    )
  }

  @Test
  fun l1ActionHashesMatch() {
    for (case in VectorCases.l1) {
      assertEquals(
        case.vector.actionHash,
        L1Signing.actionHash(case.action, case.nonce, case.vaultAddress, case.expiresAfter)
          .toHex0x(),
        case.name,
      )
    }
  }

  @Test
  fun l1SigningInputsMatch() {
    for (case in VectorCases.l1) {
      for ((deployment, expected) in
        listOf(
          HyperliquidDeployment.Mainnet to case.vector.mainnetSigningInput,
          HyperliquidDeployment.Testnet to case.vector.testnetSigningInput,
        )) {
        val input =
          L1Signing.signingInput(
            case.action,
            case.nonce,
            deployment,
            case.vaultAddress,
            case.expiresAfter,
          )
        assertEquals(Eip712.SIGNING_INPUT_SIZE, input.size)
        assertEquals(expected, input.toHex0x(), "${case.name} ${deployment.network}")
      }
    }
  }

  @Test
  fun userSignedSigningInputsMatch() {
    val testnet = HyperliquidDeployment.Testnet
    for (case in VectorCases.user) {
      val input =
        UserSigning.signingInput(case.action.message(testnet, case.nonce), testnet.signatureChainId)
      assertEquals(case.vector.signingInput, input.toHex0x(), case.name)
    }
  }

  @Test
  fun hashInputAppendsNonceVaultAndExpiryAsTheSdkDoes() {
    val action = VectorCases.l1.first { it.name == "scheduleCancel" }.action
    val packed = MsgPack.encode(action).toHexDigits()

    assertEquals(
      packed + "00000000000000ff" + "00",
      L1Signing.hashInput(action, 255L).toHexDigits(),
    )
    assertEquals(
      packed + "00000000000000ff" + "01" + VectorCases.VAULT.drop(2),
      L1Signing.hashInput(action, 255L, VectorCases.VAULT).toHexDigits(),
    )
    assertEquals(
      packed + "00000000000000ff" + "00" + "00" + "0000000000000100",
      L1Signing.hashInput(action, 255L, expiresAfter = 256L).toHexDigits(),
    )
  }

  @Test
  fun derivesTheSdkTestAddress() {
    val key = decodeHex(SdkVectors.PRIVATE_KEY)
    val address = EvmAddress.fromPublicKey(secp256k1PublicKeyUncompressed(key))

    assertEquals(SdkVectors.ADDRESS, address)
    assertEquals(SdkVectors.CHECKSUM_ADDRESS, EvmAddress.checksum(address))
  }

  @Test
  fun parsesSignatureChainIds() {
    assertEquals(421_614L, UserSigning.parseChainId("0x66eee"))
    assertEquals(42_161L, UserSigning.parseChainId("0xa4b1"))
  }
}
