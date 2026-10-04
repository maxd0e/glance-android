# Glance first-release security audit

Audit dates: 2026-10-04–2026-10-05. Scope: report only, before a stable Zapstore release.

## Release recommendation

**Do not publish a stable release yet.** The production directory-verification key has a publicly known private counterpart, existing Electrum connections do not recheck a revoked routing policy, and duress mode overrides the persisted offline switch. Additional storage, directory-cache, input-processing, and release-provenance issues are described below. This audit did not establish an unauthenticated route to reading a real profile's xpub, or demonstrate theft of a user's signing key.

Glance does not import real-wallet signing keys. However, the implementation generates, stores, and can reveal a BIP39 seed for the duress wallet. “No private data exists to leak” is therefore not a valid description of the entire app. Xpubs, descriptors, addresses, history, labels, and server configuration also require confidentiality even when they cannot authorize spending. An incorrect receive address or compromised distribution artifact can harm users despite watch-only operation.

This is an evidence-based engineering audit, not a certification or proof of zero risk. Confirmed code defects, reproduced component behavior, environmental failures, and untested scenarios are distinguished explicitly. No production fixes, publishing, signing-key changes, or merges were performed.

## Remediation status (2026-10-05)

This section records the post-audit implementation and supersedes the original release recommendation for source-code readiness. The findings and verification record below remain unchanged as historical evidence of the audited baseline.

### Findings

- [x] **F01 — Directory trust:** removed the RFC 8032 fixture from production trust, injected the verification key through release configuration, rejected blank/malformed/RFC fixture keys at the release gate, and disabled directory refresh when no production key is configured. `ManifestTrustTest` and the release-configuration gate cover the source behavior. Production key generation and deployment remain an operational release gate below.
- [x] **F02 — Route revocation:** added generation-scoped network leases that cancel active OkHttp calls and close blocking Electrum sockets on lock, backgrounding, profile changes, offline transitions, and route changes. Reused-socket revocation is covered by `NetworkSessionGateTest`.
- [x] **F03 — Duress/offline policy:** offline mode now prevents Tor bootstrap and every decoy request. An online duress session may synchronize only the isolated decoy through Tor, with no direct, directory, or fiat route. `DuressNetworkPolicyTest` covers the policy and Section 6.7 now states the resolved contract.
- [x] **F04 — Keystore enforcement:** encrypted preferences verify that Tink is actually using Android Keystore, delete fallback keyset material, remove the alias, and fail closed to a generic recovery screen when protection is unavailable.
- [x] **F05 — Device transfer:** both modern data-extraction rules and legacy backup rules exclude every app-data domain from cloud backup and device transfer. `BackupRulesTest` verifies the exclusions.
- [x] **F06 — Hostile responses:** HTTP bodies, Electrum lines/frames, call duration, identifiers, values, and persisted numeric ranges now have protocol-specific bounds. Oversized Electrum input is covered by `BlockchainClientTest`.
- [x] **F07 — Directory expiry and rollback:** persisted pool rows no longer grant authority; only a currently verified, unexpired manifest can replace bundled roles. Expiry falls back to bundled endpoints and the manifest store retains an independent monotonic version floor. `ServerPoolTest` covers restored authority, expiry fallback, and rollback protection.
- [x] **F08 — Release provenance:** release automation resolves an existing tag, checks out that exact ref, asserts `HEAD` equals the tag commit, derives downstream commit metadata from that checkout, distinguishes beta/stable publication, verifies the uploaded APK byte-for-byte, and pins GitHub Actions by commit.

### Additional audit points

