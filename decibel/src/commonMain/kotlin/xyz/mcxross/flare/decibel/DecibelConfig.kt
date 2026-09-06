package xyz.mcxross.flare.decibel

import kotlinx.serialization.Serializable

@Serializable
enum class DecibelNetwork {
  TESTNET,
  MAINNET,
}

@Serializable
data class DecibelCapabilities(
  val gasSponsorship: Boolean = false,
  val encryptedOrders: Boolean = false,
)

@Serializable
data class DecibelDeployment(
  val network: DecibelNetwork,
  val packageAddress: String,
  val usdcMetadataAddress: String,
  val chainId: UByte,
  val upstreamRestUrl: String,
  val upstreamWebSocketUrl: String,
  val aptosFullnodeUrl: String,
) {
  companion object {
    val Testnet =
      DecibelDeployment(
        network = DecibelNetwork.TESTNET,
        packageAddress = "0xe7da2794b1d8af76532ed95f38bfdf1136abfd8ea3a240189971988a83101b7f",
        usdcMetadataAddress = "0xbdabb88aa9a875f3a2ebe0974e24f3ae5e57cfd17c6abdfef8a8111f43681b7e",
        chainId = 2u,
        upstreamRestUrl = "https://api.testnet.aptoslabs.com/decibel",
        upstreamWebSocketUrl = "wss://api.testnet.aptoslabs.com/decibel/ws",
        aptosFullnodeUrl = "https://api.testnet.aptoslabs.com/v1",
      )

    val Mainnet =
      DecibelDeployment(
        network = DecibelNetwork.MAINNET,
        packageAddress = "0x50ead22afd6ffd9769e3b3d6e0e64a2a350d68e8b102c4e72e33d0b8cfdfdb06",
        usdcMetadataAddress = "0xbae207659db88bea0cbead6da0ed00aac12edcdda169e591cd41c94180b46f3b",
        chainId = 1u,
        upstreamRestUrl = "https://api.mainnet.aptoslabs.com/decibel",
        upstreamWebSocketUrl = "wss://api.mainnet.aptoslabs.com/decibel/ws",
        aptosFullnodeUrl = "https://api.mainnet.aptoslabs.com/v1",
      )

    fun forNetwork(network: DecibelNetwork): DecibelDeployment =
      when (network) {
        DecibelNetwork.TESTNET -> Testnet
        DecibelNetwork.MAINNET -> Mainnet
      }
  }
}

/**
 * Runtime endpoints deliberately differ from [DecibelDeployment] upstream endpoints. Production
 * applications point these at the Flare Worker so upstream credentials never ship in the app.
 */
data class DecibelConfig(
  val deployment: DecibelDeployment = DecibelDeployment.Testnet,
  val restBaseUrl: String = deployment.upstreamRestUrl,
  val webSocketUrl: String = deployment.upstreamWebSocketUrl,
  val origin: String = "flare://mobile",
  val capabilities: DecibelCapabilities = DecibelCapabilities(),
  val accessToken: suspend () -> String? = { null },
)
