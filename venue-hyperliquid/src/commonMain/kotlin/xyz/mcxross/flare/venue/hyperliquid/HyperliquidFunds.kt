package xyz.mcxross.flare.venue.hyperliquid

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.formatCalendarDateTime
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.HlDecimal
import xyz.mcxross.flare.hyperliquid.model.LedgerUpdate
import xyz.mcxross.flare.hyperliquid.model.Side
import xyz.mcxross.flare.hyperliquid.model.TwapHistoryEntry
import xyz.mcxross.flare.hyperliquid.model.UsdClassTransfer
import xyz.mcxross.flare.hyperliquid.model.UsdSend
import xyz.mcxross.flare.hyperliquid.model.UserSignedAction
import xyz.mcxross.flare.hyperliquid.model.VaultTransfer
import xyz.mcxross.flare.hyperliquid.model.Withdraw
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.venue.model.ActionState

/**
 * Moves USDC: withdrawals to Arbitrum, transfers to other Hyperliquid accounts and between perps
 * and spot, signed by the owner; and vault deposits and redemptions, which Hyperliquid's app signs
 * with the agent key. A move that lands is committed under its ledger transaction hash when the
 * ledger already lists it, otherwise under its nonce.
 */
class HyperliquidFunds(
  private val client: HyperliquidClient,
  private val actions: HyperliquidActions,
  private val preferences: AppPreferences,
  private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
  /** Withdraws [amount], fee included, to [destination] on Arbitrum, or to the owner's address. */
  suspend fun withdraw(
    account: String,
    destination: String?,
    amount: String,
    prompt: VaultPrompt,
  ): ActionState = sending {
    val owner = owner()
    requireMainAccount(account, "Withdrawals")
    val target = destination?.trim()?.takeIf(String::isNotEmpty) ?: owner
    require(HyperliquidCredentialParser.isAddress(target)) { "Enter a valid Arbitrum address" }
    val usdc = usdc(amount)
    require(usdc > WITHDRAWAL_FEE) { "A withdrawal must be more than the $WITHDRAWAL_FEE USDC fee" }
    ownerSigned(Withdraw(target, usdc), prompt.copy(requireFreshAuthorization = true)) { lapses ->
      JournaledAction.Withdraw(account, target, usdc.toWire(), lapses)
    }
  }

  /** Sends [amount] of perp USDC to another Hyperliquid account. */
  suspend fun send(
    account: String,
    destination: String,
    amount: String,
    prompt: VaultPrompt,
  ): ActionState = sending {
    owner()
    requireMainAccount(account, "Transfers")
    val target = destination.trim()
    require(HyperliquidCredentialParser.isAddress(target)) { "Enter a valid Hyperliquid address" }
    require(!target.sameAs(account)) { "That’s this account’s own address" }
    val usdc = usdc(amount)
    ownerSigned(UsdSend(target, usdc), prompt.copy(requireFreshAuthorization = true)) { lapses ->
      JournaledAction.SendUsdc(account, target, usdc.toWire(), lapses)
    }
  }

  /** Moves [amount] between the perp and spot balances, which unified accounts don't keep apart. */
  suspend fun movePerpSpot(
    account: String,
    amount: String,
    toPerp: Boolean,
    prompt: VaultPrompt,
  ): ActionState = sending {
    owner()
    val usdc = usdc(amount)
    require(!client.info.userAbstraction(account).sharesSpotCollateral) {
      "This account shares one balance between perps and spot, so there’s nothing to move"
    }
    val subAccount = actions.vaultFor(account)
    ownerSigned(UsdClassTransfer(usdc, toPerp, subAccount), prompt) { lapses ->
      JournaledAction.MovePerpSpot(account, usdc.toWire(), toPerp, lapses)
    }
  }

  /**
   * Deposits [amount] into [vault], or redeems it. A vault refuses redemptions until the lockup of
   * the latest deposit ends (4 days for HLP, 1 day for others); that's checked first.
   */
  suspend fun vault(
    account: String,
    vault: String,
    isDeposit: Boolean,
    amount: String,
    prompt: VaultPrompt,
  ): ActionState = sending {
    requireMainAccount(account, "Vault deposits and redemptions")
    require(HyperliquidCredentialParser.isAddress(vault)) { "That isn’t a Hyperliquid vault" }
    val usdc = usdc(amount)
    if (!isDeposit) {
      val stake =
        client.info.userVaultEquities(account).firstOrNull { it.vaultAddress.sameAs(vault) }
          ?: throw IllegalArgumentException("You have nothing in this vault")
      val lockedUntil = stake.lockedUntilTimestamp ?: 0L
      require(lockedUntil <= clockMs()) {
        "Your latest deposit is locked until ${formatCalendarDateTime(lockedUntil)}"
      }
    }
    val sentAt = clockMs()
    var journaled: JournaledAction? = null
    val result =
      sending {
        actions.sendAsAgent(
          VaultTransfer.ofUsdc(vault, isDeposit, usdc),
          prompt,
          record = { lapses ->
            JournaledAction.VaultTransfer(account, vault, isDeposit, usdc.toWire(), lapses).also { journaled = it }
          },
        ) { response, reference ->
          HyperliquidResults.default(response, reference)
        }
      }
    withLedgerHash(result, journaled, sentAt)
  }

  /** Signs [action] with the owner key; it lapses once its nonce leaves Hyperliquid's window. */
  private suspend fun ownerSigned(
    action: UserSignedAction,
    prompt: VaultPrompt,
    record: (lapsesAtMs: Long) -> JournaledAction,
  ): ActionState {
    val sentAt = clockMs()
    var journaled: JournaledAction? = null
    val result =
      sending {
        actions.sendAsOwner(action, prompt) { nonce ->
          record(nonce + HyperliquidActions.NONCE_WINDOW_MS).also { journaled = it }
        }
      }
    return withLedgerHash(result, journaled, sentAt)
  }

  /** Anything that fails before [block] reaches [actions] was never sent. */
  private suspend fun sending(block: suspend () -> ActionState): ActionState =
    try {
      block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      ActionState.Failed(error.message ?: "The transfer wasn’t sent", definitelyNotSubmitted = true)
    }

  /** The committed move under its ledger hash, once the ledger lists it; a short wait at most. */
  private suspend fun withLedgerHash(state: ActionState, action: JournaledAction?, sentAtMs: Long): ActionState {
    if (state !is ActionState.Committed || action == null) return state
    repeat(LEDGER_ATTEMPTS) { attempt ->
      if (attempt > 0) delay(LEDGER_RETRY_MS)
      runSuspendCatching { actions.ledgerEntry(action, state.reference, sentAtMs) }
        .getOrNull()
        ?.let { return state.copy(reference = it.hash) }
    }
    return state
  }

  private suspend fun owner(): String =
    preferences.values.first().ownerAddress ?: error("Use a device that holds the owner key")

  private suspend fun requireMainAccount(account: String, what: String) {
    require(actions.vaultFor(account) == null) { "$what go from your main account. Select it first." }
  }

  private fun usdc(amount: String): HlDecimal {
    val value = HlDecimal.parseOrNull(amount.trim())
    require(value != null && value.signum > 0) { "Enter an amount greater than zero" }
    require(value.decimalPlaces <= USDC_DECIMALS) { "USDC amounts have at most $USDC_DECIMALS decimals" }
    return value
  }

  companion object {
    /** What Hyperliquid's bridge keeps from each withdrawal to Arbitrum, in USDC. */
    val WITHDRAWAL_FEE: HlDecimal = HlDecimal.of(1)

    private const val USDC_DECIMALS = 6
    private const val LEDGER_ATTEMPTS = 3
    private const val LEDGER_RETRY_MS = 1_000L
  }
}

