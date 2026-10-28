package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.ExternalFeePayerSubmitter
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.ExternalFeePayerRequest

/** Pays an Aptos transaction's gas through Flare's Gas Station, behind `sponsoredFees`. */
interface GasSponsorshipRepository {
  /** A conservative deployment policy check. It never signs or submits a transaction. */
  suspend fun canSponsorSwap(): Boolean = false

  suspend fun submit(
    request: ExternalFeePayerRequest,
    ownerOnly: Boolean,
  ): AptosResult<String>

  suspend fun resolve(fingerprint: String): AptosResult<String?>

  suspend fun submitSwap(
    request: ExternalFeePayerRequest,
    authorization: String,
  ): AptosResult<String> =
    AptosResult.Failure(xyz.mcxross.kaptos.model.AptosError.Validation("Swap sponsorship is unavailable"))
}

/** Every Decibel command is sponsored by default, on the Worker route of the key that signs it. */
internal fun GasSponsorshipRepository.submitterFor(command: DecibelCommand): ExternalFeePayerSubmitter =
  ExternalFeePayerSubmitter { request ->
    submit(request, ownerOnly = command.requiredSigner() == TradingSigner.OWNER)
  }