- [x] PIN failure counters clamp corrupt negative state and saturate at `Int.MAX_VALUE`; boundary coverage passes.
- [x] Preference deletion/commit and Tor-stop failures are no longer silently ignored; incomplete erase remains on the retry-only recovery path. The physical-device security suite passes the normal and retry persistence cases.
- [x] Wrapped database keys use a temporary file, `fsync`, and atomic replacement; malformed wrappers consistently surface `DatabaseKeyRecoveryRequiredException` without replacing an existing protected key.
- [x] Backup passphrases and watch-target inputs use explicit non-learning/password-oriented keyboard options where supported. New exports require at least 12 characters and explain offline-guessing risk.
- [x] README/spec disclosures now describe the decoy seed exception, the risk of sending funds to the decoy, stealth limitations, optional screenshot exposure, and JVM string zeroization limits.
- [x] SQLCipher was updated to 4.9.0. Desktop secp256k1 JNI is test-only and Android unit-test classpaths exclude the Android JNI artifact; the complete app JVM suite now passes.
- [x] The five recorded device-test failures were corrected: Room schemas 12–14 are packaged, migrations target version 14, pending ordering is defined, transaction aggregation expectations match the DAO contract, and the timing-sensitive security case passes on physical hardware.
- [x] Release CI now uses JDK 21 and Android SDK 37, validates the Gradle JVM, supports stable and beta tags intentionally, and uses reviewed immutable action commits.

### Post-remediation verification

- [x] Complete affected JVM suites passed: `:core-crypto:test`, `:core-network:test`, `:core-data:testDebugUnitTest`, `:core-security:testDebugUnitTest`, and `:app:testDebugUnitTest`.
- [x] `:core-data:lintRelease`, `:core-security:lintRelease`, `:app:lintRelease`, and Android-test compilation passed.
- [x] Physical Android 10 verification passed: migrations 8/8, persistence 11/11, and security 17/17.
- [x] `verifyDependencyPins`, `verifySensitiveDataLeakage`, release assembly, lint-vital, and `verifyReleaseLoggingStripped` passed.
- [x] Release publication fails closed while signing inputs are absent.

### Remaining release gates

- [ ] Generate the production Ed25519 directory key under an approved custody process, sign and deploy the production manifest, and configure `GLANCE_DIRECTORY_PUBLIC_KEY_BASE64` in the protected GitHub release environment.
- [ ] Build and repeat critical checks on the exact signed candidate, then verify signing-certificate continuity, GitHub protections, the selected Zapstore asset/event/hash, publisher identity, and channel.
- [ ] Complete the contributor guide's explicit human review of the security-critical encryption, PIN/duress, Tor-routing, and stealth changes before merge or publication.
- [ ] Treat the broader packet/DNS capture, hostile-IME inspection, fault injection, reboot/time-change throttling, logcat/native logging, and supported-device matrix work in the coverage table as defense-in-depth validation rather than completed source fixes.

## Baseline and threat model

