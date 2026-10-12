package xyz.mcxross.flare

import android.content.Context
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.Venue
import xyz.mcxross.flare.venue.hyperliquid.HyperliquidVenue

/** The hyperliquid flavor's exchange, on the network its build settings name. */
object VenueEntry {
  fun venue(context: Context): Venue =
    HyperliquidVenue(
      if (context.getString(R.string.flare_hyperliquid_network) == "mainnet") Network.MAINNET
      else Network.TESTNET,
      builderAddress = context.getString(R.string.flare_hyperliquid_builder_address),
    )
}
