# Optional Decibel referrals

The worker owns program availability and the suggested public code. The app offers an editable,
removable suggestion during account creation and an entry under Account → Settings → Referral code.
Existing bindings are read-only. There is no dependency on swaps, trading commands, or builder fees.

## Disable or remove

Set `REFERRALS_ENABLED="false"` in `decibel-worker/wrangler.toml` and deploy. The worker hides the
feature and rejects new redemptions. Visible screens refresh configuration every 30 seconds.
Read-only reconciliation remains available for requests submitted before shutdown.
`DEFAULT_REFERRAL_CODE` changes the suggestion without an app release; an empty value leaves it blank.

To remove the app integration, remove `includes(decibelReferralsModule())` from `DecibelVenue.kt`.
Both view models accept a nullable `ReferralRepository`; normal onboarding remains available.
Keep pending-intent safeguards until outstanding requests have been resolved before removing the
worker routes or deleting this module. Existing attribution at Decibel is unaffected by removing UI.

## Submission and recovery

Background operations only read configuration, eligibility, and code validity. Apply or the existing
account-creation action explicitly submits the chosen code. The worker derives the owner from its
authenticated session and enforces Decibel's eligibility rules. The app never trusts a client-selected
owner or trading account as proof of attribution.

Decibel redemption can create a trading account. A public code intent is saved before the POST so a
timeout, cancellation, or process restart cannot fall through to ordinary account creation. Neither
the app nor the worker automatically resubmits an uncertain redemption. Reads reconcile it instead.
An owned account may be reused while attribution remains pending, but account existence alone never
counts as successful referral attribution. If no account can be verified, setup waits for a definitive
result; do not clear the intent merely because time passed. A persistently unknown result requires
provider investigation before a manual recovery decision.

The existing `tradingAccountOpened` setup marker is saved before clearing a confirmed intent.
No new success flag is added to account profiles. Session, profile, and authorization checks reject
late results after a wallet change. Read failures retain automatic backoff and diagnostic events.

## Verification

Common tests cover validation cancellation, removal of suggestions, program shutdown, visibility,
owner changes, explicit submission, and uncertain outcomes. Venue tests verify the wire contract,
owner-bound sessions, and durable-intent ordering. Onboarding tests ensure referred accounts are
reused and clearing the field preserves normal creation.

Decibel debug previews include `referral-onboarding`, `referral-settings`, `referral-bound`,
`referral-ineligible`, and `referral-pending`. These use sample state and cannot redeem a code or
create an account. Live verification for this change is read-only; a real redemption is not part of
the test suite.
