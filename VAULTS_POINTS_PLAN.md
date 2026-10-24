# Vaults and reward points

Implementation plan for the vault and points surfaces, based on Flare at `d5e693b`.
Prepared on 2026-10-22.

Both surfaces are thin: vaults show a name, AUM and a fee with nothing to judge a manager by, and
rewards render one endpoint as a list. Both venues already return more than `:shared` models. The
work is therefore mostly widening neutral models and splitting two ports out of
[AccountRepository](shared/src/commonMain/kotlin/xyz/mcxross/flare/data/AccountRepository.kt), not
adding venue-specific screens.

Phases 0 through 4 are done. The sections below are kept as the record of why, with progress marked
under **Phases**.

## What each venue already returns and the app discards

| Datum | Decibel | Hyperliquid | Shown today |
| --- | --- | --- | --- |
| Stake cash flows | `total_deposited`, `total_withdrawn`, `all_time_earned` | `followerState.allTimePnl` | No |
| Manager's own stake | manager capital rule (5% of NAV or $100k) | `leaderFraction` | No |
| Depositor count | account-owned vault metrics | `followers` | No |
| NAV history | share price series | `portfolio.accountValueHistory` | One latest value |
| Protocol vs user vault | vault type filter | computed, then dropped | No |
| Headline return | — | `apr` | No |
| Points by day | `/points/amps/daily` | n/a | No |
| Claimable campaigns | `/campaigns/*` | n/a | No |
| Rank context | `/points_leaderboard`, `/points/global` | `/leaderboard` | Bare rank |

## Two rules

**A capability is a binding or a null, never a new boolean.**
[VenueCapabilities](shared/src/commonMain/kotlin/xyz/mcxross/flare/venue/Venue.kt) already carries
14 flags. Adding one per feature is how this rots.

- A venue missing a whole surface binds no port. The precedent is `SessionRepository`, bound only by
  venues with a backend session and injected with `getOrNull()`.
- A venue missing one datum inside a surface it has returns null for it. The precedent is `Rewards`,
  which already carries five optional parts plus `RewardPart`/`keepingUnavailableFrom` for partial
  failure.
- A flag survives only for an *action* that data cannot imply, such as `referralCodes` — whether a
  code can be redeemed from inside the app.

**Adding a venue means writing a venue module, not editing `:shared`.**
Two places fail that test today and are fixed in phase 0:

- `PortfolioScreen` decides the whole shape of the rewards page with
  `if (state.rewardPointsName == null)`, using a display label as a type discriminator. It breaks on
  the first venue with both points and a fee tier.
- `capabilities.vaults` and `capabilities.rewards` duplicate information that port binding states
  exactly, synchronously, before any data loads.

## Ports

Both venue account repositories lean on private plumbing (`readAccount`, `currentAccount`, `moving`,
sessions), so phase 0 segregates interfaces without moving code between classes: one implementation
binds several ports, the way `DefaultWalletRepository` already binds `WalletRepository` and
`AptosSigningWallet`. Splitting the implementations later is invisible to `:shared`.

```kotlin
interface VaultsRepository {
  suspend fun vaults(limit: Int = 50): List<VaultSummary>
  suspend fun vaultPositions(): List<VaultPosition>
  suspend fun navHistory(vault: String, range: SeriesRange): Series? = null   // phase 3
  fun contribute(vault: String, amount: String, prompt: VaultPrompt, feePayment: FeePayment): Flow<ActionState>
  fun redeem(vault: String, shares: String, prompt: VaultPrompt, feePayment: FeePayment): Flow<ActionState>
}

interface RewardsRepository {
  suspend fun rewards(): Rewards?
  suspend fun redeemReferralCode(code: String): Boolean
  suspend fun pointsHistory(range: SeriesRange): Series? = null               // phase 3
  suspend fun leaderboardAround(account: String): LeaderboardWindow? = null   // phase 4
  suspend fun campaigns(): List<Campaign> = emptyList()                       // phase 2
  fun claim(id: String, prompt: VaultPrompt, feePayment: FeePayment): Flow<ActionState> = // phase 2
    flowOf(ActionState.Failed("Claims aren’t available here", definitelyNotSubmitted = true))
}
```

A default method covers a capability a venue lacks *within* a surface it has, matching how
`transferUsdc`, `fees()` and `tradingAccountStatus()` already default. A separate port is for a
venue lacking the whole surface.

