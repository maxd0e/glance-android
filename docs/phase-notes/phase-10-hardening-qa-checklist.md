# Phase 10 hardening and QA checklist

Status: **in progress - no Phase 10 completion is claimed until every item below has recorded evidence and a human approval.**

This artifact intentionally records only commands, outcome, build/device identifiers, and
non-sensitive observations. Never enter extended public keys, descriptors, addresses, transaction
IDs, PINs, mnemonics, database keys, server credentials, IP addresses, or balances.

## Automated hardening

| Check | Command | Result | Date / reviewer |
| --- | --- | --- | --- |
| Pinned dependencies and plugins | `./gradlew verifyDependencyPins` | Pass (`--no-daemon --no-configuration-cache --console=plain --max-workers=1`) | 2026-10-01 / local Gradle 9.8.0 |
| Production secret-leak static analysis | `./gradlew verifySensitiveDataLeakage` | Pass (`--no-daemon --no-configuration-cache --console=plain --max-workers=1`) | 2026-10-01 / local Gradle 9.8.0 |
| Release logging removed from DEX | `./gradlew :app:verifyReleaseLoggingStripped` | Blocked — local optimized-release build did not complete and produced no APK to inspect | 2026-10-01 / local Gradle 9.8.0 |
| Full unit, instrumented, and lint suite | `./gradlew build lint` | Blocked — focused `:app:testDebugUnitTest` did not complete, so no passing test result is recorded | 2026-10-01 / local Gradle 9.8.0 |

`verifyDependencyPins` permits the Foojay settings plugin's required bootstrap declaration only
when it exactly matches the catalogued version. It rejects floating selectors. The static leakage
task rejects raw logging and unreviewed crash-reporting integrations from production sources;
release verification separately proves that Timber/redaction types are absent from the APK.

## Development-only regtest run

Use an emulator only. The `regtest` build type has application id suffix `.regtest`, sets
`BuildConfig.REGTEST`, and is never selected by release tasks. It does not add testnet or regtest
import paths to normal debug/release builds.

Environment status (2026-10-02): Android Debug Bridge connected to an emulator and Docker Desktop
is installed. The isolated regtest APK installed and its main activity launched successfully. The
local service stack remains blocked because Docker denied the pull of the pinned
`ghcr.io/romanz/electrs:v0.10.9` image; no wallet material, funds, or live-sync observations were
recorded.

1. Run `./scripts/start-regtest.ps1`; use `-Reset` only for disposable local test data.
2. Mine disposable funds and send them to a disposable regtest watch target using the local Bitcoin
   Core RPC. Do not copy that target into this document or source control.
3. Run `:app:verifyRegtestBuildBoundary`, then build/install `:app:assembleRegtest` on an emulator.
   The emulator reaches the local Electrum endpoint at `10.0.2.2:50001`.
4. Validate initial sync, an additional confirmed transaction and incremental refresh, UTXO state,
   receive-address reuse, transaction ordering, and cached block timestamps/chart points. Record
   only pass/fail and generic observations below.

| Scenario | Result | Date / reviewer |
| --- | --- | --- |
| Local bitcoind/electrs stack healthy | Blocked: Docker denied pull of the pinned electrs image | 2026-10-02 / local emulator run |
| Regtest APK install and startup | Pass (isolated APK installed; main activity cold-started on emulator) | 2026-10-02 / local emulator run |
| Regtest build boundary test | Pass (`:app:verifyRegtestBuildBoundary`, isolated `app.glance.wallet.regtest` BuildConfig) | 2026-10-01 / local Gradle 9.8.0 |
| Initial watched-key sync | Pending | — |
| Confirmed incremental refresh (no full rescan) | Pending | — |
| UTXO and transaction presentation | Pending | — |
| Receive address remains stable until used | Pending | — |
| Chart has confirmed-history timestamps | Pending | — |

## Manual §6 security and privacy review

| Area | Required observation | Result | Date / reviewer |
| --- | --- | --- | --- |
| Authentication | PIN throttle survives restart, caps safely, optional keypad scramble/haptics work; biometric has PIN fallback | Pending | — |
| Duress PIN | Separate generated decoy database/wallet only; no real data or security/profile controls; removal/recreation destroys old decoy material | Pending | — |
| Stealth and street mode | Calculator is functional and `=====` unlocks; shake-only street mode masks balances/chart axis and persists | Pending | — |
| Screenshot blocking | `FLAG_SECURE` defaults on and its setting behaves as disclosed | Pending | — |
| Data at rest | SQLCipher key remains Keystore-wrapped; Tink settings remain encrypted; unavailable key fails closed | Pending | — |
| Erase all data | Every real/decoy database, encrypted setting, directory/health, and Tor-cache target is attempted; failure remains retry-only | Pending | — |
| Logs/crash reports | Filtered logcat across setup, unlock, duress, biometric, sync, import/export, and erase contains no sensitive values | Pending | — |
| Tor | All Electrum/Esplora/fiat/directory traffic uses Tor by default; SOCKS DNS remains remote; background/lock cancels work and stops Tor | Pending | — |
| Direct/offline policy | Direct mode requires warning; offline prevents all network requests while cached content remains available | Pending | — |
| Privacy disclosures | Clipboard sensitivity and external-browser warning are visible and accurate | Pending | — |

## Real-wallet integration gate

Use the reviewer’s own small-funds wallet and do not record its public material, endpoints, or
balances. Test initial and incremental sync, cached/offline behavior, history and UTXO views,
receive-address reuse, and sats/fiat chart presentation.

| Run | Result | Date / reviewer |
| --- | --- | --- |
| Own small-funds wallet manual integration | Pending | — |

## Approval

Reviewer: —  Date: —

Decision: [ ] approve Phase 10 completion  [ ] changes required

Only after approval: mark Phase 10 complete in `AGENTS.md` and §17 of the wallet specification.
