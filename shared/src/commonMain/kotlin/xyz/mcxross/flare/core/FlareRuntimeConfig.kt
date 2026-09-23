package xyz.mcxross.flare.core

import xyz.mcxross.flare.decibel.DecibelNetwork
import xyz.mcxross.kaptos.TransactionDefaults

data class FlareRuntimeConfig(
  val network: DecibelNetwork = DecibelNetwork.TESTNET,
  val workerBaseUrl: String = "http://127.0.0.1:8787",
  val appOrigin: String = "flare://mobile",
  val defaultBuilderAddress: String =
    "0xe05e75a9f25b254fcc354d5a68d7d15f7b97ac6ee56922fac3c1c27009d0c25b",
  val defaultBuilderFeeBps: UInt = 5u,
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
