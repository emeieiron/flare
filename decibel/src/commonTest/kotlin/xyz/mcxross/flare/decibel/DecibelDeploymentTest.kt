package xyz.mcxross.flare.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mcxross.kaptos.core.crypto.sha3Hash
import xyz.mcxross.kaptos.model.HexInput

class DecibelDeploymentTest {
  @Test
  fun testnetCollateralIsThePackagesNamedUsdcObject() {
    val deployment = DecibelDeployment.Testnet
    val seed =
      HexInput.fromString(deployment.packageAddress.removePrefix("0x")).toByteArray() +
        "USDC".encodeToByteArray() +
        byteArrayOf(0xfe.toByte())
    val derived =
      "0x" + sha3Hash(seed).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    assertEquals(derived, deployment.usdcMetadataAddress)
    assertEquals(2u.toUByte(), deployment.chainId)
  }
}
