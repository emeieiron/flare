# Flare

Flare is an open-source, native-mobile-first client for [Decibel](https://docs.decibel.trade/llms-full.txt). It uses Kotlin and Compose Multiplatform for the shared Android/iOS application, and Kaptos as the only Aptos transaction SDK.

> [!IMPORTANT]
> Flare is under active development. Test and debug builds target Decibel testnet. Do not use this repository with funds you cannot afford to lose, and do not enable mainnet without completing the release checklist in [SECURITY.md](SECURITY.md).

## Current scope

- Anonymous perpetual-market discovery, search, favorites, order books, trades, and Vico charts.
- Local Aptos owner-wallet creation/import with 12-word BIP-39 backup confirmation.
- Independent AIP-80 API trading wallets, subaccount discovery/creation, and delegation.
- Aptos-USDC deposits and withdrawals.
- Live account overview, positions, open orders, and order/trade/funding history.
- Market and limit orders, leverage, cancel, full close, and position TP/SL.
- Sponsored transactions with explicit self-pay fallback and owner-signed API-wallet APT top-up.
- Durable pending-transaction journaling and restart reconciliation.

Encrypted order submission, external wallets, cross-chain bridging, spot, vaults, rewards, referrals, bulk orders, and TWAP are intentionally outside the first release.

## Architecture

| Path | Responsibility |
| --- | --- |
| `androidApp/` | Android application entry point and packaging |
| `iosApp/` | SwiftUI shell embedding the shared static framework |
| `shared/` | Compose UI, feature MVVM, repositories, Room, DataStore, and secure platform vaults |
| `decibel/` | Compose-free Kotlin Multiplatform Decibel SDK facade and domain types |

Features expose immutable UI state and intents from Koin-provided ViewModels. ViewModels depend on repositories; repositories own REST snapshots, WebSocket invalidation/backfill, local caches, and transaction state. The `:decibel` module owns protocol models and ABI-aware commands but never imports Compose.

The credential-isolating proxy is maintained independently in the companion `decibel-worker` repository. Keeping its TypeScript, Wrangler configuration, Durable Objects, and deployment lifecycle outside Flare keeps server-side TypeScript out of this Kotlin-centric mobile repository while retaining a clear security boundary.

Transaction values enter the system as decimal strings. Live market precision converts them to chain integers only after precision, tick, lot, minimum, and overflow checks. Kaptos builds, simulates, signs, submits, and reconciles every Aptos transaction.

## Prerequisites

- JDK 21
- Android SDK 37
- Xcode 16 or newer for iOS builds
- A running deployment from the companion `decibel-worker` repository
- A Decibel/Geomi node key and Gas Station key when self-hosting that Worker

The repository pins Compose Multiplatform 1.12.0 and Vico 3.3.1. Dependency versions are deliberate; contributor setup should update Kaptos rather than lowering Flare's toolchain or UI-library baseline.

Flare bundles Inter 4.1 under the SIL Open Font License 1.1 and uses tabular numerals throughout the Material 3 typography. Apple SF Pro files are not redistributed.

## Kaptos dependency

Public builds resolve `xyz.mcxross.kaptos:kaptos:1.0.0` from Maven Central. Contributors changing Kaptos can consume a Maven Local publication without editing tracked files:

```sh
FLARE_MAVEN_LOCAL=true ./gradlew :decibel:jvmTest
```

For unpublished dependency changes, publish FastKrypto first from its Kotlin Gradle project:

```sh
rustup run 1.92 ./gradlew -PenableSigning=false publishToMavenLocal
```

Then publish Kaptos from the Kaptos repository:

```sh
./gradlew -PenableSigning=false publishToMavenLocal
```

Kaptos resolves the pinned FastKrypto `0.2.0` publication from Maven Local or Maven Central. Flare consumes a locally published Kaptos artifact when `FLARE_MAVEN_LOCAL=true`, or when the non-committed Gradle property `useMavenLocal=true` is set. Local repositories are opt-in in Flare so a release build cannot be silently shadowed by an artifact in `~/.m2`.

## Run locally

Start the separately checked-out `decibel-worker` first:

```sh
cd ../decibel-worker
npm ci
cp .dev.vars.example .dev.vars
npm run dev
```

Set the three values in its `.dev.vars`; that file is ignored by the Worker repository. Its README is the source of truth for deployment, Durable Object migrations, route policy, and local WebSocket diagnostics.

Build Android:

```sh
./gradlew :androidApp:assembleDebug
```

Android Studio and command-line debug builds use `http://10.0.2.2:8787` by default. On the standard Android Emulator, `10.0.2.2` routes to the host machine where the local Worker is running; `127.0.0.1` would address the emulator itself. A custom endpoint can be injected with `-PflareWorkerUrl=...`.

For a USB-connected physical device, forward the device loopback port before launching and build with the loopback override:

```sh
adb reverse tcp:8787 tcp:8787
./gradlew -PflareWorkerUrl=http://127.0.0.1:8787 :androidApp:installDebug
```

Inject a deployed Worker URL for a packaged Android build:

```sh
./gradlew -PflareWorkerUrl=https://flare-worker.example.com :androidApp:assembleRelease
```

For local Kaptos development:

```sh
FLARE_MAVEN_LOCAL=true ./gradlew :androidApp:assembleDebug
```

Open `iosApp/iosApp.xcodeproj` in Xcode to run the iOS application. The default application runtime connects to `http://127.0.0.1:8787`. Set the `FLARE_WORKER_URL` Xcode build setting to the deployed HTTPS URL for packaged builds; do not commit credentials or environment-specific secrets.

## Verification

```sh
./gradlew :decibel:jvmTest :shared:testAndroidHostTest :androidApp:lintDebug :androidApp:assembleDebug
./gradlew :decibel:iosSimulatorArm64Test :shared:iosSimulatorArm64Test :shared:compileKotlinIosSimulatorArm64
```

Run `:shared:iosSimulatorArm64Test` on an Apple Silicon macOS host. Testnet end-to-end transactions require valid Worker credentials and are intentionally not part of untrusted pull-request CI.

Changes spanning the proxy boundary must also pass `npm run check` in the separate `decibel-worker` repository.

## Security model

The application contains no Decibel node or Gas Station credential. The Worker exposes fixed allowlisted routes, consumes single-use authentication challenges, scopes short-lived sessions to a network/wallet/subaccount/role, and validates sponsored BCS transactions before adding a server credential. Wallet secrets remain in platform-backed secure storage and are never written to Room or DataStore.

Read [THREAT_MODEL.md](THREAT_MODEL.md) before changing authentication, signing, sponsorship, storage, or transaction reconciliation. Report vulnerabilities through the process in [SECURITY.md](SECURITY.md).

Release evidence is tracked with [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md); mainnet remains a manual, value-capped gate.

## Contributing and license

See [CONTRIBUTING.md](CONTRIBUTING.md). Flare is licensed under the [Apache License 2.0](LICENSE). Third-party dependencies and bundled assets retain their respective licenses; see [NOTICE](NOTICE).
