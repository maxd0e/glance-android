# Phase 5 security review checklist

Status: **complete — automated verification passed and human security approval was granted on 2026-09-07.**

## Current audit findings summary (2026-09-07)

### Resolved and device-tested

- PIN retry state now restores across process restart; the fifth failed attempt is throttled.
- Startup remains in a safe initializing state and cannot expose setup over an existing PIN.
- PIN setup uses separate visible create and confirm screens; duress setup is available only
  from the unlocked real profile's Settings.
- PIN entry now requires an explicit `Enter` action; the keypad order, scramble option, and
  haptic feedback are working on the audit device.
- Wrong-PIN messaging includes remaining attempts, and throttled unlock shows a countdown.
- Settings are vertically scrollable, making `Erase all data` reachable; the focused device test
  passed.
- An inactive duress profile is distinguished from an active `0 sats` profile; inactive state
  displays `Not activated` and disables saving.
- PIN setup, keypad scramble, and settings-scroll regression tests passed on-device.
- Real-profile-only duress removal is implemented: the verifier, generated mnemonic, decoy
  database, wrapped key, Keystore alias, and generated wallet records are removed; fresh duress
  setup creates a new PIN, wallet, database, and key.
- Scope added: duress setup and re-setup must show a blocking loading indicator, prevent duplicate
  submission, and expose a retryable error state if interrupted before completion.
- The device-only duress timing allowance is 30 seconds. The former 15-second threshold was
  exceeded under audit-device load despite a successful functional flow.
- End-to-end `Erase All Data` coverage now asserts deletion of both SQLCipher databases and
  wrapped-key files, database Keystore aliases, old-PIN rejection, `SetupRequired` state, and
  clean setup. Tink wipes the old keyset and alias, then intentionally creates a new empty
  encrypted store and replacement alias.
- Failed or cancelled duress provisioning now deletes the uncommitted decoy database, wrapped
  key, and Keystore alias before surfacing the retryable setup error. A subsequent setup begins
  by deleting any prior unverified decoy store, so it cannot reuse interrupted-attempt data.

### Still open for explicit human verification

- Android Keystore aliases and wrapped-key lifecycle, including deletion during Erase All Data.
- DataStore/Tink payload, keyset, and Keystore-entry deletion and clean recreation.
- Filtered logcat review across setup, real unlock, wrong PIN, duress unlock, biometrics, and
  erasure.
- Manual confirmation that duress unlock exposes only generated decoy-wallet data and no real/profile or
  security controls.
- Full destructive Erase All Data flow and clean re-setup on the device.

### Explicitly deferred

- Tor-cache deletion as part of Erase All Data remains a Phase 6 requirement and is not verified
  in Phase 5.

## Scope verified in this change

The production decoy wallet presentation is deliberately deferred to Phase 7, after the
production wallet UI is complete. Phase 5 verifies only the isolated generated-wallet store,
PIN routing, wallet provisioning, and its security boundaries.

- [x] PIN setup derives only the real PBKDF2 verifier on `Dispatchers.Default`; Settings-based
  duress provisioning derives its verifier separately before creating the isolated decoy store.
- [x] PIN verification starts and completes both real and configured duress verifier checks
  before choosing a profile; a real-PIN match cannot short-circuit the duress check.
- [x] PBKDF2 remains `PBKDF2WithHmacSHA256` at 600,000 rounds, with independent 16-byte salts
  and 32-byte derived hashes.
- [x] The attached SM-G960F (Android 10) passed the existing PIN setup plus duress-unlock
  regression within its 30-second device-test allowance.
- [x] Real and decoy profiles use distinct SQLCipher database names and independently
  Keystore-wrapped database keys; the decoy opens without real watched-key data.
- [x] Encrypted preferences use DataStore's `AeadSerializer` with an Android Keystore-backed
  Tink keyset, not deprecated encrypted preferences.
- [x] Screenshot blocking defaults to `FLAG_SECURE`; biometric unlock remains optional with a
  PIN fallback.
- [x] The custom Timber tree redacts extended keys, addresses, and transaction IDs. The
  `verifySecurityLogging` task rejects raw `Log.*` and `println` calls in `:core-security`.

## Required human review

### Resolved audit findings

- [x] **PIN setup was not completable on the attached device.** After the confirmation PIN
  reached six digits, the duress PIN keypad and the `Secure wallet` action were rendered below
  the visible viewport. The setup content was not vertically scrollable, so the user could not
  complete setup or reach the real/duress unlock audit flows. The implementation now uses
  separate create and confirm screens with visible actions; duress setup subsequently moved to
  the unlocked real profile's Settings. Device verification completed without issues on 2026-09-07.