Phase 0 keeps the current signatures and the per-call loading the ViewModel does now. The cached
`StateFlow` snapshot with per-part staleness lands in phase 3, with the series, so the refactor
stays behaviour-preserving.

## Model additions

```kotlin
enum class VaultKind { PROTOCOL, USER }

sealed interface ManagerFee {
  data class Interval(val percent: Double, val days: Int, val nextChargeAtMs: Long?) : ManagerFee
  data class HighWatermark(val percent: Double) : ManagerFee
  data class Flat(val percent: Double) : ManagerFee
}
```

`ManagerFee` is sealed rather than a set of nullable fields because the disclosure sentence differs
per mechanic: Decibel crystallizes on a 30–365 day interval with no high watermark, so a depositor
joining mid-interval pays a fee on gains that predate them, and a manager earns on a recovery after
a drawdown. Hyperliquid does not work that way. Sealed makes the renderer exhaustive, so a venue
with a new mechanic fails to compile at the one place that explains fees to a person, rather than
silently showing a wrong sentence about their money.

`VaultSummary` also gains `kind`, `fee: ManagerFee`, `managerStakePercent: Double?`,
`depositorCount: Int?` and `acceptsDeposits: Boolean`. `VaultPosition` gains `deposited`,
`withdrawn` and `earned`, all nullable.

Claim amounts are the one place money reaches a transaction, so they stay exact decimal text:

```kotlin
data class Campaign(
  val id: String,
  val name: String,
  val status: CampaignStatus,  // NOT_ELIGIBLE, IN_PROGRESS, READY, CLAIMED, EXPIRED
  /** Exact decimal text. Signed into the claim; never parsed to Double. */
  val amountUsdc: String?,
  val expiresAtMs: Long?,
  val detail: String,
)
```

Existing vault and points models stay `Double`; their doc comments say display only and nothing
signs them. Hyperliquid's SDK keeps money as decimal text through `DecimalTextSerializer` and the
venue mapper is where it degrades, so a claim must not take that path.

## Phases

**Phase 0 — ports and section-driven rewards. No features. Done.**

1. `VaultsRepository`: move `vaults`, `vaultPositions`, `contributeToVault`, `redeemFromVault` off
   `AccountRepository`. Bind in both venue modules. `PortfolioViewModel` takes it optional and gates
   on nullability instead of `capabilities.vaults`.
2. `RewardsRepository`: move `rewards` and `redeemReferralCode`. Bind in both venue modules.
   `PortfolioViewModel` and `SettingsViewModel` take it optional. `referralCodes` becomes explicit
   per descriptor rather than defaulting to `rewards`.
3. Remove `vaults` and `rewards` from `VenueCapabilities`; the bindings state them.
4. Rewards page renders an ordered list of sections the ViewModel assembles — points, streak, tier,
   fee tier, referral — each present or absent, with no branch on venue or on `rewardPointsName`.

Behaviour identical; tests green.

**Phase 1 — what the venues report and the app dropped. Done.** `VaultKind`, `ManagerFee`, manager
stake, depositor count and the stake's cash flows, with the DLP/HLP vault pulled out of the list as
its own card and the interval-fee consequence stated in the deposit sheet.

Two things turned out differently from the sketch above. Decibel's real `PublicVaultDto` already
carries `vault_type`, `depositors` and `manager_cash_pct`, which `VaultInfo` was not parsing, so
these needed SDK fields rather than only mappers — no manager-capital rule had to be inferred. And
nothing in either API states the fee *interval length*, so `ManagerFee.Interval` carries nullable
`days`/`nextChargeAtMs`: the mechanic is a documented protocol fact, the period is not published.
`acceptsDeposits` was dropped as planned-but-redundant, since `isActive` already means exactly that
on both venues.

The venue's own vault needed copy that must not live in `:shared`, so `VaultTerms` gained
`ProtocolVaultTerms(summary, risk)`, set per descriptor the way `ReceiveInstructions` already is.

**What `openapi.json` showed for later phases.** `PublicVaultDto` also returns `apr`,
`all_time_return`, `past_month_return`, `pnl_90d`, `max_drawdown`, `sharpe_ratio`, `volume_30d`,
`average_leverage` and `created_at`, and `/api/v1/vaults` takes a `VaultSortKey` of
`tvl | age | pnl | sharpe_ratio | weekly_win_rate | max_drawdown | apr` plus `status`, `vault_type`,
`vault_address` and `search`. So making the list judgeable is server-side sorting over fields that
already exist, not a client-side ranking pass. `AccountVaultPerformanceDto` further carries a
`deposits` array whose entries have `unlock_timestamp_ms`, which is a better lockup countdown than
the single per-position timestamp, plus `locked_amount`, `unrealized_pnl` and `weekly_win_rate_12w`.
Note `current_num_shares` is an integer of raw chain units, as the existing normalization assumes.

