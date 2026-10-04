# Glance UI Design Governance

**Status:** Required implementation guidance for the Phase 7 UI rework and all
subsequent UI work.

This document translates the approved UI reference images in
[`references/`](references/) into reusable implementation rules. It supplements
the product specification; it does not replace it. In a conflict, follow
[`docs/spec/Glance_Wallet_Spec.md`](../spec/Glance_Wallet_Spec.md), especially
Sections 1, 6, 7, 8, 9, 10, 12, and 17. Security and privacy requirements in
the specification always take precedence over visual polish.

## 1. Required visual character

Glance should feel like a quiet, focused, high-trust watch-only wallet:

- Nearly black, dark-only surfaces; the screen should never resemble a bright
  exchange dashboard.
- Information is sparse and intentional. Use generous breathing room instead
  of decorative separators, gradients, shadows, or panels within panels.
- Mandarin orange is a deliberate point of attention, not general decoration.
  It identifies the active choice, primary action, key data emphasis, and the
  chart line.
- Numbers are the visual hero. Wallet names and labels are clear but secondary;
  auxiliary metadata is deliberately subdued.
- Everyday icons are precise, small, clean line icons. The hand-drawn brand
  language belongs only to the logo, splash, and empty-state artwork.
- The interface is English-only and dark-only. Do not introduce a theme or
  BTC/sats display-format selector.

## 2. Source references

Each image is an approved composition reference for its named flow. Agents must
inspect the relevant image before reworking that flow, then implement the rules
below using shared Compose components rather than reproducing a screenshot with
one-off values.

| Reference | Governs |
| --- | --- |
| `glance_home.png` | Dashboard hierarchy, total balance, chart, timeframe tabs, watched-key cards, primary add-key action |
| `glance_add_key.png` | Import form, script-type selection, fields, paste affordance, primary action |
| `glance_transactions.png` | Wallet detail app bar, tab switcher, transaction-row hierarchy |
| `glance_transaction_details.png` | Transaction detail hierarchy, data card, editable label, explorer action |
| `glance_UTXO_blubble_view.png` | UTXO tabs, list/bubble mode control, packed bubbles, legend |
| `glance_settings.png` | Settings grouping, rows, switches, disclosure affordances, destructive separation |
| `glance_pin_entry.png` | PIN unlock rhythm, digit indicators, randomized keypad layout, biometric/backspace actions |
| `glance_stealth_setup_calculator_only.png` | Single-choice option cards, explanatory copy, selected-state outline/check |

The files show a tall, rounded device framing. That framing is presentation-only:
do not implement an artificial device bezel. Do preserve the apparent screen
insets, restrained density, and bottom gesture-navigation clearance.

## 3. Immutable tokens

Use the existing `GlanceTheme` token names where possible. Do not substitute
nearby colors or create screen-local palette values.

| Token | Value | Required use |
| --- | --- | --- |
| Background | `#0B0B0B` | Full-screen background |
| Surface | `#1C1C1F` | Cards, controls, grouped rows, sheets |
| Text primary | `#F6F4EF` | Headings, important values, enabled body text |
| Text muted | `#6E6B66` | Metadata, placeholders, leading insignificant zeroes, inactive controls, dust |
| Mandarin | `#E16D3E` | Primary CTAs, selected controls, active chart, selection outlines, small emphasis |
| Warning | `#D9534F` | Destructive/irreversible actions and warnings only |

Connection-status dots are the sole exception to the normal palette: green `#5CB85C` means Tor connected, yellow `#F2C94C` means connecting, and Warning red means unavailable/off. They are compact dots only, never color text, performance, amounts, or controls; every status is also named in text and accessibility semantics.

Additional token rules inferred from the references:

- The background remains visually flat. Avoid gradients and elevation shadows;
  surface separation comes from the `Surface` color and rounded geometry.
- Major cards and grouped settings containers are broadly rounded (roughly
  16dp); compact input/control elements are slightly tighter (roughly 10-12dp).
  Keep radius values centralized as tokens.
