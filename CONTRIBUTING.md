# Contributing to Flare

Flare handles private keys and leveraged trading. A small patch can have financial consequences, so correctness and explicit failure behavior take priority over convenience.

## Development principles

1. Keep protocol and chain code out of ViewModels and composables.
2. Use Kaptos for every Aptos payload, simulation, signature, submission, balance query, and transaction wait.
3. Keep `:decibel` and `:hyperliquid` Compose-free and multiplatform.
4. Accept transaction prices, quantities, and collateral amounts as decimal strings. Do not introduce `Double` into transaction construction.
5. Simulate before authorization, journal the signed hash before submission, and preserve uncertain outcomes for reconciliation.
6. Never log secrets, signatures, complete transaction payloads, session tokens, or private account responses.
7. Preserve Flare's pinned dependency baseline. When integration fails because Kaptos lacks an API, update Kaptos rather than lowering Flare's Compose, Kotlin, Android, or Vico versions.

## Modules

- `:shared` is exchange-neutral: screens, ViewModels, storage, and the venue interfaces (`Venue`, `VenueDescriptor`, the repositories, `OrderRules`, `CredentialParser`). It must not depend on any exchange SDK (`:decibel`, `:hyperliquid`, Kaptos, FastKrypto).
- Each exchange lives in a `:venue-*` module that implements those interfaces with its SDK and binds them in `Venue.module()`. Its words, limits and links reach the screens through the `VenueDescriptor`.
- Each app build links one venue: an Android product flavor (`decibel`, `hyperliquid`) and, on iOS, an umbrella framework (`:ios-decibel`, `:ios-hyperliquid`) built by its scheme (`Flare Decibel`, `Flare Hyperliquid`).
- Features one exchange lacks are gated by `VenueCapabilities`, never by checking which exchange it is.

## Local setup

Follow the prerequisites and setup steps in [README.md](README.md).

### Developing with Local Kaptos Builds

For unpublished dependency work, publish FastKrypto and then Kaptos to Maven Local:

```sh
# In the FastKrypto repository
rustup run 1.92 ./gradlew -PenableSigning=false publishToMavenLocal

# In the Kaptos repository
./gradlew -PenableSigning=false publishToMavenLocal
```

Then build Flare with local dependency resolution enabled:

```sh
FLARE_MAVEN_LOCAL=true ./gradlew :decibel:jvmTest
```

Do not commit absolute paths or use checkout paths as Gradle dependencies.

### Live Hyperliquid testnet tests

`hyperliquid/src/jvmTest/.../HyperliquidTestnetTest.kt` runs against Hyperliquid testnet only, and
every test in it is skipped unless enabled, so a normal `jvmTest` run never touches the network:

| Environment variable | What runs |
|---|---|
| `FLARE_HYPERLIQUID_LIVE=1` | Read-only REST and WebSocket checks (markets, book, candles, streams, the 60 s ping), plus signing proofs with a fresh unfunded key (approving and revoking an agent, revoking a builder fee, withdrawals and transfers, vault transfers and TWAPs as the agent signs them, actions for a sub-account, a cancel, and every order shape Flare sends): Hyperliquid rejects the actions and names the address it recovered from each signature, which is the signer's only if it read the payload as signed. |
| `FLARE_HYPERLIQUID_TEST_KEY_FILE=<path>` | The funded lifecycle, signed by the 0x hex private key in that file: leverage, a far-from-market order placed and canceled by the owner and by an approved agent named `flare-test` (then revoked with the zero address under its name, as Hyperliquid's app does), and a builder fee approved and revoked. The key is never printed; keep the file outside the repository. |
| `FLARE_HYPERLIQUID_TEST_VAULT=1` | With the key file: also deposits 5 USDC into HLP. HLP locks a deposit for 4 days, and the test does not withdraw it. |

```sh
FLARE_HYPERLIQUID_LIVE=1 ./gradlew :hyperliquid:jvmTest --tests '*Testnet*'
FLARE_HYPERLIQUID_TEST_KEY_FILE=$HOME/.flare/hl-testnet.key ./gradlew :hyperliquid:jvmTest --tests '*Testnet*'
```

The testnet faucet only serves addresses that have deposited on Hyperliquid mainnet, so a funded
testnet account needs a mainnet deposit from the same address first.

## Change workflow

- Add focused tests for protocol JSON, fixed-point math, BCS payloads, authentication policy, or UI-state transitions as appropriate.
- Run Android, iOS compilation, and Decibel tests before opening a pull request. Key derivation and signing call FastKrypto natively, whose Android artifact has device ABIs only, so those tests run in `:hyperliquid:jvmTest` or the iOS tests, never Android host tests. Changes spanning the proxy contract must also pass the separate `decibel-worker` repository's checks.
- Explain any security-boundary change in the pull request and update [THREAT_MODEL.md](THREAT_MODEL.md) when an assumption changes.
- Keep unrelated formatting and generated changes out of the patch.
- The app version is `FLARE_VERSION` in [version.properties](version.properties); both Android flavors and both iOS schemes read it, and a release tag must be `v` plus that value.

Local gate:

```sh
./gradlew :decibel:jvmTest :hyperliquid:jvmTest \
  :shared:testAndroidHostTest :venue-decibel:testAndroidHostTest :venue-hyperliquid:testAndroidHostTest \
  :androidApp:assembleDecibelDebug :androidApp:assembleHyperliquidDebug \
  :androidApp:lintDecibelDebug :androidApp:lintHyperliquidDebug \
  :shared:compileTestKotlinIosSimulatorArm64 :venue-decibel:compileTestKotlinIosSimulatorArm64 \
  :venue-hyperliquid:compileTestKotlinIosSimulatorArm64 \
  :ios-decibel:compileKotlinIosSimulatorArm64 :ios-hyperliquid:compileKotlinIosSimulatorArm64
```

On macOS, also run the iOS tests and build the app:

```sh
./gradlew :shared:iosSimulatorArm64Test :venue-decibel:iosSimulatorArm64Test \
  :venue-hyperliquid:iosSimulatorArm64Test
xcodebuild -project iosApp/iosApp.xcodeproj -scheme "Flare Decibel" -configuration Debug-Decibel \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build
xcodebuild -project iosApp/iosApp.xcodeproj -scheme "Flare Hyperliquid" -configuration Debug-Hyperliquid \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build
```

## Protocol changes

Treat the [Decibel full documentation](https://docs.decibel.trade/llms-full.txt) and published Move entry functions as the source of truth. Capture new response shapes as JSON fixtures and new transaction calls as golden ABI/BCS tests. Large Aptos integers and IDs must remain strings or explicit integer wrappers.

## Security reports

Do not open public issues for vulnerabilities involving key material, authorization, Worker credentials, sponsorship, replay, or transaction integrity.
