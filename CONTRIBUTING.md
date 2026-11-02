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

## Change workflow

- Add focused tests for protocol JSON, fixed-point math, BCS payloads, authentication policy, or UI-state transitions as appropriate.
- Run Android, iOS compilation, and Decibel tests before opening a pull request. Key derivation and signing call FastKrypto natively, whose Android artifact has device ABIs only, so those tests run in `:hyperliquid:jvmTest` or the iOS tests, never Android host tests. Changes spanning the proxy contract must also pass the separate mainnet-only `decibel-worker` repository's checks.
- Explain any security-boundary change in the pull request and update [THREAT_MODEL.md](THREAT_MODEL.md) when an assumption changes.
- Keep unrelated formatting and generated changes out of the patch.
- Versions are independent per exchange in [version.properties](version.properties): `FLARE_DECIBEL_VERSION` and `FLARE_HYPERLIQUID_VERSION`. Each value is shared by that exchange's Android flavor and iOS scheme. Publishing one exchange does not bump or publish the other.

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

## Android releases

Release each exchange independently using [Release Android](.github/workflows/release-android.yml). A tag selects the exchange: `decibel-vX.Y.Z` must match `FLARE_DECIBEL_VERSION`, and `hyperliquid-vX.Y.Z` must match `FLARE_HYPERLIQUID_VERSION`. The workflow tests shared modules and the selected exchange, then signs and publishes only that exchange's APK. The apps retain separate package IDs, `xyz.mcxross.flare.decibel` and `xyz.mcxross.flare.hyperliquid`, so their releases and installed updates remain independent.

1. Increase only the selected exchange's build number in `version.properties` for every published build, including replacements under the same public version. `FLARE_DECIBEL_BUILD` and `FLARE_HYPERLIQUID_BUILD` set Android `versionCode` and iOS `CURRENT_PROJECT_VERSION`; each must be a decimal integer from 1 to 2100000000 without leading zeros and greater than every build previously distributed for that exchange. Change its `*_VERSION` only when advancing the public version. Update release documentation and merge the verified change to `main`.
2. Tag the verified commit on `main` with the matching exchange and version, then push that tag. For Decibel 0.2.0, the tag is `decibel-v0.2.0`; a Decibel-only release leaves Hyperliquid's version unchanged.
3. Review and approve the run in the `android-release` environment. Its deployment tag policy must allow `decibel-v*` and `hyperliquid-v*`. For Decibel, set `FLARE_DECIBEL_WORKER_URL` to the intended HTTPS mainnet Worker before approval and verify the release's network configuration.

Decibel stays at public version 0.2.0 until stable. The current build is 203, which can update installed 0.2.0/builds 200 and 202 and 0.2.1/build 201 APKs without uninstalling or clearing app data. Keep the package ID and signing key unchanged, and increase the build number for every future replacement. The versioned APK name includes the build, for example `flare-decibel-0.2.0-build203.apk`; the stable `flare-decibel-android.apk` alias points to the same APK. A public version or tag alone does not distinguish replacement downloads; record the build number, source commit, and checksums in the release notes.

Use exchange-specific release links in download documentation. GitHub's global `releases/latest` can point to either exchange and does not identify the newest APK for both. The historical `v0.1.0` Hyperliquid APK remains a testnet preview; it does not establish a mainnet release version.

## Protocol changes

Treat the [Decibel full documentation](https://docs.decibel.trade/llms-full.txt) and published Move entry functions as the source of truth. Capture new response shapes as JSON fixtures and new transaction calls as golden ABI/BCS tests. Large Aptos integers and IDs must remain strings or explicit integer wrappers.

## Security reports

Do not open public issues for vulnerabilities involving key material, authorization, Worker credentials, sponsorship, replay, or transaction integrity.
