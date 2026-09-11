package xyz.mcxross.flare.data

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Account activity reads as "how long ago", the way people remember their own trades. Older entries
 * fall back to a local calendar date rather than a UTC timestamp.
 */
fun formatRelativeTime(
  unixMs: Long,
  nowMs: Long = Clock.System.now().toEpochMilliseconds(),
): String {
  val elapsedMs = nowMs - unixMs
  return when {
    elapsedMs < -60_000L -> formatCalendarDate(unixMs)
    elapsedMs < 60_000L -> "Just now"
    elapsedMs < 3_600_000L -> "${elapsedMs / 60_000L}m ago"
    elapsedMs < 86_400_000L -> "${elapsedMs / 3_600_000L}h ago"
    elapsedMs < 7 * 86_400_000L -> "${elapsedMs / 86_400_000L}d ago"
    else -> formatCalendarDate(unixMs)
  }
}

/** A short local calendar date, for anything too far away to describe in elapsed time. */
fun formatCalendarDate(unixMs: Long): String {
  val local = Instant.fromEpochMilliseconds(unixMs).toLocalDateTime(TimeZone.currentSystemDefault())
  val month = local.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase)
  return "$month ${local.day}"
}
