package xyz.mcxross.flare.data

import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.ExternalFeePayerRequest

/**
 * Pays an Aptos transaction's gas through Flare's Gas Station (Decibel only, behind
 * `VenueCapabilities.sponsoredFees`). The Worker implementation lives in `:venue-decibel`; this
 * interface moves there with DefaultTradingRepository, its only user (PR 8).
 */
interface GasSponsorshipRepository {
  suspend fun submit(
    request: ExternalFeePayerRequest,
    ownerOnly: Boolean,
  ): AptosResult<String>

  suspend fun resolve(fingerprint: String): AptosResult<String?>
}
