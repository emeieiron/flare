package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.decibel.model.UserCampaigns
import xyz.mcxross.flare.venue.model.ClaimState

class DecibelCampaignMappersTest {
  private val json = Json { ignoreUnknownKeys = true }

  /** 2026-10-22T00:00:00Z, inside the first window and past the second. */
  private val now = 1_792_627_200_000L

  private fun claim(
    id: Long,
    ready: Long = 0,
    claimable: Long = 0,
    claimed: Long = 0,
    allocation: Boolean = true,
    endSec: Long = 1_795_062_600,
    claimedAt: Long? = null,
  ) =
    """{"campaign_id":$id,"campaign_type":"maker_incentive","title":"Maker Rebates",""" +
      """"status":"active","reward_asset":"0xbae2","has_allocation":$allocation,""" +
      """"ready_to_claim":$ready,"claimable_amount":$claimable,"claimed_amount":$claimed,""" +
      """"claim_start_ts_sec":1792470600,"claim_end_ts_sec":$endSec,""" +
      (claimedAt?.let { """"claimed_at_ts_sec":$it,"claim_tx_hash":"0xabc",""" } ?: "") +
      """"start_ts_sec":1792470600,"end_ts_sec":1795062600,"total_funded":1}"""

  @Test
  fun eachAllocationLandsInTheStateItIsActuallyIn() {
    val payload =
      """{"ready_to_claim":20907000,"lifetime_earned":41814000,"total_claimed":20907000,"claims":[""" +
        listOf(
          claim(1, ready = 20_907_000),
          claim(2, claimable = 5_000_000, claimed = 5_000_000, claimedAt = 1_792_000_000),
          claim(3, claimable = 1_000_000, endSec = 1_792_000_000),
          claim(4, allocation = false),
          claim(5, claimable = 2_500_000),
        ).joinToString(",") +
        """]}"""
    val rewards = json.decodeFromString<UserCampaigns>(payload).toCampaignRewards(now)

    // Chain units become exact decimal text; nothing is parsed to a Double.
    assertEquals("20.907", rewards.readyToClaim)
    assertEquals("41.814", rewards.lifetimeEarned)
    assertEquals(
      listOf(
        ClaimState.READY,
        ClaimState.CLAIMED,
        ClaimState.EXPIRED,
        ClaimState.NOT_ELIGIBLE,
        ClaimState.IN_PROGRESS,
      ),
      rewards.campaigns.map { it.state },
    )
    assertEquals("20.907", rewards.campaigns[0].amount)
    assertEquals("5", rewards.campaigns[1].amount)
    assertEquals("0xabc", rewards.campaigns[1].claimReference)
    // An allocation that earned nothing shows no amount at all.
    assertNull(rewards.campaigns[3].amount)
    assertEquals(1_795_062_600_000L, rewards.campaigns[0].claimableUntilMs)
  }

  @Test
  fun anAccountWithNoCampaignsAddsNothingToTheScreen() {
    val rewards =
      json.decodeFromString<UserCampaigns>("""{"ready_to_claim":0,"lifetime_earned":0,"claims":[]}""")
        .toCampaignRewards(now)
    assertEquals("0", rewards.readyToClaim)
    assertEquals(false, rewards.hasAnything)
  }
}
