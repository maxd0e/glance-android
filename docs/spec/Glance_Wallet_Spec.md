# Glance — Technical Specification & Build Plan (Final)

**App name:** Glance
**Domain:** glancewallet.app
**Package ID:** `app.glance.wallet`
**License:** MIT (permissive, no strings attached)
**Relationship to Sentinel:** Independent Kotlin reimplementation of the discontinued Sentinel watch-only wallet — not a fork, no shared code or infrastructure. (Working name during early planning was "Sentiens Wallet.")

This is the consolidated, build-ready version of the spec. It supersedes all earlier drafts.

---

## 1. Purpose & Non-Goals

Glance is a **pure watch-only** Bitcoin balance/transaction tracker for single-sig HD wallets. It replaces Sentinel's self-hosted Dojo dependency with a choice of public Electrum/Esplora servers, and is built entirely in Kotlin with a deliberately small, high-quality dependency footprint.

**Explicitly out of scope:**
- Private keys, seed phrases, transaction signing, transaction construction/broadcasting, fee estimation UI
- Multisig / general output descriptors (beyond the narrow Taproot case in §2)
- BIP47 payment codes (PayNym)
- Testnet/Signet in production builds (regtest only, dev builds)
- CSV/PDF export
- Non-English localization (English only for now; structured for i18n later)
- Light theme
- **Any percentage/gain-loss or price-change indicators anywhere in the app.** The wallet has no concept of cost basis (it never sees a buy-in price), so there is no meaningful "gain/loss" to show, and daily price-change percentages are deliberately left out; the fiat chart's dollar-green palette is denomination-only and never indicates performance.

**A note on the reference apps you shared (Strike, bittr, Joinstr):** these were used strictly for **visual and interaction-design inspiration** — dark theme treatment, the bittr-style balance formatting, Joinstr's bubble-packing visual. None of their actual features (buy/sell, price charts as market data, portfolio %, exchange functionality) are in scope here. Worth stating explicitly so nobody — human or AI — mistakes a screenshot's feature for a requirement.

---

## 2. Address / Key Support

| BIP | Role | Prefix | Script type |
|---|---|---|---|
| BIP32 | HD key derivation (all rows below build on this) | — | — |
| BIP44 | Legacy | `xpub` | P2PKH (`1...`) |
| BIP49 | SegWit-compatible | `ypub` | P2SH-P2WPKH (`3...`) |
| BIP84 | Native SegWit | `zpub` | P2WPKH (`bc1q...`) |
| BIP86 | Taproot | `xpub`* | P2TR (`bc1p...`) |
| BIP141 | SegWit transaction/witness structure | — | needed to parse BIP49/84 UTXOs correctly |
| BIP173 | Bech32 address encoding | — | used by BIP84 |
| BIP350 | Bech32m address encoding | — | used by BIP86 (Taproot) |

\* Taproot has no distinct SLIP-132 prefix like `ypub`/`zpub` — it's a plain `xpub` plus context telling the wallet to derive via BIP86 with x-only pubkeys. The **Add Watched Key** screen therefore needs an explicit **script-type selector** (Legacy / SegWit-compat / Native SegWit / Taproot) next to the key input. As an advanced alternative, also accept a full output descriptor string (e.g. `tr(xpub.../<0;1>/*)`), which is unambiguous.

**Unified import amendment (2026-09-13):** The selector requirement above is superseded by one Add Watch Target field for addresses, extended public keys, and supported single-sig descriptors. Addresses, `ypub`, `zpub`, and fully balanced `pkh(...)`/`sh(wpkh(...))`/`wpkh(...)`/`tr(...)` descriptors resolve locally. These descriptor templates accept optional standard key-origin metadata and a validated optional BIP380 checksum, but no other descriptor families or derivation patterns. A bare `xpub` uses a bounded Tor-routed external/internal gap-window scan across all four supported formats. One active format resolves automatically; multiple active formats are all persisted in one atomic import, with a per-format label suffix. Zero matches open a choice dialog because the format cannot be detected; it begins unselected, shows all four formats, and keeps Add disabled until the user explicitly selects one. A collapsed **Expert options** selector lets an informed user bypass discovery.

A user adds one or more independent extended public keys ("watched keys") or individual valid mainnet Bitcoin addresses ("single addresses"). Each HD key is scanned independently; a single address is reconciled only as that fixed address, with no derivation or gap scan. The home screen shows the **sum of all watch targets** as one total balance. A duplicate address already present in the local watch list is rejected to prevent double counting.

---

## 3. Backend / Network Model

- **Dual protocol support:** Electrum protocol (persistent TLS socket, JSON-RPC) and Esplora (REST/HTTP), selectable per server entry.
- **Public servers by default**, with a settings screen to add/edit/remove custom servers (host, port, protocol, TLS on/off).
- **No self-hosted indexing service** — index caching is entirely client-side (§4).
- All traffic — Electrum sockets and Esplora HTTP — is routed through the bundled Tor SOCKS proxy when Tor is enabled (default: on).

---

## 4. Address Scanning & Sync Strategy

- For each watched key, Room stores `highestUsedIndex` (per chain: external/internal), the derived-address list up to that index plus the gap-limit buffer, and each address's tx/UTXO cache.
- **Default gap limit: 20**, configurable in advanced settings.
- On refresh, Glance does **not** rescan from index 0:
  1. Loads `highestUsedIndex` from Room.
  2. Only queries the server for addresses from `highestUsedIndex` through `highestUsedIndex + gapLimit` that aren't already cached as "confirmed unused." The one exception is the current Receive address (the lowest unused external index): every explicit refresh rechecks it so a payment advances Receive without rescanning the remaining unused cache.
  3. If a new used address is found near the gap boundary, the window slides forward and the new highest index is persisted.
  4. Already-confirmed-used addresses' history/UTXO state refreshes from cache first, then reconciles against the server with a lightweight "any new tx since last-seen height?" check.
- Electrum's batched JSON-RPC is used to check multiple address statuses per refresh, reducing round-trips against public servers and avoiding rate limits. Esplora calls similarly only walk forward from cached state, never re-request already-cached indices.
- A single-address target persists exactly one address record. Each explicit refresh checks that one address and refreshes its UTXOs plus up to the newest 50 signed history rows through two sequential 25-row cursor pages when its status changes; it never derives either chain or applies the gap limit. Older history is cached locally in bounded 50-row batches (two cursor pages) when the user explicitly chooses Load more on the Transactions page, or once per target/range chart session when a partial dashboard chart needs older data. The chart-driven batch is Tor-routed, never runs offline, and stops after that one batch; further loading remains explicit, so high-volume addresses do not trigger an unbounded history download or repeat older requests.
- Wallet reconciliation is explicit: perform it once immediately after importing a watched key, then only from a pull-to-refresh gesture. Pulling on Home refreshes every watched key; pulling in a wallet detail refreshes only that key. Reopening the app must use cached data and never trigger a wallet-server sync.
- This is a purely client-side cache — no separate indexing backend is built or required.
- **Sync reliability note (2026-09-09):** fetching a block timestamp for `BlockTimestampCache` is chart-cache enrichment, not a prerequisite for address discovery. A network failure while fetching that timestamp must not pause sync or discard the newly fetched address history/UTXOs. The timestamp remains uncached and is retried on a later sync; cancellation still stops the sync normally.
- **Protocol failover note (2026-09-09):** production sync prefers the rotating Electrum pool, but must switch to the pooled Esplora clients for the remainder of a sync when the Electrum pool is unavailable. This prevents one exhausted protocol pool from leaving a partially discovered watched key with missing funds.
- **Sync-cache integrity note (2026-09-12):** a used address's status fingerprint, history, and UTXO replacement are committed in one database transaction. A failed write therefore leaves the previous fingerprint retryable rather than suppressing recovery with an incomplete cache.
- **Confirmation and pool note (2026-09-12):** each explicit sync obtains the current tip once and recomputes cached history/UTXO confirmation counts from persisted block heights; it does not download unchanged address histories merely to advance confirmations. Healthy endpoints rotate between syncs, while one sync pins its selected Electrum endpoint and reuses its TLS connection. Cancellation bypasses endpoint retry and health penalties.
- **Device verification (2026-09-09):** after both safeguards were installed, the affected imported wallet completed discovery and showed the previously missing funds.