/** How Hyperliquid's ledger and TWAP history show the actions Flare journaled. */
internal object HyperliquidLedger {
  /**
   * Whether [update], made at or after [sinceMs], is [action]. A withdrawal or send that records
   * its nonce is matched by it; otherwise by kind, counterpart and amount, where a recorded fee may
   * or may not be part of the recorded amount.
   */
  fun matches(update: LedgerUpdate, action: JournaledAction, nonce: Long?, sinceMs: Long): Boolean {
    if (update.time < sinceMs) return false
    val delta = update.delta
    return when (action) {
      is JournaledAction.Withdraw ->
        delta.type == "withdraw" &&
          (delta.nonce?.let { nonce != null && (it == nonce || it == nonce * 1_000) }
            ?: sameAmount(delta.usdc, action.amount, delta.fee))
      is JournaledAction.SendUsdc ->
        (delta.type == "internalTransfer" || (delta.type == "send" && (delta.token ?: USDC) == USDC)) &&
          delta.destination.sameAs(action.destination) &&
          (delta.user == null || delta.user.sameAs(action.account)) &&
          (delta.nonce?.let { it == nonce } ?: sameAmount(delta.usdc ?: delta.amount, action.amount, delta.fee))
      is JournaledAction.MovePerpSpot ->
        delta.type == "accountClassTransfer" &&
          delta.toPerp == action.toPerp &&
          sameAmount(delta.usdc, action.amount, null)
      is JournaledAction.VaultTransfer ->
        if (action.isDeposit) {
          delta.type == "vaultDeposit" && delta.vault.sameAs(action.vault) && sameAmount(delta.usdc, action.amount, null)
        } else {
          delta.type == "vaultWithdraw" &&
            delta.vault.sameAs(action.vault) &&
            sameAmount(delta.requestedUsd, action.amount, null)
        }
      else -> false
    }
  }

  /** Whether [entry] is the TWAP [action] placed, which started no earlier than [sentAtMs] allows. */
  fun isTwap(entry: TwapHistoryEntry, action: JournaledAction.PlaceTwap, sentAtMs: Long): Boolean {
    val state = entry.state
    return state.coin == action.coin &&
      (state.side == Side.BUY) == action.isBuy &&
      state.minutes == action.minutes &&
      sameAmount(state.sz, action.size, null) &&
      state.timestamp >= sentAtMs - HyperliquidActions.CLOCK_SKEW_MS
  }

  private fun sameAmount(recorded: String?, sent: String, fee: String?): Boolean {
    val actual = recorded?.let(HlDecimal::parseOrNull) ?: return false
    val expected = HlDecimal.parseOrNull(sent) ?: return false
    if (actual.compareTo(expected) == 0) return true
    val charged = fee?.let(HlDecimal::parseOrNull)?.takeIf { it.signum > 0 } ?: return false
    return (actual + charged).compareTo(expected) == 0 || actual.compareTo(expected + charged) == 0
  }
}
