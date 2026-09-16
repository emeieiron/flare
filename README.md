# Flare

**Flare** is an open-source, native mobile client for decentralized perpetual and spot trading on [Decibel](https://decibel.trade), powered by Aptos. 

Built with **Kotlin Multiplatform**, Flare shares its core business and transaction logic across **Android and iOS**, using [**Kaptos**](https://github.com/mcxross/kaptos) as its core Aptos transaction and blockchain engine.

> [!IMPORTANT]
> Flare is under active development on Decibel testnet. Do not use this repository with mainnet funds without completing the audit checklist in [SECURITY.md](SECURITY.md).

---

## Features

- **Native Mobile Experience**: Native UI on Android (Jetpack Compose) and iOS (SwiftUI) powered by a shared Kotlin Multiplatform business logic core.
- **Perpetuals & Spot Trading**:
  - **Perpetuals**: Leverage configuration, margin modes, position management with attached Take-Profit/Stop-Loss (TP/SL), and funding history.
  - **Spot Markets**: Direct spot pair trading, real-time asset balances, and portfolio holdings.
  - **Order Execution**: Market and limit orders across both perps (`dex_accounts_perp_entry`) and spot (`dex_accounts_spot_entry`), real-time L2 order books, live trade feeds, and interactive Vico charts.
- **Self-Custodial Account Management**:
  - BIP-39 mnemonic generation and seed phrase recovery.
  - Seamless import for recovery phrases, raw Ed25519 private keys, and AIP-80 standard keys.
  - Subaccount creation, discovery, and verified API trading wallet delegation.
- **Gasless Trading**: Built-in sponsored transactions via Gas Station with automatic self-pay fallback.
- **Offline Resilience**: Durable pending-transaction journaling with automatic restart reconciliation.

---

## Tech Stack

| Layer | Technology |
| :--- | :--- |
| **UI & Presentation** | Jetpack Compose (Android), SwiftUI (iOS), Vico Charts, MVI/MVVM |
| **Platforms** | Android (Jetpack), iOS (SwiftUI shell embedding shared framework) |
| **Blockchain & Web3** | [Kaptos](https://github.com/mcxross/kaptos) (Kotlin Multiplatform SDK for Aptos) |
| **State & DI** | Koin, Kotlinx Coroutines & Flow |
| **Local Storage** | Room, DataStore, Platform Secure Enclaves (Keychain & EncryptedSharedPreferences) |
| **Networking** | Ktor HTTP/WebSockets, Decibel Worker Proxy |

---

## Architecture

```
flare/
├── androidApp/  # Android entry point, manifests, and packaging
├── iosApp/      # SwiftUI wrapper embedding the shared Kotlin framework
├── shared/      # Shared ViewModels, domain logic, secure storage, and repositories
└── decibel/     # Protocol models, Move ABI serialization, and market types
```

- **`:shared`** houses all core business logic, domain models, and state management.
- **`:decibel`** provides a headless Kotlin Multiplatform client for Decibel protocol types.
- **Kaptos** handles all on-chain interactions: building payloads, gas simulation, transaction signing, submission, and confirmation.

---

## Getting Started

### Prerequisites

- JDK 21+
- Android Studio Ladybug+ / Android SDK 37
- Xcode 16+ (for iOS)
- Running instance of the companion `decibel-worker`

### 1. Start the Decibel Worker Proxy

```sh
git clone https://github.com/emeieiron/decibel-worker
cd decibel-worker
npm ci && cp .dev.vars.example .dev.vars
npm run dev
```

### 2. Run Android

```sh
./gradlew :androidApp:installDebug
```

*(By default, the debug build connects to the local worker proxy at `http://10.0.2.2:8787`).*

### 3. Run iOS

Open `iosApp/iosApp.xcodeproj` in Xcode and select your target simulator or physical device.

---

## Security

Private keys never leave the device's secure hardware (iOS Keychain and Android EncryptedSharedPreferences). Network communications pass through a stateless, credential-isolating proxy worker to keep sensitive node and gas credentials off client devices.

For threat analysis and security reports, see [SECURITY.md](SECURITY.md) and [THREAT_MODEL.md](THREAT_MODEL.md).

---

## Contributing & License

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for local development workflows and Kaptos snapshot testing.

Flare is licensed under the [Apache License 2.0](LICENSE).