---

## 5. Fiat Price Sources

One keyless provider is used: **mempool.space**. Current price uses `/api/v1/prices`; historical price uses `/api/v1/historical-price?currency=...&timestamp=...`, or the provider's one full-history response for a multi-bucket chart refresh. It supports **USD, EUR, GBP, CAD, CHF, AUD, JPY**.

When Tor is enabled, fiat requests use Mempool's fixed onion API over the bundled SOCKS route. Android permits cleartext only for that exact onion hostname; direct mode uses Mempool's HTTPS endpoint after the explicit Tor opt-out.

**Historical coverage:** Fiat chart buckets are persisted by provider, currency, and stable UTC bucket timestamp. ALL uses the finest UTC-aligned interval that fits within 120 points: daily while the range produces at most 120 daily buckets, weekly while it produces at most 120 weekly buckets, then monthly (capped at the latest 120 buckets). Mempool's full-history response supplies every chart range; Glance persists only selected buckets. A fully cached bucket set makes no later historical request. When Tor is enabled this request is onion-only—there is no clearnet fallback.

**Reliability note:** Fiat price/chart calls must fail gracefully — cache and show the last-known price rather than blocking or erroring the core wallet screens, since fiat display is a convenience layer on top of the actual on-chain balance, not the source of truth.

---

## 6. Security & Privacy Feature Set

### 6.1 Authentication
- Numeric PIN, with optional keypad scrambling and haptic feedback (both toggleable).
- Biometric unlock via `androidx.biometric` (`BiometricPrompt`) using `BIOMETRIC_STRONG`, with PIN as fallback. No biometric data is stored by the app — authentication is delegated entirely to the OS. The coordinator must also enforce the encrypted biometric-enabled preference; UI visibility alone is not an authorization gate.
- Persisted PIN retry deadlines use UTC wall-clock timestamps, not boot-relative elapsed time, so an active throttle remains meaningful across restart/reboot.
- PIN retry backoff must cap permanently at its maximum delay; high failure counts must never overflow into a shorter or zero delay.

### 6.2 Duress PIN
> **Authoritative implementation rule (2026-09-24):** Duress setup creates a separate encrypted BIP39 12-word (no passphrase) BIP84 Native SegWit wallet. This generated wallet is the sole decoy-only exception to Glance's watch-only rule: it has no signing, import, export, or recovery workflow. The decoy independently syncs its own balance, history, UTXOs, and derived addresses through bundled Tor only; it has no fiat/chart surface, no direct-network fallback, and no directory requests. Its unlocked settings surface may explicitly reveal the generated phrase. A synthetic balance, mocked transaction set, copied real-profile data, or caller-supplied decoy dataset is never valid production behavior. Removal deletes the verifier, mnemonic, decoy database, and wrapped key; re-creation always generates a new wallet and mnemonic.

- A second PIN, set separately from the real PIN, unlocks a **fully separate decoy profile** — a distinct encrypted store, not a filtered view or copy of the real one, so no real-profile watched keys, labels, settings, addresses, transactions, or balances are reachable from the decoy.
- The decoy profile is backed by its own real 12-word watch-only wallet, starts with no balance, and may fetch and synchronize its own derived public wallet data from the real Bitcoin blockchain.
- The decoy's initial zero-balance state must remain internally consistent, and synchronization failures must degrade gracefully without exposing sensitive network details.
- From the real profile's security settings, the user may remove the duress profile. Removal
  requires real-profile authentication and explicit destructive confirmation, then permanently
  deletes the duress verifier, generated mnemonic, decoy database, decoy database key, wrapped-key
  file, and all generated wallet records. It must not affect the real profile.
- Re-enabling duress requires a fresh setup flow: choose and confirm a new duress PIN, then create
  a new isolated database, independently wrapped key, and newly generated wallet. Old duress
  credentials and decoy data must never be reused. This control is available only
  from the real profile; the decoy profile exposes no security or profile-management controls.
- **Limitation worth documenting:** a second encrypted database file is itself potentially visible to forensic disk inspection — its existence, not its contents. This is the same inherent limitation hidden-volume schemes elsewhere (e.g. VeraCrypt) have. It doesn't make the feature pointless, but it isn't a guarantee against a sufficiently motivated device-level forensic search — worth an honest in-app note rather than implying full deniability.

#### 6.2.1 Decoy wallet scope
- The decoy must be a convincing, internally consistent wallet surface backed by its own isolated 12-word watch-only wallet. It may display the wallet's derived addresses, transaction history, UTXOs, and labels using the same production wallet components as the real profile.
- It starts empty, with no balance, and may fetch and synchronize its own public wallet data from the real Bitcoin blockchain. It must never access or reuse the real profile's extended public keys, descriptors, addresses, transaction IDs, labels, server configuration, balances, or history items.
- A duress unlock exposes ordinary wallet screens backed solely by the isolated decoy database. The decoy profile does not expose security settings, profile-switching controls, or UI that reveals duress mode.
- Phase 5 establishes the isolated encrypted decoy store, PIN routing, and decoy wallet provisioning. Phase 7 completes its production wallet presentation and blockchain synchronization behavior.

### 6.3 Stealth Mode
One optional disguise, switchable via `<activity-alias>` (swaps launcher icon + label without reinstalling):
- **Calculator:** a genuinely functional basic calculator. Tapping `=` five times consecutively (`=====`) opens Glance.
- This disguise is not an authentication or forensic boundary. Package inspection or an explicit launch of the exported main activity can reveal the Glance PIN surface; access still depends on authentication.

### 6.4 Street Mode
- Masks BTC/fiat balances (e.g., `•••• sats`) and, when active, also hides the balance chart's **y-axis values** (the line shape still renders, but no numbers are legible).
- Triggered **only** by a shake gesture (accelerometer-based) — no tap fallback.
- State persists (encrypted prefs) across app restarts/reboots until shaken again or toggled off in settings.

### 6.5 Screenshot Blocking
- Disabling screenshot blocking intentionally permits screenshots and may expose sensitive screens through platform recents behavior. PIN, mnemonic, and passphrase values held as JVM strings cannot be reliably zeroized after use.
- `FLAG_SECURE` set **by default ON** — toggleable in settings.

### 6.6 Data-at-Rest & Logging
- All local data lives in a **Room database encrypted with SQLCipher**. The SQLCipher key is generated on-device and wrapped by an **Android Keystore**-backed key (hardware-backed where supported) — never stored in plaintext, never transmitted.
- If a wrapped database key exists but its Android Keystore alias is missing or unusable, fail closed without generating a replacement alias. Show a non-sensitive recovery surface offering confirmed Erase All Data; the old encrypted store is unrecoverable on that device.
- Settings/secrets outside the DB use **`androidx.datastore:datastore-tink`** (keyed off `AndroidKeyStore`) — not `androidx.security:security-crypto`'s `EncryptedSharedPreferences`, which Google deprecated in April 2025.
- **No cleartext logging of xpubs, addresses, or txids** — enforced via a custom Timber tree that redacts mainnet and testnet Bech32 addresses plus other sensitive fields, and a CI lint check that fails the build on raw `Log.d`/`println` calls in security-sensitive modules. Release builds strip logging entirely via R8.
- **Erase All Data** wipes the Room DB, encrypted prefs, and any cached Tor state, behind an irreversible-confirmation dialog. It must attempt every cleanup target even after a failure; it may report success and enter setup only after all targets are removed. An incomplete wipe remains on a generic locked retry-erase surface.

