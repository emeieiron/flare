# Transaction validation — 2026-09-06

For subsequent sponsorship fixes and the live open-position leverage test, see [the September 7 follow-up](sponsorship-and-leverage-validation.md).

The self-paid testnet account and trading lifecycle passed through Flare's Decibel service, Kaptos, and the local Worker. Successful Gas Station submission is **not verified**: the local sponsorship endpoint returned HTTP 401. This is transaction-layer evidence, not a certification of every mobile UI or secure-storage interaction.

## Environment

- Local Worker: `http://127.0.0.1:8787`, companion repository `../decibel-worker`.
- Network: Aptos testnet, independently checked chain ID `2` before writes.
- Decibel package: `0xe7da2794b1d8af76532ed95f38bfdf1136abfd8ea3a240189971988a83101b7f`.
- Testnet USDC metadata: `0x5428acf5c112826d0c74ae1cd2de9030f53d1d01235e6c2621d967bf914ee1c8`.
- Subaccount: `0x295ab842fd26a2cb71e332bca17cfc596d235acab653522da36ee4eb0e3fe90f`.
- Independent API wallet: `0x22cb34e89cef550a8b6c128d2114bd589b0c8ced5503274dfc44b59a8e533e6d`.
- Each funded scenario used 10 test USDC and minimum-size BTC orders (`0.00002 BTC`). The harness caps transaction gas units at 50,000; mint and APT top-up also check the simulated gas price.

The owner and API keys were loaded or generated outside the repository. The harness reads an AIP-80 key from `FLARE_TEST_KEY_FILE`; it does not accept a key on the command line or print it. It retains the API key in a mode-0600 sibling file so interrupted tests can resume without generating another wallet.

## Live coverage

| Path | Evidence |
| --- | --- |
| Owner authentication | Challenge signed with Kaptos; Worker session issued |
| Subaccount creation | Committed; discovered through Decibel REST |
| Collateral funding | Testnet USDC minted; 10 USDC deposited and observed in account equity |
| API-wallet setup | Independent key generated, perpetual permission delegated, owner-funded APT top-up committed |
| API authentication | Perpetual delegation verified; API session issued |
| Market configuration | Cross margin and 1× leverage committed before opening a position |
| Limit placement | Minimum-size post-only BTC order observed resting in open orders |
| Cancellation | Matching on-chain `CANCELLED` event with zero remaining size; later REST snapshots show no open orders |
| Market entry | IOC price produced by Flare's order validator; resulting position observed through REST |
| Full close | Opposite-side reduce-only order; zero position observed through REST |
| Position TP/SL | Both triggers placed using mark price, observed on the position, and individually cancelled |
| Delegation revocation | Committed, disappeared from REST, and fresh API authentication returned HTTP 403 |
| Withdrawal | Remaining collateral returned to the owner; final subaccount equity below one USDC base unit |
| Gas sponsorship rejection | HTTP 401 produced an explicit self-pay estimate and `definitelyNotSubmitted=true` |

All 24 direct transaction hashes were independently fetched from the fullnode through the Worker and checked for `success=true`. Public hashes, versions, and order events are in [transaction-validation-evidence.json](transaction-validation-evidence.json). The final state has no test orders, no open position, and no active API delegation. The subaccount and API wallet still exist; the API wallet retains unused test APT for subsequent tests.

## Defects found and fixed

1. **Worker SHA3 incompatibility.** Node's `createHash("sha3-256")` threw `Digest method not supported` in Workers, breaking authenticated sessions and sponsored-transaction verification. The Worker now uses `@noble/hashes` for SHA3. Its tests run in both Node and Workers.
2. **Missing Worker transaction prerequisites.** Single-module ABI lookup, gas estimation, and wallet balance routes were absent from the read allowlist. Added narrowly matched routes and rejection tests.
3. **Wrong testnet collateral metadata.** The previous address came from a stale documentation example. Mint events and `SHA3-256(package || "USDC" || 0xfe)` identify the configured package's actual USDC object. Flare and the Worker now agree, with a derivation regression test.
4. **Combined delegation indexing.** `delegate_all_trading_to_for_subaccount` emitted perp, vault-token, and spot grants, but REST exposed the last spot grant. Flare now calls `delegate_perp_trading_to_for_subaccount`; the Worker still requires all-perpetual-market permission.
5. **Unverified simulation/confirmation shapes.** Empty simulation results allowed signing, and a confirmation for another hash could be accepted. Execution now requires one simulation result and a matching user transaction confirmation. The same guards protect APT top-up and reconciliation. Uncertain post-submission failures retain the prepared reference.
6. **API-wallet setup owned by the UI.** The account repository now coordinates key reuse, owner authorization, delegation checks, pending-transaction checks, and bounded connection retries. Repeated setup does not replace the key or repeat a known delegation. Only reads/connections are retried.

