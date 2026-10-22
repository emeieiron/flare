package xyz.mcxross.flare.venue.hyperliquid

import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.binds
import org.koin.dsl.module
import xyz.mcxross.flare.strategies.StrategyMarketFeed
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.RewardsRepository
import xyz.mcxross.flare.data.VaultsRepository
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.HyperliquidConfig
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment
import xyz.mcxross.flare.store.FlareDatabase
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.ReceiveInstructions
import xyz.mcxross.flare.venue.VaultTerms
import xyz.mcxross.flare.venue.Venue
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.VenueChain
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.VenueExplorer
import xyz.mcxross.flare.venue.VenueSetup
import xyz.mcxross.flare.venue.WithdrawalTerms

/**
 * Hyperliquid, reached directly: its reads need no credentials, so there is no backend session.
 * The wallet's own address is its account. Setup enables trading with an agent key on this device,
 * approved by the owner, plus Flare's builder fee at [builderAddress] if the person opts in (none
 * when it's blank).
 */
class HyperliquidVenue(
  private val network: Network = Network.TESTNET,
  builderAddress: String = "",
) : Venue {
  private val deployment = HyperliquidDeployment.forNetwork(network.toHyperliquidNetwork())
  private val builder: String? =
    builderAddress.trim().takeIf(String::isNotEmpty)?.also {
      require(HyperliquidCredentialParser.isAddress(it)) { "The builder address must be an EVM address" }
    }

  override val descriptor: VenueDescriptor =
    VenueDescriptor(
      id = "hyperliquid",
      displayName = "Hyperliquid",
      network = network,
      // HyperCore charges no gas; USDC arrives from another Hyperliquid address.
      chain = VenueChain(name = "Hyperliquid", feeToken = "HYPE", feeTokenDecimals = 8),
      capabilities = Capabilities,
      explorer = explorer(network),
      builderAddress = builder,
      builderFeeBps = if (builder != null) BUILDER_FEE_BPS else 0,
      maxBuilderFeeBps = 10,
      setup =
        VenueSetup(
          tradingAccounts = false,
          openTradingAccount = false,
          builderFee = builder != null,
          separateSpotBuilderApproval = false,
          enableTrading = true,
        ),
      receive = Receive,
      withdrawal =
        WithdrawalTerms(
          network = "Arbitrum",
          feeUsdc = HyperliquidFunds.WITHDRAWAL_FEE.toWire(),
          internalTransfers = true,
        ),
      vaultTerms = VaultTerms(stakeInUsdc = true, lockupPerDeposit = true),
      // Hyperliquid files its markets under no asset class, so they're all crypto.
      perpCategories = listOf("crypto"),
    )

  override fun module(): Module = hyperliquidModule(deployment, network, builder)

  companion object {
    /** The builder fee setup turns on when the person opts in: 0.05%, under the 0.1% approved. */
    const val BUILDER_FEE_BPS = 5

    /**
     * What the Hyperliquid build shows: spot, TWAPs (a slice every 30 s, so no interval to choose),
     * vaults, existing sub-accounts (created in Hyperliquid's app), moving USDC between perps and
     * spot, and the fee tier and referral standing as its rewards. Decibel's Amps, streak, referral
     * codes, gas sponsorship and top-ups, and in-app deposits have no Hyperliquid counterpart.
     */
    val Capabilities =
      VenueCapabilities(
        spot = true,
        twap = true,
        subaccounts = true,
        sponsoredFees = false,
        networkFeeTopUp = false,
        inAppDeposit = false,
        perpSpotTransfer = true,
        batchCancel = true,
        twapInterval = false,
        createSubaccounts = false,
        referralCodes = false,
      )

    /** USDC arrives from another Hyperliquid account or through the official bridge, never a chain. */
    val Receive =
      ReceiveInstructions(
        summary =
          "Deposit USDC to this address on Hyperliquid: send it from another Hyperliquid account, " +
            "or bridge it in through Hyperliquid’s official app.",
        warning =
          "Never send from an exchange or network that doesn’t support Hyperliquid deposits. " +
            "Funds sent that way can be lost for good.",
      )

    fun explorer(network: Network): VenueExplorer =
      VenueExplorer(
        name = "Hyperliquid Explorer",
        transactionUrlTemplate =
          when (network) {
            Network.MAINNET -> "https://app.hyperliquid.xyz/explorer/tx/"
            Network.TESTNET -> "https://app.hyperliquid-testnet.xyz/explorer/tx/"
          } + VenueExplorer.REFERENCE,
        transactionReference = TRANSACTION_HASH,
      )

    /** Only L1 transaction hashes have explorer pages; order ids and nonces don't. */
    private val TRANSACTION_HASH = Regex("0x[0-9a-fA-F]{64}")
  }
}

/** Hyperliquid's repositories, on the common bindings from `flareModule`. */
private fun hyperliquidModule(deployment: HyperliquidDeployment, network: Network, builder: String?) =
  module {
    single { HyperliquidClient(httpClient = get(), config = HyperliquidConfig(deployment)) }
    single<CredentialParser> { HyperliquidCredentialParser }
    singleOf(::HyperliquidWalletRepository) {
      bind<WalletRepository>()
      bind<HyperliquidSigningWallet>()
    }
    single<HyperliquidSubmitter> { ClientSubmitter(get()) }
    single { HyperliquidNonces(get()) }
    single {
      HyperliquidActions(
        journal = get<FlareDatabase>().transactionJournalDao(),
        preferences = get(),
        wallets = get(),
        client = get(),
        submitter = get(),
        nonces = get(),
        network = network,
      )
    }
    single { HyperliquidSetup(get(), get(), get(), get(), get()) }
    singleOf(::HyperliquidMarketsRepository) { bind<MarketsRepository>() }
    single<StrategyMarketFeed> { HyperliquidStrategyMarketFeed(get(), get()) }
    singleOf(::HyperliquidChartRepository) { bind<ChartRepository>() }
    singleOf(::HyperliquidMarketDetailsRepository) { bind<MarketDetailsRepository>() }
    single { HyperliquidFunds(get(), get(), get()) }
    single { HyperliquidAccountRepository(get(), get(), get(), get(), builder) } binds
      arrayOf(AccountRepository::class, VaultsRepository::class, RewardsRepository::class)
    single { HyperliquidAssets(get()) }
    single<TradingRepository> {
      HyperliquidTradingRepository(client = get(), actions = get(), setup = get(), funds = get(), assets = get())
    }
  }
