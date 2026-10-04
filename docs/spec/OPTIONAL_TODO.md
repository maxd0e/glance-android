# Optional follow-ups

- [ ] **Make Tor-connected erase deterministic.** Erase All Data must complete on the first
  attempt whether bundled Tor has been started or not. Diagnose and remove the race/failure
  between session shutdown and `TorController.clear()`, while still retaining the durable
  fail-closed retry marker for genuine cleanup failures. Add a connected-Tor regression that
  verifies profile, preferences, Tor cache, signed manifest, endpoint health, and the retry
  marker are all removed in one successful erase.

- [ ] **Keystore-bound biometric authorization.** Require a fresh `BIOMETRIC_STRONG`
  `BiometricPrompt` authorization for the key material used by biometric unlock, using Android
  Keystore user-authentication constraints. Keep the coordinator's encrypted preference check as
  a second authorization gate; future callers must not be able to invoke biometric unlock without
  a successful platform prompt. Add success, cancellation, disabled-preference, and direct-call
  regression coverage.

- [ ] **Zeroize SQLCipher passphrases.** Clear every mutable passphrase byte array immediately
  after database-open use, including failure paths, without retaining it in exceptions or logs.
  Add a focused test seam that proves cleanup is reached on both successful and failed opens.

- [ ] **Bound untrusted network responses.** Apply explicit, documented byte limits before
  parsing Electrum JSON-RPC and Esplora/fiat HTTP responses; reject oversized bodies with generic,
  retryable errors and verify no unbounded buffering remains.

- [ ] **Apply screenshot protection before Compose.** Set `FLAG_SECURE` in `MainActivity.onCreate`
  from the secure default, then reconcile the encrypted preference when it is available, so no
  first-frame screenshot window exists.

- [x] **Validate Mempool onion fiat on-device.** On the targetSdk 37 release candidate, verify
  current and historical `http://…onion` requests work through the local SOCKS proxy while Tor
  is enabled and that no direct or clearnet fallback occurs. Verified 2026-09-25 on SM-G960F /
  Android 10 with `TorLiveSmokeTest`; both current and historical Mempool requests passed through
  the fixed onion client. No clearnet fallback path is used by the test.

- [ ] **Post-auth cached-data warm-up.** After a successful PIN or biometric check has selected
  the real or decoy profile, open only that profile's SQLCipher database and warm the cached Home
  queries on a background dispatcher before presenting the wallet. Never open or query a profile
  while the app is locked, never preload data during first-time PIN setup (there is no cache), and
  never make wallet, fiat, address-derivation, or other network requests as part of this warm-up.
  Preserve real/decoy isolation and test that no database access occurs before authentication.

## Derivation / format-identification review follow-ups

- [x] **Close the discovery chain provider.** `XpubFormatDiscovery.discover` closes its
  `FallbackChainDataProvider` in `finally`, preventing leaked Tor-routed Electrum TLS sockets
  and per-address subscriptions on both successful and failed discovery attempts.

- [x] **Normalize pasted addresses before parsing.** `classifyUnifiedImport` normalizes before
  extracting and parsing bare or BIP21 addresses, so invisible clipboard markers no longer fall
  through to the misleading extended-public-key error.

- [x] **Descriptor origin/checksum compatibility.** Supported single-sig descriptors accept
  optional standard key-origin metadata and validate optional BIP380 `#checksum` suffixes (e.g.
  Sparrow/Coldcard exports). Glance still rejects all descriptor families and derivation patterns
  outside its four supported templates.

- [ ] **Thread the gap-limit setting when it lands.** `NetworkClients.syncEngine()`,
  `xpubFormatDiscovery()`, and `initialDerivedAddresses` all rely on `SyncConfig()`'s default.
  When the advanced gap-limit setting is added, route it through all three call sites.