## Protocol behavior confirmed

- Transaction commitment and REST indexing are separate observations. An immediate REST read after cancellation returned the old order even though the chain emitted `CANCELLED`. Verification must poll the indexed condition instead of treating a fixed two-second delay as sufficient.
- TP/SL validates against **mark price**, not the order-book bid. A deliberately unsuitable trigger failed simulation with `EINVALID_TRIGGER_PRICE`; no transaction was submitted.
- The deployed settings entry function rejects configuration while holding a position, including an unchanged setting, with `ECANNOT_MODIFY_SETTINGS_WHILE_HOLDING_POSITION`. The current portfolio leverage action still targets this function; changing leverage on an open position remains unsupported and needs a product-level decision.

Protocol reference: [Decibel documentation](https://docs.decibel.trade/llms-full.txt). Deployed ABIs and committed events were used when the example configuration disagreed with the network.

## Repeatable verification

Final regression run passed: 47 Decibel JVM tests, 16 shared Android host tests, 47 Decibel iOS simulator tests, and 22 shared iOS simulator tests. The opt-in live test was skipped in this offline run; its live results are recorded above. Android debug assembly and lint passed. Worker typechecking and 76 test executions passed (38 cases in each of Node and Workers).

Offline/native regressions and Android checks:

```sh
./gradlew :decibel:jvmTest :shared:testAndroidHostTest \
  :decibel:iosSimulatorArm64Test :shared:iosSimulatorArm64Test \
  :androidApp:assembleDebug :androidApp:lintDebug
```

Worker checks, from `../decibel-worker`:

```sh
npm run check
```

The live JVM test is skipped unless `FLARE_TEST_KEY_FILE` is set. Put that file in a dedicated directory, keep its permissions at 0600, and use a funded **testnet-only** account. Inspect first:

```sh
FLARE_TEST_KEY_FILE=/private/path/owner-key \
  ./gradlew :decibel:jvmTest --tests '*LocalWorkerTransactionTest' --rerun-tasks
```

Explicit write scenarios:

```sh
FLARE_TEST_KEY_FILE=/private/path/owner-key \
FLARE_TEST_ACTION=create FLARE_TEST_SELF_PAY=true \
  ./gradlew :decibel:jvmTest --tests '*LocalWorkerTransactionTest' --rerun-tasks

FLARE_TEST_KEY_FILE=/private/path/owner-key \
FLARE_TEST_ACTION=lifecycle FLARE_TEST_SELF_PAY=true \
  ./gradlew :decibel:jvmTest --tests '*LocalWorkerTransactionTest' --rerun-tasks

FLARE_TEST_KEY_FILE=/private/path/owner-key \
FLARE_TEST_ACTION=protections FLARE_TEST_SELF_PAY=true \
  ./gradlew :decibel:jvmTest --tests '*LocalWorkerTransactionTest' --rerun-tasks
```

`create` refuses to duplicate an existing owned subaccount. `lifecycle` tests funding, delegation, post-only placement/cancellation, revocation and withdrawal. `protections` tests market entry, TP/SL, full close and cleanup. Without `FLARE_TEST_SELF_PAY=true`, Decibel commands request sponsorship; custom mint/top-up helpers use test APT.

Before resuming a failed live scenario, inspect the saved `flare-testnet-transactions.log` and current chain/indexer state. The test does not automatically resubmit a failed write or unwind an uncertain transaction. Recovery actions (`trade`, `positions`, `resume-protections`) are explicit and assume the corresponding partially completed test state. Never rerun a whole scenario blindly after a timeout.

## Remaining validation

- Successful Gas Station submission and sponsored fingerprint recovery require valid sponsorship credentials; local HTTP 401 remains unresolved.
- The harness exercises Kaptos and the real Worker, but does not exercise Android Keystore/iOS Keychain user-presence prompts or the Room journal under mobile process death.
- Device-level restart, concurrent submission/reconciliation, and network-loss tests remain necessary before production readiness.
- Onboarding's owner setup currently defaults to sponsorship. The live self-pay results establish the transaction service's behavior, not an end-to-end self-pay onboarding UI flow.
- Mainnet was not used. UI styling and layout were not refreshed.
