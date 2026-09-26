package xyz.mcxross.flare.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Flare's icons. Each is drawn on a 24-unit grid inside a 2-unit margin, with 1.75-unit strokes and
 * round caps and joins that echo the round-capped bars of the Flare mark. Shapes follow the usual
 * keylines (a 20-unit circle, an 18-unit square) so icons of different forms carry the same weight.
 *
 * They are vectors defined in shared code, so they render crisply at any density on Android and iOS.
 * Tint them like any icon; the colour they are drawn with here is ignored. Arrows that point along the
 * reading direction mirror in right-to-left layouts.
 */
object FlareIcons {
  // Navigation: an outline for idle tabs and a filled form for the selected one.

  /** A price line over its baseline. */
  val Markets: ImageVector by lazy {
    icon("Markets") {
      stroke("M3.5 15.5 L8.5 10.5 L12.25 13.75 L20.5 5.5")
      stroke("M3.5 20 H20.5")
    }
  }

  /** The price line as a filled area, for the selected Markets tab. */
  val MarketsFilled: ImageVector by lazy {
    icon("MarketsFilled") {
      solid("M3.5 15.5 L8.5 10.5 L12.25 13.75 L20.5 5.5 V20 H3.5 Z")
      stroke("M3.5 20 H20.5")
    }
  }

  /** An allocation pie with one slice drawn out. */
  val Portfolio: ImageVector by lazy {
    icon("Portfolio") {
      stroke("M12.5 12.5 V4.5 A8 8 0 1 1 4.5 12.5 Z")
      fill("M10.5 10.5 H2.5 A8 8 0 0 1 10.5 2.5 Z")
    }
  }

  /** The allocation pie, solid, for the selected Portfolio tab. */
  val PortfolioFilled: ImageVector by lazy {
    icon("PortfolioFilled") {
      solid("M12.5 12.5 V4.5 A8 8 0 1 1 4.5 12.5 Z")
      fill("M10.5 10.5 H2.5 A8 8 0 0 1 10.5 2.5 Z")
    }
  }

  /** A clock: orders, fills and transfers over time. */
  val Activity: ImageVector by lazy {
    icon("Activity") {
      stroke("M3.25 12 A8.75 8.75 0 1 1 20.75 12 A8.75 8.75 0 1 1 3.25 12 Z")
      stroke("M12 7.5 V12 L15 14.25")
    }
  }

  /** The clock, solid with its hands cut out, for the selected Activity tab. */
  val ActivityFilled: ImageVector by lazy {
    icon("ActivityFilled") {
      fillEvenOdd("M2.375 12 A9.625 9.625 0 1 1 21.625 12 A9.625 9.625 0 1 1 2.375 12 Z M11.125 7.5 A0.875 0.875 0 0 1 12.875 7.5 V11.562 L15.525 13.55 A0.875 0.875 0 0 1 14.475 14.95 L11.475 12.7 A0.875 0.875 0 0 1 11.125 12 Z")
    }
  }

  /** A person. */
  val Account: ImageVector by lazy {
    icon("Account") {
      stroke("M8.25 8 A3.75 3.75 0 1 1 15.75 8 A3.75 3.75 0 1 1 8.25 8 Z")
      stroke("M4.75 20 C4.75 16.25 8 14 12 14 C16 14 19.25 16.25 19.25 20")
    }
  }

  /** The person, solid, for the selected Account tab. */
  val AccountFilled: ImageVector by lazy {
    icon("AccountFilled") {
      solid("M8.25 8 A3.75 3.75 0 1 1 15.75 8 A3.75 3.75 0 1 1 8.25 8 Z")
      solid("M4.75 20 C4.75 16.25 8 14 12 14 C16 14 19.25 16.25 19.25 20 Z")
    }
  }

  // Actions and movement.

