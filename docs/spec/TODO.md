# TODO: Multi-format xpub import

Goal: when format discovery reports `XpubDiscoveryResult.Multiple`, automatically import every detected active format in one atomic step; keep the explicit format choice only for `NoMatch`.

Background: a plain `xpub` used under several script types (e.g. Legacy + Native SegWit) currently requires two sequential imports. The data model already supports same-key/different-type coexistence; this change is a UI/persistence convenience plus a spec amendment.

## 1. Tests first (write failing tests before production code)

### `app/src/test/java/app/glance/wallet/FallbackFormatSelectionTest.kt` (rewrite)

- Begins unselected → `canConfirm == false`, `selected` empty.
- Toggling an available format on → contained in `selected`, `canConfirm == true`.
- Toggling the same format off → `selected` empty again, `canConfirm == false`.
- Toggling multiple formats → all present in `selected`.
- Toggling a format not in `available` → ignored (selection unchanged).

### `app/src/test/java/app/glance/wallet/AddWatchTargetImportTest.kt` (update)

- `Multiple` imports every discovered active format without showing a format-choice dialog.

### `app/src/androidTest/java/app/glance/wallet/AddWatchTargetPersistenceTest.kt` (extend)

- `persistWatchTargets(db, "hd", XPUB, listOf(LEGACY, NATIVE_SEGWIT))`:
  - persists two `WatchedKeyEntity` rows, one per script type;
  - labels suffixed per format: `"hd (LEGACY)"`, `"hd (NATIVE SEGWIT)"`;
  - duplicate detection checks existing watched-key material/script types before candidate windows, so re-import before sync adds only missing formats;
  - single-format call keeps the label unchanged (no suffix).

## 2. Implementation

### `app/src/main/java/app/glance/wallet/Phase7Wallet.kt`

- Keep `FallbackFormatSelection` and its single-choice dialog for `NoMatch` only.
- New `persistWatchTargets(database, label, source, scriptTypes: Collection<ScriptType>)`:
  - `require(scriptTypes.isNotEmpty())`;
  - normalize once via `normalizeWatchedKeyInput`;
  - parse/validate every requested type (single failure aborts);
  - query existing `WatchedKeyEntity` material/script types in the same transaction, then collect candidates and upsert only missing types;
  - label suffix `" (FORMAT)"` (same `name.replace('_', ' ')` rendering as the rest of the UI) applied only when multiple types are imported;
  - reuse the existing "already being tracked" message so `importErrorMessage` keeps mapping it.
- `Multiple` bypasses `FormatChoiceDialog`; `NoMatch` retains its existing copy.

Other entry points (`UnifiedImport.Address`, `UnifiedImport.Key`, `Match`, Expert options) keep using single `persistWatchTarget`.

## 3. Spec amendment

`docs/spec/Glance_Wallet_Spec.md`, §2 line 46 "Unified import amendment": automatic multiple-match imports persist one watched key per detected format with a per-format label suffix, inside one transaction; zero-match imports retain an explicit choice. Date the amendment 2026-09-13.

## 4. Verification

- `:app:testDebugUnitTest` (fallback selection + copy tests).
- `:app:compileDebugAndroidTestKotlin` for the extended instrumented persistence test; run on-device if an emulator/device is available, otherwise report as compile-only.
- `:app:lintDebug` and `:core-data:test` as sanity for the changed module set.
- Report commands and results; human review per AGENTS.md before merge.

## 5. Connected-test follow-ups

These failures were observed in the connected Android suite on 2026-09-15. They are test infrastructure or test-alignment work, not changes to wallet behavior.

- Package `core-data/schemas` as app `androidTest` assets under `schemas/`, so `GlanceDatabaseMigrationTest` can load the required v7 Room schema.
- Make the PIN-entry spacing assertion robust to device-pixel rounding while retaining the governed 24dp keypad gap (or increase the rendered gap slightly if needed).
- Update the initial secure-creation retry test for the auto-advance PIN flow: it must assert a usable keypad after failure, not the removed `setup_confirm` Enter button.
- Treat `TorLiveSmokeTest` as skipped when `GLANCE_LIVE_ADDRESS` is absent, rather than allowing Android's assumption reporting to fail the ordinary connected suite; retain the live test when explicit credentials are supplied.
- Dispose Compose content and wait for it to become idle before closing in-memory Room databases in wallet-content tests, preventing observers from reopening a closed database.
- Scroll `phase7_settings_scroll` to the expanded duress controls before asserting or clicking **Set up duress profile** in settings tests.
