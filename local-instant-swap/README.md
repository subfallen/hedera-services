# Local Instant Swap genesis fixtures

This fork creates the following fixtures during **genesis**, using ordinary dispatched HAPI transactions whose
creation, association, and transfer facts appear in the consensus streams. They are for the disposable local Hedera
network (`ledger.id=0x03`, EVM chain ID 298). They do not provision any production account.

| Entity | ID | Purpose |
| --- | --- | --- |
| Principal | `0.0.888881` | Real contract account with the application's `SelfManager.manager()` ABI and a primitive Ed25519 admin key |
| Customers | `0.0.888882` through `0.0.888887` | Six independent account IDs for five occupied swap slots plus one contention attempt |
| PLEX/USDC reference | `0.0.777782` | Immutable synthetic reference at $0.10/PLEX |
| WETH/USDC reference | `0.0.777783` | Immutable synthetic reference at $2,000/WETH |
| WBTC/USDC reference | `0.0.777784` | Immutable synthetic reference at $100,000/WBTC |
| WHBAR/USDC reference | `0.0.777785` | Immutable synthetic HBAR cost mark at $0.10/WHBAR |

The principal's public key is the existing `A4589188_PUBLIC_KEY` local fixture (the app's disposable `account9999`
Ed25519 signing fixture). Customers use the existing `TRADER_ECDSA_PUBLIC_KEY` fixture. The principal, customer, and
broker signing keys are distinct. No private keys are added here. The real principal admin key authorizes its token
debit, each customer signs its own input debit, and the broker pays and signs the native `CryptoTransfer` transaction.

Each principal/customer starts with 10,000 HBAR and exactly $1,000 of each admitted HTS asset:

| Asset | Token ID | Decimals | Initial atoms per account |
| --- | --- | --- | --- |
| USDC | `0.0.20005` | 6 | 1,000,000,000 |
| PLEX | `0.0.666666` | 6 | 10,000,000,000 |
| HTS-WETH | `0.0.20001` | 6 | 500,000 |
| HTS-WBTC | `0.0.20000` | 7 | 100,000 |

Associations are explicit, and funding is a balanced transfer from the existing token treasury/broker. No custom fee
schedule or fee-schedule key is installed. WETH/WBTC decimals now have explicit values equal to their previous seeded
random values; the old random draws are still consumed, preserving every other token's decimals. ERC-20 contracts
`0.0.111111`/`0.0.222222` are separate fixtures and are not these admitted HTS assets.

The backend must event-onboard the principal as an ACTIVE BOT and let its ordinary durable allowance workflow approve
the **actual dynamically created settlements contract**. Genesis cannot guess that spender, and a broker-spender
allowance would not establish readiness. Direct Instant Swap settlement uses ordinary signed debits, not allowances.

## Immutable reference contract boundary

`LocalInstantSwapReferencePools.sol` exposes only `token0`, `token1`, `fee`, `tickSpacing`, `slot0`, `liquidity`,
`tickBitmap`, and `ticks`. Every result is compiled into a pure runtime. There is no storage, mutation, admin key,
delegatecall, or selfdestruct. The displayed liquidity is deliberately synthetic: these contracts **cannot execute AMM
swaps and hold no pool reserves**. Existing SaucerSwap factory/router/quoter/pool fixtures remain separate.

The fixtures exercise the application's real sampler, warmup window, tick/depth math, reference-age checks, and native
HTS settlement. They do not demonstrate executable AMM liquidity, hedging economics, or production prices.

`fixtures.json` is the shared contract with `lambdaplex-mocks` and the backend: exact runtime bytes and SHA-256 hashes,
token order, decimal/price metadata, fee 1500, tick spacing 60, active liquidity, initialized lower/upper ticks, and
exact ABI responses. The mock mirror must first observe the real successful immutable contract creation and matching
runtime. It may answer only at actual indexed blocks after creation, refusing unknown selectors, missing/mismatched
code, pre-creation blocks, and mutable contracts. Constant answers are valid historical reads only because these
particular verified runtimes cannot change. Never serve a live mutable pool's current state as historical state.

## Rebuild and verify

From this directory, with Node 24:

```sh
npm ci --ignore-scripts
npm run build
npm run check
npm test
```

The pinned Solidity compiler targets Paris and writes five root-level `.bin` artifacts plus `fixtures.json`. The
Dockerfile packages those artifacts and the same dev configuration/settings used by `:app:run`, pinning local
ledger/chain IDs and record/sidecar generation. `check` recompiles and requires byte-for-byte reproducibility. The EVM tests
execute the creation bytecode, verify deployed runtime hashes, all exported ABI answers, initialized and absent ticks,
signed bitmap boundaries, invalid calldata, exact square-root prices, and the principal manager ABI. Java tests in
`SystemTransactionsTest` check the dispatched identities, immutable pool creation, token metadata, explicit
associations, and balanced inventory transfers. CI verifies the generated artifacts and EVM contract behavior.

When changing the shared manifest, update the copies in both application and mock mirror in the same coordinated
change. These fixture IDs are reserved by this fork; do not reuse them for a different runtime or meaning.

## Reset versus restart

A fresh local network built from this revision receives the fixtures once at genesis. A warm consensus restart
preserves balances, keys, associations, contracts, and transaction history; it does not mint or top up inventory.
Restarting only the backend must reuse those ledger facts and recover its durable workflows, while rebuilding its
in-memory reference warmup window from newly indexed blocks.

An older saved local ledger does not acquire new genesis fixtures by upgrading its image. Use the local stack's
explicit disposable reset procedure when first adopting these fixtures, resetting the matching consensus, mirror,
and application data together. No reset or stack start is performed by this build. A backend startup check should
explain missing/mismatched fixtures and fail rather than invent evidence, delete state, or silently initialize a
partially upgraded ledger. Node liveness alone is insufficient: the application must observe actual creation,
funding, associations, allowances, and reference samples before declaring Instant Swap ready.
