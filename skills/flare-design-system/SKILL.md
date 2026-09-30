---
name: flare-design-system
description: How to achieve a polished, task-focused Web3 mobile UI using Flare's design primitives as a reference. Use it whenever you build or restyle screens or components for a wallet or other onchain app (Compose or SwiftUI), even if Flare isn't mentioned. Covers light and dark themes, custom components, onboarding, transaction signing screens, number formatting, charts and motion.
---

# Polishing mobile UI with the Flare design system

Flare is a composable Web3 mobile toolkit combining reusable architecture, customizable design primitives, and complete functional flows. Its design system emphasizes clarity, tabular legibility, tactile feedback, and restrained surfaces.

These are defaults the user can override. For app structure and security, use `flare-architecture`.

## 1. Light and dark mode by default

Follow the system or user setting, with WCAG AA contrast in both:

| | Dark | Light |
|---|---|---|
| Canvas | `#000000` | `#FFFFFF` |
| Grouped surface | `#111111` | `#F6F7F9` |
| Elevated card | `#1A1A1A` | `#FFFFFF`, bordered |
| Hairline borders | `#242424` / `#343434` | `#E2E4E8` / `#D0D3D9` |
| Text | `#F5F5F5` / `#A3A3A3` | `#111418` / `#5E6470` |
| Positive / negative | Lime `#C4F564` / Coral `#FF766B` | Green `#00823B` / Red `#D92D20` |

## 2. Prefer custom components over Material components

Avoid stock Material 3 buttons, text fields, cards and sheets: their shadows, ripples and padding soften the interface.

- **Buttons:** Pill-shaped, at least 52 dp tall; primary, outline, buy, sell and destructive styles. Loading shows inside the button, not as a detached spinner.
- **Inputs:** 18 dp rounded corners, explicit hairline borders, decimal keyboard for amounts, inline validation.
- **Sheets:** Clean drag handle, header, scrollable body and sticky bottom actions.
- **Selection:** Segmented controls and chips in pill geometry with a smoothly sliding highlight.

## 3. Smooth onboarding & cold launch handling

- **Zero-flash cold launch:** An in-app splash identical to the system launch screen holds while services and vaults boot, then the brand mark animates and reveals the app already drawn beneath. No white/black flashes or layout shifts.
- **Progressive onboarding:** Offer "Create", "Import" and "Explore first" (browse public market data before creating an account). Forms go step by step, with actions pinned above the keyboard and inline hints under fields.
- **Secret inputs:** Masked, autocorrect off, screen capture blocked.

## 4. Biometric transaction authorization UI

- **Review before prompt:** A confirmation sheet shows assets, amount, network fee and slippage.
- **Authorize:** The primary action shows its loading state and raises the biometric prompt.
- **Calm completion:** One quiet receipt: confirmed hash, explorer link and a single Done button.
- **Plain language:** Name progress by the user's action ("Sending…") and state failures in one actionable sentence, never as raw chain errors.

## 5. Layout, typography & financial formatting

- **Screen gutters:** 24 dp horizontal; charts and hero elements bleed to the edges.
- **Touch targets:** Minimum 48 dp (44 dp absolute minimum).
- **Tabular figures:** Set tabular numerals (`tnum`) in the base text styles so prices, amounts and timestamps never jitter.
- **No color alone:** Pair positive/negative colors with a sign (+/−) or arrow.
- **Small amounts:** Show nonzero balances under half a cent as "<$0.01"; a change that rounds to zero is a flat "$0.00", never signed.
- **Count insets once:** Apply safe-area and keyboard insets only at the root, never again in nested screens.

## 6. Charts, motion & haptics

- **Charts:** Default to ~60 visible intervals. Page older history quietly on drag. Pinch zoom from 12 to 240 intervals. Fit the y-axis to visible data with 12% vertical padding, never anchored to zero.
- **Motion:** Short, purposeful transitions (120–240 ms). Damped springs with no bounce. Always honor reduced-motion settings.
- **Haptics:** Only for moments that matter: a gesture crossing its threshold, a value stepping, success or failure.
- **Silent refreshes:** Swap fresh data in place without "Updating…" spinners. Skeletons appear only on initial cold loads.
