package xyz.mcxross.flare.data

import xyz.mcxross.flare.decibel.model.Candle

/** Joins two runs of candles by open time, oldest first. Where they overlap, [newer] wins. */
fun mergeCandles(older: List<Candle>, newer: List<Candle>): List<Candle> {
  if (older.isEmpty()) return newer
  if (newer.isEmpty()) return older
  if (older.last().openTimeMs < newer.first().openTimeMs) return older + newer
  return (newer + older).distinctBy(Candle::openTimeMs).sortedBy(Candle::openTimeMs)
}

/** Applies a streamed candle: it replaces the candle it updates, or follows the latest one. */
fun List<Candle>.withLiveCandle(candle: Candle): List<Candle> {
  val latest = lastOrNull() ?: return listOf(candle)
  return when {
    candle.openTimeMs == latest.openTimeMs -> if (candle == latest) this else dropLast(1) + candle
    candle.openTimeMs > latest.openTimeMs -> this + candle
    else -> {
      val index = binarySearchBy(candle.openTimeMs, selector = Candle::openTimeMs)
      if (index < 0 || this[index] == candle) this
      else toMutableList().also { it[index] = candle }
    }
  }
}

/**
 * The latest page fetched after the stream reconnects. It extends the series when the two meet;
 * after a longer gap it starts the series over, because the history in between is missing.
 */
fun List<Candle>.caughtUpWith(latest: List<Candle>, intervalMs: Long): List<Candle> {
  if (isEmpty() || latest.isEmpty()) return latest.ifEmpty { this }
  return if (latest.first().openTimeMs <= last().openTimeMs + intervalMs) mergeCandles(this, latest)
  else latest
}