### 6.7 Tor
- Bundled in the app (no Orbot dependency) via `05nelsonm/kmp-tor`, exposing a local SOCKS proxy that all network clients route through.
- Settings toggle, default **on**. Turning it off shows an explicit warning that the querying server will see the device's real IP.
- An Electrum hostname must be passed to the SOCKS socket unresolved so Tor, rather than the device resolver, performs DNS. On an initial cold launch, when Tor is enabled, bootstrap begins before the PIN/setup screen and that screen remains behind an establishing-Tor gate while startup is pending. If startup fails, PIN/setup may proceed but every app network path remains blocked until Tor becomes ready or the user explicitly confirms the persisted direct-mode opt-out. Later renewals, retries, and foreground reconnects keep the active screen visible and report the inline/menu `Connecting to Tor` state instead. Signed-directory refresh begins only after a real-profile unlock. A duress unlock may synchronize only the isolated decoy wallet through Tor when offline mode is off; it makes no directory or fiat requests and never uses direct routing. Offline mode prevents even Tor bootstrap and all decoy requests.
- Backgrounding or locking cancels active wallet sync work and stops the Tor daemon without enabling direct traffic. Direct clients are available only after the persisted, explicitly confirmed Tor opt-out; cancellation is checked between sync batches.
- **Offline mode** is an independent persisted, default-off kill switch in the Home connection menu. Its persisted value is loaded before any bootstrap; it stops or preempts Tor startup and rejects every Glance Electrum, Esplora, fiat, and directory request while retaining cached data. It bypasses pre-PIN Tor bootstrap; turning it off restores the separately persisted Tor/direct policy.
- The Home connection menu reports semantic status only (connected, connecting, unavailable, offline, or direct). It must not display or fetch an IP address: the local SOCKS address is not useful, and checking a Tor exit IP adds an unnecessary external request and a changing, correlatable identifier.

### 6.8 Privacy trade-off worth documenting
Moving from a self-hosted Dojo to public servers is a real trade-off: whoever runs the Electrum/Esplora server can see which addresses are being queried together and correlate them as one wallet, even over Tor — Tor hides *who* is asking, not *what* they're asking about. This is inherent to any watch-only wallet that doesn't run its own full node, not a flaw in this design; worth a short in-app privacy notice so users understand it isn't a like-for-like replacement of Dojo's privacy model.

Copying an address or transaction ID marks the Android clipboard entry sensitive where the platform supports it. Opening a transaction in a system browser is an intentional external privacy exception: each launch requires an explicit warning that it bypasses Glance's Tor connection and may expose both transaction ID and device IP.

---

## 7. Design System

### 7.1 Theme (dark only — no light mode)

| Token | Hex | Use |
|---|---|---|
| Background | `#0B0B0B` | App background — sampled directly from your logo file |
| Surface | `#1C1C1F` | Cards, sheets, list rows |
| Text — primary | `#F6F4EF` | Headlines, balances — sampled from the logo's white stroke |
| Text — muted | `#6E6B66` | Grayed leading zeros, secondary labels, dust UTXOs |
| Accent — "Mandarin" | `#E16D3E` | Buttons, active states, chart line, highlighted digits |
| Fiat chart — "Dollar green" | `#85BB65` | Fiat chart line and fill only; never a gain/loss or trend signal |
| Warning / Destructive | `#D9534F` | Erase-all-data confirmation, Tor-disabled warning — not used for price/performance |

**On the accent color:** taken directly from the logo you designed — the orange lightning bolt samples to `#E16D3E`, "Mandarin." Pulling the exact value from the artwork (rather than picking something merely "orange-ish") keeps the app UI and the brand mark visually identical.

The fiat chart alone uses dollar green to distinguish its denomination; it is not paired with red and never indicates gains, losses, or price movement, per §1.

### 7.2 Balance Formatting — one canonical format, no toggle
Every amount in the app (dashboard total, per-key balance, transaction amounts) uses the same hybrid format, modeled on the bittr reference. The compact label inside a UTXO bubble is the sole exception, defined in §9.2 so its value remains readable within the circle:

```
                    ₿ 0.00 006 839 sats
  └─ muted (#6E6B66) zeros ─┘└─ primary text, bold sats ─┘
```

- `₿` prefix, amount padded to 8 decimal places like standard BTC notation.
- Leading zeros with no significance render in the muted color.
- The trailing significant digits (the actual sat count) render in bold primary text.
- Always suffixed with `sats` — there is no separate BTC-only or sats-only display mode.
- Dust UTXO state is conveyed by its bubble visual treatment (§9); UTXO balance text always uses the standard amount color.

### 7.3 Charting approach
A hand-rolled Compose `Canvas` composable (a single `Path` over normalized data points) — a charting library isn't warranted for one simple line, and this keeps the minimal-dependency goal intact.

### 7.4 Hand-drawn illustration scope (v1)
The logo — a sketchy white line-art wallet with the Mandarin lightning bolt on black — sets the visual tone, but for v1 that "drawn" feel is scoped to **branding surfaces only**: the app icon, splash screen, and empty-state illustrations (e.g. "your watch list is empty"). Everyday functional icons (send, receive, settings, tab bar, etc.) use a standard clean icon set — not hand-drawn — for legibility and to keep this buildable solo. Typography stays clean and legible throughout, including headers — no hand-lettered fonts anywhere, numbers included. Extending the sketchy line style to icons, borders, or the chart stroke itself is explicitly deferred to a future update, not v1.

**Production pipeline for these assets:** source artwork is drawn as SVG (`docs/design/artwork/`, not part of the Gradle build) and imported into `app/src/main/res/drawable/` as VectorDrawable XML via Android Studio's Vector Asset tool (right-click `res` → New → Vector Asset → Local file). VectorDrawable is a **narrow subset of SVG** — no filters (`feTurbulence`, `feDisplacementMap`, blurs), no `<style>` blocks or CSS classes, no embedded raster images. Practical implications:
- The hand-drawn "wobble" must be baked into the path geometry itself (hand-drawn anchor points, or a filter effect flattened to a static path before export) — a filter-based wobble will silently disappear on import.
- Use inline presentation attributes only (`fill="#F6F4EF"`, not CSS classes).
- Keep the `viewBox` in plain unitless numbers (e.g. `0 0 140 120`), not `mm`/`pt`/`in`.
- Always check the Vector Asset dialog's live preview — it flags anything it had to drop.

### 7.5 App icon / adaptive icon
Android adaptive icons (API 26+) use a 108dp × 108dp canvas per layer, but different launchers mask it into different shapes (circle, squircle, rounded square, teardrop). Only the inner **66dp-diameter safe zone**, centered, is guaranteed visible across all of them.

