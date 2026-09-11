# Flare mobile design

Flare prioritizes the current task: find a market, understand its price, review an order, or manage an account. Use typography and spacing to establish hierarchy. Add a surface only when it groups an interaction, such as a search field or a bottom sheet.

## References

The refresh draws on the restrained type, open chart area, and clear primary actions in [Robinhood’s mobile screenshots](https://apps.apple.com/us/app/robinhood-trading-investing/id938003185). [Google’s Robinhood design study](https://design.google/library/robinhood-investing-material) provides additional context for progressive onboarding and visual hierarchy. Flare uses its own colors and native components; no Robinhood assets are bundled.

## Foundations

| Token | Value / use |
| --- | --- |
| Canvas | `#000000` |
| Surface | `#101210`, sheets and grouped interactions |
| Elevated | `#191C19`, selected controls and search |
| Primary text | `#F5F5F5` |
| Secondary text | `#A3A3A3` |
| Tertiary text | `#8B8D89` |
| Accent / positive | `#C4F564` |
| Negative | `#FF766B` |
| Border | `#242424`, section separators |
| Font | Bundled Inter; tabular numerals |
| Price | 40/46, medium; portfolio balance 48/54 |
| Page title | 32/38, medium |
| Section title | 22/28, medium |
| Body | 14/22 or 16/24 |
| Screen gutters | 24 dp |
| Primary action | At least 52 dp high; pill shape |
| Tabs and icon targets | At least 48 dp where space permits; 44 dp minimum |
| Transitions | Short fades and control-color transitions; no bounce |

`FlareTheme.kt` owns colors, typography, shapes, and default foreground color. The latter also applies to screens outside a Material scaffold. Use explicit semantic color with a label or direction indicator; color alone must not carry meaning.

## Navigation

The persistent navigation has four destinations:

- **Markets:** searchable market list and watchlist.
- **Portfolio:** balance, buying power, positions, deposits, and withdrawals.
- **Activity:** open orders, order history, trades, and funding.
- **Account:** wallet setup, slippage preference, security, recovery, and connection details.

Opening a market pushes its detail screen. The bottom navigation is hidden there, leaving a back action and a persistent Trade button. Selection derives from the navigation back stack, so system back cannot leave a stale selected tab. Main destinations retain their navigation state when returning from account setup.

Keep market details focused on price, chart, and statistics. Put indicators in Chart settings and reveal the order book on request. Trade opens a separate sheet: direction and order type → size and leverage → optional take profit and stop loss → review → authorization. Review shows the size, leverage, margin mode, estimated margin, entry price, order value, slippage, and separate profit/loss estimates at the entered exit prices. A completed order or transfer ends on a receipt with one Done action; completing an order clears its inputs.

`OrderControls.kt` contains the custom amount surfaces and expandable leverage scale. Size has the strongest hierarchy. Leverage sits beside its estimated margin; tapping it reveals a scale and quick values bounded by the market maximum. Take profit and stop loss share one compact row. All numeric controls support a decimal keyboard and accessible labels; the scale also exposes adjustable progress semantics.

For a new position, the selected leverage is applied to the market before the entry is submitted. Failed or unresolved configuration stops the sequence. Existing positions retain their current leverage, with a short explanation of the protocol restriction. [Estimated initial margin](https://docs.decibel.trade/for-traders/margin) is order value divided by selected leverage, before fees; it is not a liquidation-loss estimate. Cross margin is used unless the market requires isolated margin or an existing position already uses it. Leverage configuration and entry authorization remain in the same order flow.

Take profit and stop loss are attached to the entry in the same [Decibel order transaction](https://docs.decibel.trade/developer-hub/on-chain/order-management/place-order). The entered price is both the trigger and limit for each exit. The review explains that a triggered limit order may remain unfilled. Estimates use the entered base-asset size and the limit entry price, or the current best ask/bid for a market buy/sell, before fees and funding. They do not multiply the position size by leverage again. For longs, take profit must be above entry and stop loss below; shorts reverse these conditions. Exact decimal and tick validation runs before review and again before submission. Simulation, signing, and transaction reconciliation remain authoritative.

## Account setup and import

The welcome screen has Create account, Import account, and Explore markets first. Browsing remains available when the network is temporarily unavailable.

A single password-style input recognizes supported BIP-39 recovery phrases, raw 32-byte hex keys, and AIP-80 Ed25519 keys locally. Raw keys are canonicalized to AIP-80 before secure storage. No credential content is persisted in UI saveable state.

Key encoding does **not** establish owner or trading permissions. A private key therefore reveals one Owner key / Trading key choice, because that distinction cannot be read from the key itself. Recovery phrases always use the owner path. A trading key also asks for the trading account it may act for, and Flare verifies that permission before adopting the key; a rejected key never replaces a working one.

Creation separates recording the recovery phrase from confirming three words. If the app leaves the foreground before confirmation, the words are cleared from UI memory. Resuming setup requires authorization to retrieve the phrase again. Keep recovery operations behind fresh device authorization.

Setup ends when the account can trade, not when it holds funds. Flare finds the owner's trading accounts, offers to create one when none exists, and gives this device its own trading key. Creating the account and enabling trading are two transactions presented as one reviewed step. Retrying reuses the same key and resolves an unknown transaction outcome before sending anything again.

## Authorization

Opening the app is the unlock. An installation with saved credentials authenticates once, and ordinary trading works for the rest of that visit: no session controls, no expiry mid-session, and no prompt for opening an order ticket. Leaving the app clears signing authority; returning asks once more.

The action decides the key, so the interface never asks the user to choose one:

| Action | Key | Prompt |
| --- | --- | --- |
| Reading account data | Existing session | None |
| Placing, cancelling, leverage, TP/SL, closing | Device trading key | None during the visit |
| Deposit, withdrawal, enabling or revoking trading access, showing a secret | Owner key, or the secret itself | Fresh approval at the final confirmation |

A fresh approval belongs to the action the user just reviewed and completes it; it never doubles as a session step. After an owner action, the trading session returns on its own. An installation that holds only a trading key can trade, but deposits, withdrawals, and delegation are unavailable to it.

Each owner is a separate profile with its own trading accounts and one device trading key. Switching profiles inside an authenticated visit does not prompt again, and screens are keyed by profile and trading account so no state carries across a switch.

## Charts

Line charts are the default for new preferences. Existing saved chart-style preferences are respected.

- Line charts fit the entire selected period to the available width.
- Price bounds follow the data, with 12% vertical padding and a small positive span for flat or single-point data. Prices are not anchored to zero.
- Candles open at the most recent 60 intervals, or the full series when shorter. Vertical bounds follow the visible candles, including partial candles and their wicks, as users pan and pinch. Users can zoom out to the full period.
- Changing market, time range, or style resets the chart viewport.
- Vico handles marker selection using its actual plot coordinates, avoiding mismatched hand-built crosshairs.
- Range controls fit across the screen. Loading states replace the previous range’s chart while its new data loads.

## Component ownership

| Location | Responsibility |
| --- | --- |
| `design/FlareTheme.kt` | Shared visual tokens and type |
| `design/FlareComponents.kt` | Buttons, tabs, navigation, market rows, search, prices |
| `design/FlareLayouts.kt` | Headers, action/detail rows, section labels, sheets |
| `design/AssetIdentity.kt` | Human-readable asset names and monograms |
| `feature/onboarding/` | Account setup and unified import |
| `feature/markets/` | Discovery and watchlist |
| `feature/trade/` | Detail screen, chart viewport, order ticket |
| `feature/portfolio/` | Portfolio, funding sheet, position sheet |
| `feature/orders/` | Activity and history |
| `feature/settings/` | Account preferences and recovery |

Keep each screen’s state and intents in its ViewModel. Reusable components accept values and callbacks; they do not resolve repositories or sign transactions. Account identifiers are mapped to public market symbols for display, with abbreviated addresses as a fallback.

`OrderEntry.kt` owns order construction and display estimates; `OrderPlacement.kt` sequences leverage and entry; `OrderReview.kt` owns the review layout. `TransactionReceipt` is shared by orders and funding. Small quantities use plain decimals, and nonzero balances below half a cent display as less than one cent instead of a misleading signed zero.

See [native validation and screenshots](docs/mobile-ui-validation.md) for the verified phone flows and platform checks.

## Offline design preview

`androidApp/src/debug/.../DesignPreviewActivity.kt` renders the production composables with labeled synthetic data. It has no repositories, credentials, or transaction execution and is excluded from release builds.

After `./gradlew :androidApp:installDebug`:

```sh
adb shell am start -n xyz.mcxross.flare/.DesignPreviewActivity --es screen chart
```

Supported screens: `welcome`, `import`, `markets`, `chart`, `candles`, `portfolio`, `activity`, and `account`. Search, watchlist, chart controls, and the ticket can be exercised locally. Account creation, credential import, and order submission are disabled in this harness.

Run the actual app with the local worker to verify loading, live prices, search, watchlist, range changes, and navigation. Private-account and transaction checks additionally require an authorized testnet wallet; preview data is not evidence of a successful financial transaction.
