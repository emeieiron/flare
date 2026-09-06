# Flare Trading UI — Design System Spec

## 1. Foundations

### 1.1 Color Tokens

```css
:root {
  /* Background */
  --bg-canvas: #000000;
  --bg-surface: #0D0D0D;
  --bg-elevated: #171717;
  --bg-hover: #202020;

  /* Borders */
  --border-subtle: #242424;
  --border-default: #343434;
  --border-strong: #4A4A4A;

  /* Text */
  --text-primary: #F5F5F5;
  --text-secondary: #A3A3A3;
  --text-tertiary: #686868;
  --text-disabled: #4F4F4F;

  /* Semantic */
  --positive: #00C805;
  --positive-muted: #063B08;
  --negative: #FF4D00;
  --negative-muted: #431409;
  --warning: #F5B700;
  --info: #0A84FF;

  /* Indicator */
  --indicator-blue: #0A84FF;
  --indicator-cyan: #64D9E7;
  --indicator-orange: #FF8A00;
  --indicator-yellow: #F5B700;
  --indicator-green: #00C805;
}
```

### 1.2 Typography

**Font stack**

```css
font-family: "SF Pro Display", "SF Pro Text", Inter, system-ui, sans-serif;
font-variant-numeric: tabular-nums;
```

| Token                | Size | Weight | Line Height |
| -------------------- | ---: | -----: | ----------: |
| `type.display-price` | 32px |    500 |        36px |
| `type.page-title`    | 28px |    500 |        32px |
| `type.section-title` | 20px |    500 |        24px |
| `type.body`          | 14px |    400 |        20px |
| `type.label`         | 12px |    600 |        16px |
| `type.data`          | 12px |    500 |        16px |
| `type.micro`         | 11px |    500 |        14px |
| `type.button`        | 14px |    600 |        16px |

### 1.3 Spacing

```css
--space-1: 4px;
--space-2: 8px;
--space-3: 12px;
--space-4: 16px;
--space-5: 20px;
--space-6: 24px;
--space-8: 32px;
--space-10: 40px;
--space-12: 48px;
```

### 1.4 Radius

```css
--radius-sm: 6px;
--radius-md: 10px;
--radius-lg: 14px;
--radius-xl: 18px;
--radius-pill: 999px;
```

### 1.5 Border

```css
--border-width: 1px;
--border-default-spec: 1px solid var(--border-default);
--border-subtle-spec: 1px solid var(--border-subtle);
--border-active-positive: 1px solid var(--positive);
--border-active-info: 1px solid var(--info);
```

---

## 2. Layout

### 2.1 Screen

* Background: `var(--bg-canvas)`
* Horizontal padding: `20px`
* Vertical padding: `16px`
* Section gap: `24px`

### 2.2 Grid

* Single-column mobile layout
* Dense chart mode may reduce horizontal padding to `12px`

### 2.3 Safe Areas

* Top safe inset preserved
* Bottom safe inset preserved
* Bottom dock/nav must sit above home indicator with `8px–12px` visual clearance

---

## 3. Iconography

* Style: outline
* Stroke: `1.75px–2px`
* Size: `20px / 24px`
* Color default: `var(--text-secondary)`
* Color active: `var(--text-primary)`
* Semantic icon color permitted for positive/negative states only

---

## 4. Components

## 4.1 Top Bar

### Standard

* Height: `44px`
* Icon size: `24px`
* Horizontal gap: `12px`

### Search / Instrument Selector

* Height: `32px`
* Background: `var(--bg-elevated)`
* Radius: `8px`
* Padding: `0 10px`
* Border: none

Content:

* Ticker: `12px / 600 / var(--text-primary)`
* Price: `11px / 500 / var(--text-secondary)`
* Delta: `11px / 600 / var(--positive)` or `var(--negative)`

---

## 4.2 Asset Header

Structure:

1. Ticker label
2. Asset name
3. Price
4. Delta row
5. Context label

Specs:

* Ticker label: `11px / 600 / var(--text-primary) / uppercase`
* Asset name: `28px / 500 / var(--text-primary)`
* Price: `32px / 500 / var(--text-primary)`
* Delta: `12px / 600`
* Context label: `12px / 600 / var(--text-primary)`

---

## 4.3 Buttons

### Primary CTA

* Height: `48px`
* Min width: `144px`
* Padding: `0 24px`
* Radius: `24px`
* Background: `var(--positive)`
* Text: `14px / 600 / #000000`
* Border: none

### Outline Button

* Height: `40px`
* Padding: `0 16px`
* Radius: `20px`
* Background: transparent
* Border: `var(--border-default-spec)`
* Text: `14px / 600 / var(--text-primary)`

### Buy Button

* Height: `40px`
* Padding: `0 16px`
* Radius: `20px`
* Background: transparent
* Border: `var(--border-default-spec)`
* Text: `14px / 600 / var(--positive)`

### Sell Button

* Same as Buy Button
* Text color: `var(--negative)`

### Compact Semantic Button

* Height: `28px`
* Padding: `0 12px`
* Radius: `var(--radius-pill)`
* Background: `var(--positive-muted)` or `var(--negative-muted)`
* Text: `12px / 600 / var(--positive)` or `var(--negative)`

---

## 4.4 Chips / Pills

### Neutral Chip

* Height: `32px`
* Padding: `0 12px`
* Radius: `var(--radius-pill)`
* Background: transparent
* Border: `var(--border-default-spec)`
* Text: `12px / 500 / var(--text-secondary)`

### Selected Chip

