# Mobile refresh validation — 2026-09-09

The redesigned production screens were exercised on the Pixel 9 Pro XL Android 16 emulator against the local worker and Aptos testnet. Transactions were entered and authorized through the native app. The live worker's upstream independently returned `chain_id: 2` during cleanup. No mainnet assets were involved.

The companion worker runs from `~/dev/personal/decibel-worker` on port 8787. Android reaches it at `http://10.0.2.2:8787`. This refresh did not change the worker repository, create accounts through a browser, or deploy a service.

## Verified experience

- The supplied recovery phrase and owner private key each imported through the same native form and discovered the same existing Decibel subaccount. Credential format recognition happens locally. Owner/API permission ambiguity is handled by a contextual API-wallet choice in that form.
- The app created and delegated an API wallet on the phone, then connected private account data. Native vault authorization remained enabled. Credential contents and the device passcode are absent from the repository and screenshots.
- Live markets loaded, including search by asset name, search clearing, watchlist changes, and navigation among Markets, Portfolio, Activity, and Account. Returning from market details and account setup preserved the expected navigation state.
- Line and candle charts were inspected with live data. Period/style changes reset the viewport. Candle panning keeps visible wicks within the vertical range. The default line view fits the selected period.
- Order entry uses shared custom amount surfaces, a compact expandable leverage scale, and paired optional take-profit/stop-loss fields. Minimum size and estimated margin remain visible.
- Review displays direction, size, leverage, margin mode, entry, notional value, estimated margin, slippage, and distinct estimated profit/loss at the entered exit prices. Invalid exit direction, precision, tick alignment, and minimum size are rejected before review and submission.
- A native 2× selection reduced the displayed estimated margin from about $1.58 to $0.79 for the same 0.00002 BTC size. The resulting position reported `Long · 2×`. While it remained open, the selector showed the protocol restriction and prevented changing leverage.
- Completed orders and funding transfers end on a receipt. Done clears completed order inputs; reopening the ticket did not retain the submitted size or exit prices.

## Native testnet transactions

These are abbreviated hashes displayed by the phone, not reconstructed full hashes. Account state and Activity were checked before proceeding to subsequent actions.

| Action | Native result |
| --- | --- |
| Create/delegate trading wallet | Connected successfully using the existing owner and subaccount |
| Deposit 10 test USDC, once | Committed, `0x0790a972…d4e77b`; portfolio increased from $0.00 to $10.00 |
| Buy limit, 0.00002 BTC at 70,000 | Placed, `0x1573fb49…b59333`; appeared in Open orders |
| Cancel that limit order | Committed, `0x0c6bcd5a…1ef355`; no open orders |
| Buy market, 0.00002 BTC | Placed, `0x5694357e…efcfea`; exact position appeared |
| Close that position | Position disappeared; opening and closing fills appeared in Trades |
| Buy with TP 82,000 and SL 75,000 attached | Placed, `0x08c24938…4a1158`; both exit orders appeared in live Activity |
| Close protected position | No positions or remaining exit orders |
| Apply 2× and buy 0.00002 BTC with the same exits | Placed, `0xe52ae4fb…d464c8`; portfolio reported 2× and Activity showed both exits |
| Close the 2× position | No positions or remaining exit orders |
| Withdraw remaining 9.992382 test USDC | Committed, `0xa38b51f9…a859b1`; returned to owner wallet; portfolio and available balance both $0.00 |

Final cleanup left no open positions or orders and no trading collateral. The API wallet remains available in the native vault for continued app testing. The BTC testnet market's last applied leverage setting is 2×; new entries apply the leverage displayed in their review.

Attached entry exits are fixed-size child orders in Activity. They do not necessarily populate the position-wide TP/SL fields returned by Decibel. Verification used the actual Stop Limit and Take Profit Limit rows, each showing 0.00002 BTC and the expected price. Closing the position removed both exits.

The TP/SL verification covers attachment and cleanup, not execution after a trigger. Exit orders use the entered price as both trigger and limit. Review explains that a triggered limit order can remain unfilled. Profit/loss estimates assume a full fill at the shown entry and exit prices, before fees and funding; they are not guaranteed returns or maximum-loss limits.

## Native sponsorship correction

The first native delegation attempt was definitively rejected by Gas Station before submission. The app had inherited Kaptos' generic maximum of 2,000,000 gas units and 20-second expiration, while the previously verified Decibel lifecycle used 50,000 gas units and 60 seconds. The native runtime now uses those bounded transaction defaults. Delegation and the subsequent native transactions then succeeded. No sponsor secret or policy was changed for this refresh.

`PhoneTransactionConfigIosTest` builds an unsigned sponsored transaction with the actual runtime configuration and checks its encoded gas ceiling and expiration. The existing encrypted vault, signature checks, exact decimal conversion, simulation, owner-only funding rules, and pending-transaction journal remain in use.

Leverage is a separate confirmed configuration step before entry. `OrderPlacementTest` verifies that failed or unresolved configuration never submits the entry, while an existing position keeps its leverage and skips reconfiguration. Pending journal entries must reconcile before another order can proceed. Self-pay applies only to the failed stage the user explicitly approves.

## Platform checks

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| Decibel JVM | 49 | 1 opt-in live harness |
| Decibel iOS simulator | 49 | 0 |
| Shared Android host | 38 | 0 |
| Shared iOS simulator | 44 | 0 |
| Total | 180 | 1 |

Android debug assembly, installation, and lint passed. Lint reports 18 warnings and no errors. Shared iOS compilation and simulator tests passed. The native interaction and screenshot checks here cover Android; iOS device UI interaction was not performed.

```sh
./gradlew :decibel:jvmTest :decibel:iosSimulatorArm64Test \
  :shared:testAndroidHostTest :shared:iosSimulatorArm64Test \
  :shared:compileKotlinIosSimulatorArm64 \
  :androidApp:lintDebug :androidApp:assembleDebug
```

The app continues to depend on the patched Kaptos 1.0.0 Maven Local artifacts described in [the earlier SDK validation](sponsorship-and-leverage-validation.md#reproduction).

## Screenshots

The trading screenshots use actual testnet data. Welcome/import captures use the explicitly labeled debug design harness without importing or exposing credentials.

| Screen | Capture |
| --- | --- |
| Welcome and import | [Welcome](ui-refresh/welcome.png), [unified import](ui-refresh/import.png) |
| Markets and account | [Live markets](ui-refresh/markets.png), [account](ui-refresh/account.png) |
| Line chart | [Live default viewport](ui-refresh/line-chart.png) |
| Order entry | [Custom amount and exit controls](ui-refresh/order-entry.png) |
| Leverage | [Expanded scale and presets](ui-refresh/leverage-picker.png) |
| Review | [Leverage, margin, estimated profit and loss](ui-refresh/order-review.png) |
| Receipt | [Completed order](ui-refresh/order-receipt.png) |
| Attached exits | [Live TP/SL orders](ui-refresh/attached-exits.png) |
| Position | [Confirmed 2× position](ui-refresh/position-2x.png) |
| Candles | [Initial viewport](ui-refresh/candles.png), [after panning](ui-refresh/candles-panned.png) |
| Cleanup | [Withdrawal receipt](ui-refresh/withdrawal.png), [zero balance](ui-refresh/portfolio-clean.png), [no open orders](ui-refresh/orders-clean.png) |
