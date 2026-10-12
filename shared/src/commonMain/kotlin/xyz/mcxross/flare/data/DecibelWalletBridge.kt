package xyz.mcxross.flare.data

import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.kaptos.account.Account
import xyz.mcxross.kaptos.model.AccountAddress

// Decibel-only signing seams that DefaultAccountRepository and DefaultTradingRepository still use
// from :shared. Their implementations live in :venue-decibel; this file moves there with those two
// repositories (PRs 7 and 8).

/**
 * Decibel's view of the device's keys: Kaptos accounts to sign Aptos transactions and Worker
 * challenges. Bound to the same instance as [WalletRepository].
 */
interface AptosSigningWallet : WalletRepository {
  /**
   * Stores an imported trading key only after [verify] accepts it, so a rejected key never replaces
   * a working one. [verify] may sign with the key, which [WalletRepository.importApiWallet] can't.
   */
  suspend fun importAptosApiWallet(
    key: String,
    prompt: VaultPrompt,
    verify: suspend (Account) -> Unit,
  ): String

  suspend fun <T> withOwnerAccount(prompt: VaultPrompt, block: suspend (Account) -> T): T

  suspend fun <T> withApiAccount(prompt: VaultPrompt, block: suspend (Account) -> T): T
}

/** The Flare Worker session, which can also verify a trading key before it is stored. */
interface AptosSessionRepository : SessionRepository {
  /** Authenticates a trading key that is not stored yet, to verify it before adopting it. */
  suspend fun verifyApiCredential(account: Account, subaccount: String): SessionStatus
}

/** Renew a session that has less than this left, so an action never starts on an expiring token. */
const val SESSION_RENEWAL_MARGIN_MS = 60_000L

fun String.sameAptosAddress(other: String): Boolean =
  AccountAddress.fromString(this) == AccountAddress.fromString(other)