* Height: `32px`
* Padding: `0 12px`
* Radius: `var(--radius-pill)`
* Background: `var(--positive)`
* Border: none
* Text: `12px / 600 / #000000`

### Indicator Chip

* Height: `32px`
* Padding: `0 12px`
* Radius: `var(--radius-pill)`
* Background: rgba(255,255,255,0.02)
* Border: semantic indicator border
* Text: `12px / 500 / var(--text-primary)`

Semantic variants:

* Blue: border `var(--indicator-blue)`
* Cyan: border `var(--indicator-cyan)`
* Orange: border `var(--indicator-orange)`
* Neutral: border `var(--border-default)`

---

## 4.5 Time Range Selector

Options:
`1D / 1W / 1M / 3M / YTD / 1Y / 5Y / Advanced`

### Item

* Height: `20px–24px`
* Padding: `0 8px`
* Radius: `8px`
* Text: `11px / 700`

### Inactive

* Background: transparent
* Text: `var(--positive)`

### Active

* Background: `var(--positive)`
* Text: `#000000`

---

## 4.6 Bottom Navigation

* Height: `56px`
* Background: `var(--bg-canvas)`
* Border-top: none
* Items: 5
* Icon size: `24px`

### Inactive

* Icon/text: `var(--text-tertiary)`

### Active

* Icon/text: `var(--text-primary)`

---

## 4.7 Bottom Trade Dock

* Height: `72px`
* Background: `var(--bg-canvas)`
* Border-top: `var(--border-subtle-spec)`
* Padding: `8px 16px 12px`
* Item gap: `8px`

Contents:

* Buy action
* Quantity selector
* Sell action

### Quantity Selector

* Height: `40px`
* Padding: `0 16px`
* Radius: `20px`
* Border: `var(--border-default-spec)`
* Background: transparent
* Text: `14px / 500 / var(--text-primary)`

---

## 5. Chart System

## 5.1 Base Chart

* Background: `var(--bg-canvas)`
* No card container
* Minimal grid
* Axis labels: `11px / 500 / var(--text-secondary)`
* Divider lines: `1px solid var(--border-subtle)`

## 5.2 Line Chart

* Stroke width: `2px`
* Positive line: `var(--positive)`
* Negative line: `var(--negative)`
* Fill: none

## 5.3 Candlestick Chart

* Bull candle body/wick: `var(--positive)`
* Bear candle body/wick: `var(--negative)`
* Candle body radius: `0px–2px`

## 5.4 Price Marker

* Height: `24px`
* Padding: `0 8px`
* Radius: `var(--radius-pill)`
* Background: semantic state color
* Text: `12px / 600`
* Positive: bg `var(--positive)`, text `#FFFFFF` or `#000000`
* Negative: bg `var(--negative)`, text `#FFFFFF`

## 5.5 Reference / Crosshair Line

* Thickness: `1px`
* Style: dashed
* Color: `#444444`

## 5.6 Subchart Panels

* Separated by `var(--border-subtle-spec)`
* Background: transparent
* Header text: `11px / 600 / var(--text-primary)`
* Indicator values: `11px / 600 / semantic series color`

---

## 6. Indicator Specs

## 6.1 RSI

* Plot line: light blue/cyan family
* Panel label: `RSI`
* Reference labels: `40`, `60`
* Value text: `11px / 600`

## 6.2 MACD

* Histogram positive: `var(--positive)`
* Histogram negative: `var(--negative)`
* Signal lines:

    * Line 1: `var(--indicator-cyan)`
    * Line 2: `var(--indicator-orange)`
    * Line 3: `var(--indicator-green)`

---

## 7. States

### Hover

* Background shift: `var(--bg-hover)` or `rgba(255,255,255,0.04)`

### Pressed

* Opacity reduce by `6%–10%`

### Focus

* Outline: `1px solid var(--info)`
* Offset: `2px`

### Disabled

* Text/icon: `var(--text-disabled)`
* Border: `var(--border-subtle)`
* Background: transparent or `var(--bg-surface)`

### Positive

* Use `var(--positive)` / `var(--positive-muted)`

### Negative

* Use `var(--negative)` / `var(--negative-muted)`

---

## 8. Motion

* Micro interaction: `120ms ease-out`
* Button / chip state change: `120ms ease-out`
* Panel transition: `200ms ease-out`
* Chart transition: `240ms ease-out`

No bounce.
No decorative animation.
Numeric update flash: `100ms–160ms` semantic tint.

---

## 9. Accessibility

* Minimum touch target: `44px x 44px`
* Contrast target: WCAG AA minimum
* Semantic colors must be paired with text or symbols
* Use explicit labels: `Buy`, `Sell`, `+`, `-`, `▲`, `▼`
* Use tabular numerals for prices and chart values

---

## 10. Component Inventory

* TopBar
* SearchField
* AssetHeader
* PriceDisplay
* DeltaLabel
* PrimaryButton
* OutlineButton
* BuyButton
* SellButton
* CompactActionButton
* Chip
* IndicatorChip
* TimeRangeSelector
* LineChart
* CandlestickChart
* PriceMarker
* IndicatorPanel
* BottomNavigation
* BottomTradeDock
* QuantitySelector

---

## 11. Usage Rules

* Default UI is monochrome
* Semantic colors are reserved for market state, trade actions, and indicator distinction
* Avoid elevated cards around charts
* Prefer pills and compact rounded rectangles for controls
* Keep navigation neutral
* Keep primary market data highest contrast
* Preserve dense information hierarchy with restrained decoration