- The home primary CTA is a fully pill-shaped Mandarin button. Other controls
  may be rounded rectangles; do not make every button a pill.
- Use a consistent 16dp-ish horizontal screen gutter, with larger vertical gaps
  between information groups than within a group. Preserve the airy reference
  composition on compact devices rather than shrinking type and touch targets.
- The standard control/row height must remain accessible (minimum 48dp touch
  target); the PIN keypad uses noticeably larger square-ish targets.
- Use subtle dividers only inside dense data cards where they clarify row
  boundaries. Do not add page-wide rules or card outlines merely for structure.

## 4. Typography and amount treatment

Typography is clean, modern, and legible; it is never hand-lettered. Favor a
small hierarchy of weights rather than many font sizes.

- Screen titles are centered in the top bar where the reference shows them;
  wallet detail titles use this treatment. Home uses the wordmark/name at top
  left rather than a centered title.
- Primary row titles use strong primary text. Section labels, captions,
  placeholders, dates, confirmation counts, key prefixes, and supporting copy
  are muted and visibly smaller.
- Important numeric values use primary text and a stronger weight. Keep signs
  (`+` / `-`) with their amounts; signs are informational, not color-coded.
- Canonical amounts must use the hybrid formatting from specification §7.2:
  BTC symbol, eight-decimal padded BTC value, muted insignificant leading
  zeroes, bold significant digits, and a `sats` suffix. Use it consistently on
  Home, key cards, transaction rows/details, and any amount-bearing view.
- Fiat is subdued convenience information beneath/on behalf of a BTC amount.
  It must not dominate the balance, imply market performance, or prevent
  on-chain content rendering.
- No red/green transaction or price semantics. A received amount is identified
  by text and `+`, and a sent amount by text and `-`, not performance colors.

### Address presentation

Receive and on-chain Support addresses use one shared full-address treatment;
they are never truncated. Render them in a monospace face as four-character
groups separated by spaces, with the final group left at its natural one to
four characters and wrapping naturally across lines. Preserve the valid source
casing: Bech32/Bech32m addresses remain lowercase while Base58 address case is
never changed.

- Alternating groups use bold `Text primary`, then regular `Text muted`.
- The first data group after a `bc1q` or `bc1p` prefix is bold Mandarin; for
  formats without either multi-character prefix, the first group is bold
  Mandarin.
- The final two characters are always bold Mandarin, overriding the normal
  group treatment when necessary.
- Use a generic accessibility label rather than exposing the full address to a
  screen reader. Lightning invoices retain their own payload treatment.

## 5. Global navigation and interaction grammar

- Use a simple top app bar on secondary screens: back arrow on the leading edge,
  a centered title, and an optional trailing overflow/settings action. Icons
  must have content descriptions.
- Keep back navigation visually quiet: a clean white/muted line arrow, never a
  filled button unless context demands it.
- Use an overflow menu only for contextual wallet actions; use a visible
  top-right settings affordance for wallet-level settings as defined by the
  specification.
- All selected controls read clearly without relying only on color: filled
  Mandarin state, a checkmark, text weight, or an outline must supplement it.
- Prefer inline, non-sensitive sync/offline feedback that preserves cached
  content. Do not replace an available dashboard with a blocking loader.
- The Home connection sheet owns the persistent **Offline mode** switch. Its
  red status dot is paired with explicit “Offline” text and muted cached-data
  copy; Tor controls remain visible but disabled while Offline mode is active.
- Respect safe drawing insets and leave bottom clearance for gesture navigation.

## 6. Dashboard: Home

Reference: `glance_home.png`.

The dashboard is a vertically scrolling, single-column composition in this
order:

1. Top identity row: **Glance** at left; compact utility actions at right
   (support/brand entry and settings in the reference).