  val Search: ImageVector by lazy {
    icon("Search") {
      stroke("M4 10.75 A6.75 6.75 0 1 1 17.5 10.75 A6.75 6.75 0 1 1 4 10.75 Z")
      stroke("M15.75 15.75 L20 20")
    }
  }

  val Close: ImageVector by lazy { icon("Close") { stroke("M6.5 6.5 L17.5 17.5 M17.5 6.5 L6.5 17.5") } }

  val Add: ImageVector by lazy { icon("Add") { stroke("M12 5 V19 M5 12 H19") } }

  val Remove: ImageVector by lazy { icon("Remove") { stroke("M5 12 H19") } }

  val Check: ImageVector by lazy { icon("Check") { stroke("M4.75 12.75 L9.5 17.25 L19.25 7.25") } }

  val CheckCircle: ImageVector by lazy {
    icon("CheckCircle") {
      stroke("M3.25 12 A8.75 8.75 0 1 1 20.75 12 A8.75 8.75 0 1 1 3.25 12 Z")
      stroke("M8 12.25 L10.75 15 L16 9.5")
    }
  }

  val ChevronRight: ImageVector by lazy { icon("ChevronRight", autoMirror = true) { stroke("M9.25 5.5 L15.75 12 L9.25 18.5") } }

  val ChevronDown: ImageVector by lazy { icon("ChevronDown") { stroke("M5.5 8.75 L12 15.25 L18.5 8.75") } }

  val ChevronUp: ImageVector by lazy { icon("ChevronUp") { stroke("M5.5 15.25 L12 8.75 L18.5 15.25") } }

  val ArrowBack: ImageVector by lazy { icon("ArrowBack", autoMirror = true) { stroke("M19.5 12 H4.5 M10.5 6 L4.5 12 L10.5 18") } }

  val Copy: ImageVector by lazy {
    icon("Copy") {
      stroke("M10.75 8.5 H17.25 A2.25 2.25 0 0 1 19.5 10.75 V17.25 A2.25 2.25 0 0 1 17.25 19.5 H10.75 A2.25 2.25 0 0 1 8.5 17.25 V10.75 A2.25 2.25 0 0 1 10.75 8.5 Z")
      stroke("M15.5 8.5 V6.75 A2.25 2.25 0 0 0 13.25 4.5 H6.75 A2.25 2.25 0 0 0 4.5 6.75 V13.25 A2.25 2.25 0 0 0 6.75 15.5 H8.5")
    }
  }

  /** Opens something outside Flare, such as the block explorer. */
  val External: ImageVector by lazy {
    icon("External", autoMirror = true) {
      stroke("M14 4.5 H19.5 V10 M19.5 4.5 L11.5 12.5")
      stroke("M18 13.5 V17.25 A2.25 2.25 0 0 1 15.75 19.5 H6.75 A2.25 2.25 0 0 1 4.5 17.25 V8.25 A2.25 2.25 0 0 1 6.75 6 H10.5")
    }
  }

  /** Reverses a direction, such as a transfer's from and to. */
  val Swap: ImageVector by lazy {
    icon("Swap") {
      stroke("M8 19.5 V4.5 M4.5 8 L8 4.5 L11.5 8 M16 4.5 V19.5 M12.5 16 L16 19.5 L19.5 16")
    }
  }