**Field semantics confirmed against mainnet.** Aptos testnet has no vaults, so these were read from
15 live mainnet vaults through a second `wrangler dev` whose only difference is
`DECIBEL_REST_ORIGIN`. Two of them contradicted a reasonable-looking assumption, so they are
recorded here rather than re-derived:

| Field | What it is | Caution |
| --- | --- | --- |
| `profit_share` | percentage | 10.0 is 10% |
| `apr` | **percentage**, and may be negative | Hyperliquid's `apr` is a *fraction*; the venues differ |
| `all_time_return`, `past_month_return`, `max_drawdown` | percentages | drawdown arrives already negative |
| `sharpe_ratio` | bare ratio | |
| `weekly_win_rate_12w` | **fraction** | 0.846 is 84.6%, unlike the returns beside it |
| `volume_30d` | USD | `0.0` on a vault that never traded; not worth a row |
| `depositors` | real count | Hyperliquid has no equivalent — its `followers` is a capped page |
| `manager_cash_pct` | **share held in cash, not the manager's stake** | see below |

`manager_cash_pct` is the share of the vault sitting in cash rather than deployed. All 12 mainnet
vaults with no open position read exactly `100.000`, and those carry 1–119 depositors; the only
three with exposure read below it and fall as leverage rises (`avg_lev` 0.01 → 99.8%, 1.33 → 65.5%,
7.18 → 56.9%). It is therefore not a manager stake, and Decibel reports none: `managerStakePercent`
stays null there and only Hyperliquid's `leaderFraction` fills it. The exact denominator could not
be reconstructed from `tvl`, `perp_equity` and `average_leverage`, so it is shown as "Held in cash",
a label the behaviour supports without needing the formula.

**Phase 2 — campaigns. Reading done; claiming blocked.**

Reading is built: `Rewards.campaigns` carries a `CampaignRewards` the rewards page renders as its
own section, with each allocation in a `ClaimState` of `NOT_ELIGIBLE | IN_PROGRESS | READY |
CLAIMED | EXPIRED` derived from `has_allocation`, `ready_to_claim`, `claimed_amount` and the claim
window. Amounts are integer chain units in the API and become exact decimal text through
`ULong.toDecimalString(6)`; no campaign amount is ever a `Double`. Campaigns fold into the existing
`Rewards` record rather than a separate port method, so `keepingUnavailableFrom` already covers a
partial failure — `RewardPart.CAMPAIGNS`. Hyperliquid returns null and the section disappears.

Mainnet has 13 active campaigns across `affiliate`, `volume_milestone`, `maker_incentive`,
`liquidation_rebate` and `first_funded_trial`. `campaign_type` and `status` are documented as
extensible enums, so both are kept as strings rather than Kotlin enums.

Two things block the rest, both outside this repo:

1. **The Worker does not allowlist the campaign routes.** `src/index.ts` in `decibel-worker` gates
   every Decibel path through `PUBLIC_DECIBEL_ENDPOINTS` / `ACCOUNT_DECIBEL_ENDPOINTS`, and
   `campaigns/active`, `campaigns/account`, `campaign_locks` and `protected_trials` are on neither,
   so they 404 before reaching upstream. `campaigns/active` is public; the other three take an
   `account` parameter and work with the existing `enforceAccountScope` default branch. This needs
   a PR against `decibel-worker`; until it lands the section stays empty in any real build.
2. **No claim transaction is discoverable.** The REST API is read-only for campaigns, the docs
   describe no claim entry function, and the Decibel package has no campaign module — all 121
   modules at `0x50ead22a…` were listed and none is campaign-, claim- or reward-related. Campaigns
   are a separate on-chain package whose address `campaigns/active` does not return. Claiming is
   therefore not implemented: guessing a Move call that moves money is the one thing not worth
   doing. The screen says so rather than offering a button that cannot work.

When the entry function is known, claiming is a `RewardsRepository.claim()` returning
`Flow<ActionState>` through `trading.executeCommand`, journalled before broadcast, with an uncertain
outcome staying pending rather than failed so nothing is claimed twice.

