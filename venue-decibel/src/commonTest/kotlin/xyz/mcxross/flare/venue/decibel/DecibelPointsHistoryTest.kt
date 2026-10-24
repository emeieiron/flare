package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.decibel.model.OwnerAmpsDaily

class DecibelPointsHistoryTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun aSeasonArrivesNewestFirstAndChartsOldestFirst() {
    val history =
      json
        .decodeFromString<OwnerAmpsDaily>(
          """{"owner":"0x1","season":"S1","days":[""" +
            """{"day_index":3,"day_start_unix_ms":1792627200000,"total_amps":1240.0,"trading_amps":900.0},""" +
            """{"day_index":2,"day_start_unix_ms":1792540800000,"total_amps":980.5,"trading_amps":700.0},""" +
            """{"day_index":1,"day_start_unix_ms":1792454400000,"total_amps":410.25,"trading_amps":410.25}""" +
            """]}"""
        )
        .toPointsHistory()

    assertEquals(listOf(1792454400000L, 1792540800000L, 1792627200000L), history.map { it.timeMs })
    assertEquals(listOf(410.25, 980.5, 1240.0), history.map { it.value })
  }

  @Test
  fun anOwnerWithNoSeasonDaysChartsNothing() {
    val history =
      json.decodeFromString<OwnerAmpsDaily>("""{"owner":"0x1","season":"S1","days":[]}""").toPointsHistory()
    assertEquals(emptyList(), history)
  }
}
