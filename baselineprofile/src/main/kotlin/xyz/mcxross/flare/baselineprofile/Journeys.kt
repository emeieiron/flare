package xyz.mcxross.flare.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val TARGET_PACKAGE = "xyz.mcxross.flare"

private val Price = Pattern.compile("\\$[0-9][0-9,]*\\.[0-9]+")

/** Long enough for someone to get to the device and unlock the app. */
private const val UNLOCK_WAIT_MS = 300_000L

/**
 * Launches Flare and waits on Markets. Every launch asks for the device credential, and nothing here
 * answers it: whoever runs the journey unlocks the app on the device while this waits.
 */
internal fun MacrobenchmarkScope.launchUnlocked() {
  startActivityAndWait()
  waitForMarkets(UNLOCK_WAIT_MS, "Unlock Flare on the device when it asks, and keep an account there.")
}

/** Markets is the first screen once the app is unlocked; it's ready when a price shows. */
internal fun MacrobenchmarkScope.waitForMarkets(
  timeoutMs: Long = 15_000,
  hint: String = "Markets didn't come back.",
) {
  check(device.wait(Until.hasObject(By.text(Price)), timeoutMs) == true) {
    "Markets prices didn't appear. $hint"
  }
}

/** Whether Markets is already on screen, so a pass can reuse the unlocked app. */
internal fun MacrobenchmarkScope.onMarkets(): Boolean = device.hasObject(By.text(Price))


/** Swipes across the market pages and flings the full list, as someone browsing would. */
internal fun MacrobenchmarkScope.browseMarkets() {
  val width = device.displayWidth
  val y = device.displayHeight / 2
  repeat(4) {
    device.swipe((width * 0.85).toInt(), y, (width * 0.15).toInt(), y, 12)
    device.waitForIdle()
  }
  repeat(4) {
    device.swipe((width * 0.15).toInt(), y, (width * 0.85).toInt(), y, 12)
    device.waitForIdle()
  }
  device.findObject(By.text("All"))?.click()
  device.waitForIdle()
  device.findObject(By.scrollable(true))?.let { list ->
    list.setGestureMargin(device.displayWidth / 5)
    repeat(2) {
      list.fling(Direction.DOWN)
      device.waitForIdle()
      list.fling(Direction.UP)
      device.waitForIdle()
    }
  }
}

/** Opens a market, switches the chart style both ways, and returns to the list. */
internal fun MacrobenchmarkScope.openMarket() {
  device.findObject(By.text(Price))?.click() ?: return
  device.wait(Until.hasObject(By.text("Market stats")), 15_000)
  device.findObject(By.desc("Show candles"))?.click()
  device.waitForIdle()
  device.findObject(By.desc("Show line chart"))?.click()
  device.waitForIdle()
  device.pressBack()
  waitForMarkets()
}

/** Visits every tab, ending back on Markets. */
internal fun MacrobenchmarkScope.visitTabs() {
  for (tab in listOf("Portfolio", "Activity", "Account", "Markets")) {
    device.findObject(By.text(tab))?.click()
    device.waitForIdle()
  }
}
