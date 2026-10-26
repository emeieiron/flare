package xyz.mcxross.flare.decibel

import kotlinx.serialization.Serializable

@Serializable
data class DecibelCapabilities(
  val gasSponsorship: Boolean = false,
  val encryptedOrders: Boolean = false,
)

@Serializable
data class DecibelDeployment(
  val packageAddress: String,
  val usdcMetadataAddress: String,
  val chainId: UByte,
  val upstreamRestUrl: String,
  val upstreamWebSocketUrl: String,
  val aptosFullnodeUrl: String,
) {
  companion object {
    /** Decibel on Aptos mainnet. Flare trades nothing else. */
    val Mainnet =
      DecibelDeployment(
        packageAddress = "0x50ead22afd6ffd9769e3b3d6e0e64a2a350d68e8b102c4e72e33d0b8cfdfdb06",
        // Circle's native USDC, an asset Decibel does not own, so it is not derived from the
        // package the way a package-minted collateral token would be.
        usdcMetadataAddress = "0xbae207659db88bea0cbead6da0ed00aac12edcdda169e591cd41c94180b46f3b",
        chainId = 1u,
        upstreamRestUrl = "https://api.mainnet.aptoslabs.com/decibel",
        upstreamWebSocketUrl = "wss://api.mainnet.aptoslabs.com/decibel/ws",
        aptosFullnodeUrl = "https://api.mainnet.aptoslabs.com/v1",
      )
  }
}

/**
 * Runtime endpoints deliberately differ from [DecibelDeployment] upstream endpoints. Production
 * applications point these at the Flare Worker so upstream credentials never ship in the app.
 */
data class DecibelConfig(
  val deployment: DecibelDeployment = DecibelDeployment.Mainnet,
  val restBaseUrl: String = deployment.upstreamRestUrl,
  val webSocketUrl: String = deployment.upstreamWebSocketUrl,
  val origin: String = "flare://mobile",
  val capabilities: DecibelCapabilities = DecibelCapabilities(),
  val accessToken: suspend () -> String? = { null },
)
