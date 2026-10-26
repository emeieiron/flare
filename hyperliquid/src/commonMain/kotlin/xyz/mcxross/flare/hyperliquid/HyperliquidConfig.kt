package xyz.mcxross.flare.hyperliquid

import kotlinx.serialization.Serializable

/**
 * Hyperliquid's public endpoints and the network constants its signatures commit to.
 *
 * L1 actions (orders, cancels, leverage, TWAP, vault transfers) are signed over a phantom agent
 * whose `source` names the network. User-signed actions (agent and builder-fee approvals,
 * withdrawals, transfers) carry `hyperliquidChain` and `signatureChainId` in the signed message.
 */
@Serializable
data class HyperliquidDeployment(
  val restUrl: String,
  val webSocketUrl: String,
  /** The phantom agent's `source`. */
  val agentSource: String,
  /** `hyperliquidChain` in user-signed actions. */
  val hyperliquidChain: String,
  /**
   * The EIP-712 chain id user-signed actions declare, as hex. Hyperliquid accepts any chain id
   * that matches the signature; `0x66eee` (Arbitrum Sepolia) is what the official SDK sends.
   */
  val signatureChainId: String = "0x66eee",
) {
  companion object {
    /** Hyperliquid mainnet. Flare trades nothing else. */
    val Mainnet =
      HyperliquidDeployment(
        restUrl = "https://api.hyperliquid.xyz",
        webSocketUrl = "wss://api.hyperliquid.xyz/ws",
        agentSource = "a",
        hyperliquidChain = "Mainnet",
      )
  }
}

/**
 * Runtime endpoints default to the deployment's public ones; Hyperliquid needs no credentials for
 * reads, so the app talks to it directly.
 */
data class HyperliquidConfig(
  val deployment: HyperliquidDeployment = HyperliquidDeployment.Mainnet,
  val restBaseUrl: String = deployment.restUrl,
  val webSocketUrl: String = deployment.webSocketUrl,
)