  val Settings: ImageVector by lazy {
    icon("Settings") {
      stroke("M10.389 5.291 L10.592 3.111 A9 9 0 0 1 13.408 3.111 L13.611 5.291 A6.9 6.9 0 0 1 15.605 6.117 L17.29 4.719 A9 9 0 0 1 19.281 6.71 L17.883 8.395 A6.9 6.9 0 0 1 18.709 10.389 L20.889 10.592 A9 9 0 0 1 20.889 13.408 L18.709 13.611 A6.9 6.9 0 0 1 17.883 15.605 L19.281 17.29 A9 9 0 0 1 17.29 19.281 L15.605 17.883 A6.9 6.9 0 0 1 13.611 18.709 L13.408 20.889 A9 9 0 0 1 10.592 20.889 L10.389 18.709 A6.9 6.9 0 0 1 8.395 17.883 L6.71 19.281 A9 9 0 0 1 4.719 17.29 L6.117 15.605 A6.9 6.9 0 0 1 5.291 13.611 L3.111 13.408 A9 9 0 0 1 3.111 10.592 L5.291 10.389 A6.9 6.9 0 0 1 6.117 8.395 L4.719 6.71 A9 9 0 0 1 6.71 4.719 L8.395 6.117 A6.9 6.9 0 0 1 10.389 5.291 Z")
      stroke("M9.25 12 A2.75 2.75 0 1 1 14.75 12 A2.75 2.75 0 1 1 9.25 12 Z")
    }
  }

  // Objects.

  /** A market that isn't on the watchlist. */
  val Star: ImageVector by lazy {
    icon("Star") {
      stroke("M12 3.5 L14.616 9 L20.655 9.788 L16.232 13.975 L17.349 19.962 L12 17.05 L6.651 19.962 L7.768 13.975 L3.345 9.788 L9.384 9 Z")
    }
  }

  /** A market on the watchlist. */
  val StarFilled: ImageVector by lazy {
    icon("StarFilled") {
      solid("M12 3.5 L14.616 9 L20.655 9.788 L16.232 13.975 L17.349 19.962 L12 17.05 L6.651 19.962 L7.768 13.975 L3.345 9.788 L9.384 9 Z")
    }
  }

  val Key: ImageVector by lazy {
    icon("Key") {
      stroke("M3.5 16 A4.5 4.5 0 1 1 12.5 16 A4.5 4.5 0 1 1 3.5 16 Z")
      stroke("M11.2 12.8 L19.75 4.25 M16.75 7.25 L19 9.5 M14.25 9.75 L16 11.5")
    }
  }

  val Wallet: ImageVector by lazy {
    icon("Wallet") {
      stroke("M6.5 6.5 H17.5 A3 3 0 0 1 20.5 9.5 V16.5 A3 3 0 0 1 17.5 19.5 H6.5 A3 3 0 0 1 3.5 16.5 V9.5 A3 3 0 0 1 6.5 6.5 Z")
      stroke("M20.5 10.25 H16.75 A2.25 2.25 0 0 0 16.75 14.75 H20.5")
      stroke("M16.9 12.5 h0.01")
      stroke("M6.25 6.5 L14.9 4.05 A1.9 1.9 0 0 1 17.3 5.9 V6.5")
    }
  }

  /** A reward, such as a referral. */
  val Gift: ImageVector by lazy {
    icon("Gift") {
      stroke("M4.75 7.5 H19.25 A1.25 1.25 0 0 1 20.5 8.75 V10 A1.25 1.25 0 0 1 19.25 11.25 H4.75 A1.25 1.25 0 0 1 3.5 10 V8.75 A1.25 1.25 0 0 1 4.75 7.5 Z")
      stroke("M5 11.25 V18.5 A1.5 1.5 0 0 0 6.5 20 H17.5 A1.5 1.5 0 0 0 19 18.5 V11.25 M12 7.5 V20")
      stroke("M12 7.5 C10.6 4.2 7.1 3.6 7.1 5.75 C7.1 7 8.8 7.5 12 7.5 C15.2 7.5 16.9 7 16.9 5.75 C16.9 3.6 13.4 4.2 12 7.5 Z")
    }
  }

  /** Supporting Flare. */
  val Heart: ImageVector by lazy {
    icon("Heart") {
      stroke("M12 19.75 C9 17.9 3.75 14.2 3.75 9.4 C3.75 6.9 5.7 4.9 8.1 4.9 C9.75 4.9 11.15 5.85 12 7.2 C12.85 5.85 14.25 4.9 15.9 4.9 C18.3 4.9 20.25 6.9 20.25 9.4 C20.25 14.2 15 17.9 12 19.75 Z")
    }
  }

