# Release checklist

This checklist records evidence required for an Android/iOS release. Mainnet stays disabled until every mainnet item is complete; producing a testnet build does not waive security or reproducibility checks.

## Source and dependencies

- [ ] The release commit is tagged from a protected branch and the worktree is clean.
- [ ] `LICENSE`, `NOTICE`, dependency notices, `SECURITY.md`, and `THREAT_MODEL.md` match the shipped artifact.
- [ ] Kaptos and FastKrypto resolve from their pinned Maven Central coordinates without Maven Local enabled.
- [ ] Dependency review, license policy, and secret scanning pass.
- [ ] Android and iOS release archives are reproducibly built from the tagged commit.

## Automated verification

- [ ] Decibel SDK fixture, fixed-point, validation, pagination, WebSocket, and golden-payload tests pass.
- [ ] Shared Android host and iOS simulator tests pass.
- [ ] Android release assembly and the iOS simulator application build pass.
- [ ] The pinned `decibel-worker` release passes typecheck, authentication, replay, role, sponsorship-policy, and route tests.
- [ ] Large-text, accessibility-label, touch-target, navigation, and screenshot checks pass on both platforms.

## Testnet acceptance

- [ ] Create an owner wallet, confirm its backup, remove it, and restore it from 12 words.
- [ ] Discover/create a subaccount; deposit and withdraw Aptos USDC.
- [ ] Create/import an independent API wallet and complete delegation and proxy authentication.
- [ ] Submit sponsored limit and IOC orders; verify cancel, full close, leverage, and TP/SL.
- [ ] Exercise safe sponsorship rejection, self-pay confirmation, and owner-signed API-wallet APT top-up.
- [ ] Interrupt submission, restart the app, and verify transaction reconciliation without duplicate submission.
- [ ] Confirm account/order states become visibly stale after connectivity loss and recover through snapshot backfill.

## Platform security

- [ ] Owner and API secrets are extracted only through authenticated Android Keystore and iOS Keychain flows on physical devices.
- [ ] Backgrounding and five-minute expiry clear signing access; owner actions and export require fresh authorization.
- [ ] API-wallet-only mode cannot access owner funding, delegation, export, removal, or self-pay operations.
- [ ] The deployed `decibel-worker` production secrets are distinct from testnet secrets and never appear in application packages or logs.
- [ ] The packaged Android resource and iOS build setting point to the intended HTTPS Worker deployment.

## Mainnet canary

- [ ] A read-only market/account smoke test completes against immutable mainnet deployment values.
- [ ] A designated reviewer verifies package address, chain ID, USDC metadata, Worker route policy, and capability flags.
- [ ] A manually approved, value-capped account completes deposit, delegation, limit/IOC order, cancel/close, and withdrawal.
- [ ] Release owners record hashes and outcomes in a private release record with no secret material.
- [ ] Mainnet is enabled only after canary review; rollback and Worker-disable procedures are ready.

Encrypted transactions are a separate capability. They require reviewed Kaptos support, network/server capability, acceptance coverage, and explicit consent before any plaintext retry.
