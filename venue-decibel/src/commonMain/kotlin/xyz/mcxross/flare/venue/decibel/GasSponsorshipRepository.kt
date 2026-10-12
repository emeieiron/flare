package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.ExternalFeePayerRequest

/** Pays an Aptos transaction's gas through Flare's Gas Station, behind `sponsoredFees`. */
interface GasSponsorshipRepository {
  suspend fun submit(
    request: ExternalFeePayerRequest,
    ownerOnly: Boolean,
  ): AptosResult<String>

  suspend fun resolve(fingerprint: String): AptosResult<String?>
}
