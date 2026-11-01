# Flare theming and component specs

Templates for `SKILL.md`. Token names are suggestions; keep whatever names the app already uses. Links point at Flare's `main` branch.

## Contents

1. Compose theme
2. SwiftUI theme
3. Component specs
4. Where Flare implements it

## 1. Compose theme

```kotlin
@Immutable
data class AppColors(
  val canvas: Color, val surface: Color, val elevated: Color,
  val border: Color, val borderStrong: Color, val focus: Color,
  val text: Color, val textSecondary: Color, val textTertiary: Color, val textDisabled: Color,
  val positive: Color, val negative: Color, val positiveMuted: Color, val negativeMuted: Color,
  val onAccent: Color,
)

val DarkColors = AppColors(
  canvas = Color(0xFF000000), surface = Color(0xFF111111), elevated = Color(0xFF1A1A1A),
  border = Color(0xFF242424), borderStrong = Color(0xFF343434), focus = Color(0xFF7A7A7A),
  text = Color(0xFFF5F5F5), textSecondary = Color(0xFFA3A3A3), textTertiary = Color(0xFF8B8D89),
  textDisabled = Color(0xFF4F4F4F),
  positive = Color(0xFFC4F564), negative = Color(0xFFFF766B),
  positiveMuted = Color(0xFF202B14), negativeMuted = Color(0xFF431409),
  onAccent = Color(0xFF000000),
)

val LightColors = AppColors(
  canvas = Color(0xFFFFFFFF), surface = Color(0xFFF6F7F9), elevated = Color(0xFFFFFFFF),
  border = Color(0xFFE2E4E8), borderStrong = Color(0xFFD0D3D9), focus = Color(0xFF80868F),
  text = Color(0xFF111418), textSecondary = Color(0xFF5E6470), textTertiary = Color(0xFF6A707C),
  textDisabled = Color(0xFFA9AEB8),
  positive = Color(0xFF00823B), negative = Color(0xFFD92D20),
  positiveMuted = Color(0xFFF0F9F3), negativeMuted = Color(0xFFFFF6F5),
  onAccent = Color(0xFFFFFFFF),
)

val LocalAppColors = staticCompositionLocalOf { DarkColors }

private fun style(size: Int, line: Int, weight: FontWeight) =
  TextStyle(
    fontFamily = Inter, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    fontFeatureSettings = "tnum",                      // figures never jitter as values change
  )

val AppTypography = Typography(
  displayLarge = style(56, 60, FontWeight.Medium),    // hero balance
  headlineLarge = style(32, 38, FontWeight.Medium),   // page title
  titleLarge = style(22, 28, FontWeight.Medium),      // section title, header price
  bodyLarge = style(16, 24, FontWeight.Normal),
  bodySmall = style(12, 18, FontWeight.Normal),       // captions, hints
  labelLarge = style(15, 20, FontWeight.SemiBold),    // buttons
  labelMedium = style(13, 18, FontWeight.Medium),     // chips, field labels
  labelSmall = style(11, 16, FontWeight.Medium),      // badges
)

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val colors = if (dark) DarkColors else LightColors
  CompositionLocalProvider(LocalAppColors provides colors) {
    // MaterialTheme only carries typography; no Material components are used.
    MaterialTheme(typography = AppTypography) {
      CompositionLocalProvider(LocalContentColor provides colors.text, content = content)
    }
  }
}

object AppTheme {
  val colors: AppColors
    @Composable @ReadOnlyComposable get() = LocalAppColors.current
}
```

Keep chart configuration objects stable in Compose. Vico keys its saved zoom and scroll state on the `Zoom`, `AutoScrollCondition` and layer-padding objects you pass, so declare them as top-level `val`s or `remember` them. A fresh instance on every recomposition resets the zoom.

## 2. SwiftUI theme

Put colours in the asset catalog as colour sets with **Any** and **Dark** appearances, named after the tokens (Canvas, Surface, Elevated, Border, BorderStrong, Focus, TextPrimary, TextSecondary, TextTertiary, TextDisabled, Positive, Negative, PositiveMuted, NegativeMuted, OnAccent), using the values in `SKILL.md`.

