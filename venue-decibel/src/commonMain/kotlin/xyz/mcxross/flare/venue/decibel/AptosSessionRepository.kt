package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.SessionStatus
import xyz.mcxross.kaptos.account.Account

/** The Flare Worker session, which can also verify a trading key before it is stored. */
interface AptosSessionRepository : SessionRepository {
  /** Authenticates a trading key that is not stored yet, to verify it before adopting it. */
  suspend fun verifyApiCredential(account: Account, subaccount: String): SessionStatus
}

/** Renew a session that has less than this left, so an action never starts on an expiring token. */
const val SESSION_RENEWAL_MARGIN_MS = 60_000L
