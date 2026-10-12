package xyz.mcxross.flare.venue

import org.koin.core.module.Module
import xyz.mcxross.flare.decibel.DecibelNetwork

/**
 * The exchange a Flare build trades on. Each build links exactly one venue module
 * (`:venue-decibel`, …) and hands its [Venue] to `App`; the screens, ViewModels and storage in
 * `:shared` stay the same for every exchange.
 */
interface Venue {
  /** What the app shows about the exchange, and which of its features it supports. */
  val descriptor: VenueDescriptor

  /**
   * Binds the exchange's repositories (markets, charts, account, trading, wallet and session) on
   * top of the common bindings in `di/FlareModule.kt`.
   */
  fun module(): Module
}

/**
 * Describes a venue to the app.
 *
 * [network] is still Decibel's enum: it becomes an exchange-neutral `Network` with the same names
 * once the repositories behind the seam use neutral types.
 */
data class VenueDescriptor(
  /** Stable lowercase identifier, such as `decibel`. Matches the build flavor and iOS scheme. */
  val id: String,
  val displayName: String,
  val network: DecibelNetwork,
  val capabilities: VenueCapabilities,
  val explorer: VenueExplorer,
)

/** The features an exchange supports. The app hides what a venue can't do. */
data class VenueCapabilities(
  val spot: Boolean,
  val twap: Boolean,
  val vaults: Boolean,
  val subaccounts: Boolean,
  /** Exchange rewards, such as Decibel's Amps, streak and tier. */
  val rewards: Boolean,
  /** Network fees paid by a gas station, with the wallet paying itself as the fallback. */
  val sponsoredFees: Boolean,
  /** Topping up the trading key with the chain's fee token. */
  val networkFeeTopUp: Boolean,
  /** Deposits made from inside the app, rather than sent to an address shown in it. */
  val inAppDeposit: Boolean,
)

/** Where the app links to see an action on chain. */
data class VenueExplorer(
  /** Shown on links, as in "View on [name]". */
  val name: String,
  /** A transaction page URL, with [REFERENCE] where the hash or action reference goes. */
  val transactionUrlTemplate: String,
) {
  init {
    require(transactionUrlTemplate.startsWith("https://")) { "Explorer links must use HTTPS" }
    require(REFERENCE in transactionUrlTemplate) { "The transaction URL needs a $REFERENCE slot" }
  }

  fun transactionUrl(reference: String): String =
    transactionUrlTemplate.replace(REFERENCE, reference)

  companion object {
    const val REFERENCE = "{reference}"
  }
}
