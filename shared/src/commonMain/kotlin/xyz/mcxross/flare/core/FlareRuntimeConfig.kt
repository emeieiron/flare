package xyz.mcxross.flare.core

import xyz.mcxross.flare.decibel.DecibelNetwork
import xyz.mcxross.kaptos.TransactionDefaults

data class FlareRuntimeConfig(
  val network: DecibelNetwork = DecibelNetwork.TESTNET,
  val workerBaseUrl: String = "http://127.0.0.1:8787",
  val appOrigin: String = "flare://mobile",
) {
  // Match the bounded settings used by the verified Decibel transaction lifecycle.
  // Kaptos' generic ceiling of 2,000,000 exceeds this app's Gas Station policy.
  val transactionDefaults =
    TransactionDefaults(maxGasAmount = 50_000uL, expirationSecondsFromNow = 60uL)

  init {
    require(
      workerBaseUrl.startsWith("https://") ||
        LocalDevelopmentWorker.matches(workerBaseUrl.trimEnd('/'))
    ) {
      "The Flare Worker must use HTTPS outside local development"
    }
    require(appOrigin == "flare://mobile") {
      "The application origin must match the fixed Worker authentication domain"
    }
  }

  val decibelRestUrl: String
    get() = "${workerBaseUrl.trimEnd('/')}/decibel"

  val decibelWebSocketUrl: String
    get() =
      workerBaseUrl
        .trimEnd('/')
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/decibel/ws"

  val aptosFullnodeUrl: String
    get() = "${workerBaseUrl.trimEnd('/')}/aptos/v1"
}

private val LocalDevelopmentWorker =
  Regex("http://(?:127\\.0\\.0\\.1|10\\.0\\.2\\.2)(?::[0-9]{1,5})?")
