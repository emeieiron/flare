---
name: flare-architecture
description: How to build a polished, production-ready Web3 mobile app using the Flare toolkit as a reference architecture. Use it whenever you scaffold or extend a wallet or other onchain app for Android or iOS with Kotlin Multiplatform, on Aptos, Sui or any chain, even if Flare isn't mentioned. Covers module layering, MVVM, onchain SDKs (Kaptos, Ksui, kotlinx-serialization-bcs), transaction safety, biometric signing, storage versioning, cold launch and onboarding.
---

# Building Web3 mobile apps with Flare architecture

Flare is a composable Web3 mobile toolkit combining reusable architecture, customizable design primitives, and complete functional flows for onchain interactions. While the Flare codebase implements a mobile trading app on Aptos & Decibel, **its architecture and patterns serve as a reference blueprint for building polished, production-ready mobile apps on any blockchain**.

These are defaults the user can override, except the rules for money, transactions and secrets. For UI, use `flare-design-system`.

## 1. Scaffolding & build system

- **Build system:** **Gradle** with the Kotlin DSL and a version catalog (`gradle/libs.versions.toml`).
- **Project templates:** Generate new projects with the **Kotlin Multiplatform Wizard** (https://kmp.jetbrains.com).
- **Non-shared UI:** Native UIs (Jetpack Compose on Android, SwiftUI on iOS) sharing Kotlin business logic, rather than a shared UI such as Compose Multiplatform.

## 2. Module layering & protocol modularity

Keep dependencies pointing strictly downward:

```
[Platform apps: androidApp (Jetpack Compose) / iosApp (SwiftUI)]
  Native UI and platform integration (Keystore, Keychain, biometrics)
      │
      ▼
[:shared]
  Application logic: repositories, ViewModels, local storage, vault contracts
      │
      ▼
[Dedicated protocol modules (e.g. :decibel)]
  Headless KMP SDKs: API clients, models, transaction payloads
      │
      ▼
[Chain SDKs (Kaptos / Ksui / kotlinx-serialization-bcs)]
  RPC, simulation, signing, BCS serialization
```

- **Protocol modularity:** *Distinct features like protocol support belong in dedicated modules*, free of UI and platform code, so they can be tested on the JVM and reused across apps, bots or CLI tools.

## 3. MVVM architecture pattern

Use **MVVM** across all features:

- **Model:**
  - Repositories expose immutable `StateFlow` snapshots and handle caching and sync silently.
  - Domain logic stays pure and deterministic, free of I/O and UI.
  - Local storage keeps offline caches and the transaction journal.
- **ViewModel:**
  - Expose a single immutable UI state through a `StateFlow`.
  - Model every user interaction as a sealed `Intent`, handled by one `onIntent(intent)`.
  - Send one-off events (navigation, notices) through a `Channel` exposed as an `effects` Flow.
  - Run live work (streams, polling) only while the screen is visible, e.g. under `repeatOnLifecycle`.
- **View:**
  - Passive UI that renders the state and delegates actions to `onIntent`.
  - Split the route (binds the ViewModel and lifecycle) from a stateless screen that previews and tests on its own.

## 4. Onchain data & transaction execution

- **Chain SDKs:** **[Kaptos](https://github.com/mcxross/kaptos)** for Aptos, **[Ksui](https://github.com/mcxross/ksui/)** for Sui.
- **BCS ser/de:** **[kotlinx-serialization-bcs](https://github.com/mcxross/kotlinx-serialization-bcs)** for Move BCS serialization and deserialization.
- **Dependency versioning:** Default to **stable releases** on Maven Central. If a required feature exists only in a snapshot, **tell the user**.
- **Transaction safety:**
  - Carry prices, amounts and fees as **exact decimal strings** or fixed-point units, never `Double`, which rounds silently.
  - Simulate against RPC before prompting for authorization.
  - Journal the signed hash before broadcasting and reconcile on restart; an uncertain outcome stays pending, never failed, so nothing is sent twice.

## 5. Security & biometric transaction signing

- **Biometric signing by default:** Sign behind biometrics with device passcode fallback (`BiometricPrompt` on Android, `LocalAuthentication` on iOS), through a platform hardware vault.
- **Hardware isolation:** Keep private keys and mnemonics only in Android Keystore / iOS Keychain, device-bound and never backed up.
- **Foreground authorization:** Scope signing authority to the foreground session. Backgrounding revokes it immediately and wipes decrypted secrets from memory.
- **Screen security:** Block screenshots and app-switcher previews wherever secrets are shown or entered. Never keep secrets in saveable UI state or logs.

## 6. Local storage versioning

- Start local storage (Room database, DataStore schemas) at **v1 and stay at v1** until the app is published. Avoid premature migrations while pre-release schemas are still changing.

## 7. Cold launch & onboarding polish

- **Zero-flash cold launch:** Drive startup through explicit stages (loading → locked or onboarding → ready). Hold a splash that matches the system launch screen while vaults and databases initialize, so the handoff has no visible seam.
- **Progressive onboarding:** Allow exploring read-only market data before requiring a wallet. One credential input recognizes phrases and keys, with real-time inline hints.
- **Resilient flows:** Save each step so interrupted onboarding resumes without orphan credentials.