```swift
extension Color {
    static let canvas = Color("Canvas")
    static let surface = Color("Surface")
    static let elevated = Color("Elevated")
    static let borderStrong = Color("BorderStrong")
    static let textPrimary = Color("TextPrimary")
    static let textSecondary = Color("TextSecondary")
    static let textDisabled = Color("TextDisabled")
    static let positive = Color("Positive")
    static let negative = Color("Negative")
    static let onAccent = Color("OnAccent")
}

extension Font {
    // Scales with Dynamic Type; tabular figures everywhere.
    static let appBody = Font.custom("Inter", size: 16, relativeTo: .body).monospacedDigit()
    static let appButton = Font.custom("Inter", size: 15, relativeTo: .callout).weight(.semibold).monospacedDigit()
    static let appControl = Font.custom("Inter", size: 13, relativeTo: .footnote).weight(.medium).monospacedDigit()
}

struct PillButtonStyle: ButtonStyle {
    enum Kind { case primary, buy, sell, destructive }
    var kind: Kind = .primary
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.appButton)
            .frame(maxWidth: .infinity, minHeight: 52)
            .padding(.horizontal, 24)
            .foregroundStyle(isEnabled ? content : Color.textDisabled)
            .background(isEnabled ? container : Color.surface, in: Capsule())
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }

    private var container: Color {
        switch kind {
        case .primary, .buy: return .positive
        case .sell, .destructive: return .negative
        }
    }

    private var content: Color { .onAccent }
}

struct Pill: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.appControl)
                .frame(maxWidth: .infinity, minHeight: 48)
                .foregroundStyle(selected ? Color.textPrimary : Color.textSecondary)
                .background(selected ? Color.elevated : Color.clear, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? Color.elevated : Color.borderStrong, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .animation(.easeOut(duration: 0.12), value: selected)
    }
}
```

- **Presentation.** Use the system `.sheet` with detents and `NavigationStack` for presentation and navigation, and style what they contain.
- **Loading state.** For a button that's working, show the progress inside the label and set `.disabled(true)` so taps are ignored.

## 3. Component specs

| Component | Spec |
|---|---|
| Button | Pill, minimum height 52, horizontal padding 24, 15/20 semibold label. Primary and buy use positive fill with on-accent text; sell and destructive use negative fill; outline is a hairline with primary text. Disabled uses surface fill with disabled text. Working keeps its size, animates the label and ignores taps |
| Text field | 18 dp corners, label inside at 13 sp, value at 16/24. Surface fill in dark; canvas with a hairline in light. Focus border uses the focus colour; error uses the negative colour plus a one-line hint. Trailing Paste/Clear for addresses and keys |
| Amount field | As a text field, plus the available balance and a unit badge. Decimal keyboard. Validates on exact decimals |
| Chip | Pill, minimum height 32 (inline) or 48 (standalone). Selected: elevated fill, primary text. Unselected: hairline (`#343434` / `#D0D3D9`) with secondary text, or plain secondary text |
| Segmented control | Surface track with a 3 dp inset, equal segments, the lighter pill slides to the selection |
| Grid of options | 4 columns, 8 dp gaps, pills 48 tall, inside a sheet; picking applies and closes |
| Sheet | Canvas background, drag handle, title, 24 dp gutter, scrollable body, actions pinned at the bottom. A sheet can't be dismissed while its action is working |
| Detail row | Label in secondary text on the left, value in primary text on the right, tabular |
| Inline hint | 12/18 under the field, positive or negative colour, at most one line |
| Receipt | Outcome mark, one-line summary, hash with an explorer link, a single Done |
| Skeleton | Same geometry as the content it stands in for; a 1.3 s shimmer sweep, or static with reduced motion |

## 4. Where Flare implements it

| What | Files |
|---|---|
| Tokens, type and motion values | [FlareTheme.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/design/FlareTheme.kt) (dark set only) |
| Buttons, fields, chips, segmented control, navigation | [FlareComponents.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/design/FlareComponents.kt), [FlareFilterChips.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/design/FlareFilterChips.kt), [AddressText.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/design/AddressText.kt) |
| Sheets, confirmation, receipt, rows | [FlareLayouts.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/design/FlareLayouts.kt) |
| Plain-language errors | [ActionFeedback.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/design/ActionFeedback.kt) |
| Skeletons, splash and brand mark | [FlareShimmer.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/design/FlareShimmer.kt), [FlareMark.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/design/FlareMark.kt), [OutcomeIcon.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/design/OutcomeIcon.kt) |
| Step layout, secret field, inline hints, keyboard chaining | [OnboardingScreen.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/onboarding/OnboardingScreen.kt), [OnboardingSteps.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/onboarding/OnboardingSteps.kt) |
| Charts and the interval grid | [PriceChart.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/trade/PriceChart.kt), [ChartViewport.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/trade/ChartViewport.kt) |
| Number formatting | [MarketsRepository.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/data/MarketsRepository.kt) (`formatPrice`, `formatBalance`, `formatSignedBalance`, `formatCompact`, `formatQuantity`) |
| Insets counted once, launch splash | [App.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/App.kt), [MainActivity.kt](https://github.com/emeieiron/flare/blob/main/androidApp/src/main/kotlin/xyz/mcxross/flare/MainActivity.kt) |
| Preview harness | [DesignPreviewActivity.kt](https://github.com/emeieiron/flare/blob/main/androidApp/src/debug/kotlin/xyz/mcxross/flare/DesignPreviewActivity.kt) |
