package xyz.mcxross.flare

import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.VenueExplorer
import xyz.mcxross.flare.venue.VenueSetup

/** A venue for ViewModel tests: every feature on unless [capabilities] says otherwise. */
fun testVenue(
  capabilities: VenueCapabilities = VenueCapabilities.All,
  setup: VenueSetup = VenueSetup(),
) =
  VenueDescriptor(
    id = "test",
    displayName = "Test",
    network = Network.TESTNET,
    capabilities = capabilities,
    explorer = VenueExplorer("Test Explorer", "https://explorer.test/${VenueExplorer.REFERENCE}"),
    proxyUrl = "https://proxy.test",
    builderAddress = "0xb0b",
    builderFeeBps = 5,
    rewardPointsName = "Amps",
    setup = setup,
  )

/** A venue with none of the exchange-specific features. */
val BareCapabilities =
  VenueCapabilities(
    spot = false,
    twap = false,
    vaults = false,
    subaccounts = false,
    rewards = false,
    sponsoredFees = false,
    networkFeeTopUp = false,
    inAppDeposit = false,
  )
