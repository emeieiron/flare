package xyz.mcxross.flare.venue.hyperliquid

import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.HyperliquidConfig
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment
import xyz.mcxross.flare.venue.Faucet
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.ReceiveInstructions
import xyz.mcxross.flare.venue.Venue
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.VenueChain
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.VenueExplorer
import xyz.mcxross.flare.venue.VenueSetup

/**
 * Hyperliquid, reached directly: its reads need no credentials, so there is no backend session.
 * The wallet's own address is its account. Markets, charts, books and the account are live; trading
 * comes later, so setup ends once the wallet exists.
 */
class HyperliquidVenue(network: Network = Network.TESTNET) : Venue {
  private val deployment = HyperliquidDeployment.forNetwork(network.toHyperliquidNetwork())

  override val descriptor: VenueDescriptor =
    VenueDescriptor(
      id = "hyperliquid",
      displayName = "Hyperliquid",
      network = network,
      // HyperCore charges no gas; USDC arrives from another Hyperliquid address.
      chain = VenueChain(name = "Hyperliquid", feeToken = "HYPE", feeTokenDecimals = 8),
      capabilities = Capabilities,
      explorer = explorer(network),
      maxBuilderFeeBps = 10,
      setup =
        VenueSetup(
          tradingAccounts = false,
          openTradingAccount = false,
          builderFee = false,
          enableTrading = false,
        ),
      receive = receiveInstructions(network),
    )

  override fun module(): Module = hyperliquidModule(deployment)

  companion object {
    /**
     * What the Hyperliquid build shows. Spot, TWAP and vaults are Hyperliquid features the screens
     * keep room for; the rest are Decibel's (rewards, gas sponsorship and top-ups, in-app deposits,
     * creating subaccounts), or not offered yet (moving USDC between perps and spot). The account
     * reads live, but its actions stay disabled until trading is enabled.
     */
    val Capabilities =
      VenueCapabilities(
        spot = true,
        twap = true,
        vaults = true,
        subaccounts = false,
        rewards = false,
        sponsoredFees = false,
        networkFeeTopUp = false,
        inAppDeposit = false,
        perpSpotTransfer = false,
        actionsUnavailableReason = ACTIONS_UNAVAILABLE,
      )

    /** USDC arrives from another Hyperliquid account or through the official bridge, never a chain. */
    fun receiveInstructions(network: Network): ReceiveInstructions =
      ReceiveInstructions(
        summary =
          "Deposit USDC to this address on Hyperliquid: send it from another Hyperliquid account, " +
            "or bridge it in through Hyperliquid’s official app.",
        warning =
          "Never send from an exchange or network that doesn’t support Hyperliquid deposits. " +
            "Funds sent that way can be lost for good.",
        faucet =
          when (network) {
            Network.TESTNET ->
              Faucet(
                label = "Get test USDC",
                url = "https://app.hyperliquid-testnet.xyz/drip",
                note =
                  "The test USDC faucet only pays an address that has already deposited on " +
                    "Hyperliquid mainnet.",
              )
            Network.MAINNET -> null
          },
      )

    fun explorer(network: Network): VenueExplorer =
      VenueExplorer(
        name = "Hyperliquid Explorer",
        transactionUrlTemplate =
          when (network) {
            Network.MAINNET -> "https://app.hyperliquid.xyz/explorer/tx/"
            Network.TESTNET -> "https://app.hyperliquid-testnet.xyz/explorer/tx/"
          } + VenueExplorer.REFERENCE,
      )
  }
}

/** Hyperliquid's repositories, on the common bindings from `flareModule`. */
private fun hyperliquidModule(deployment: HyperliquidDeployment) = module {
  single { HyperliquidClient(httpClient = get(), config = HyperliquidConfig(deployment)) }
  single<CredentialParser> { HyperliquidCredentialParser }
  singleOf(::HyperliquidWalletRepository) { bind<WalletRepository>() }
  singleOf(::HyperliquidMarketsRepository) { bind<MarketsRepository>() }
  singleOf(::HyperliquidChartRepository) { bind<ChartRepository>() }
  singleOf(::HyperliquidMarketDetailsRepository) { bind<MarketDetailsRepository>() }
  singleOf(::HyperliquidAccountRepository) { bind<AccountRepository>() }
  singleOf(::HyperliquidTradingRepository) { bind<TradingRepository>() }
}
