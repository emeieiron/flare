package xyz.mcxross.flare.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline profile shipped with release builds:
 *
 *   ./gradlew :androidApp:generateReleaseBaselineProfile
 *
 * The journeys are the paths people take first, so their code is compiled ahead of time on install
 * instead of being interpreted and JIT-compiled while someone is using the app.
 *
 * Run it on a device with an account in Flare. Every cold start asks for the device credential, so
 * each collection is a single pass and the run asks twice: unlock the app each time.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
  @get:Rule val rule = BaselineProfileRule()

  /** Launch to the first prices on Markets. R8 also lays this code out first in the dex files. */
  @Test
  fun startup() =
    rule.collect(packageName = TARGET_PACKAGE, maxIterations = 1, includeInStartupProfile = true) {
      pressHome()
      launchUnlocked()
    }

  /** What people do next: browse the markets, open one, and visit every tab. */
  @Test
  fun journeys() =
    rule.collect(packageName = TARGET_PACKAGE, maxIterations = 1) {
      pressHome()
      launchUnlocked()
      browseMarkets()
      openMarket()
      visitTabs()
    }
}
