package xyz.mcxross.flare.venue.hyperliquid

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.SetupTransactionException
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.ApproveAgent
import xyz.mcxross.flare.hyperliquid.model.ApproveBuilderFee
import xyz.mcxross.flare.hyperliquid.model.ExtraAgent
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TradingKeyScope

/** Marks setup the person already confirmed, so the owner signatures inside it don't ask again. */
private class ConfirmedSetup : AbstractCoroutineContextElement(ConfirmedSetup) {
  companion object Key : CoroutineContext.Key<ConfirmedSetup>
}

/**
 * Enabling trading on Hyperliquid: the owner approves this device's agent key under [AGENT_NAME],
 * and optionally Flare's builder fee. Both are owner-signed and idempotent, so an interrupted setup
 * resumes by checking Hyperliquid's state (`extraAgents`, `maxBuilderFee`) and sending only what
 * isn't in effect. A key that has signed before and isn't approved now was deregistered, and is
 * replaced rather than approved again: its old signatures could otherwise be replayed.
 */
class HyperliquidSetup(
  private val client: HyperliquidClient,
  private val preferences: AppPreferences,
  private val wallets: HyperliquidSigningWallet,
  private val actions: HyperliquidActions,
  private val nonces: HyperliquidNonces,
  private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
  private val verificationDelayMs: Long = 1_000L,
) {
  private val mutex = Mutex()

  suspend fun <T> confirmed(prompt: VaultPrompt, block: suspend () -> T): T {
    if (!insideConfirmedSetup()) wallets.confirmIdentity(prompt)
    return withContext(ConfirmedSetup()) { block() }
  }

  private suspend fun insideConfirmedSetup(): Boolean =
    currentCoroutineContext()[ConfirmedSetup] != null

  /** Owner actions authenticate every time, unless they're part of a setup confirmed once. */
  private suspend fun ownerPrompt(prompt: VaultPrompt): VaultPrompt =
    prompt.copy(requireFreshAuthorization = !insideConfirmedSetup())

  private suspend fun owner(): String =
    preferences.values.first().ownerAddress ?: error("Use a device that holds the owner key")

  /** Hyperliquid refuses every action from an address that has never been funded. */
  suspend fun awaitsFirstDeposit(account: String): Boolean = client.info.userRole(account).isMissing

  suspend fun enableTrading(prompt: VaultPrompt) = mutex.withLock {
    val owner = owner()
    check(!awaitsFirstDeposit(owner)) {
      "Deposit to your account first. Hyperliquid enables trading once it holds funds."
    }
    actions.reconcile()
    val stored = wallets.profile.first().apiWalletAddress
    if (stored != null && approved(owner, stored)) return@withLock
    // A key that signed before was approved before: if it isn't now, it was deregistered.
    val reusable =
      stored != null && (actions.approvalPending(owner, stored) || !nonces.hasSigned(stored))
    val agent =
      if (reusable) checkNotNull(stored)
      else wallets.newApiWallet(prompt.copy(requireFreshAuthorization = false))
    val result =
      actions.sendAsOwner(ApproveAgent(agent, AGENT_NAME), ownerPrompt(prompt)) { nonce ->
        JournaledAction.ApproveAgent(owner, agent, AGENT_NAME, nonce + HyperliquidActions.NONCE_WINDOW_MS)
      }
    if (result !is ActionState.Committed) throw SetupTransactionException(result)
    var lastError: Exception? = null
    repeat(VERIFICATION_ATTEMPTS) { attempt ->
      try {
        if (approved(owner, agent)) {
          actions.reconcile()
          return@withLock
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        lastError = error
      }
      if (attempt < VERIFICATION_ATTEMPTS - 1) delay(verificationDelayMs shl attempt.coerceAtMost(2))
    }
    throw IllegalStateException(
      "Hyperliquid accepted this device’s key, but doesn’t list it yet. Try again in a moment.",
      lastError,
    )
  }

  private suspend fun approved(account: String, agent: String): Boolean =
    activeAgents(account).any { it.address.sameAs(agent) }

  private suspend fun activeAgents(account: String): List<ExtraAgent> {
    val now = clockMs()
    return client.info.extraAgents(account).filter { it.validUntil <= 0 || it.validUntil > now }
  }

  suspend fun delegations(account: String): List<TradingKey> =
    activeAgents(account).map {
      TradingKey(
        address = it.address,
        scope = TradingKeyScope.ALL_MARKETS,
        expiresAtMs = it.validUntil.takeIf { until -> until in 1 until NO_EXPIRY_MS },
      )
    }

  /**
   * Removes [address] as Hyperliquid's app does: the zero address approved under its name. This
   * device's key is then forgotten, since it must never be approved again.
   */
  suspend fun revoke(address: String, prompt: VaultPrompt): ActionState {
    val owner = owner()
    val agent =
      client.info.extraAgents(owner).firstOrNull { it.address.sameAs(address) }
        ?: return ActionState.Failed("That key can’t trade for this account", definitelyNotSubmitted = true)
    val result =
      actions.sendAsOwner(ApproveAgent.revoke(agent.name), prompt.copy(requireFreshAuthorization = true)) {
        nonce ->
        JournaledAction.RevokeAgent(owner, agent.address, agent.name, nonce + HyperliquidActions.NONCE_WINDOW_MS)
      }
    if (result is ActionState.Committed && wallets.profile.first().apiWalletAddress.sameAs(address)) {
      wallets.discardApiWallet(prompt)
    }
    return result
  }

  /** One approval covers perpetuals and spot. [feeBps] is in basis points; Hyperliquid counts tenths. */
  suspend fun approveBuilderFee(builder: String, feeBps: UInt, prompt: VaultPrompt): ActionState {
    require(builder.isNotBlank()) { "No builder to approve" }
    require(feeBps in 1u..MAX_BUILDER_FEE_BPS) { "Builder fee must be between 1 and 10 bps (up to 0.10%)" }
    val owner = owner()
    val tenths = feeBps.toInt() * 10
    val result =
      actions.sendAsOwner(ApproveBuilderFee.ofTenthsOfBasisPoint(builder, tenths), ownerPrompt(prompt)) {
        nonce ->
        JournaledAction.ApproveBuilderFee(owner, builder, tenths, nonce + HyperliquidActions.NONCE_WINDOW_MS)
      }
    if (result is ActionState.Committed) {
      preferences.setBuilderSupport(builder, feeBps.toInt(), builderApproved = true)
      preferences.setSpotBuilderApproved(true, builder)
    }
    return result
  }

  suspend fun revokeBuilderFee(builder: String, prompt: VaultPrompt): ActionState {
    val owner = owner()
    val result =
      actions.sendAsOwner(ApproveBuilderFee.revoke(builder), prompt.copy(requireFreshAuthorization = true)) {
        nonce ->
        JournaledAction.RevokeBuilderFee(owner, builder, nonce + HyperliquidActions.NONCE_WINDOW_MS)
      }
    if (result is ActionState.Committed) {
      preferences.setBuilderApproved(false)
      preferences.setSpotBuilderApproved(false, builder)
    }
    return result
  }

  /** Brings the saved builder approval in line with what Hyperliquid has, quietly. */
  suspend fun syncBuilderApproval(defaultBuilder: String?) {
    val saved = preferences.values.first()
    val owner = saved.ownerAddress ?: return
    val builder = saved.builderAddress ?: defaultBuilder?.takeIf(String::isNotBlank) ?: return
    val approved = runSuspendCatching { client.info.maxBuilderFee(owner, builder) > 0 }.getOrNull() ?: return
    if (saved.builderApproved != approved || saved.spotBuilderApproved != approved) {
      preferences.setBuilderSupport(builder, saved.builderFeeBps, builderApproved = approved)
      preferences.setSpotBuilderApproved(approved, builder)
    }
  }

  /** Stores an API wallet's key only once Hyperliquid lists it as one of [account]'s agents. */
  suspend fun importTradingKey(key: String, account: String, prompt: VaultPrompt) {
    require(HyperliquidCredentialParser.isAddress(account)) { "Enter the account’s address" }
    wallets.importApiWallet(key, prompt) { address ->
      require(!address.sameAs(account)) {
        "That’s the account’s own key. Import it as the account’s recovery phrase or key instead."
      }
      check(approved(account, address)) {
        "That key isn’t an API wallet of this account. Approve it on Hyperliquid first."
      }
    }
  }

  companion object {
    /** The name Flare approves its agents under: one per account, so a new device replaces the last. */
    const val AGENT_NAME = "flare"
    private const val VERIFICATION_ATTEMPTS = 5
    private const val MAX_BUILDER_FEE_BPS = 10u
    /** Later than any `valid_until` Hyperliquid accepts (180 days); a larger value means none. */
    private const val NO_EXPIRY_MS = 4_102_444_800_000L
  }
}
