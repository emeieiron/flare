# Account capabilities and foreground authorization

## Agreed policy

Authenticate once when opening an installation with saved credentials. Keep ordinary trading
and automatic server-session renewal available for the foreground visit, without a five-minute
signing timeout. Clear local signing material on backgrounding; system overlays that do not stop
the app must not cause another prompt. Sensitive actions require fresh device authentication:
deposits, withdrawals, delegation, revocation, and exporting secrets.

Use independent profiles for each owner or API-only import. Each owner profile retains its trading
accounts, selected account, backup status, and one independently generated device trading key.
Importing an owner does not recover remote delegates' private keys. Never replace credentials on
an inconclusive network response. Profile switches must not reuse another profile's drafts or data.

Onboarding discovers accounts, offers creation if none exist, and configures trading without a
required deposit. Verify API-only imports before activating them. Retry setup with the existing
key and reconcile unknown transaction outcomes before resubmission.

Prefer sponsorship. Offer an explicitly reviewed self-payment fee only after definitive sponsor
rejection and checking the signer's APT balance. Otherwise show a concise operation failure.
Withdraw to the owner by default; other destinations need an explicitly reviewed transfer and
resumable state if withdrawal succeeds but transfer does not.

## Implementation sequence

1. Add a foreground vault boundary, lifecycle gate, and secure storage namespaces. Preserve legacy
   storage and wipe cached bytes on backgrounding. Test cancellation and late authentication.
2. Persist independent account profiles; support adding and switching profiles. Isolate navigation
   state by profile and selected trading account. Test migration and credential reuse.
3. Resolve sessions at the repository capability boundary. Renew sessions silently while authorized;
   enforce owner-only commands and restore trading after owner actions. Guard signing after background.
4. Remove manual unlock controls and technical session copy. Keep launch retry separate from ordinary
   action UI. Review account identity on transaction screens.
5. Make onboarding resumable and deposit-free, validate API imports, and add setup fee fallback.
6. Add destination-aware withdrawal with durable transfer continuation and balance validation.
7. Compile supported mobile targets and run focused vault, profile, setup, session, and transaction
   tests. Record any environment limits and remaining device verification honestly.

## Acceptance checks

- First launch without credentials has no unlock prompt; returning installations have one.
- Trading past five minutes and switching configured profiles adds no biometric prompts.
- Backgrounding clears authorization, including in-flight authorization/signing attempts.
- Deposits/withdrawals/delegations/exports require fresh approval once per reviewed operation.
- API-only profiles cannot fund, withdraw, delegate, revoke, or create trading accounts.
- Setup retries keep the same key and never interpret verification failure as no delegation.
- A successful withdrawal is never repeated while continuing its destination transfer.
- Existing single-owner credentials remain accessible after migration.