2. Centered aggregate wallet balance, using canonical hybrid formatting at the
   largest numeric scale on the screen. The BTC glyph and meaningful trailing
   digits receive Mandarin/primary emphasis as shown; muted zeroes recede.
3. Optional, smaller muted fiat equivalent immediately below the total.
4. A chart region with generous empty space and a single thin Mandarin path.
   The Home reference permits one restrained, solid low-opacity Mandarin area
   beneath that path. It has no gradient, grid, axes, price-change badge, or
   competing series.
5. A centered timeframe selector directly beneath: `1D`, `1W`, `1M`, `1Y`,
   `All`. The active range is Mandarin text; inactive choices are plain muted
   text. Follow the specification for chart value/toggle behavior.
6. A muted **Watched keys** section label.
7. Separate rounded cards for each watched key. A card presents the label on the
   left, a truncated muted extended-key identifier beneath it, and the key's
   canonical balance right-aligned. Do not expose a full xpub/descriptor.
8. A centered, pill-shaped Mandarin **+ Add key** action after the list.

The screen must still look composed with no keys, unavailable fiat, disabled
chart, or cached-only data. Empty states may use approved hand-drawn artwork;
normal data screens must not.

## 7. Forms and choice controls

Reference: `glance_add_key.png`.

- Form labels sit above their controls in muted text. Required inputs do not
  need loud asterisks; validation is clear, specific, and non-sensitive.
- Inputs are filled `Surface` rounded rectangles with muted placeholder text.
  Avoid an outlined Material text-field appearance that conflicts with the
  references.
- The watched-key/descriptor input has a trailing paste affordance. It must be
  accessible and must never log clipboard/key contents.
- Script type is a 2×2 grid: Legacy, Native SegWit, SegWit-compat, Taproot.
  Each option names the resulting address family on a smaller secondary line.
  The selected choice is Mandarin-filled; all unselected choices remain dark
  surface tiles. Preserve explicit selection for plain xpub imports.
- The optional label follows the selector. The full-width primary submit action
  sits below with clear separation and is Mandarin-filled.
- Disabled states must remain understandable and should not look like enabled
  Mandarin CTAs. Validation/error treatment must be accessible but reserve red
  for actual warnings/errors.

## 8. Wallet detail, transactions, and transaction detail

References: `glance_transactions.png`, `glance_transaction_details.png`.

- A wallet detail starts with the standard back/title/overflow app bar. Its
  primary content selector is a full-width, two-segment surface control:
  **Transactions** and **UTXOs**. The active segment is Mandarin-filled; the
  inactive label is muted.
- Transaction history uses individually separated rounded surface cards, not a
  dense table. Each card has a bold transaction direction at left, muted date
  and confirmations/status below, and a right-aligned signed canonical amount.
  Pending is a textual status and must not be colored as price movement.
- A transaction detail centers the direction label above the large signed amount
  before showing its facts. The direction label may use Mandarin emphasis.
- Facts appear in one rounded surface card: left-side muted keys; right-aligned
  primary values; restrained internal dividers. The reference order is date,
  confirmations, block height, txid, and address.
- Long identifiers are abbreviated for display. Copy buttons sit immediately by
  the abbreviated txid/address and provide an accessible label and confirmation.
  Do not disclose values in logs, errors, or analytics.
- The editable **Label** field is separated from the facts card and has a clear
  placeholder. A large, full-width secondary surface action opens the selected
  block explorer, accompanied by a standard external-link icon.

## 9. UTXO representation

Reference: `glance_UTXO_blubble_view.png`.

- The wallet-detail Transactions/UTXOs segmented control remains in place.
- In UTXOs mode, a secondary compact text selector offers **List** and
  **Bubbles**. The selected mode is Mandarin text; the inactive mode is muted.
  It is a view mode, not a primary segmented pill.
- Bubble mode uses a single large rounded surface canvas/card with deliberately
  spacious packing. Normal UTXOs have a thin Mandarin outline and a low-key
  dark/translucent fill; dust UTXOs are muted gray outlines/fills.
