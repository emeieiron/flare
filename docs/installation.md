# Installation

[Back to Flare](../README.md)

**Android:** each exchange is its own app, with independent versions and releases.

- **Decibel 0.2.0 (build 203) — Aptos mainnet:** download [`flare-decibel-android.apk`](https://github.com/emeieiron/flare/releases/download/decibel-v0.2.0/flare-decibel-android.apk) from the [Decibel 0.2.0 release](https://github.com/emeieiron/flare/releases/tag/decibel-v0.2.0). It trades real funds. Deposit USDC on Aptos and keep a little APT for the fees Flare's gas station cannot cover. The release notes explain how to verify the checksum, signing certificate and build provenance.
- **Hyperliquid v0.1.0 — historical testnet preview:** download [`flare-hyperliquid-android.apk`](https://github.com/emeieiron/flare/releases/download/v0.1.0/flare-hyperliquid-android.apk) from the [historical v0.1.0 release](https://github.com/emeieiron/flare/releases/tag/v0.1.0). This APK uses testnet and is not a stable mainnet release. The Decibel release does not update Hyperliquid; [build the current source](../README.md#build) to use its mainnet implementation.
- **Already testing Decibel?** Install the current 0.2.0 APK (build 203) as a normal update. Its build number is higher than the earlier mainnet 0.2.0 (builds 200 and 202) and 0.2.1 (build 201) APKs, so those can update in place while retaining your wallet, device trading key, and local trading state. Updating from the published testnet v0.1.0 APK retains your wallet and device trading key, clears local testnet trading state on the first mainnet launch, and runs account setup again for mainnet. If you use an older legacy Flare app with a different package ID, install Decibel alongside it and restore your wallet from its recovery phrase or re-import your key; wallet data does not transfer automatically between the two apps.

Decibel remains at public version 0.2.0 until stable. Replacement downloads can keep that public version while increasing the build number; check the release notes and checksums to identify the current build.

Decibel shows network fees in one row before order confirmation. Flare-covered fees fall back automatically to APT payment within the same confirmation. When this device holds the owner key, the owner wallet can pay directly; devices holding only a trading key use that key's APT. The fee row shows an estimate when available or a maximum when the order requires a preceding leverage change.

**iOS:** [build it yourself](../README.md#build) for now. App Store and Google Play listings will follow.

