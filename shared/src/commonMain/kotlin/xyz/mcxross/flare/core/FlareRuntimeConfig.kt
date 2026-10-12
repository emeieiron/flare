package xyz.mcxross.flare.core

import xyz.mcxross.flare.venue.Network

/** Settings every Flare build shares, whichever exchange it trades on. */
data class FlareRuntimeConfig(
  val network: Network = Network.TESTNET,
  val appOrigin: String = "flare://mobile",
) {
  init {
    require(appOrigin == "flare://mobile") {
      "The application origin must match the fixed Worker authentication domain"
    }
  }
}