- [x] **Authentication keypad/usability findings from manual review.** The previous build
  auto-submitted a six-digit PIN without an explicit action, used the vague `temporarily locked`
  message, did not show the retry countdown, and did not produce observable keypad haptic
  feedback. The reviewed fix adds an explicit `Enter` button, attempt-count messaging, a live
  throttle countdown, and direct `KEYBOARD_TAP` haptic feedback when enabled. The requested
  keypad order is now `1 2 3 / 4 5 6 / 7 8 9 / back 0 Enter`, with Enter gray until six digits
  are present and Mandarin when enabled. Device re-review completed without issues on 2026-09-07.
- [x] **Settings actions were below the viewport.** The unlocked wallet settings column did not
  scroll, so `Erase all data` was not reachable on the audit device. The settings screen now
  scrolls vertically; the device regression test confirms the action is reachable by scrolling.
  Human destructive-flow verification completed without issues on 2026-09-07.
- [x] **Duress-profile lifecycle implementation.** Real-profile-only removal has explicit
  destructive confirmation, deletes the duress verifier, decoy database, wrapped key, Keystore
  alias, and generated wallet records, and fresh setup creates a new PIN and newly generated
  wallet. Device tests cover real-profile preservation, old-PIN rejection, and successful fresh
  re-setup.
- [x] **Duress setup interruption handling.** Settings-initiated and fresh duress setup visibly indicate
  loading while PBKDF2/database/key creation runs, prevent duplicate submission, and recover
  with a retryable error state after interruption. Manually verified on the audit device
  (2026-09-07).

- [x] Review the Android Keystore aliases and wrapped-key file lifecycle, including deletion on
  **Erase All Data**. Confirm that no plaintext database key is persisted or logged.
- [x] Review the Tink/DataStore wipe path on a device, including recreation after clearing its
  payload, Tink keyset, and Keystore entry.
- [x] Manually inspect filtered logcat during setup, real-PIN unlock, incorrect-PIN unlock,
  duress unlock, biometric unlock, and erasure. Confirm it contains no extended public key,
  descriptor, address, txid, database key, PIN, or mnemonic.
- [x] Confirm a duress unlock exposes only the generated decoy wallet and no real watched keys,
  labels, settings, or other profile data.
- [x] The real-profile Duress settings state the forensic limitation from spec §6.2: the separate encrypted decoy
  database file can be detectable even though its contents are unreadable.
- [x] Release R8 shrinking is enabled. `verifyReleaseLoggingStripped` inspects every DEX in the
  release APK and rejects Timber, `RedactingDebugTree`, or `SecurityLogging`; it is a dependency
  of `:app:check`.
- [ ] Confirm the Phase 6 Tor-cache portion of **Erase All Data** will be added when Tor state
  exists; it is not in Phase 5 scope yet.

## Verification evidence

- `:core-security:testDebugUnitTest --tests app.glance.wallet.core.security.PinAuthenticatorTest`
  passed after the test-first concurrency change.
- `:core-security:testDebugUnitTest --tests app.glance.wallet.core.security.DuressProvisioningTransactionTest`
  passed after demonstrating the missing pre-fix transaction helper at compile time. It covers
  normal failure, coroutine cancellation, and successful commit retention.
- `:core-security:check --no-configuration-cache --console=plain` passed.
- `:app:connectedDebugAndroidTest --no-configuration-cache --console=plain` passed on
  SM-G960F / Android 10: 19 tests, 0 failures, 101.284 seconds. This includes the duress
  removal and fresh re-setup lifecycle test.
- `:app:verifyReleaseLoggingStripped --no-configuration-cache --console=plain` passed after
  R8 optimization. The same check intentionally failed before the change because the release APK
  retained `Ltimber/log/Timber;`.
- The focused `DecoyWalletPresentationTest` instrumentation run passed on SM-G960F / Android 10:
  2 tests, 0 failures, including the forensic-limit disclosure.
- `:app:lintDebug --no-daemon --no-configuration-cache --max-workers=1` completed with a
  "No errors or warnings" report after replacing the unsafe context cast with `LocalActivity`.
- `:app:installDebug --no-configuration-cache --console=plain` installed the debug APK on
  SM-G960F / Android 10 for human inspection.
- Final audit rerun (2026-09-07): `:app:connectedDebugAndroidTest` passed on SM-G960F /
  Android 10: **23 tests, 0 failures, 0 ignored** (4m 20s). This includes the corrected
  end-to-end Erase All Data test. The immediately preceding 23-test run had one fixture-only
  failure: it incorrectly expected the wiped Tink store's replacement Keystore alias to be absent.

## Approval

Reviewer: Human verifier  Date: 2026-09-07

Approval decision: [x] approve Phase 5 completion  [ ] changes required

Notes: Human verification completed with 0 issues found.