- **Background layer:** solid `#0B0B0B` — no artwork needed.
- **Foreground layer:** the wallet+bolt mark, scaled to roughly **87% and centered** — measured directly against your logo file, its extremities (the clasp knob, the flap tip) sit only ~4% outside the safe zone at full bleed, so a modest, uniform scale-down is all that's needed, not a redraw.
- **Monochrome layer:** optional (Android 13+ themed icons, which recolor to match the user's wallpaper). Skip it for v1 since a themed icon overrides Mandarin with a system color — revisit later if Material You theming matters more than brand-color consistency.
- Generate all of the above — plus the legacy square icon (pre-Android 8) and round-icon variant — in one pass via Android Studio's Image Asset tool (right-click `res` → New → Image Asset → Adaptive and Legacy).
- **Separately:** the Play Store listing needs its own **512×512 hi-res icon** PNG, showing the **full-bleed, un-cropped** logo — Play Store applies its own corner rounding, not the adaptive-icon mask system, so this one bypasses the safe-zone constraint entirely.

---

## 8. Balance Chart

- Shown on the Home dashboard, directly under the total balance.
- **Timeframes:** 1D / 1W / 1M / 1Y / ALL, as fixed tabs.
- **Unit selection:** the dashboard's total sats balance and fiat balance are the chart controls; there is no separate Sats/fiat toggle. The active unit is displayed first in the large balance style. The inactive balance is shown below it and, when tapped, moves to the large position and redraws the chart in that unit:
  - **Sats mode** — the wallet's actual held balance over time, computed locally by replaying cached transaction history (inflows/outflows by confirmation time, using the `BlockTimestampCache` from §16). No external dependency.
  - **Fiat mode** — that balance converted to fiat at each historical point, using whichever provider is set in §5.
- **Cursor inspection:** tapping or dragging snaps to the nearest sample and temporarily replaces the active large dashboard balance with that sample's value; releasing restores the current balance. The chart has no separate selected-value display. A muted timestamp follows the marker above the canvas without moving or obscuring the plotted line. It includes time for 1D/1W and date only for 1M/1Y/ALL.
- **Settings toggle:** "Show balance chart" — default on, can be turned off entirely.
- **Street mode:** y-axis values hidden while active (§6.4).
- **One denomination-specific line color** — sats uses Mandarin and fiat uses dollar green `#85BB65`; neither changes with trend direction, and no percentage/delta is shown anywhere near it. This directly reflects §1: the wallet has no cost-basis data, so there's nothing meaningful to color-code.

---

## 9. Wallet / Key Detail Page — Transactions & UTXOs

Two tabs: **Transactions** and **UTXOs**.

### 9.1 Transactions tab
Chronological list — amount (bittr-format), confirmations, date, label. Unconfirmed rows appear before all confirmed rows; ordering within each group remains newest first. Confirmation status is conveyed with text/icon (e.g. "pending" in muted text).

### 9.2 UTXOs tab
Each real wallet's **Wallet settings** controls this tab's **List** or **Bubbles** view. It defaults to Bubbles, persists across restarts, and the tab itself has no view-mode control or redundant mode label. A newly added wallet inherits the most recently chosen view.

- **List view:** each row shows amount (bittr-format), address (truncated), confirmations, label. Tapping a row opens the UTXO detail sheet.
- **Bubble label:** inside a bubble only, use a regular-weight compact label with up to three significant digits: whole sats below 1,000; `k sats` below 1 million; `M sats` below `₿0.1`; and a `₿`-prefixed value at or above `₿0.1`. Scale the label from 8sp up to 18sp only while it fits in the circle; otherwise omit it. Tapping always reveals the canonical exact amount in the shared detail sheet.
- **Bubble view:** each UTXO rendered as a circle. Non-dust bubble geometry uses a shared visual scale: normalize each positive sat amount to the dataset's actual maximum, apply a 0.85 power scale, then interpolate **circle area** between 3 and 16 visual-radius units. This gives a uniquely largest UTXO a clearly dominant bubble while keeping medium-large bubbles distinct and smaller UTXOs around the outside. Dust bubbles all use a fixed 2.5 visual-radius units. Original sats remain authoritative for labels, details, sorting, and all wallet logic. This mirrors the Joinstr reference you shared — packed circles of varying size, with a tap-to-expand detail state.
- **Dust and pending styling:** Wallet settings exposes a per-wallet dust threshold in sats, defaulting to **5,000**; it accepts `0` to disable dust styling. In Bubble view, confirmed UTXOs below the threshold render muted (`#6E6B66`), while a zero-confirmation UTXO renders amber (`#F0B35C`) until its first confirmation, taking priority over dust styling. UTXO balance text in List view, bubble labels, and the detail sheet always uses the standard amount color. No other color-coding (age, per-key grouping, etc.).
- Tapping a bubble or a list row opens the **same UTXO detail sheet**: amount, full address, txid, confirmations, coin age.

### 9.3 Receive
A **Receive** button on an HD-key page generates the next unused address for that key — respecting the gap-limit/index-caching logic in §4, it does not force the index forward on repeat taps — and shows it as a QR code (via ZXing) with a copy button and a share button (native Android share sheet). The same address is shown on every visit until it actually receives an on-chain transaction; only then does the next tap advance to the following unused address. A single-address page instead exposes an **Address** action with the fixed address’s QR, copy, and share controls; it never implies that a new address can be generated.

### 9.4 Wallet settings, rename, and delete watched key
A settings icon at the top-right of the real wallet-detail page opens a dedicated **Wallet settings** screen. It allows the watched key's label to be renamed; an empty label retains the normal script-type fallback name. The screen's destructive section removes just this key — its cached addresses, transaction/UTXO history, and labels — without touching any other watched keys, shared transaction labels, or the PIN/encryption setup. Distinct from Settings' "Erase All Data," which wipes everything. Confirmation is a single "Are you sure you want to delete? Yes / No" dialog — no extra friction, since the key is public data and can always be re-added to fully recover the view. The decoy profile exposes no wallet-management controls.

### 9.5 Transaction detail
Opened by tapping a row in the Transactions tab: amount (bittr-format), date/time, confirmation count and block height, full txid with a copy button, the address involved, an editable label, and a "view in block explorer" link (opens whichever explorer is set in External Settings). Fee information and similar detail are left for a future update.

---

## 10. Screens

- **Home / Dashboard** — bittr-format total balance, balance chart (§8), watched-key list, street-mode masking; tapping the logo + wordmark (top-left, this screen only) opens Support / Donate (§11)
- **Add Watch Target** — select a watched key or a Bitcoin address. The key path accepts a pasted/scanned key + script-type selector (Legacy / SegWit-compat / Native SegWit / Taproot) or descriptor string; the address path accepts any valid mainnet Bitcoin address, including a local bare-address or BIP21 QR scan, and has no script-type selector. Scanning never imports private keys, seeds, transactions, or PSBTs.
- **Wallet / Key Detail** — Transactions tab, UTXOs tab using the wallet's persisted list/bubble selection, Receive button, and top-right wallet-settings entry for rename/delete and UTXO view, per §9
- **UTXO Detail** — bottom sheet, shared by list and bubble entry points
- **Transaction Detail** — opened from the Transactions tab, per §9.5
- **Support / Donate** — on-chain/Lightning QR + copy, per §11
- **Loading / Splash** — shown on cold start, per §12
- **Settings**
  - *Wallet*: PIN code, PIN scramble, haptic PIN, street mode
  - *External Settings*: server/explorer selection (+ custom), fiat source (§5), currency, show-chart toggle
  - *App Behavior*: screenshot block, stealth mode config, duress PIN setup
  - *Backup*: export/import encrypted backup
  - *Support*: same donate page as the Home logo tap (§11)
  - *About*: version
  - *Troubleshooting*: redacted error log, erase all data

---

## 11. Support / Donate Page

- Fixed on-chain and Lightning donation addresses — yours, not the user's — shown as a QR code (ZXing) plus a copy button, **one at a time** behind an On-chain / Lightning tab toggle.
- Hardcoded in the app for v1, with placeholder addresses until you swap in the real ones. Fetching this from glancewallet.app once the site is live would let addresses rotate without a new app release, but that's a future idea, not built now.
- Two entry points, both opening the same screen: tapping the **logo + wordmark** in the top-left of the Home screen (the only screen where the logo is tappable — every other screen uses a back-arrow header instead), and a **Support** row at the bottom of Settings.

---

## 12. Loading / Initial Sync

When Tor is enabled, cold start first shows a full-screen establishing-Tor screen before PIN/setup. A failed bootstrap may reveal PIN/setup but keeps all network clients fail-closed. After unlock, Home shows cached balances and watched keys immediately; reopening must not initiate wallet sync. Wallet-server reconciliation occurs after import or an explicit pull-to-refresh gesture, and routine sync never blocks cached content.

If Tor or a wallet server is unavailable, retain Home and its cached content. Show a generic retryable inline status without endpoint details; Tor-specific failures offer Tor retry, while wallet-server failures offer sync retry. Do not use a full-screen or Lottie sync gate.

---

## 13. Backup / Export / Import
Encrypted backup file containing watched keys (xpub/ypub/zpub/Taproot key or descriptor), fixed single addresses, labels, server config, and non-security settings. No private data exists to leak. Encrypted with a user-supplied passphrase (Argon2/PBKDF2-derived key), exportable/importable.

---

## 14. Tech Stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin, 100% | — |
| UI | Jetpack Compose + Material3 | — |
| Min / Target SDK | 24 / latest | — |
| Architecture | MVVM + Clean layers, Coroutines + Flow | Standard, testable, no heavy framework |
| DI | Koin (lightweight, no codegen) | Avoids Dagger/Hilt's annotation-processing weight; swappable for Hilt if preferred |
| BTC derivation | **bitcoin-kmp** (`fr.acinq.bitcoin`) + **secp256k1-kmp** | Corrected from bitcoinj during review — bitcoinj has no working Taproot/P2TR address generation (confirmed open and unimplemented on its own issue tracker since 2019). bitcoin-kmp explicitly implements BIP32/39/86/173/174/341/342/350, is genuinely Kotlin Multiplatform, and is production-proven in ACINQ's own Phoenix/lightning-kmp wallets |
| Electrum client | Hand-rolled thin client over `SSLSocket` | JSON-RPC over a socket is simple enough not to need a dependency |
| Esplora client | OkHttp | Battle-tested, built-in SOCKS proxy support for Tor routing |
| Fiat price | mempool.space (§5) | Keyless Bitcoin-native source; fixed onion API while Tor is enabled |
| Charting | Hand-rolled Compose `Canvas` | Avoids a charting-library dependency for one simple line |
| Animation | `com.airbnb.android:lottie-compose` | Renders the splash animation (§12); wraps the mature `lottie-android` renderer, same "high-quality dependency" bar as the rest of the stack |
| Local DB | Room + SQLCipher (`net.zetetic:sqlcipher-android`) | Industry-standard encrypted local storage on Android |
| Tor | **`05nelsonm/kmp-tor`** | Actively maintained (releases into 2026). Don't confuse with ACINQ's `tor-mobile-kmp`, which is explicitly deprecated |
| Biometrics | `androidx.biometric` | Official Jetpack library |
| Secrets/prefs | **`androidx.datastore:datastore-tink`** (keyed off `AndroidKeyStore`) | Corrected during review — `androidx.security:security-crypto` (`EncryptedSharedPreferences`/`MasterKey`) was officially deprecated by Google in April 2025. `datastore-tink` is an official first-party Google artifact purpose-built for this — a dedicated `AeadSerializer` wrapping DataStore in Tink encryption — not a manual DIY wiring of the two |
| QR codes | **`com.google.zxing:core`** + CameraX | ZXing locally encodes Receive and Support/Donate QR codes and locally decodes watched-key QR payloads; CameraX supplies the permission-gated preview. No cloud barcode service or scanner wrapper is used. |

Exact pinned versions for all of the above live in `gradle/libs.versions.toml`, not in this document — version numbers go stale the moment they're written down, so treat that file as the source of truth and this table as the "what and why."

---

### Android crypto packaging invariant

`core-crypto` remains a Kotlin/JVM module so derivation logic stays Android-free and JVM-testable. That does **not** package the native secp256k1 implementation into the Android app: `:app` must explicitly depend on the pinned `secp256k1-kmp-jni-android` artifact. The final APK must contain `libsecp256k1-jni.so` for every supported ABI.

JVM derivation tests alone are insufficient. Every change to Bitcoin dependency wiring, Gradle module type, or APK packaging must run a focused connected-device test that generates a non-secret public account key at runtime, parses it through `parseWatchedKey`, and derives a receiving address. The test must not embed or log extended key material. This detects a missing Android JNI backend, which otherwise appears in the import UI as a misleading generic decode failure.

---

## 15. Module Layout

```
glance/
├─ app/                 # Compose UI, navigation, DI wiring
├─ core-crypto/         # bitcoin-kmp wrapper: BIP32/44/49/84/86 derivation, address encoding — Kotlin Multiplatform, unit-testable without Android
├─ core-network/        # Electrum socket client, Esplora client, fiat price clients, Tor bootstrap
├─ core-data/           # Room entities/DAOs, SQLCipher setup, repositories
├─ core-security/       # Keystore key management, biometric, PIN/duress logic, stealth-mode alias switching
├─ core-common/         # Shared models/utilities
└─ docs/                # Spec, reference screenshots, and source design artwork — plain folder, NOT a Gradle module, not listed in settings.gradle.kts
   ├─ spec/
   └─ design/
      ├─ logo/
      ├─ artwork/       # source SVGs before VectorDrawable conversion, see §7.4
      └─ references/
```

---

## 16. Core Data Model (Room, sketch)

- `WatchedKey` — id, label, target type (HD key or single address), source string (xpub/ypub/zpub/Taproot key/descriptor or fixed address), scriptType for HD targets, dateAdded
- `DerivedAddress` — keyId, chain (external/internal), index, address, isUsed
- `AddressHistory` — addressId, txid, confirmations, blockHeight, valueSats
- `BlockTimestampCache` — blockHeight, timestamp (added on review — the §8 sats-mode balance chart needs to plot by time, not block height; fetched once per height via `blockchain.block.header` on Electrum or `/block/:hash` on Esplora, then cached so it's never re-fetched)
- `Utxo` — addressId, txid, vout, valueSats, confirmations
- `Label` — refType (address/tx), refId, text
- `ServerConfig` — protocol (electrum/esplora), host, port, useTls, isCustom
- `FiatPriceCache` — provider, currency, timestamp, price (for chart fiat mode)
- `DecoyProfile` — generated decoy wallet material: the BIP39 mnemonic and derived account public key (separate encrypted store from the real profile; contains no copied real wallet data)

---

## 17. Phased Build Plan (solo dev + AI-assisted)

Each phase is scoped to be handed to an AI coding agent as a discrete unit, with concrete steps and an unambiguous acceptance test — per §18, one phase per PR, tests before code.

[x] **Phase 0 — Scaffolding**
1. [x] Android Studio is installed. The local IDE uses bundled JetBrains Runtime 25; CI provisions Temurin JDK 17. Kotlin/JVM modules declare their required toolchains explicitly.
2. [x] The Empty Compose Activity uses package `app.glance.wallet`, minSdk 24, and compileSdk/targetSdk 37. All three SDK values are sourced from `gradle/libs.versions.toml`.
3. [x] All six Gradle modules appear in `settings.gradle.kts`: `app`, `core-common`, `core-crypto`, `core-network`, `core-data`, and `core-security`. `app` has `implementation` dependencies on every core module. `core-common`, `core-crypto`, and `core-network` are Kotlin/JVM libraries to preserve Android-free core logic; `core-data` and `core-security` are Android libraries.
4. [x] `gradle/libs.versions.toml` pins every declared version, including `secp256k1-kmp` 0.23.0 and `androidx.biometric` 1.1.0. No `VERIFY` markers remain. The current Gradle dependency graph resolves the crypto artifact; biometric remains catalogued for its Phase 5 integration.
5. [x] `docs/` is a plain, non-Gradle folder and contains the specification, source logo/artwork, and reference screenshots under the Section 15 tree.
6. [x] Git is initialized with an Android Studio-oriented `.gitignore`; the initial scaffold commit is `3dee1cc` (`Phase 0: scaffold Glance multi-module app`).
7. [x] `.github/workflows/verify.yml` runs on every push and pull request, provisions JDK 17 and Android API 37/build-tools 36.0.0, then runs `./gradlew --no-daemon build lint`.
   *Verified locally (2026-09-06):* `:app:lintDebug :app:assembleDebug` completed successfully, and the resulting debug APK was installed and launched on a physical API 29 device. The workflow has no recorded remote run yet because this local repository has no configured Git remote; its first remote run must be checked after push.

[x] **Phase 1 — Core crypto (`core-crypto`)**
1. [x] Add `bitcoin-kmp` from the version catalog and add `secp256k1-kmp-jni-android` explicitly to `:app` so its Android native library is packaged even though `core-crypto` is JVM-only.
2. [x] Implement the BIP32 HD derivation wrapper.
3. [x] Implement address generation for all four script types (§2): BIP44 (`xpub`→P2PKH), BIP49 (`ypub`→P2SH-P2WPKH), BIP84 (`zpub`→P2WPKH, BIP173/Bech32), BIP86 (Taproot `xpub`→P2TR, BIP350/Bech32m).
4. [x] Implement key/descriptor parsing for the Add Watched Key screen: plain extended key + explicit script-type tag for the first three types, plus basic `tr(...)` descriptor-string parsing for Taproot's advanced input path.
5. [x] Pull the official BIP32/44/49/84/86 test vectors from each BIP's own reference repo and write the unit tests **before** the corresponding implementation (§18, rule 1).
   *Done when:* all four script types derive the correct addresses against official test vectors, and the module has zero Android dependencies — fully testable in plain JUnit, no emulator required.
   *Verified locally (2026-09-06):* `:core-crypto:test` passed, including BIP32 public derivation, BIP44/49/84/86 address vectors, BIP173/BIP350 checksum validation, mainnet/watch-only import validation, and the restricted `tr(xpub/<0;1>/*)` parser.
   *Android packaging verification (2026-09-08):* A missing Android secp256k1 JNI dependency caused every valid public-key import to surface as a generic decode failure despite passing JVM tests. `:app` now packages `libsecp256k1-jni.so`; the focused `CryptoImportOnDeviceTest` passed on the SM-G960F / Android 10 device by generating a non-secret account key at runtime, importing it, and deriving a Native SegWit receive address.

[x] **Phase 2 — Persistence (`core-data`)**
1. [x] Add Room + `sqlcipher-android` + `androidx.sqlite`.
2. [x] Implement the schema from §16: `WatchedKey`, `DerivedAddress`, `AddressHistory`, `BlockTimestampCache`, `Utxo`, `Label`, `ServerConfig`, `FiatPriceCache`, `DecoyProfile`.
3. [x] Wire SQLCipher's support-factory into Room's database builder; the SQLCipher key is generated on-device and wrapped by an `AndroidKeyStore`-backed key (§6.6) — write this so it never touches a log statement or exception message in plaintext.
4. [x] Write a Room migration test even for the v1 schema, to establish the pattern before it's actually needed.
   *Done when:* CRUD works against every entity, and pulling the raw `.db` file off a test device (`adb shell` + `sqlite3`) confirms it's unreadable without the Keystore-wrapped key.
   *Verified locally (2026-09-06):* `:app:connectedDebugAndroidTest` passed on an SM-G960F (Android 10), including CRUD/cascade coverage for every Phase 2 entity, v1 schema validation through `MigrationTestHelper`, and rejection of the encrypted database by Android's standard SQLite engine.

**[x] Phase 3 — Network layer (`core-network`)**
1. [x] Electrum client: hand-rolled over `SSLSocket`, line-delimited JSON-RPC, batched status requests to limit round-trips (§4).
2. [x] Esplora client: OkHttp-based REST client.
3. [x] Fiat price client: keyless mempool.space behind `FiatPriceProvider` (§5), gracefully degrading to a cached last-known price on failure.
4. [x] Define one `ChainDataProvider` interface so Phase 4's sync engine doesn't need to know which protocol or server is active.
   *Done when:* both blockchain protocols fetch balance/history for a known test key against a real public server and the keyless Mempool provider returns a current price; Electrum↔Esplora remains a config change rather than a code change.
   *Verified locally (2026-09-06):* `GLANCE_LIVE_SMOKE=true :core-network:test` passed against the configured public Electrum and Esplora endpoints plus the keyless Mempool provider. The focused suite also covers TLS hostname verification, Electrum status batching, protocol-normalized address data, and cached fiat current/history fallbacks.

**[x] Phase 4 — Sync engine**
1. [x] Implement gap-limit scanning per watched key (§4): default gap limit 20, configurable.
2. [x] Implement the incremental index-caching logic — extend forward from the cached `highestUsedIndex`, never rescan from 0, batch-verify already-used addresses against cache first.
3. [x] Implement multi-key balance aggregation for the Home dashboard total (§2).
4. [x] Populate `BlockTimestampCache` (§16) as blocks are encountered, feeding the sats-mode balance chart (§8).
   *Done when:* re-running sync on an already-scanned key doesn't re-request already-cached indices (assert this via request-count checks in tests, not just eyeballing it), and adding a second watched key correctly sums into the dashboard total.
   *Verified locally (2026-09-06; updated 2026-09-12):* `:core-data:testDebugUnitTest`, `:core-network:test`, `:core-data:lintDebug`, and `:app:assembleDebug` passed. The focused sync suite covers incremental status reconciliation request counts, gap-window extension, multi-key balance aggregation, block-timestamp caching, current-receive rechecks, atomic snapshot persistence, and confirmation refresh. The on-device v6-to-v7 migration test remains to be rerun after installing the current instrumentation APK.

[x] **Phase 5 — Security core (`core-security`)**
1. [x] `AndroidKeyStore`-backed key management for the SQLCipher key from Phase 2.
2. [x] PIN entry: numeric keypad with optional scramble and haptics (§6.1).
3. [x] Biometric unlock via `androidx.biometric` `BiometricPrompt`, PIN as fallback.
   Current state (2026-09-07): the Phase 5 security implementation includes real-profile-only
   duress removal and fresh re-setup. Removal deletes the duress verifier, isolated decoy
   database, wrapped key, Keystore alias, and generated wallet records. Fresh setup creates a
   new duress PIN, wallet, database, and key. The debug build is installed on the
   SM-G960F / Android 10 audit device. The final automated rerun passed (23 tests, 0 failures)
   after adding end-to-end Erase All Data coverage; human security verification completed with
   no issues on 2026-09-07.
   Scope addition: duress PIN setup and re-setup must show a blocking progress indicator while
   PIN verification and isolated database/key creation are running, prevent duplicate submission,
   and show an explicit retryable error state if setup is interrupted before completion.
4. [x] Duress PIN (§6.2): a second PIN unlocking a **fully separate decoy database** — not a filtered view or copy of the real one — backed by its own provisioned 12-word watch-only wallet that starts with no balance. The decoy may fetch and synchronize its own public wallet data and exposes no security/profile-management UI. Complete its convincing wallet presentation in Phase 7.
5. [x] Screenshot blocking: `FLAG_SECURE`, default on (§6.5).
6. [x] Settings/secrets storage via `androidx.datastore:datastore-tink`'s `AeadSerializer` (§14) — not the deprecated `EncryptedSharedPreferences`.
7. [x] A custom Timber tree that redacts xpubs/addresses/txids from any log line, plus a CI lint rule that fails the build on a raw `Log.d`/`println` inside this module.
   *Done when:* the duress PIN opens a fully separate decoy DB while the real PIN opens the real one; the decoy is independently provisioned, starts with no balance, synchronizes only its own public wallet data, and cannot access real-profile data or expose security/profile-management UI; no sensitive string appears in logcat under a manual security-lint pass. Tests cover profile isolation, decoy data separation, and absence of security/profile-management controls. The production decoy wallet presentation is Phase 7 work.
   *Verified locally (2026-09-07):* `:core-security:check` passed, including the sensitive-logging gate and the focused failed/cancelled-duress-provisioning rollback tests. `:app:connectedDebugAndroidTest` passed on the SM-G960F / Android 10 audit device (23 tests, 0 failures), covering SQLCipher/Keystore persistence, real/decoy profile isolation, duress lifecycle, erased-data cleanup, and the decoy UI boundary. Human security verification on the installed debug build completed with 0 issues; the manual logcat, decoy-isolation, keystore/DataStore wipe, and destructive erase/re-setup checks were accepted. Tor-cache deletion remains Phase 6 work because no Tor state exists in Phase 5.

[x] **Phase 6 — Tor integration, server directory & pooled sync**
1. [x] Embed `05nelsonm/kmp-tor` (§14 — confirm you're not accidentally pulling the deprecated `tor-mobile-kmp`).
2. [x] Wire its local SOCKS proxy into all four network paths: the Electrum socket client, the Esplora OkHttp client, and both fiat price clients.
3. [x] Add the Settings toggle (§6.7), default on, with an explicit warning dialog when the user turns it off.
4. [x] Extend "Erase All Data" to also clear cached Tor state, the signed manifest, and endpoint health/backoff state.
5. [x] Use the pooled adapter in the production `SyncEngine` composition; persist the last valid signed manifest and endpoint health/backoff; refresh the manifest after real-profile unlock and periodically in the background over Tor only, verifying HTTPS/TLS, Ed25519 signature, version, and expiry.
6. [x] Host the signed directory at `https://glancewallet.app/.well-known/glance-server-manifest.json`; bundle the pinned Ed25519 public key. Preserve custom server entries when applying remote updates and keep protocol/server selection on the Tor-routed client path.
   *Done when:* all chain and fiat paths use the pooled, Tor-routed clients; manifest failures retain the last valid manifest; custom endpoints survive refresh; erase removes all directory/health state; and direct networking is available only after the explicit Tor opt-out.
   *Acceptance note (2026-09-13):* Mempool is the sole keyless fiat provider. Its Tor route is its bundled onion service; unavailable refreshes retain cached values and never block wallet data.
   *Verified locally (2026-09-08):* `:core-network:test`, `:core-data:testDebugUnitTest`, and `:app:compileDebugKotlin` passed. The production composition now exposes `NetworkClients.syncEngine(...)` backed by `PooledChainDataProvider`; file-backed manifest/health state is restored before refresh and erased with Tor state. Human security review of Tor routing and endpoint rotation: no bypass found; pooled Electrum/Esplora/fiat adapters obtain the current route at request/connection time, and direct mode remains gated by the existing explicit warning/opt-out.
   *Tor fiat follow-up (2026-09-13):* The fiat client now uses Mempool's onion service while Tor is enabled and HTTPS only after the explicit direct opt-out. Android permits cleartext solely for that exact `.onion` host; the connected-device smoke test verifies both current and historical onion requests. Until that opt-in device test is recorded, cached fiat values remain the safe degraded state. The signed directory schema is unchanged, so deployed signed manifests remain valid.
   *Tor hardening follow-up (2026-09-12):* Tor startup and shutdown now rethrow coroutine cancellation rather than publishing a misleading unavailable/disabled state. OkHttp clients are cached per immutable direct/SOCKS route, preserving connection reuse without mixing proxy configurations. Restored server-pool endpoints missing a health row are initialized as healthy rather than failing selection. Electrum destination resolution remains delegated to SOCKS, and re-enabling Tor in an unlocked real session resumes the existing Tor-only directory refresh flow. `:app:testDebugUnitTest --tests app.glance.wallet.TorReadinessTest`, `:core-network:test --tests app.glance.wallet.core.network.BlockchainClientTest`, and `:core-network:test` passed.
   *Audit remediation (2026-09-12):* Backgrounding now cancels registered wallet refreshes, sync checks coroutine cancellation between batches, and stopping the daemon preserves the persisted Tor/direct-routing policy rather than failing open. Public signed-manifest endpoints, including Electrum, require TLS. Shared network-state filenames eliminate erase drift; erase attempts all profile, preference, Tor, manifest, and health cleanup before reporting a generic retry-only incomplete state. Generated decoy-wallet provisioning is transactional and repeat-safe with UI error handling, and PIN backoff is bounded at its maximum delay. `:core-security:testDebugUnitTest`, `:core-network:test`, `:core-data:test`, `:app:testDebugUnitTest --tests app.glance.wallet.WalletSyncCoordinatorTest`, `:app:compileDebugKotlin`, and `:app:compileDebugAndroidTestKotlin` passed. Mandatory human review and on-device verification of Tor background cancellation, repeat decoy provisioning, and erase retry behavior remain required before merge.

**[x] Phase 7 — UI, Design System & Charting**

*Phase 7 status amendment (2026-09-25): Phase 7 is complete. The opt-in connected-device Mempool current/history smoke test passes through the bundled onion route with no clearnet fallback. The decoy design is intentional: it uses its own real 12-word watch-only wallet, starts with no balance, and may fetch and synchronize its own public blockchain data. It remains isolated from the real profile in a separate encrypted database. The full test suite passes, and the current design and artwork are accepted as-is. Street-mode masking remains Phase 8 work. This amendment supersedes earlier Phase 7 notes that described a fixed synthetic decoy dataset or listed UI/assets/device validation as incomplete.*

*Phase 7 completion verification (2026-09-25):* `TorLiveSmokeTest` passed on the SM-G960F / Android 10 target device with targetSdk 37 configuration. Mempool current and historical USD requests both succeeded through the fixed onion endpoint while bundled Tor was ready; the smoke test does not instantiate a clearnet client or fallback path. The live historical response exposed an array form for the single-timestamp request, so the client now accepts both the legacy object response and a usable returned history point. `:core-network:test --tests app.glance.wallet.core.network.FiatPriceClientTest` and the opt-in `:app:connectedDebugAndroidTest` run both passed.*

*Phase 7 completion amendment (2026-09-25):* The connected-device smoke-test requirement is complete. The Gradle opt-in now selects `TorLiveSmokeTest` when `GLANCE_LIVE_MEMPOOL_ONION` is supplied even without `GLANCE_LIVE_TEST_CLASS`; the test remains excluded when neither live-test flag is set. This supersedes the earlier Phase 7 notes below that describe the smoke test as still open or Phase 7 as in progress.

*Wallet settings update (2026-09-11): The real wallet detail page now exposes a top-right settings entry with a dedicated rename/delete screen. Deletion is confirmed with Yes/No, atomically removes only the selected watched key and its owned cached records/labels, retains transaction labels still referenced by another watched key, and returns to Home. The debug APK was installed on the SM-G960F / Android 10 device. This supersedes the prior Phase 7 note that key deletion confirmation remained required.*

*Security audit remediation (2026-09-12): Electrum destination DNS is now SOCKS-resolved, persisted PIN throttling uses wall-clock deadlines, biometrics require `BIOMETRIC_STRONG` and coordinator preference enforcement, and missing Keystore aliases fail closed into confirmed erase-only recovery. Tor/directory startup is restricted to real unlocked sessions. Browser explorer launches warn on every use, Android clipboard entries are marked sensitive where supported, and debug redaction includes `tb1` addresses. Focused core tests, app unit tests, and Android-test compilation passed; a connected-device suite was dispatched but had not produced a final report at the time of this update.*

*Sync repair (2026-09-11): Electrum's compact address history supplies transaction IDs and heights but not signed per-address sat deltas. A v4 refresh could therefore replace cached transaction amounts with zeroes while leaving UTXO-derived total balances intact, producing zero-valued transaction rows and a zero sats chart. Production sync now retains Electrum for batched status checks and UTXO reconciliation, then enriches any replacement history through the pooled Esplora client before persisting it. If that enrichment is unavailable, sync leaves the existing snapshot/status untouched so an explicit retry can recover rather than persisting zero-value history. Database v5 invalidates used-address statuses once more, causing the next explicit refresh after upgrade to repair affected v4 caches without a cold-start rescan. Focused sync regression coverage verifies both enrichment and the no-zero-persist failure path.*

1. Compose theme: the dark palette + Mandarin accent from §7.1, exposed as a single `GlanceTheme` composable.
2. The bittr-format balance composable (§7.2), reused everywhere an amount is shown — dashboard total, per-key balances, transaction amounts.
3. Hand-rolled Canvas line chart (§7.3, §8): timeframe tabs, sats/fiat toggle, disable-chart setting, street-mode y-axis masking, always the single Mandarin line with no trend-based coloring.
4. App icon (§7.5): generate the adaptive + legacy + round icon set via Android Studio's Image Asset tool from the scaled/centered logo and the solid `#0B0B0B` background; separately produce the 512×512 full-bleed hi-res icon for the Play Store listing.
5. Convert the hand-drawn branding SVGs (logo mark, donate-page heart, empty-state art) to VectorDrawable per the production notes in §7.4 — check the Vector Asset import preview for anything silently dropped.
6. Initial sync (§12): keep Home available while Tor connects and wallet sync runs; show small inline status/progress and retryable non-sensitive failures without blocking cached wallet content.
7. Build the screens: Home dashboard; Add Watch Target (HD key/script-type/descriptor or fixed mainnet address, with local BIP21 QR address extraction); Wallet/Key Detail (Transactions tab; UTXOs tab using the persisted per-wallet list/bubble selection and dust coloring; Receive for HD keys or fixed Address QR/copy/share for single addresses; delete-key; transaction detail); Support/Donate (on-chain/Lightning toggle, hardcoded placeholder addresses, heart artwork); Settings (all groups from §10).
8. After the real wallet UI is complete, build the convincing decoy wallet presentation from the same production components: the independently provisioned 12-word watch-only wallet starts with no balance, then may fetch and synchronize its own public addresses, transaction history, UTXOs, and labels through the real blockchain. It must never access or reuse real-profile wallet data or expose security/profile-management controls.
   *Done when:* the full cold-start-to-daily-use flow works end to end — splash → add a key of any of the four script types or a valid mainnet single address → sync → dashboard with a working chart → tap the logo for Support → choose the UTXO view in Settings → open Wallet Detail → flip Transactions/UTXOs → tap a UTXO or transaction for its detail sheet → Receive produces an HD QR or Address displays the fixed-address QR → delete the target and confirm it's actually gone. A duress unlock shows the finished independently provisioned decoy wallet presentation, backed only by its isolated database. The full test suite passes, the current design is accepted as-is, and only the opt-in connected-device Mempool onion current/history smoke test remains open.

**[x] Phase 8 — Stealth mode & street mode**
1. Icon/label aliasing via `<activity-alias>` for the genuinely functional Calculator disguise (§6.3; unlock gesture: `=====`).
2. Shake-gesture detector (accelerometer-based) driving street mode (§6.4): masks the balance and the chart's y-axis values, no tap fallback, state persists across app restarts until shaken again or toggled in settings.
   *Done when:* the Calculator functions as a real, usable decoy app and correctly unlocks into Glance; shaking toggles street mode (balance **and** chart) and the masked state survives a full app restart.

**[x] Phase 9 — Backup/export/import**
1. Backup format (§13): watched keys (any of the four script types or a descriptor), fixed single addresses, labels, server config, non-security settings — no private data exists to include.
2. Encrypt the backup file with a user-supplied passphrase via Argon2 or PBKDF2 key derivation.
3. Build the export and import flows in Settings.
   *Done when:* exporting on one install and importing into a clean install reproduces an identical watch list, labels, and settings — a round-trip test, not just "the button doesn't crash."

**Phase 10 — Hardening & QA**
1. Dependency audit against the pinned catalog — no floating versions slipped in during development.
2. Static analysis pass specifically for secret leakage (xpubs, addresses, txids in logs or crash reports).
3. Manual security review checklist covering everything in §6.
4. Regtest end-to-end validation (dev builds only — §1 excludes regtest from production).
5. Manual integration testing against your own real small-funds wallet — public test keys with realistic transaction history are scarce, so this is the primary way to validate sync/UTXO/chart behavior against genuine chain data before release.
   *Done when:* every item above has been run at least once and its result recorded — this phase produces a checklist artifact, not just code.

**Phase 11 — Release prep**
1. Play Store policy review — in particular, **stealth mode's icon/label swapping (§6.3) is a real risk against Google's Deceptive Behavior policy**, which explicitly covers apps whose icon/title don't accurately reflect their function, and has real rejection/suspension precedent industry-wide. Test against current enforcement before relying on Play Store as the primary channel for that specific feature.
2. Review Tor-bundling and financial-app policies more broadly.
3. Set up the Zapstore/GitHub release pipeline for interim builds — the resilient fallback regardless of how the Play Store review goes.
4. Release signing.
   *Done when:* at least one signed build is distributed through the non-Play channel, independent of Play Store's outcome.

---

## 18. AI Development Best Practices (kept short — the essentials only)

1. **Tests before code.** For every function in `core-crypto` and `core-security`, write the test (ideally against an official BIP test vector) before the implementation — never the reverse.
2. **One phase, one PR.** Feed an AI coding agent one phase from §17 at a time, not the whole spec — keeps context focused and diffs reviewable.
3. **No unreviewed security-critical merges.** Changes to key derivation, encryption, or duress/stealth logic need a passing test suite *and* a human diff review before merge.
4. **Pin every dependency version.** No floating versions — reproducibility matters more than always being on latest.

---

## 19. Assumptions & Defaults Log

For traceability, these are the judgment calls made where the spec didn't dictate an exact choice:

1. **bitcoin-kmp + secp256k1-kmp** for BIP32/44/49/84/86 derivation (corrected from bitcoinj on review — see §14).
2. **Koin** for lightweight DI, over Hilt or no framework.
3. **`05nelsonm/kmp-tor`** for bundled Tor (see §14 for why the exact fork matters).
4. **Separate decoy database** for duress mode, over a filtered view of the real DB.
5. **Gap limit default of 20.**
6. **Mandarin (`#E16D3E`)**, sampled directly from your logo file, as the sole accent color.
7. **Bubble radius uses max-normalized power scaling (α = 0.85)**, interpolated through circle area between the minimum and maximum radii, so the largest UTXO is visually dominant without aggressive logarithmic compression.
8. **Taproot import** via explicit script-type selector on a plain `xpub`, with descriptor-string input as the advanced alternative.
9. **mempool.space** as the sole, keyless fiat-price provider for v1, using its onion service whenever Tor is enabled.
10. **Hand-drawn styling scoped to branding only** (app icon, splash, empty states) for v1 — icons, borders, and chart stroke stay clean/standard, per your instruction, with fuller "drawn" treatment deferred to a future update.
11. **ZXing** for QR code generation (Receive, Support/Donate).
12. Donate addresses are **hardcoded** for v1 rather than fetched remotely, since glancewallet.app isn't live yet — worth revisiting once it is.
13. App icon: logo scaled to **~87%, centered**, against a solid `#0B0B0B` background layer, based on measuring the actual logo file against the adaptive-icon safe zone — not a guess.
14. Hand-drawn SVG artwork (logo, heart, empty-states) is produced as source SVG and converted to VectorDrawable — since that format doesn't support SVG filters, any "wobble" must be drawn into the path itself, not applied as an effect.
15. `androidx.datastore:datastore-tink` named specifically (not generic "DataStore + Tink") once its existence as an official first-party artifact was confirmed.
16. ZXing local encoding/decoding plus permission-gated CameraX scanning for watched-key QR import only; no cloud scanner service or generic QR import.

17. **Phase 7 decoy wallet decision:** v1 uses an independently provisioned real 12-word watch-only wallet for the decoy profile. It starts with no balance and may fetch and synchronize its own public blockchain data, while remaining fully isolated from the real profile in a separate encrypted database. It never reuses real-profile wallet data or exposes security/profile-management controls.

**Corrections made in the security/compatibility review pass:**
- Switched BTC derivation library (bitcoinj → bitcoin-kmp) — bitcoinj cannot generate Taproot addresses.
- Switched settings/secrets storage (`androidx.security:security-crypto` → DataStore + Tink) — the old API was deprecated by Google in April 2025.
- Added `BlockTimestampCache` to the data model — the balance chart's sats-mode series needs timestamps, not just block heights.
- Documented that duress-mode's second encrypted database is forensically detectable as existing, even if unreadable (§6.2).
- Flagged stealth mode's icon-swapping as a concrete Google Play Deceptive Behavior policy risk to test before relying on Play Store for that feature (§17, Phase 11).
- Replaced the prior CoinGecko-key recommendation with Mempool-only pricing through its onion service while Tor is enabled; fiat/chart calls continue to degrade gracefully to cached data.

App name **Glance**, package ID `app.glance.wallet`, domain `glancewallet.app`, and the MIT license are confirmed final.
