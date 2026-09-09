# Flare threat model

## Assets

- Owner mnemonic or imported Ed25519 private key.
- Independent API-wallet private key.
- Short-lived Worker session tokens and authentication signatures.
- Decibel node and Aptos Gas Station credentials.
- User intent: network, wallet, subaccount, market, side, price, size, leverage, and funding amount.
- Transaction journal state needed to distinguish committed, failed, and uncertain submissions.

## Trust boundaries

The mobile process is not a trusted server. A modified client can call every public Worker route and submit arbitrary JSON. The separately maintained `decibel-worker` service therefore validates authentication and transaction scope independently before injecting any credential.

The Worker is trusted with upstream credentials but not wallet secrets. It receives public keys, signatures, short-lived session claims, and signed transaction BCS. It must never receive a mnemonic or private key.

Decibel, Aptos fullnodes, and the Gas Station are external systems. Their responses can be delayed, malformed, stale, or ambiguous after a transport failure. Flare validates response shapes and preserves uncertain transaction state instead of assuming success or failure.

## Principal threats and controls

| Threat | Control | Residual risk |
| --- | --- | --- |
| Device theft or local extraction | Android authenticated Keystore wrapping; iOS passcode-bound, user-presence Keychain; five-minute signing sessions; step-up for owner actions and export | A compromised unlocked OS or accessibility stack may capture displayed/reconstructed secrets |
| Mnemonic/API-key confusion | Separate owner/API storage and independent generation; owner supports BIP-39/BIP-44 or imported Ed25519; keys use canonical AIP-80. A unified input detects encoding, while API role is explicitly selected and delegation remains verified | Users can still disclose exported material outside Flare |
| Worker credential extraction | Credentials exist only as Worker secrets; fixed routes; no unrestricted proxy; redacted logging | A compromised Worker account can abuse upstream service quotas and sponsorship |
| Challenge replay | Domain-separated `FLARE_AUTH_V1`; 32-byte nonce; five-minute expiry; Durable Object atomic consumption | Durable Object or platform compromise defeats this control |
| Session privilege escalation | Claims bind network, wallet, optional subaccount, and role; owner routes reject API role; active delegation verified before API session issuance | Upstream delegation indexing can lag, causing temporary denial or stale authorization until session expiry |
| Sponsoring arbitrary transactions | Worker parses BCS, verifies Ed25519 signature and sender, chain ID, Decibel package/module/function, argument count, subaccount, USDC metadata, and zero fee-payer placeholder | New authenticators or payload formats require explicit parser updates; upstream Gas Station policy remains defense in depth |
| Price/quantity corruption | Decimal-string inputs; live precision metadata; overflow/tick/lot/minimum validation; simulation before signing | A valid but stale book can still produce adverse execution within the disclosed slippage limit |
| Market-order manipulation | IOC limit derived from best ask/bid and visible basis-point slippage; disabled without a reliable book | A fast or manipulated market can move within the accepted slippage window |
| Duplicate or ambiguous submission | Single submission mutex; exact signed hash journaled before direct submit; Kaptos sponsorship fingerprint reserved idempotently in a Durable Object; seven-day status lookup recovers the submitted hash; final state reconciled through Kaptos | A Worker failure after upstream acceptance but before the submitted hash is durably recorded remains visible as an unresolved fingerprint and must not trigger automatic resubmission |
| WebSocket loss or reordering | Sequence tracking, exponential backoff with jitter, topic restoration, stale UI state, and REST backfill | Upstream topics without sequence numbers rely on bounded snapshot refresh |
| Sensitive telemetry | No analytics/crash telemetry by default; logs exclude credentials, signatures, payloads, and private responses | Platform and network operators retain their own metadata |

## Security invariants

- UI code never contains deployment package addresses or upstream credentials.
- ViewModels never sign or submit transactions directly.
- Mnemonics, private keys, raw account responses, and session tokens are never stored in Room or DataStore.
- Owner funding, delegation, removal, and secret export always require fresh authorization.
- API-only profiles cannot perform owner operations.
- Self-pay is offered only after an explicit Gas Station rejection that is known not to have submitted the transaction.
- An encrypted-order failure is never retried as plaintext without explicit user approval.

Review this document whenever a route, authenticator, wallet store, transaction format, network capability, or telemetry behavior changes.

## Credential import compatibility

The encrypted `OWNER_MNEMONIC` slot retains its legacy storage key so existing accounts remain readable. Its value is either a validated BIP-39 phrase or a canonical `ed25519-priv-0x…` owner key. Owner signing dispatches on the validated encoding; export and removal retain fresh authorization. Raw 32-byte hex imports are normalized locally and are never stored in preferences or sent to the Worker. An AIP-80 prefix identifies an algorithm, not a delegated role. API-only profiles continue to store their key separately and cannot perform owner operations.
