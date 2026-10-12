package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.ExternalFeePayerSubmitter
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

/** Every Decibel command is sponsored by default, on the Worker route of the key that signs it. */
internal fun GasSponsorshipRepository.submitterFor(command: DecibelCommand): ExternalFeePayerSubmitter =
  ExternalFeePayerSubmitter { request ->
    submit(request, ownerOnly = command.requiredSigner() == TradingSigner.OWNER)
  }
