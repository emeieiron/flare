package xyz.mcxross.flare.data

import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.SeriesPoint

/**
 * Where the selected account stands in the venue's programs: reward points, streaks and tiers on
 * some venues, a fee tier and referral standing on others. Only venues with programs bind it; where
 * nothing is bound the screens leave rewards out.
 */
interface RewardsRepository {
  /**
   * The account's rewards, or null when there are none to show. Parts that failed to load are
   * listed in [Rewards.unavailable], so the others still show.
   */
  suspend fun rewards(): Rewards?

  /** False when the venue took the code but didn't apply it. */
  suspend fun redeemReferralCode(code: String): Boolean

  /**
   * Points earned per day, oldest first, or empty where the venue keeps no history. One point per
   * day of whatever the venue calls its points.
   */
  suspend fun pointsHistory(): List<SeriesPoint> = emptyList()
}
