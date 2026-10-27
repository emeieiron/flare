package xyz.mcxross.flare

import android.content.Context
import xyz.mcxross.flare.venue.Venue
import xyz.mcxross.flare.venue.hyperliquid.HyperliquidVenue

/** The hyperliquid flavor's exchange: Hyperliquid mainnet. */
object VenueEntry {
  fun venue(context: Context): Venue =
    HyperliquidVenue(builderAddress = context.getString(R.string.flare_hyperliquid_builder_address))
}
