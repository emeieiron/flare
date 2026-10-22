# Vaults and reward points

Implementation plan for the vault and points surfaces, based on Flare at `d5e693b`.
Prepared on 2026-10-22.

Both surfaces are thin: vaults show a name, AUM and a fee with nothing to judge a manager by, and
rewards render one endpoint as a list. Both venues already return more than `:shared` models. The
work is therefore mostly widening neutral models and splitting two ports out of
[AccountRepository](shared/src/commonMain/kotlin/xyz/mcxross/flare/data/AccountRepository.kt), not
adding venue-specific screens.

Phases 0 and 1 are done. The sections below are kept as the record of why, with progress marked
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

**Phase 2 — campaigns and claiming.** Decibel binds `campaigns()`/`claim()`; Hyperliquid inherits
the defaults. Decimal text end to end, journalled before broadcast, an uncertain claim stays pending
rather than failed, never claimed twice. Expiry countdown per tile, since an allocation lapses at
the end of the cycle after it is published.

**Phase 3 — the two series.** Vault NAV and daily points are the same shape. Generalize
`PortfolioPoint`/`PortfolioChartRange`/[PortfolioChart](shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/portfolio/PortfolioChart.kt)
into one `Series` plus one chart taking a value formatter, rather than growing a third bespoke
chart. Both ports move to a cached `StateFlow` snapshot with a day-boundary-aware refresh.

**Phase 4 — context and habit.** Leaderboard window and global stats behind the rank, a referral
panel with gate progress and the account's own code, and the streak as a week strip with the grace
day labelled as the one free miss it is.

**Phase 5 — DLP shares as collateral.** Decibel-only on-chain action with an explicit opt-in and
risk acknowledgement. Genuinely venue-specific, so it comes last.

## Testing

Per-venue mapper tests and `ServiceContractTest` already exist. Add a shared contract test over each
new port that both venue implementations run against, and give
[TestVenue](shared/src/commonTest/kotlin/xyz/mcxross/flare/TestVenue.kt) vaults-without-rewards and
rewards-without-vaults variants, so the section-driven UI and the unbound-port path are proven
without a third real exchange.
