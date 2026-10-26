package xyz.mcxross.flare.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import xyz.mcxross.kaptos.core.crypto.sha3Hash
import xyz.mcxross.kaptos.model.HexInput

class DecibelDeploymentTest {
  /**
   * Mainnet collateral is Circle's native USDC, an asset Decibel does not own. A package-minted
   * collateral token would be the package's own `SHA3-256(package || "USDC" || 0xfe)` object, so
   * that derivation is the negative case here: matching it would mean the deployment points at a
   * token the package minted instead of the real asset.
   */
  @Test
  fun mainnetCollateralIsCirclesNativeUsdc() {
    val deployment = DecibelDeployment.Mainnet
    assertEquals(
      "0xbae207659db88bea0cbead6da0ed00aac12edcdda169e591cd41c94180b46f3b",
      deployment.usdcMetadataAddress,
    )
    assertEquals(1u.toUByte(), deployment.chainId)

    val seed =
      HexInput.fromString(deployment.packageAddress.removePrefix("0x")).toByteArray() +
        "USDC".encodeToByteArray() +
        byteArrayOf(0xfe.toByte())
    val packageMinted =
      "0x" + sha3Hash(seed).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    assertNotEquals(packageMinted, deployment.usdcMetadataAddress)
  }
}
