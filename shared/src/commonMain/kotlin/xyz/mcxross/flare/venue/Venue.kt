package xyz.mcxross.flare.venue

import org.koin.core.module.Module
import xyz.mcxross.flare.core.toDecimalString

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

/** Describes a venue to the app. */
data class VenueDescriptor(
  /** Stable lowercase identifier, such as `decibel`. Matches the build flavor and iOS scheme. */
  val id: String,
  val displayName: String,
  val network: Network,
  val chain: VenueChain,
  val capabilities: VenueCapabilities,
  val explorer: VenueExplorer,
  /** The backend the app reaches the venue through, shown in settings; null when it has none. */
  val proxyUrl: String? = null,
  /** Flare's builder address on the venue, which receives the optional builder fee. */
  val builderAddress: String? = null,
  /** The builder fee setup turns on when the person opts in, in basis points. */
  val builderFeeBps: Int = 0,
  /** The most a builder fee approval allows, in basis points; later rate changes stay under it. */
  val maxBuilderFeeBps: Int = 0,
  /** The least a vault deposit or redemption may be; null when the venue sets none. */
  val vaultMinimums: VaultMinimums? = null,
  /** What the venue calls its reward points, such as Decibel's Amps; null when it has none. */
  val rewardPointsName: String? = null,
  /** The steps account setup takes once the wallet exists. */
  val setup: VenueSetup = VenueSetup(),
)

/** The chain a venue settles on, as the app names it and counts its network fees. */
data class VenueChain(
  /** As in "an Aptos address" or "the Aptos network". */
  val name: String,
  /** The token network fees are paid in, such as APT. Fee amounts count its smallest unit. */
  val feeToken: String,
  val feeTokenDecimals: Int,
  /** The least the wallet sends when topping up the device trading key, in the smallest unit. */
  val minimumFeeTopUp: ULong = 0uL,
  /** The assets the receive screen asks for. */
  val depositAssets: List<String> = listOf("USDC"),
) {
  /** [units] of the fee token at full precision, as in "0.00012 APT". */
  fun feeAmount(units: ULong): String = "${units.toDecimalString(feeTokenDecimals)} $feeToken"
}

data class VaultMinimums(
  /** In USDC. */
  val deposit: Double,
  val redemptionShares: Double,
  /** The vaults they apply to, as in "The minimum deposit for DLP vaults". */
  val vaults: String,
)

/**
 * The venue's account setup after the wallet exists. Enabling trading on the device is always a
 * step; the others depend on how the exchange organizes accounts.
 */
data class VenueSetup(
  /** An owner trades from one of several trading accounts, picked or created during setup. */
  val tradingAccounts: Boolean = true,
  /** A wallet made in the app opens its trading account on chain before it can trade. */
  val openTradingAccount: Boolean = true,
  /** Setup offers to approve Flare's builder fee. */
  val builderFee: Boolean = true,
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
  /** Moving USDC between perpetuals and spot margin, where the exchange keeps them apart. */
  val perpSpotTransfer: Boolean = false,
) {
  companion object {
    /** Every feature on: the starting state of screens before a venue's flags arrive. */
    val All =
      VenueCapabilities(
        spot = true,
        twap = true,
        vaults = true,
        subaccounts = true,
        rewards = true,
        sponsoredFees = true,
        networkFeeTopUp = true,
        inAppDeposit = true,
        perpSpotTransfer = true,
      )
  }
}

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
