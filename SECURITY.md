# Security policy

## Supported versions

Flare has not reached a stable release. Security fixes are applied to the default branch only until the first supported release line is declared.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repository. Include:

- affected commit or version;
- platform and network;
- prerequisites and a minimal reproduction;
- expected and observed authorization or transaction behavior;
- whether a real key, credential, or funded account may be exposed.

Do not include live mnemonics, private keys, session tokens, node keys, Gas Station keys, or funded-account signatures. Create testnet-only proof material when reproduction requires a signature.

Please avoid public disclosure until maintainers have acknowledged the report and coordinated a fix. This policy does not promise a bounty.

## Release security gates

Mainnet must remain disabled until all of the following are complete:

- owner and API-wallet vault tests pass on physical Android and iOS devices;
- the separately versioned `decibel-worker` nonce replay, signature/address, session scope, rate limit, topic, and sponsored-BCS tests pass;
- testnet end-to-end funding, delegation, orders, cancel, close, TP/SL, fallback, restart reconciliation, and withdrawal pass;
- Kaptos is consumed from its pinned Maven Central release without a fallback override;
- dependency-license and secret scans pass;
- a read-only mainnet smoke test succeeds;
- a manually approved, value-capped canary completes the transaction sequence.

Encrypted transactions must not be enabled merely to satisfy the mainnet gate. They require a separate reviewed capability flag and plaintext-fallback consent flow.
