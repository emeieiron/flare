package xyz.mcxross.flare.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures what the baseline profile buys, each case with and without it:
 *
 *   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 *
 * Startup runs unattended: it times the first frame, which comes before the unlock prompt.
 * Browsing needs one unlock per case.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun startupWithoutProfile() = startup(CompilationMode.None())

  @Test fun startupWithBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

  private fun startup(mode: CompilationMode) =
    rule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(StartupTimingMetric()),
      compilationMode = mode,
      startupMode = StartupMode.COLD,
      iterations = 10,
      setupBlock = { pressHome() },
    ) {
      startActivityAndWait()
    }
}

@RunWith(AndroidJUnit4::class)
class MarketsScrollBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun browseWithoutProfile() = browse(CompilationMode.None())

  @Test fun browseWithBaselineProfile() = browse(CompilationMode.Partial(BaselineProfileMode.Require))

  private fun browse(mode: CompilationMode) =
    rule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = mode,
      // Passes share one warm, unlocked app rather than starting cold and asking again each time.
      startupMode = null,
      iterations = 5,
      setupBlock = { if (!onMarkets()) launchUnlocked() },
    ) {
      browseMarkets()
    }
}