- Bubble radius is proportional to `sqrt(sats)` exactly as the specification
  requires, so area represents value. Do not encode value, age, wallet, or
  confirmation state with additional colors.
- Place a compact legend below the canvas using small circular swatches:
  Mandarin for UTXO and muted gray for `dust, under 5,000 sats`.
- Bubble interactions, if any, must preserve readable detail access and never
  make a wallet's balance inferable from a meaningless decorative geometry.

## 10. Settings architecture

Reference: `glance_settings.png`.

Settings are a vertical list of explicitly named groups. Use muted group labels
above rounded surface containers, with aligned rows and restrained internal
dividers. Keep the reference order unless the product specification changes it:

1. **Wallet:** PIN code, Scramble PIN entry, Haptic PIN, Street mode.
2. **External settings:** Server, Fiat source, Currency, Show balance chart.
3. **App behavior:** Screenshot block, Stealth mode, Duress PIN.
4. **Backup:** Export backup, Import backup.
5. **Support:** Support Glance.
6. **About:** Version.
7. **Troubleshooting:** Share error log, Erase all data.

Rules for settings rows:

- A boolean uses a compact trailing switch. Enabled is Mandarin; disabled is
  muted gray. The label remains primary in both states.
- A drill-in row shows its current concise value in muted text and a trailing
  chevron. Do not use a switch for an action requiring a confirmation/warning.
- Ordinary action rows use a chevron; support may use a small Mandarin heart;
  destructive rows use Warning text and a Warning chevron only.
- Destructive controls are isolated at the end and must still follow the
  specification's explicit irreversible-confirmation flow.
- The decoy profile must never show security/profile-management controls even
  if shared UI components are used.

## 11. PIN unlock

Reference: `glance_pin_entry.png`.

- Center a compact Glance label, a muted **Enter your PIN** instruction, and a
  row of six status dots in the upper half of the screen.
- Filled dots are Mandarin; unfilled dots are muted. Never show PIN digits.
- Place the keypad in a balanced 3-column grid with generous gaps and large,
  rounded dark-surface keys. The numerals are centered primary text.
- When PIN scrambling is enabled, the numeric arrangement must be randomized
  while preserving the visual grid and its accessibility semantics.
- The final row reserves leading/trailing actions for biometric unlock and
  backspace, with the last numeric key centered. They use clean icons and
  content descriptions; biometric availability follows platform/security rules.
- Error feedback must not reveal whether a real or duress PIN was attempted.

### Shared PIN-entry composition

The unlock reference establishes the reusable treatment for every numeric-PIN
step: initial PIN creation, PIN confirmation, Settings-initiated duress-PIN
creation/confirmation, and PIN unlock. These
screens must use the same full-screen `#0B0B0B`
background and the same centered 280dp, three-column keypad; they must never
fall back to a narrow dialog-like keypad or a separate form visual language.

- The header is intentionally prominent: use primary-text headline sizing for
  the step title (the unlock title is the **Glance** wordmark) and a larger
  muted body instruction beneath it. The brand wordmark has clear hierarchy
  over its instruction.
- Keep a deliberate vertical rhythm: 8dp from title to instruction, 26dp from
  instruction to PIN dots, and 24dp from dots to the keypad. Do not collapse
  these gaps to fit unrelated controls; preserve the airy, calm composition.
- PIN dots stay six circular indicators, 11dp each with a 10dp gap. Filled is
  Mandarin and unfilled is muted; PIN digits are never rendered as text.
- Keypad rows use 86dp × 52dp rounded `Surface` keys with 10dp gaps. Numerals
  use 18sp primary text. This is a shared accessible target size, not a
  screen-local adjustment.
- The unlock final row uses Mandarin biometric and white backspace icons, with
  the zero centered. The biometric icon is Mandarin only when that action is
  available; backspace always matches the primary-white numerals.
