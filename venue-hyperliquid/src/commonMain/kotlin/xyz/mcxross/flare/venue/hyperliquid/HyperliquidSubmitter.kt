package xyz.mcxross.flare.venue.hyperliquid

import kotlin.time.Clock
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.ExchangeOutcome
import xyz.mcxross.flare.hyperliquid.model.L1Action
import xyz.mcxross.flare.hyperliquid.model.UserSignedAction
import xyz.mcxross.flare.hyperliquid.signing.EvmSigner
import xyz.mcxross.flare.store.AppPreferences

/**
 * Signs one action with the nonce it's given and sends it once. [beforeSend] runs between the two:
 * anything that fails before it never reached Hyperliquid.
 */
interface HyperliquidSubmitter {
  suspend fun submit(
    action: UserSignedAction,
    signer: EvmSigner,
    nonce: Long,
    beforeSend: suspend () -> Unit,
  ): ExchangeOutcome

  suspend fun submit(
    action: L1Action,
    signer: EvmSigner,
    nonce: Long,
    vaultAddress: String?,
    expiresAfter: Long?,
    beforeSend: suspend () -> Unit,
  ): ExchangeOutcome
}

class ClientSubmitter(private val client: HyperliquidClient) : HyperliquidSubmitter {
  override suspend fun submit(
    action: UserSignedAction,
    signer: EvmSigner,
    nonce: Long,
    beforeSend: suspend () -> Unit,
  ): ExchangeOutcome {
    val request = client.exchange.sign(action, signer, nonce = nonce)
    beforeSend()
    return client.exchange.send(request)
  }

  override suspend fun submit(
    action: L1Action,
    signer: EvmSigner,
    nonce: Long,
    vaultAddress: String?,
    expiresAfter: Long?,
    beforeSend: suspend () -> Unit,
  ): ExchangeOutcome {
    val request = client.exchange.sign(action, signer, vaultAddress, expiresAfter, nonce)
    beforeSend()
    return client.exchange.send(request)
  }
}

/**
 * Nonces for each signer, kept in the app's preferences: strictly increasing and saved before use,
 * so a restart or a clock that steps back never repeats one. Hyperliquid tracks nonces per signer.
 */
class HyperliquidNonces(
  private val preferences: AppPreferences,
  private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
  suspend fun next(signer: String): Long = preferences.nextSequence(key(signer), clockMs())

  /** Whether [signer] ever signed on this device. */
  suspend fun hasSigned(signer: String): Boolean = preferences.lastSequence(key(signer)) != null

  private fun key(signer: String) = "hyperliquid_nonce_${signer.lowercase()}"
}
