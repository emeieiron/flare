package xyz.mcxross.flare.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals

class HyperliquidDeploymentTest {
  /** What hyperliquid-python-sdk's signing.py signs with `is_mainnet=True`. */
  @Test
  fun signsForMainnetAndTalksToItsApi() {
    val deployment = HyperliquidConfig().deployment

    assertEquals(HyperliquidDeployment.Mainnet, deployment)
    assertEquals("a", deployment.agentSource)
    assertEquals("Mainnet", deployment.hyperliquidChain)
    assertEquals("0x66eee", deployment.signatureChainId)
    assertEquals("https://api.hyperliquid.xyz", deployment.restUrl)
    assertEquals("wss://api.hyperliquid.xyz/ws", deployment.webSocketUrl)
  }
}