  /** A device allowed to trade. */
  val Device: ImageVector by lazy {
    icon("Device") {
      stroke("M9 2.75 H15 A3.25 3.25 0 0 1 18.25 6 V18 A3.25 3.25 0 0 1 15 21.25 H9 A3.25 3.25 0 0 1 5.75 18 V6 A3.25 3.25 0 0 1 9 2.75 Z")
      stroke("M10.5 18 H13.5")
      stroke("M10.35 10.5 H13.65 A1.1 1.1 0 0 1 14.75 11.6 V13.65 A1.1 1.1 0 0 1 13.65 14.75 H10.35 A1.1 1.1 0 0 1 9.25 13.65 V11.6 A1.1 1.1 0 0 1 10.35 10.5 Z")
      stroke("M10.6 10.5 V9.25 A1.4 1.4 0 0 1 13.4 9.25 V10.5")
    }
  }

  /** Line chart style. */
  val ChartLine: ImageVector by lazy {
    icon("ChartLine") {
      stroke("M3.5 15.5 L8.5 10.5 L12.25 13.75 L20.5 5.5")
      stroke("M3.5 20 H20.5")
    }
  }

  /** Candlestick chart style: one body hollow, one solid, as candles are drawn. */
  val ChartCandles: ImageVector by lazy {
    icon("ChartCandles") {
      stroke("M6 4.5 V7.5 M6 14.5 V17.5")
      stroke("M5 7.5 H7 A1 1 0 0 1 8 8.5 V13.5 A1 1 0 0 1 7 14.5 H5 A1 1 0 0 1 4 13.5 V8.5 A1 1 0 0 1 5 7.5 Z")
      stroke("M12 7 V9.5 M12 16.5 V20")
      solid("M11 9.5 H13 A1 1 0 0 1 14 10.5 V15.5 A1 1 0 0 1 13 16.5 H11 A1 1 0 0 1 10 15.5 V10.5 A1 1 0 0 1 11 9.5 Z")
      stroke("M18 3.5 V5.5 M18 12.5 V15.5")
      stroke("M17 5.5 H19 A1 1 0 0 1 20 6.5 V11.5 A1 1 0 0 1 19 12.5 H17 A1 1 0 0 1 16 11.5 V6.5 A1 1 0 0 1 17 5.5 Z")
    }
  }
}

private const val STROKE_WIDTH = 1.75f

/** Paths are drawn in one colour; an icon's tint replaces it. */
private val Ink = SolidColor(Color.Black)

private class IconScope(private val builder: ImageVector.Builder) {
  /** An outline at the set's stroke weight. */
  fun stroke(pathData: String) {
    builder.addPath(
      addPathNodes(pathData),
      stroke = Ink,
      strokeLineWidth = STROKE_WIDTH,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
    )
  }

  /** A filled shape with no outline. */
  fun fill(pathData: String) {
    builder.addPath(addPathNodes(pathData), fill = Ink)
  }

  /** A filled shape with holes, where overlapping subpaths cut each other out. */
  fun fillEvenOdd(pathData: String) {
    builder.addPath(addPathNodes(pathData), pathFillType = PathFillType.EvenOdd, fill = Ink)
  }

  /** Filled and outlined, so a solid shape keeps the same outer edge as its outline form. */
  fun solid(pathData: String) {
    builder.addPath(
      addPathNodes(pathData),
      fill = Ink,
      stroke = Ink,
      strokeLineWidth = STROKE_WIDTH,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
    )
  }
}

private fun icon(name: String, autoMirror: Boolean = false, draw: IconScope.() -> Unit): ImageVector =
  ImageVector.Builder(
      name = "Flare.$name",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
      autoMirror = autoMirror,
    )
    .also { IconScope(it).draw() }
    .build()