- Creation, confirmation, and duress-PIN steps auto-advance immediately after
  the sixth digit, just like unlock. Their final row preserves the same grid
  geometry with a centered zero and backspace; do not show an **Enter** key or
  use it as a separate CTA.
- Validation feedback sits below the keypad with clear separation and does not
  alter the header/keypad geometry. Duress setup is available only from
  Settings after the real profile is unlocked.

## 12. Stealth choice screen

Reference: `glance_stealth_setup.png`.

- Use the standard back/title app bar, then a concise muted explanation below.
- Present Off and Calculator as generous stacked rounded surface cards;
  each is fully tappable and semantically a single-choice option.
- The selected option remains a dark surface card with a Mandarin outline and
  a trailing Mandarin checkmark. Do not depend on a Mandarin fill alone here.
- Calculator includes muted explanatory copy for its five-consecutive-`=`
  unlock behavior. Copy must remain honest: the disguise must be functional,
  as required by the specification.
- This screen is security-sensitive: preserve the exact alias/launch behavior
  specified for stealth mode and seek the mandated human review.

## 13. Charts, loading, privacy, and accessibility

- The balance chart is hand-rolled Compose Canvas content: one Mandarin path
  with the Home-only restrained solid Mandarin area beneath it; no library-style
  axes/grid/chrome. The spec governs its range, fiat/sats,
  caching, gesture marker, and unavailable-state behavior.
- Street mode masks every balance and chart Y-axis value. It is shake-only in
  production and must retain accessible, non-sensitive state descriptions.
- Cached wallet data always renders before a refresh. Progress and failures are
  compact, generic, and non-sensitive; never show a server endpoint, watched
  key, address, txid, or route detail in user-visible errors.
- Use text labels in addition to color for every state. Maintain sufficient
  contrast for primary/muted text and touch targets of at least 48dp.
- Provide meaningful content descriptions for all icon-only controls, toggles,
  copy actions, chart controls, and dynamic balance/status content. Ensure
  screen-reader phrasing does not accidentally defeat street-mode masking.

## 14. Rules against visual drift

Future agents must not make any of these changes without an approved product and
design decision recorded in the specification or this document:

- Light theme, gradients, glass effects, neon color schemes, or arbitrary new
  accent colors.
- Green/red performance indicators, percentage change, gain/loss, trading,
  send, receive-to-spend, fee, signing, or broadcast UI.
- BTC/sats display-mode preferences or inconsistent amount formatting.
- Busy exchange-style dashboards, charts with trend-dependent colors, filled
  chart areas, decorative data widgets, or multiple competing chart series.
- Full xpubs, descriptors, addresses, txids, PINs, or server credentials in
  logs, errors, screenshots, accessibility output, or unredacted UI listings.
- Hand-drawn functional icons, borders, typefaces, or chart paths in v1.
- Changes that allow a duress surface to reveal real data, security controls,
  profile-management controls, or live network behavior.

## 15. Mandatory UI-change checklist

Before implementing a UI rework or new screen:

1. Read the applicable product-spec section, this document, and the relevant
   reference image(s).
2. Identify reusable theme/component primitives; add or adjust those before
   screen-local styling.
3. Write/update the focused test before changing behavior, as required by
   `AGENTS.md`. Do not backfill the test after implementation.
4. Check all relevant states: populated, empty, loading/syncing, cached/offline,
   unavailable fiat, disabled, error, street mode, and decoy mode where in scope.
5. Verify accessibility labels, touch targets, contrast, truncated sensitive
   identifiers, and `FLAG_SECURE`/privacy implications.
6. Run the focused module tests and visually inspect the affected screens on a
   representative compact Android device/emulator before handoff.
7. Obtain explicit human review before merging security-critical UI changes
   (PIN, duress, stealth, Tor/privacy settings, deletion, or sensitive display).

When a reference conflicts with an implemented, finalized specification detail,
document the conflict and request a decision. Do not silently redesign the
product.