**Phase 3 — the two series.** Vault NAV and daily points are the same shape. Generalize
`PortfolioPoint`/`PortfolioChartRange`/[PortfolioChart](shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/portfolio/PortfolioChart.kt)
into one `Series` plus one chart taking a value formatter, rather than growing a third bespoke
chart. Both ports move to a cached `StateFlow` snapshot with a day-boundary-aware refresh.

**Phase 4 — context and habit. Done, and it fixed more than it added.**

The rank now reads against the field from `points/global` — "#4,812 of 61,200", "Top 7.86%", and a
share of everything distributed. A leaderboard *window* was dropped deliberately: it shows strangers'
addresses either side of you, which is a lot of screen for little meaning. The streak is a week
strip with the grace day spelled out, and the referral panel shows the code, how many traders it
brought in, who referred the account, and — when Decibel still withholds a code — what unlocks it.
The gate wording lives on the descriptor (`VenueDescriptor.referralGate`), not in `:shared`.

**Three bugs, each hiding the next.** Streak and tier had never worked on Decibel:

1. Neither route was allowlisted in the Worker, so both 404'd in 1–3ms before reaching upstream.
2. The streak DTO declared snake_case where `/streaks/account` answers in **camelCase** — every
   field would have decoded to a default. A passing test encoded the same wrong assumption.
3. Both sent `account=` with the subaccount where the API takes `owner=` with the wallet. All four
   owner-scoped reads — amps, daily amps, streak, tier — now share one `readOwner` helper.

Only the first was visible from outside; each had to be fixed before the next could be observed.

**Worker changes this needed**, in `decibel-worker`: PR #2 allowlists `points_leaderboard`,
`points/global`, `points/tier` and `streaks/account`, and turns the owner-scope branch into a set.
PR #3, stacked on it, scopes the referral routes — two of which carry the account in the *path*,
which `enforceAccountScope` had no mechanism for — and makes a malformed address refuse rather than
raise a 500. Its helpers live in `src/scope.ts` so they can be tested; `index.ts` imports
`cloudflare:workers` and cannot be loaded by the node test environment.

**Phase 5 — DLP shares as collateral** remains the only phase not started.

**Phase 5 — DLP shares as collateral. Reading done; the write is specified and not written.**

`account_overviews` already carries `secondary_collateral`, which `AccountOverview` was not parsing.
The balance breakdown now lists each secondary asset with what the venue values it at and the credit
it counts at — Decibel stores the discount as a haircut in basis points, so 1,000 bps is shown as
"counts at 90%". The screen deliberately does **not** multiply value by credit: whether
`value_in_usdc` is already haircut is not stated anywhere, and inventing that figure is how the APR
and `manager_cash_pct` mistakes happened.

**The write is a transfer, not a toggle.** The docs describe a "toggle Enable as Collateral", but
on-chain it is:

```
dex_accounts_entry::transfer_assets_between_non_collateral_and_collateral(
  &signer, Object<Subaccount>, Object<Metadata>, i64
)
```

A signed amount moves shares between a subaccount's non-collateral and collateral buckets —
positive pledges, negative releases. Three reads support it, all through `POST /view`, which the
Worker already proxies, so no further proxy change is needed:

| Call | Gives |
| --- | --- |
| `vault::get_vault_share_asset_type(vault)` | the share asset's `Metadata`, needed before anything is pledged |
| `vault_share_asset::get_user_unlocked_balance(metadata, account)` | what may be pledged, excluding shares still in lockup |
| `accounts_collateral::get_cross_secondary_balance(account, metadata)` | what is pledged now |

Two things to get right when it is written. The amount is **i64**: Kaptos has no signed variant, but
BCS encodes i64 and u64 identically for the same bit pattern, so `MoveArgument.U64(value.toULong())`
is correct for a negative Kotlin `Long` — and deserves a test asserting the exact bytes for a
negative amount rather than trusting that reasoning. And pledging changes what a liquidation takes:
DLP collateral is burned through the vault rather than sold, so the confirmation has to say that
before it is signed, the way the vault deposit sheet states the fee mechanic.

## Testing

Per-venue mapper tests and `ServiceContractTest` already exist. Add a shared contract test over each
new port that both venue implementations run against, and give
[TestVenue](shared/src/commonTest/kotlin/xyz/mcxross/flare/TestVenue.kt) vaults-without-rewards and
rewards-without-vaults variants, so the section-driven UI and the unbound-port path are proven
without a third real exchange.
