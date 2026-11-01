package xyz.mcxross.flare.venue.decibel

internal data class HistoryWindow<T>(val items: List<T>, val hasMore: Boolean)

/** Retains pages already opened by the reader, provided the refreshed page still overlaps them. */
internal fun <T, K> refreshedHistoryWindow(
  previous: List<T>,
  incoming: List<T>,
  previousHasMore: Boolean,
  incomingHasMore: Boolean,
  pageSize: Int = 50,
  key: (T) -> K,
): HistoryWindow<T> {
  val incomingKeys = incoming.mapTo(mutableSetOf(), key)
  if (previous.size > pageSize && previous.any { key(it) in incomingKeys }) {
    return HistoryWindow((incoming + previous).distinctBy(key), previousHasMore)
  }
  // No overlap means more than a page may have arrived; retain a contiguous window.
  return HistoryWindow(incoming, incomingHasMore)
}
