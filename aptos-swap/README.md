**Aptos swap infrastructure**

This headless module quotes and validates wallet-held APT → Circle-native USDC on Aptos mainnet. It contains no screens, Decibel deposits, wallet secrets or funding orchestration.

`SwapProvider` returns a `ValidatedSwapPlan`. The Panora adapter calls Flare's authenticated worker, verifies the requested amounts and assets, validates the deployed router schema, and encodes the payload through the pinned Kaptos dependency. The payload hash must match the worker's independent Aptos SDK encoding. Frozen payload bytes bind review, simulation and signing to the same route.

The initial policy supports exact input and exact output, one FA withdrawal, direct routes through APT/USDC, explicit slippage from 1 to 100 basis points, a quote lifetime of at most 30 seconds, and zero provider fees. Unknown schemas, assets, fee multipliers or output protections fail closed. Supporting another provider or widening these limits requires a reviewed adapter and fixtures, rather than weakening the executor.

`validateEffects` checks the owner's net APT debit and USDC receipt using primary-store events. The app additionally reserves the full gas limit before signing. This is a simulation check; the on-chain router limits remain the execution bounds.

The application integration lives in `:venue-decibel` behind `:shared`'s `SwapRepository`. `AptosSwapRepository` handles owner authorization, profile binding, retention of executable quotes, and durable operation history. The pending transaction journal is written before submission, while the separate versioned DataStore record retains final results and both the original sponsorship reference and confirmed hash. Uncertain outcomes can only be reconciled. A fresh process cannot execute an old in-memory quote.

`:aptos-transaction` holds the execution mechanics shared with the Decibel SDK. It accepts payloads and policy callbacks and owns no keys or storage. Flare's `AptosSubmissionGate` serializes swap, Decibel and fee-top-up authorization. The shared module imports no chain SDK.

The companion worker provides `/swap/quote` and accepts the quote's signed authorization on `/gas/sponsor/owner`. Worker commit [`2e3bcce`](https://github.com/emeieiron/decibel-worker/commit/2e3bcce) enables quotes with `SWAPS_ENABLED=true` and a server-side `PANORA_API_KEY`; Panora's documented public key is sufficient. Sponsorship uses the existing Gas Station resource key and requires the upstream policy to permit the verified router. Quoting and self-paid execution do not depend on sponsorship: a definite rejection offers payment with the user's APT, while an uncertain submission must be reconciled first. Funding UI remains separate.

Verification uses captured public exact-input and exact-output quotes, the deployed router ABI, independent payload hashes, malformed-route tests and simulated transaction execution. Quote fixtures contain no private keys. The signing and restart tests run on the iOS simulator, following the repository's existing native runtime tests; Android host tests cannot load the chain SDK's JNI library. Live exact-input and exact-output quotes were also verified through `https://api.mcxross.xyz` with an unfunded temporary owner wallet; its session was revoked afterward. No swap was submitted. Mainnet execution with a funded wallet remains a separate release check.

```sh
./gradlew :aptos-swap:jvmTest :aptos-swap:iosSimulatorArm64Test :decibel:jvmTest :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :venue-decibel:testAndroidHostTest :venue-decibel:iosSimulatorArm64Test :androidApp:assembleDecibelDebug --no-configuration-cache
```
