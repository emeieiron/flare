package xyz.mcxross.flare.venue.hyperliquid

/** A page of history, newest first; [oldestMs] is where the next page ends. */
internal data class HistoryPage<T>(val items: List<T>, val hasMore: Boolean, val oldestMs: Long?)

/**
 * The latest [pageSize] entries up to [endMs] from a Hyperliquid history read. Those reads return
 * at most [cap] entries, oldest first, from a start time, so the window is narrowed while it comes
 * back full and widened while it holds less than a page, until it reaches the account's start.
 */
internal suspend fun <T> latestPage(
  endMs: Long,
  pageSize: Int,
  cap: Int,
  time: (T) -> Long,
  fetch: suspend (startMs: Long, endMs: Long) -> List<T>,
): HistoryPage<T> {
  var span = INITIAL_SPAN_MS
  var start: Long
  var items: List<T>
  var attempts = 0
  while (true) {
    start = (endMs - span).coerceAtLeast(0L)
    items = fetch(start, endMs)
    attempts++
    if (attempts >= MAX_ATTEMPTS) break
    if (items.size >= cap && span > MIN_SPAN_MS) {
      span = (span / 4).coerceAtLeast(MIN_SPAN_MS)
    } else if (items.size < pageSize && start > 0L) {
      span *= 8
    } else {
      break
    }
  }
  val newest = items.sortedByDescending(time)
  val page = newest.take(pageSize)
  return HistoryPage(
    items = page,
    hasMore = newest.size > pageSize || start > 0L,
    oldestMs = page.lastOrNull()?.let(time),
  )
}

private const val INITIAL_SPAN_MS = 7L * 24 * 60 * 60 * 1000
private const val MIN_SPAN_MS = 60_000L
private const val MAX_ATTEMPTS = 12
