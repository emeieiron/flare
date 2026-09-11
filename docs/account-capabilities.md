# Account capabilities and foreground authorization

## Policy

Authenticate once when opening an installation with saved credentials. Ordinary trading, and the
session renewal behind it, then stay available for that foreground visit. Backgrounding clears the
local signing material; a system overlay that does not stop the app must not cause another prompt.
Deposits, withdrawals, enabling or revoking trading access, and showing a secret require fresh
device authentication at their final confirmation, and that one approval completes the action.

Use independent profiles for each owner or imported trading key. A profile keeps its trading
accounts, selected account, backup state, and one device trading key. Importing an owner cannot
recover the private keys of delegates on other devices. Never replace credentials because a check
was inconclusive, and never carry one profile's data into another.

Setup discovers accounts, offers to create one when none exists, and enables trading without
requiring a deposit. Verify an imported trading key before adopting it. Retry with the existing key
and resolve unknown transaction outcomes before submitting again.

Withdraw to the owner wallet by default. Another destination is a reviewed withdrawal followed by a
transfer, with durable progress so a confirmed withdrawal is never repeated to finish the transfer.

## Where the policy lives

| Rule | Code |
| --- | --- |
| One authentication per foreground visit; backgrounding wipes cached keys and cancels signing | `security/ForegroundWalletVault.kt`, launch gate in `App.kt` |
| The action decides the key | `DecibelCommand.requiredSigner()` in `data/TradingRepository.kt` |
| Sessions are established by the action, pinned to it, and handed back afterwards | `data/SessionRepository.kt` (`bind`, `ensureTrading`), `execute` in `data/TradingRepository.kt` |
| Setup reuses this device's key and never replaces one on an inconclusive check | `data/TradingWalletSetup.kt` |
| Durable progress for account creation and withdrawal continuation | `store/AppPreferences.kt` (`AccountProfile`), `withdrawUsdc` in `data/AccountRepository.kt` |
| One profile per owner or imported trading key, migrated from single-account storage | `store/AppPreferences.kt` |

Sponsorship is attempted first. Only a definitive rejection with a fee estimate, plus enough APT to
cover it, offers an explicitly reviewed self-payment; otherwise the operation reports a plain
failure with a retry.

## Acceptance checks

- First launch without credentials has no unlock prompt; returning installations have one.
- Trading later in the same visit, and switching configured profiles, adds no biometric prompts.
- Backgrounding clears authorization, including in-flight authorization/signing attempts.
- Deposits/withdrawals/delegations/exports require fresh approval once per reviewed operation.
- API-only profiles cannot fund, withdraw, delegate, revoke, or create trading accounts.
- Setup retries keep the same key and never interpret verification failure as no delegation.
- A successful withdrawal is never repeated while continuing its destination transfer.
- Existing single-owner credentials remain accessible after migration.