- Commit: `b0b3ec9e45ab8dff665bf78a863f0b0e930ddaf8`.
- Audited working tree included the owner's pre-existing launcher-artwork modifications. A staged `docs/design/logo/glance_logo_light.svg` appeared while work continued; it was preserved. The report is the only intended tracked audit addition. Temporary harnesses, emulator state, inventories, and logs are under ignored `build/security-audit/`.
- Release artifact built during the audit: `app/build/outputs/apk/release/app-release-unsigned.apk`; SHA-256 `461c0a995702136a95fecc8dce19ad18b5d9d6dbd1051ae50522f4559e6b27f6`.
- APK identity: `app.glance.wallet`, version `0.1.0-beta.13`, version code `13`, min SDK `24`, target/compile SDK `37`; four Android ABIs. The artifact is **unsigned**, so this is not verification of a publishable artifact's signing identity.
- Runtime target: disposable `GlanceAudit` emulator, Android API 36, x86_64, serial `emulator-5580`. The connected physical phone was not used or modified.
- Protect against hostile servers/networks, other ordinary Android apps, accidental disclosure, and access to a lost locked phone. Root, OS compromise, hostile accessibility services/IMEs with user-granted access, and code execution inside Glance are not promised to be defeated. Weak backup passwords remain susceptible to offline guessing.
- Method: review of crypto, storage, authentication, profile routing, network clients, backup/import, sensitive UI boundaries, manifest/resources, dependencies, and release workflows; focused tests; release APK inspection; safe local reproductions. [OWASP MASVS](https://mas.owasp.org/MASVS/) provided the coverage categories.

## Findings

Severity reflects impact and prerequisites in this threat model. “High” does not mean a demonstrated remote compromise of the current production server.

### F01 — High: production directory trust uses a public test key

**Evidence:** `app/src/main/java/app/glance/wallet/GlanceApplication.kt:126` contains the Ed25519 public key from [RFC 8032 §7.1, TEST 1](https://www.rfc-editor.org/rfc/rfc8032#section-7.1). That publication includes its private seed. The local probe used the public fixture to sign a directory containing only reserved `.invalid` hosts; the actual compiled `ServerManifestCodec.decodeAndVerify` accepted it: `DIRECTORY_PUBLIC_TEST_KEY_ACCEPTED=true`. No document was uploaded or sent to the production server.

**Impact/prerequisites:** Anyone can produce a directory signature the app trusts. An attacker would still need to replace the HTTPS-delivered document, compromise its hosting/deployment path, or otherwise supply the document at the trusted input. HTTPS is a remaining boundary; the signature does not independently protect against a compromised directory host. Accepted replacement endpoints could correlate wallet queries, falsify provider data, or deny service. Tor does not hide queried wallet identifiers from the selected server.

**Recommendation:** Replace the test key with a newly generated production verification key whose private counterpart stays outside source control and ordinary web hosting. Re-sign the production document; invalidate incompatible cached directory/pool state. Check deployment ownership and key custody without printing private material.

**Retest:** Production configuration must reject this RFC fixture, accept an authorized signature, reject changed endpoints/metadata, and safely recover from old cached documents after key rotation.

### F02 — High privacy risk: existing Electrum connections bypass route revocation

**Evidence:** `core-network/src/main/kotlin/app/glance/wallet/core/network/BlockchainRepository.kt:316` returns immediately for an existing socket. `clientFactorySource.current()` is consulted only when connecting. A local SOCKS fixture completed a request, changed the route source to throw, and completed a second request on the same transport: `EXISTING_SOCKET_ACCEPTS_AFTER_ROUTE_BLOCK=true; ROUTE_CHECKS=1`.

`GlanceApplication.setNetworkSessionActive` does not cancel all active sync work when an active session changes its Tor setting. `WalletSyncCoordinator.cancel()` cancels a coroutine, not the blocking socket/OkHttp call; cancellation is checked between portions of sync, and a fetch can issue multiple blocking requests. HTTP calls likewise have no common active-call cancellation mechanism. The `MainActivity` unauthenticated branch at line 142 also does not shut down services when authentication changes in the foreground; background shutdown is separately handled in `onStop`.

**Impact/prerequisites:** A request already using a direct connection can outlive a privacy-policy transition. The component reproduction proves policy revocation is not enforced at the transport boundary; a whole-app packet capture of a direct-to-Tor transition was not completed. Stopping the Tor daemon will usually break Tor sockets, but that does not establish cancellation of direct sockets or HTTP work. Do not interpret this as evidence of direct fallback on every ordinary Tor startup.

**Recommendation:** Give network sessions/routes a generation, revoke old transports and active HTTP calls on policy/profile/lifecycle changes, and validate authorization before each request. Cancellation must close blocking I/O. Distinguish allowed pre-PIN Tor bootstrap from an already-unlocked session becoming locked.

**Retest:** With controlled Electrum and HTTP endpoints, begin a multi-request operation, then enable Tor, enable offline, lock, background, or switch profiles. No further request may use the revoked route/session; cancelled calls must terminate promptly. Verify with packet capture, including direct mode.

### F03 — Medium: duress unlock overrides offline mode

**Evidence:** `GlanceApplication.kt:72` launches duress networking with `torController.setOffline(false)` and `setEnabled(true)`. `Phase7DecoyWallet.kt:147` submits sync with `offlineMode = false`, regardless of encrypted `securitySettings.offlineMode`. `MainActivity.kt:141` calls this branch on duress unlock. These are explicit production paths, not merely permissive defaults in a library.

**Impact/prerequisites:** After a user enables the global offline switch, a duress unlock can restart networking and query the decoy wallet. This does not demonstrate direct-IP disclosure or access to real-wallet keys. It violates the promised network kill switch and can reveal activity when the user expects none.

**Recommendation:** Enforce persisted offline mode in both profiles and throughout duress lifecycle handling. Resolve the spec conflict: §6.2 authorizes Tor-only decoy sync, while §6.7 still says a duress unlock never makes requests. Offline should remain an independent constraint; do not silently choose a different product contract.

**Retest:** Persist offline, terminate/restart, unlock with duress, and assert no Tor bootstrap, directory, fiat, Electrum, or Esplora traffic; repeat while backgrounding/foregrounding.

### F04 — Medium: encrypted preferences do not enforce Keystore use

**Evidence:** `core-security/src/main/kotlin/app/glance/wallet/core/security/SecurityPreferencesStore.kt:125` calls `AndroidKeysetManager.Builder().withMasterKeyUri(...).build().keysetHandle` without verifying Keystore use. The resolved dependency is Tink Android `1.20.0`. Its [version-specific implementation](https://github.com/tink-crypto/tink-java/blob/v1.20.0/src/main/java/com/google/crypto/tink/integration/android/AndroidKeysetManager.java) permits a cleartext keyset fallback when initial Keystore creation/self-test fails. Merely passing the URI does not guarantee wrapping.

**Impact/prerequisites:** On a device encountering that failure, encrypted preference ciphertext can coexist with its usable key in private preferences. Android sandbox/file encryption still protects against ordinary other apps; this is a loss of the required independent at-rest protection, not universal plaintext exposure. Preference data includes PIN verifiers and privacy/security settings. SQLCipher's separate database wrapping path is not shown to fall back this way. Combined with an exported private-data copy, the fallback exposes preference contents and permits offline PIN guessing. Fault-injected fallback was not reproduced on the emulator.

**Recommendation:** Fail closed before writing sensitive preferences unless the configured Keystore protection is actually active. Handle provider failures with a generic recovery/error surface and remove any uncommitted fallback material.

**Retest:** Force initial master-key generation/self-test failure and prove no readable keyset plus sensitive payload is left behind. Also verify normal reopening and failure with an existing unusable master key.

### F05 — Medium: Android device-transfer exclusions are missing

**Evidence:** The merged release manifest has `allowBackup=false`, but `app/src/main/res/xml/data_extraction_rules.xml` has no active device-transfer exclusions; `backup_rules.xml` is a template without exclusions. Android documents that some manufacturers do not apply `allowBackup=false` to device-to-device transfer, and missing rules enable that transfer category by default. [Android backup documentation](https://developer.android.com/identity/data/autobackup)

**Impact/prerequisites:** Platform/OEM transfer can copy databases, preference payload/keyset, and file-based network metadata. This does **not** establish decryption of correctly Keystore-wrapped data: the database key wrapper is under `noBackupFilesDir`, and device-bound Keystore keys do not become portable automatically. It does create an unwanted data copy and an unrecoverable/inconsistent restore risk; F04 makes the preference case more consequential. Actual OEM transfer was not exercised.

**Recommendation:** Explicitly exclude all app data domains from cloud and device-transfer mechanisms for the supported Android versions. Preserve the intentional password-encrypted export workflow as the portable recovery path.

**Retest:** Inspect compiled rules and exercise a supported transfer/restore: no wallet database, preference/keyset, or network-state files should be transferred; first launch must not crash or silently reset access to retained encrypted data.

### F06 — Medium: hostile network responses have no application size budget

**Evidence:** Electrum uses `BufferedReader.readLine()` at `BlockchainRepository.kt:300`; Esplora, fiat, and directory clients consume entire `response.body.string()` values. There is no application-level line/body budget or total response deadline. The local Electrum fixture returned an oversized scalar and the transport accepted it: `ELECTRUM_ACCEPTS_2MIB_RESULT=true`. No deliberate memory-exhaustion crash was attempted. An unrelated/notification stream can also keep `readResults` waiting without a total deadline.

**Impact/prerequisites:** A selected hostile server can consume memory/CPU, stall work, or crash the process. TLS authenticates the server but does not make it trustworthy. Size handling alone does not authenticate server-reported balances/history, which are already a provider trust assumption.

**Recommendation:** Apply protocol-appropriate maximum line/body/collection sizes, deadlines, and cancellation before parsing/materializing responses; cap work induced by remote data. Validate numeric ranges and identifiers before persistence. Keep cached screens usable when rejecting a response.

**Retest:** Oversized lines, chunked/compressed bodies, deep JSON, notification floods, invalid amounts/identifiers, and slow streams must produce generic bounded failures without unbounded allocation, busy work, or sensitive error content.

### F07 — Medium: directory expiration is lost in the persisted server pool

**Evidence:** `ServerPool.kt` persists `ServerPoolSnapshot` with endpoints/health but no signature, version, or expiry. `ServerPool` restores those endpoints directly. A probe accepted a valid manifest into a pool, advanced the validation clock beyond expiry, and recreated the pool from its state store. Result: `EXPIRED_MANIFEST_REJECTED=true; EXPIRED_POOL_ENDPOINT_RESTORED=true`. The signed document's rejection does not remove those restored endpoints. Separately, rollback comparison uses `store.load()`, which returns null for an expired manifest and therefore loses the previous version floor.

**Impact/prerequisites:** Endpoints can remain authoritative after their signed authorization expires, including after restart. Revoked or formerly compromised endpoints can continue receiving queries when directory refresh fails. This does not require or prove another app can edit Glance's private files.

**Recommendation:** Bind restored endpoints to currently verified directory provenance/expiry, retain a durable monotonic version floor independently of expiry, and define a safe bundled-endpoint fallback. Treat endpoint health as a cache, not a source of new trusted authority.

**Retest:** Expired cached endpoints must not silently remain selected, older signed versions must not become acceptable after expiry, and refresh failure must preserve the documented safe fallback.

### F08 — Medium: manual release can misstate the source commit

**Evidence:** `.github/workflows/release.yml:34` labels checkout “tagged source” but does not set `ref` to `inputs.release_tag`. Manual dispatch may check out one workflow ref, name/sign its APK using another tag, and pass `git rev-list` of that other tag to Zapstore at line 97. `--verify-tag` on GitHub release creation verifies that the tag exists, not that HEAD matches it.

**Impact/prerequisites:** A maintainer can accidentally publish a signed binary that is not built from its advertised source; this also weakens review/reproducibility and incident investigation. This is not an unauthenticated workflow injection finding. The workflow was reviewed, not dispatched.

**Recommendation:** Resolve and validate the requested tag, check out that exact commit, assert HEAD equality before building, and derive artifact metadata from that verified commit. Verify the selected Zapstore release asset and its hash against the artifact actually built.

**Retest:** Dispatch from branch A requesting a tag on commit B; every source input, provenance field, and artifact must consistently identify B, or the workflow must stop before signing/publishing.

## Additional hardening and product-contract issues

- **PIN counter overflow, low practical exploitability:** `AuthenticationPolicy.kt:16` increments an `Int` without saturation. An actual compiled-code probe restoring `Int.MAX_VALUE` produced a negative failure count and zero delay. Billions of normally throttled attempts are not a realistic route; this violates the stated permanent-cap invariant, not a practical six-digit-PIN brute-force demonstration. Saturate the counter and test boundary/negative persisted values. Wall-clock rollback/advance and restart throttling still need end-to-end device verification.
- **Erase failures, medium reliability concern:** `SecurityPreferencesStore.wipe()` ignores `File.delete()` and `SharedPreferences.commit()` return values. `TorController.stopDaemon()` also suppresses non-cancellation stop failures. These weaken the “success only after every target is removed” contract. Normal erase tests cannot prove failure-path correctness. Inject deletion, commit, and daemon-stop failures; remain on retry-only recovery until postconditions hold. Do not replace synchronous deletion commits with asynchronous `apply()` merely to silence the lint suggestion.
- **Wrapped-key crash consistency, low availability concern:** `AndroidKeystoreDatabaseKeyProvider.kt:37` writes directly rather than atomically; malformed/truncated header `require` failures are not converted to `DatabaseKeyRecoveryRequiredException`. An interrupted initial write or damaged file can produce a crash rather than the intended recovery surface. Test truncated/header-corrupt wrappers and interrupted writes; never silently replace a key protecting an existing database.
- **Sensitive input/backup password policy:** `BackupUi.kt:106` uses visual password masking without explicit password keyboard options; watched-key input uses a normal text field. Explicitly suppress suggestions/personalized learning where supported and inspect actual `EditorInfo` with a benign test IME. Visual masking is not an assurance about IME handling; no keyboard exfiltration was observed. Export accepts any nonempty passphrase and uses PBKDF2-HMAC-SHA256 at 310,000 rounds: a weak password defeats backup confidentiality through offline guessing. Improve guidance/validation without claiming an honest third-party keyboard can never access input.
- **Duress/private-material disclosure:** README's blanket “does not ... store ... seed phrases” conflicts with the generated seed implementation and §6.2. The decoy has a receive screen; funds sent there would be controlled by that seed even though Glance cannot sign. Explain this precisely. Seed protection and disposal require review independently of real-profile xpub privacy. The no-recovery/no-export language and deliberate phrase-reveal feature also need a consistent product contract.
- **Stealth is not an authentication boundary:** exported `MainActivity` can be explicitly launched without the calculator alias, revealing Glance's PIN surface. Authentication remains required. Do not promise concealment from package inspection, explicit activity launch, or forensic examination.
- **Memory and screen limits:** immutable PIN/mnemonic/passphrase strings cannot be reliably zeroized by the current code; screenshots can be intentionally enabled. These are limitations to disclose, not evidence that another ordinary app can read Glance's private heap. Verify screenshot/recents behavior across sensitive dialogs and the decoy phrase, not just the main activity flag.

## Dependency and distribution review

The resolved release graph contained 306 component coordinates (including platforms/metadata and transitives). OSV querybatch returned 306 results and zero advisory matches on 2026-10-05. No source or wallet data was submitted, only public package coordinates. This is **not** an all-clear for embedded native code, unknown vulnerabilities, or unindexed Maven packages.

Relevant resolved versions include Bitcoin KMP `0.31.0`, secp256k1 KMP `0.23.0`, OkHttp `4.12.0`, Okio JVM `3.17.0`, Tink Android `1.20.0`, SQLCipher Android `4.7.2`, and Tor resources `409.5.0`. DataStore core resolves to `1.3.0-alpha10`, despite the separate catalog preference version `1.2.1`; Compose UI resolves to `1.10.4`. Review resolved artifacts, not catalog labels alone.

SQLCipher Android 4.7.2 contains core SQLCipher 4.7.0, based on SQLite 3.49.1. The vendor subsequently published a 4.9.0 security update. This is an outstanding native-component update/reachability review, not a demonstrated SQL-injection or code-execution exploit in Glance. Reviewed Room queries bind data, and migration SQL is fixed; no attacker-controlled SQL execution path was established. [Android 4.7.2 note](https://discuss.zetetic.net/t/important-sqlcipher-for-android-4-7-2-update/6888), [4.7.0 baseline](https://www.zetetic.net/blog/2025/03/25/sqlcipher-4.7.0-release/), [4.9.0 security update](https://discuss.zetetic.net/t/sqlcipher-4-9-0-release-security-update/6920).

The release graph also includes desktop secp256k1 JNI packages alongside Android JNI. `core-crypto` declares JVM JNI as a production dependency. This contributes unnecessary platform-specific packaging and requires review alongside the desktop app-test native-loader failures; the audit did not infer a production key leak from it.

Release-readiness gaps:

1. Current workflow/tag triggers and metadata remain beta-oriented; release creation always uses `--prerelease`. A stable/main channel needs an intentional, tested policy and an appropriate version code.
2. CI installs JDK 17 / SDK 36 while the daemon config requests Java 25, JVM modules request 21, and the app compiles against SDK 37. This is a configuration mismatch to reconcile and prove on clean CI; a launcher-JVM grep does not prove the daemon version. Do not assume a clean runner reproduces the successful local build.
3. Actions use mutable version tags; dependency checksum verification/locking is not configured for the resolved graph, although the Gradle distribution has a pinned SHA-256. Pin reviewed action commits and add supply-chain verification appropriate to the release process.
4. Actual production signing-certificate continuity, keystore backup/custody, GitHub environment approvals/secret permissions, tag protections, Nostr signer custody, and Zapstore certificate linkage were not independently verified. The locally built release is unsigned; `apksigner verify` correctly rejects it.
5. Review Zapstore's actual selected asset, publisher identity, channel, signed event hash, and certificate linkage before distribution. No publish/check command with possible signing side effects was run. [Zapstore publishing documentation](https://zapstore.dev/docs/publish), [trust model](https://zapstore.dev/docs/trust-model).

## Verification record

All Gradle commands used the installed Android Studio JBR through `JAVA_HOME`, `--no-daemon --no-configuration-cache --console=plain`, and bounded worker counts. Initial sandbox access failures were retried with approved access; no production source was changed to make checks pass.

| Check | Result and limits |
| --- | --- |
| `verifyDependencyPins verifySensitiveDataLeakage` | Passed. These are regex/configuration checks, not whole-program information-flow analysis. |
| Fresh `:core-crypto:test` | 23 tests, 0 failures. Covers official derivation/address cases and rejects private/test-network imports. |
| Fresh `:core-network:test` | 37 tests, 0 failures, 1 skipped live-network test. |
| Fresh `:core-data:testDebugUnitTest` | 38 tests, 0 failures. |
| Fresh `:core-security:testDebugUnitTest` | 20 tests, 0 failures. |
| `:app:testDebugUnitTest` | 169 tests, 2 failures in `QrWatchedKeyScanTest`: Android native crypto loader on desktop JVM (`UnsatisfiedLinkError`, then initializer failure). No workaround applied. |
| `:core-data:lintRelease` | Passed; no issues. |
| `:core-security:lintRelease` | Passed with one synchronous-SharedPreferences-commit warning; see erase note above. |
| `:app:lintRelease` | Failed: unused `ic_launcher_monochrome` resource in current working tree; 5 hints. This is not classified as a security vulnerability. |
| `:app:assembleRelease :app:verifyReleaseLoggingStripped` | Passed; unsigned release APK produced. Verified absence of the three Glance/Timber types targeted by the task, not every possible native/dependency log sink. |
| Final APK manifest inspection | `allowBackup=false`; not marked debuggable. Launcher/main activities exported; Room/Camera services and initializer provider unexported. Exported profile-installer receiver requires `android.permission.DUMP`. |
| Local compiled-code probes | Public test signing key accepted; expired directory endpoints restored; route revocation bypass on reused socket; 2 MiB response accepted; retry-counter overflow reproduced. |
| Android network-security-config test | Passed: only the bundled onion HTTP exception permits cleartext through the Android policy. Raw socket TLS is a separate reviewed boundary. |
| Focused Android storage batch | 5/5 passed: encrypted preference persistence, usable store after wipe, wrapped DB key not plaintext, missing alias fails without replacement, separate real/decoy encrypted databases. |
| Broader Android security/persistence/crypto-import/migration batch | Completed: 37 tests, 32 passed, 5 failed; detailed triage below. Early attempts hit startup ANRs; they are not passing runs. The focused storage tests above overlap this batch and must not be added as distinct coverage. |
| OSV Maven-coordinate audit | 306 requests, 306 responses, no advisory matches. Native audit limitations above apply. |
| Narrow tracked-file secret scan | No PEM private-key header, Nostr secret-key pattern, or AWS access-key-id pattern found by the targeted scan; no tracked keystore files identified. This is not a complete historical secret scan. |

The temporary `recheck.init.gradle` forced fresh core test execution without changing tracked build logic. Its dependency inventory helper initially failed twice (task project lookup, then cross-project resolution lock); after registration on `:app`, `:app:auditReleaseDependencies` succeeded. These harness errors are not product defects. Existing cached test success was not counted as a fresh run.

### Completed device-run failures

The retained output is `build/security-audit/runtime-security.log`. The batch ran `SecurityOnDeviceTest`, `PersistenceOnDeviceTest`, `CryptoImportOnDeviceTest`, and `GlanceDatabaseMigrationTest` through `adb -s emulator-5580 shell am instrument -w -r -e class <comma-separated fully qualified classes> app.glance.wallet.test/androidx.test.runner.AndroidJUnitRunner`. Runner completion alone is not a pass; the output explicitly reports five failures:

1. `switchingToTheDuressProfileClosesTheRealDatabaseSession`: exceeded the 30-second total setup/unlock threshold at `SecurityOnDeviceTest.kt:145`. The preceding assertion that the real database had closed passed. This is a timing/performance failure on this emulator, not demonstrated cross-profile access; repeat on representative hardware and investigate before changing the budget.
2. `pendingTransactionsAppearBeforeAllConfirmedTransactions`: pending-row ordering differed from the test expectation (`PersistenceOnDeviceTest.kt:169`). Confirmed rows remained after pending rows. Resolve the intended pending ordering and its query/test contract; this is not evidence of confidentiality loss.
3. `transactionDetailIncludesTheSpecificAddressTimestampAndReactiveLabel`: a predicate looking for the second address's row found none. The fixture associates two addresses with one transaction, while `WalletScreenDao` groups page results by transaction ID. Triage the test/aggregation contract and verify detail completeness; this failure alone does not establish missing persisted history or a security exploit.
4. `v11AddressesStartWithDetailedUtxoTracking`: missing instrumentation asset `schemas/12.json`.
5. `v1BaselineSchemaValidatesThroughLatestMigration`: the same missing schema asset. Additionally, this “latest” test targets version 12 while production is version 14. Restore schema assets and test every supported path through the actual current version. These failures leave migration validation incomplete; they do not demonstrate migration data loss.

Passing normal erase tests do not exercise real Tor-cache deletion in all failure states: the security test substitutes a cleaner callback. The device batch therefore does not close the erase-failure and lifecycle coverage gaps below.

Runtime APK SHA-256 values:

- Debug app: `21856e3fcef3cfdfc12383d97acfdfed3ac2f8e67b483c41d1f1c2f5fa67b4e`.
- Instrumentation: `6f915681198350b948c19057757445c1619f1e2ac59c49b1432d858ebbc57aaa`.

## Coverage boundaries and release gates

| Area | Established | Still required |
| --- | --- | --- |
| Crypto/receive | Primitive delegation and official-vector unit tests; local receive data comes from derived-address storage | Hardware/release-APK receive verification, broader malformed-input corpus, supported minimum-API execution |
| Storage/authentication | Source review and focused encrypted-store tests | Physical hardware Keystore/biometrics, restart/reboot/time-change throttling, fault-injected preference fallback/deletion, profile/lifecycle race stress |
| Network | TLS hostname check configured for Electrum; unresolved SOCKS destination; centralized route source; local revocation flaw reproduced | Whole-app packet/DNS capture for all route transitions, offline/duress, HTTP cancellation, background/lock and malicious-provider scenarios |
| Logs/platform | No raw app logging/crash integration found; targeted release logging removal; manifest inspection | Full sensitive-flow logcat review, native/library logging, clipboard/IME/recents, screenshot dialogs, minimum/target API device matrix |
| Backups | AES-GCM with authenticated metadata and bounded import; wrong-password/tamper unit cases; real security state excluded from export mapping | Device export/import interruption, wrong-password rate/resource behavior, IME flags, strong-password UX and OEM transfer exclusions |
| Distribution | Local build/lint/test results and workflow defects | Exact signed candidate, signing/provenance checks, GitHub protections, Zapstore event/asset identity and publisher linkage |

Before a stable release: fix and retest F01–F08; resolve the medium storage/input hardening concerns or explicitly document reviewed residual risk; complete the outstanding native-library review; obtain green applicable tests/lint on a clean build; and repeat critical checks on the exact signed candidate. Restore test-first development for each remediation: demonstrate the failure, implement the smallest fix, and rerun the focused suite. Security-critical fixes require the contributor guide's explicit human review before merge.

No real wallet, production endpoint exploitation, signed publication, or physical-phone erase was used. The absence of current users reduces incident-response urgency but does not remove these release gates. Phase 10/11 completion was not marked.
