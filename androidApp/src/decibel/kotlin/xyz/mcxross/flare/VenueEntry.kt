package xyz.mcxross.flare

import android.content.Context
import xyz.mcxross.flare.core.DecibelRuntimeConfig
import xyz.mcxross.flare.venue.Venue
import xyz.mcxross.flare.venue.decibel.DecibelVenue

/**
 * The exchange this build trades on. Every flavor provides a `VenueEntry` in its own source set;
 * this one is the decibel flavor's, configured from its build settings.
 */
object VenueEntry {
  fun venue(context: Context): Venue =
    DecibelVenue(
      DecibelRuntimeConfig(
        workerBaseUrl = context.getString(R.string.flare_worker_url),
        defaultBuilderAddress = context.getString(R.string.flare_builder_address),
      )
    )
}
