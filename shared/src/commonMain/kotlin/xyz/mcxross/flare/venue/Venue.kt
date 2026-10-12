package xyz.mcxross.flare.venue

import org.koin.core.module.Module
import xyz.mcxross.flare.core.toDecimalString
import xyz.mcxross.flare.venue.model.ActionKind

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
  /** What the receive screen tells someone funding the account; null for the chain's defaults. */
  val receive: ReceiveInstructions? = null,
  /** Where withdrawals arrive when that's not the venue's own chain; null when it is. */
  val withdrawal: WithdrawalTerms? = null,
  val vaultTerms: VaultTerms = VaultTerms(),
  /** The asset classes its perpetuals are filed under, each a filter on the markets screen. */
  val perpCategories: List<String> = DefaultPerpCategories,
)

val DefaultPerpCategories = listOf("commodity", "crypto", "equity")

/** Withdrawals that leave the venue for another network. */
data class WithdrawalTerms(
  /** The network a withdrawal arrives on, as in "an Arbitrum address". */
  val network: String,
  /** What the venue keeps from each withdrawal, in USDC as decimal text; null when nothing. */
  val feeUsdc: String? = null,
  /** USDC can also go to another account on the venue itself, without leaving it. */
  val internalTransfers: Boolean = false,
)

/** How a venue's vaults hold a stake. */
data class VaultTerms(
  /** A stake is a USDC amount, redeemed in USDC, rather than shares. */
  val stakeInUsdc: Boolean = false,
  /** Every deposit locks the whole stake again for the vault's lockup, which deposits state. */
  val lockupPerDeposit: Boolean = false,
)

/** How to fund an account on the venue, in the venue's words. */
data class ReceiveInstructions(
  /** Where the funds come from, shown above the address. */
  val summary: String,
  /** What can lose the funds for good. */
  val warning: String,
  /** Where test funds come from, on a test network. */
  val faucet: Faucet? = null,
)

data class Faucet(
  val label: String,
  val url: String,
  /** What the faucet requires, shown next to it. */
  val note: String? = null,
) {
  init {
    require(url.startsWith("https://")) { "Faucet links must use HTTPS" }
  }
}

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
  /** Spot orders need their own builder fee approval, apart from perpetuals'. */
  val separateSpotBuilderApproval: Boolean = true,
  /**
   * Setup ends by enabling trading with a device trading key. Without it, setup ends once the wallet
   * exists and its own address is the account.
   */
  val enableTrading: Boolean = true,
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
  /** Cancelling several orders in one request. */
  val batchCancel: Boolean = false,
  /** A TWAP's slice interval is the person's to choose, rather than fixed by the exchange. */
  val twapInterval: Boolean = true,
  /** Creating trading accounts (subaccounts) from the app, beyond selecting existing ones. */
  val createSubaccounts: Boolean = subaccounts,
  /** Redeeming a referral code from the app. */
  val referralCodes: Boolean = rewards,
  /**
   * The kinds of action the app can't send on the venue yet, each with the reason shown in its
   * place. The screens still show the account, and disable just those actions.
   */
  val unavailableActions: Map<ActionKind, String> = emptyMap(),
) {
  /** Why actions of [kind] can't be sent; null when they can. */
  fun unavailableReason(kind: ActionKind): String? = unavailableActions[kind]

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
  /**
   * The references the explorer has a page for; null when it has one for every reference. Others,
   * such as an exchange's order ids, get no link.
   */
  val transactionReference: Regex? = null,
) {
  init {
    require(transactionUrlTemplate.startsWith("https://")) { "Explorer links must use HTTPS" }
    require(REFERENCE in transactionUrlTemplate) { "The transaction URL needs a $REFERENCE slot" }
  }

  /** Null when the explorer has no page for [reference]. */
  fun transactionUrl(reference: String): String? =
    if (transactionReference?.matches(reference) == false) null
    else transactionUrlTemplate.replace(REFERENCE, reference)

  companion object {
    const val REFERENCE = "{reference}"
  }
}
