# Sponsorship and leverage follow-up — 2026-09-07

This follow-up extends [the initial transaction validation](transaction-validation.md). All live transactions use Aptos testnet and the dedicated test account.

## Sponsorship integration fixes

The original HTTP 401 came from an absent `GAS_STATION_API_KEY`. The Decibel node key does not authorize Gas Station requests. Created the `flare-testnet-transactions` Gas Station in the separate `flare-testnet` Geomi project and stored its server key in the worker's ignored, mode-0600 `.dev.vars`. Restart Wrangler after changing local secret values.

The station allows only the ten functions supported by Flare's worker in the configured testnet `dex_accounts_entry` module. Function rules retain simulation, allow 28–200,000 maximum gas units and 100–200 OCTA per gas unit, and impose a 10,000,000 OCTA rate limit over 3,600 seconds. This is testnet configuration, not a mainnet deployment.

Authenticated requests then exposed a second issue: Kaptos provides `RawTransactionWithData` signing bytes, whereas Geomi's Gas Station client sends the TypeScript SDK's `SimpleTransaction` envelope. The worker now verifies the original signature and transaction permissions, then changes only the envelope. The raw transaction and authenticator stay unchanged. Correlation fingerprints still identify the original Kaptos request.

The official SDK is a worker **test dependency**. Compatibility tests decode the outgoing envelope, round-trip its bytes, and verify that its signing message exactly matches the original Kaptos message. Reference implementations: [Gas Station client 2.0.3](https://unpkg.com/@aptos-labs/gas-station-client@2.0.3/build/GasStationClient.js), [Aptos SimpleTransaction](https://github.com/aptos-labs/aptos-ts-sdk/blob/main/src/transactions/instances/simpleTransaction.ts). Setup reference: [Decibel Gas Station documentation](https://docs.decibel.trade/quickstart/gas-station).

## Confirmation and retry semantics

The first successful sponsored deposit reached the chain before the fullnode used for reads observed its hash. Kaptos incorrectly converted the initial `transaction_not_found` response into an empty GraphQL error and stopped polling. The SDK fix retries that specific transient response and pending transactions, preserves unrelated REST errors, bounds the entire wait including slow requests, propagates parent cancellation, and respects `checkSuccess` for committed aborts. Flare continues to verify the returned transaction hash and preserve unresolved journal references.

An absent sponsor key now returns HTTP 503 with `gas_station_not_configured`; an upstream credential rejection returns HTTP 503 with `gas_station_credentials_rejected`. The client offers explicit self-pay only for these specific pre-submission errors or existing definitive rejection statuses. Generic 503s and transport failures remain uncertain. The worker checks existing fingerprint records first, so removing credentials cannot turn a submitted request into a safe retry.

## Live sponsorship validation

Seven transactions were independently verified as successful on the Aptos testnet fullnode: deposit, delegate, configure market, place order, cancel order, revoke delegation, and withdraw. Every receipt contains the station's external fee payer `0x4eb85b9ce0031295c922e394a1a932462e163438e8e4678f53c3b563fff69e90`. See [public transaction evidence](sponsorship-validation-evidence.json).

The lifecycle spans recovery runs. The initial deposit exposed the SDK confirmation bug; after fixing it, the trade run committed through cancellation, but a diagnostic receipt request timed out. Read-only reconciliation confirmed the cancellation before the cleanup scenario revoked the delegation and withdrew collateral. Final checks found no open orders or positions and no remaining delegation or collateral. Receipt diagnostics now run after lifecycle cleanup. No deposit or order was repeated to recover confirmation.

This follow-up verifies seven commands with sponsorship. Subaccount creation and position protection commands retain the earlier self-pay validation; they were not rerun with sponsorship. Device biometric interaction remains outside these automated and live service tests.

## Leverage while holding a position

The deployed testnet package exposes one user settings entry point. A live attempt to change an open minimum BTC position from 1× to 2× cross margin failed simulation with `ECANNOT_MODIFY_SETTINGS_WHILE_HOLDING_POSITION`. No leverage-change transaction was signed or submitted. The seven surrounding setup/cleanup transactions were independently confirmed: [public evidence](leverage-validation-evidence.json).

Flare now displays the current leverage and explains that the position must be closed before changing leverage or margin mode. Removed the nonfunctional position leverage actions. It does not close and reopen positions automatically: that would execute trades, incur fees and slippage, and alter existing protection orders. Enabling an in-place leverage change requires protocol support.

To repeat the live restriction test:

```sh
FLARE_TEST_KEY_FILE=/private/path/owner-key \
FLARE_TEST_ACTION=leverage FLARE_TEST_SELF_PAY=true \
  ./gradlew :decibel:jvmTest --tests '*LocalWorkerTransactionTest' --rerun-tasks
```

## Reproduction

The Kaptos confirmation fix is committed as `121b122f` in the companion repository `~/dev/kaptos`; the worker fix is `0fbb4c1`. Flare currently resolves Kaptos 1.0.0 from Maven Local. Build the patched SDK before running these checks; a reproducible external release requires publishing and selecting a version containing that fix.

```sh
# In ~/dev/kaptos
./gradlew -PenableSigning=false :kaptos:jvmTest --tests '*TransactionConfirmationTest' :kaptos:publishToMavenLocal

# In ../decibel-worker
npm run check

# In Flare
./gradlew :decibel:jvmTest :shared:testAndroidHostTest \
  :decibel:iosSimulatorArm64Test :shared:iosSimulatorArm64Test \
  :androidApp:assembleDebug :androidApp:lintDebug
```

Live sponsorship uses the existing opt-in `lifecycle` and `protections` scenarios without `FLARE_TEST_SELF_PAY=true`. Each sponsored command checks fingerprint recovery and confirms an external fee payer on-chain. Inspect chain/indexer state before resuming any failed scenario; never repeat a deposit or trade solely because confirmation failed.

## Final checks

- Flare: 140 passing test executions across Decibel JVM/iOS and shared Android/iOS; one opt-in live test skipped in the ordinary suite. The separate sponsored cleanup run passed.
- Android debug assembly and lint passed.
- Worker: TypeScript checking and all 90 Node/Workers test executions passed.
- Kaptos: 249 unit tests passed, four skipped; all local Maven artifacts published with signing disabled.
- Changed files passed whitespace and secret checks. Local keys are excluded from commits.
