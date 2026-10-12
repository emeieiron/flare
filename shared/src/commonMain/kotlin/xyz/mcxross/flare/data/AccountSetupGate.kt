package xyz.mcxross.flare.data

import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.setupPending
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.model.ActionKind

/** What a wallet whose setup was left for later still needs before it can send actions. */
enum class SetupNeed(val reason: String, val actionLabel: String) {
  /** The exchange enables trading only once the account's first deposit arrives. */
  FUNDS("Add funds to start trading. Trading can be enabled once your first deposit arrives.", "Add funds"),
  /** The account can enable trading on this device, which setup does next. */
  TRADING("Enable trading on this device to place orders and move funds.", "Enable trading"),
}

/** [capabilities] with every action held back by [need], keeping the venue's own reasons first. */
fun VenueCapabilities.gatedBy(need: SetupNeed?): VenueCapabilities =
  if (need == null) this
  else copy(unavailableActions = ActionKind.entries.associateWith { need.reason } + unavailableActions)

/**
 * Whether the active wallet's setup was left for later, and what it waits for. Whether the account
 * still waits for its first deposit is read from the venue only when [refresh] is asked, and no more
 * often than [minIntervalMs].
 */
class AccountSetupGate(
  private val preferences: AppPreferences,
  private val accounts: AccountRepository,
  private val minIntervalMs: Long = MIN_CHECK_INTERVAL_MS,
  private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
  private data class FundingCheck(val profileId: String, val awaitsDeposit: Boolean, val atMs: Long)

  private val funding = MutableStateFlow<FundingCheck?>(null)
  private val mutex = Mutex()

  /** Null when the active wallet's setup is done, or was never left for later. */
  val need: Flow<SetupNeed?> =
    combine(preferences.values, funding) { saved, check ->
        val active = saved.profiles.firstOrNull { it.id == saved.activeProfileId }
        when {
          active == null || !active.setupPending -> null
          check?.profileId == active.id && check.awaitsDeposit -> SetupNeed.FUNDS
          // Unknown or funded: resuming setup checks the deposit itself before enabling trading.
          else -> SetupNeed.TRADING
        }
      }
      .distinctUntilChanged()

  /**
   * Re-reads the deposit check for a wallet left waiting; a failed read keeps the last answer. A
   * check made less than the interval ago is reused, or with [whenDue] waited out before reading.
   */
  suspend fun refresh(whenDue: Boolean = false) {
    val active = pendingProfile() ?: return
    val last = funding.value
    if (last?.profileId == active) {
      val wait = minIntervalMs - (nowMs() - last.atMs)
      if (wait > 0) if (whenDue) delay(wait) else return
    }
    mutex.withLock {
      val profile = pendingProfile() ?: return
      val current = funding.value
      if (current != null && current != last && current.profileId == profile) return
      val awaits = runSuspendCatching { accounts.awaitsFirstDeposit() }.getOrNull() ?: return
      funding.value = FundingCheck(profile, awaits, nowMs())
    }
  }

  private suspend fun pendingProfile(): String? {
    val saved = preferences.values.first()
    return saved.profiles.firstOrNull { it.id == saved.activeProfileId }?.takeIf { it.setupPending }?.id
  }

  private companion object {
    const val MIN_CHECK_INTERVAL_MS = 15_000L
  }
}
