package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.data.SetupTransactionException
import xyz.mcxross.flare.hyperliquid.model.ApproveAgent
import xyz.mcxross.flare.hyperliquid.model.ApproveBuilderFee
import xyz.mcxross.flare.hyperliquid.model.ExchangeOutcome
import xyz.mcxross.flare.hyperliquid.model.ExchangeResponse
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.PendingTransactionEntity
import xyz.mcxross.flare.store.journalOperation
import xyz.mcxross.flare.store.operationName
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.TradingKeyScope

class HyperliquidSetupTest {
  private val prompt = VaultPrompt("Enable trading", "Confirm your identity")

  /** `/info` for a funded owner whose agents are [agents] (name to address). */
  private fun SetupHarness.funded(agents: () -> List<Pair<String, String>>, maxBuilderFee: () -> Int = { 0 }) {
    answer = { type, _ ->
      when (type) {
        "userRole" -> """{"role":"user"}"""
        "extraAgents" ->
          agents().joinToString(",", "[", "]") { (name, address) ->
            """{"name":"$name","address":"$address","validUntil":${now + 86_400_000}}"""
          }
        "maxBuilderFee" -> maxBuilderFee().toString()
        else -> null
      }
    }
  }

  @Test
  fun anUnfundedAccountMustDepositBeforeEnablingTrading() = runTest {
    val harness = SetupHarness { type, _ -> """{"role":"missing"}""".takeIf { type == "userRole" } }.withOwner()

    assertTrue(harness.setup.awaitsFirstDeposit(SetupHarness.OWNER))
    val error = assertFailsWith<IllegalStateException> { harness.setup.enableTrading(prompt) }
    assertTrue("Deposit" in error.message.orEmpty())
    assertTrue(harness.submitter.sent.isEmpty())
    assertNull(harness.preferences.values.first().apiWalletAddress)
  }

  @Test
  fun enablingTradingJournalsTheApprovalBeforeSendingIt() = runTest {
    val harness = SetupHarness().withOwner()
    val agents = mutableListOf<Pair<String, String>>()
    harness.funded({ agents })
    val seen = mutableListOf<String>()
    harness.submitter.beforeSigning = { nonce ->
      val entry = harness.journal.find(nonce.toString())!!
      seen += entry.state
      assertTrue(entry.operationName.startsWith("DELEGATE_TRADING:${SetupHarness.OWNER}:"))
    }
    harness.submitter.afterSigning = { nonce -> seen += harness.journal.find(nonce.toString())!!.state }
    harness.submitter.outcome = { action ->
      agents += "flare" to (action as ApproveAgent).agentAddress
      ExchangeOutcome.Accepted(ExchangeResponse.Default)
    }

    harness.setup.confirmed(prompt) { harness.setup.enableTrading(prompt) }

    val agent = harness.preferences.values.first().apiWalletAddress!!
    val (sent, nonce) = harness.submitter.sent.single()
    assertEquals(ApproveAgent(agent, "flare"), sent)
    assertEquals(harness.now, nonce)
    assertEquals(listOf("PREPARED", "SUBMITTING"), seen)
    assertTrue(harness.journal.entries.value.isEmpty())
    // Confirmed once for the whole setup; the owner signature inside it doesn't ask again.
    assertEquals(1, harness.wallet.confirmations)
    assertFalse(harness.wallet.ownerPrompts.single().requireFreshAuthorization)
  }

  @Test
  fun anAgentAlreadyApprovedIsNotApprovedAgain() = runTest {
    val harness = SetupHarness().withOwner()
    val agent = harness.wallet.createApiWallet(prompt)
    harness.funded({ listOf("flare" to agent) })

    harness.setup.enableTrading(prompt)

    assertTrue(harness.submitter.sent.isEmpty())
    assertEquals(agent, harness.preferences.values.first().apiWalletAddress)
  }

  @Test
  fun anUnknownApprovalStaysPendingAndResumesWithTheSameKey() = runTest {
    val harness = SetupHarness().withOwner()
    val agents = mutableListOf<Pair<String, String>>()
    harness.funded({ agents })
    harness.submitter.outcome = { ExchangeOutcome.Unknown("Connection reset") }

    val first = assertFailsWith<SetupTransactionException> { harness.setup.enableTrading(prompt) }
    val pending = assertIs<ActionState.Pending>(first.transaction)
    val agent = harness.preferences.values.first().apiWalletAddress!!
    assertEquals("RECONCILE_REQUIRED", harness.journal.find(pending.reference)!!.state)
    assertEquals(1, harness.actions.pendingTransactions.first().size)
    assertEquals("DELEGATE_TRADING", harness.actions.pendingTransactions.first().single().operation)

    // Still not listed: the same key is approved again, which is harmless if the first went through.
    harness.now += 1_000
    harness.submitter.outcome = { action ->
      agents += "flare" to (action as ApproveAgent).agentAddress
      ExchangeOutcome.Accepted(ExchangeResponse.Default)
    }
    harness.setup.enableTrading(prompt)

    assertEquals(agent, harness.preferences.values.first().apiWalletAddress)
    assertEquals(listOf(agent, agent), harness.submitter.sent.map { (it.first as ApproveAgent).agentAddress })
    // The earlier attempt is settled by what Hyperliquid now lists, never marked failed.
    assertTrue(harness.journal.entries.value.isEmpty())
  }

  @Test
  fun aKeyThatSignedBeforeAndIsNoLongerApprovedIsReplaced() = runTest {
    val harness = SetupHarness().withOwner()
    val old = harness.wallet.createApiWallet(prompt)
    harness.nonces.next(old)
    harness.funded({ emptyList() })

    assertFailsWith<IllegalStateException> { harness.setup.enableTrading(prompt) }

    val sent = harness.submitter.sent.single().first as ApproveAgent
    assertNotEquals(old, sent.agentAddress)
    assertEquals(sent.agentAddress, harness.preferences.values.first().apiWalletAddress)
  }

  @Test
  fun aRejectedApprovalFailsAndLeavesNothingPending() = runTest {
    val harness = SetupHarness().withOwner()
    harness.funded({ emptyList() })
    harness.submitter.outcome = { ExchangeOutcome.Rejected("Must deposit before performing actions.") }

    val error = assertFailsWith<SetupTransactionException> { harness.setup.enableTrading(prompt) }

    val failed = assertIs<ActionState.Failed>(error.transaction)
    assertTrue(failed.committed)
    assertEquals("Must deposit before performing actions.", failed.message)
    assertTrue(harness.journal.entries.value.isEmpty())
  }

  @Test
  fun anActionThatCouldNotBeSignedWasNeverSent() = runTest {
    val harness = SetupHarness().withOwner()
    harness.funded({ emptyList() })
    harness.wallet.signingFails = IllegalStateException("Cancelled")

    val error = assertFailsWith<SetupTransactionException> { harness.setup.enableTrading(prompt) }

    assertTrue(assertIs<ActionState.Failed>(error.transaction).definitelyNotSubmitted)
    assertTrue(harness.journal.entries.value.isEmpty())
    assertTrue(harness.submitter.sent.isEmpty())
  }

  @Test
  fun noncesRiseForEachSignerAndAreNeverReusedByTheJournal() = runTest {
    val harness = SetupHarness().withOwner()
    harness.funded({ emptyList() })
    harness.submitter.outcome = { ExchangeOutcome.Unknown("Timeout") }
    val builder = "0x8c967e73e7b15087c42a10d344cff4c96d877f1d"

    // The clock doesn't move: each nonce is still above the last.
    harness.setup.approveBuilderFee(builder, 10u, prompt)
    harness.setup.approveBuilderFee(builder, 10u, prompt)
    val restarted = HyperliquidNonces(harness.preferences) { harness.now - 5_000 }

    val nonces = harness.submitter.sent.map { it.second }
    assertEquals(listOf(harness.now, harness.now + 1), nonces)
    assertEquals(harness.now + 2, restarted.next(SetupHarness.OWNER))
    assertEquals(nonces.map { it.toString() }.toSet(), harness.journal.entries.value.keys)
  }

  @Test
  fun reconciliationConfirmsWhatTookEffectAndFailsOnlyWhatNoLongerCan() = runTest {
    val harness = SetupHarness().withOwner()
    val builder = "0x8c967e73e7b15087c42a10d344cff4c96d877f1d"
    var fee = 0
    var infoDown = false
    harness.funded({ emptyList() }, { fee })
    val base = harness.answer
    harness.answer = { type, body -> if (infoDown) null else base(type, body) }
    harness.submitter.outcome = { ExchangeOutcome.Unknown("Timeout") }

    val first = assertIs<ActionState.Pending>(harness.setup.approveBuilderFee(builder, 10u, prompt))
    assertEquals(0, harness.actions.reconcile().finalized)

    // Hyperliquid can't be asked: unresolved, never failed.
    infoDown = true
    assertEquals(1, harness.actions.reconcile().unresolved)
    assertEquals("RECONCILE_REQUIRED", harness.journal.find(first.reference)!!.state)
    infoDown = false

    // Still not in effect, but still able to take effect: unresolved.
    harness.now += HyperliquidActions.NONCE_WINDOW_MS - 1
    assertIs<ActionState.Pending>(harness.actions.status(first.reference))

    // In effect: confirmed.
    fee = 100
    assertIs<ActionState.Committed>(harness.actions.status(first.reference))
    assertNull(harness.journal.find(first.reference))

    // Past the nonce window and not in effect: it never will be.
    fee = 0
    val second = assertIs<ActionState.Pending>(harness.setup.approveBuilderFee(builder, 10u, prompt))
    harness.now += HyperliquidActions.NONCE_WINDOW_MS + 1
    val result = harness.actions.reconcile()
    assertEquals(1, result.finalized)
    assertNull(harness.journal.find(second.reference))
  }

  @Test
  fun anOrderIsReconciledByItsCloid() = runTest {
    val harness = SetupHarness().withOwner()
    var status = """{"status":"unknownOid"}"""
    harness.answer = { type, body ->
      if (type == "orderStatus") {
        assertEquals("0x0123456789abcdef0123456789abcdef", body["oid"]!!.jsonPrimitive.content)
        status
      } else null
    }
    val profile = harness.preferences.values.first().activeProfileId
    harness.journal.upsert(
      PendingTransactionEntity(
        hash = "0x0123456789abcdef0123456789abcdef",
        network = "TESTNET",
        operation =
          journalOperation(
            JournaledAction.encode(JournaledAction.PlaceOrder(SetupHarness.OWNER, harness.now + 60_000)),
            profile,
          ),
        state = "RECONCILE_REQUIRED",
        createdAtMs = harness.now,
        updatedAtMs = harness.now,
      )
    )

    assertEquals(1, harness.actions.reconcile().unresolved)
    assertEquals("PLACE_ORDER", harness.actions.pendingTransactions.first().single().operation)

    status = Fixtures.ORDER_STATUS
    assertEquals(1, harness.actions.reconcile().finalized)
    assertTrue(harness.journal.entries.value.isEmpty())
  }

  @Test
  fun delegationsAreTheAccountsActiveAgents() = runTest {
    val harness = SetupHarness().withOwner()
    harness.answer = { type, _ ->
      """[{"name":"flare","address":"0x1000000000000000000000000000000000000009","validUntil":${harness.now + 1_000}},""" +
        """{"name":"old","address":"0x100000000000000000000000000000000000000a","validUntil":${harness.now - 1}}]"""
          .takeIf { type == "extraAgents" }
    }

    val keys = harness.setup.delegations(SetupHarness.OWNER)

    assertEquals(listOf("0x1000000000000000000000000000000000000009"), keys.map { it.address })
    assertEquals(TradingKeyScope.ALL_MARKETS, keys.single().scope)
    assertEquals(harness.now + 1_000, keys.single().expiresAtMs)
  }

  @Test
  fun revokingThisDevicesKeyApprovesTheZeroAddressUnderItsNameAndForgetsTheKey() = runTest {
    val harness = SetupHarness().withOwner()
    val agent = harness.wallet.createApiWallet(prompt)
    harness.funded({ listOf("flare valid_until 1900000000000" to agent) })

    val result = harness.setup.revoke(agent.uppercase().replace("0X", "0x"), prompt)

    assertIs<ActionState.Committed>(result)
    assertEquals(ApproveAgent.revoke("flare valid_until 1900000000000"), harness.submitter.sent.single().first)
    assertTrue(harness.wallet.ownerPrompts.single().requireFreshAuthorization)
    assertEquals(1, harness.wallet.discarded)
    assertNull(harness.preferences.values.first().apiWalletAddress)
    assertIs<ActionState.Failed>(harness.setup.revoke("0x100000000000000000000000000000000000000b", prompt))
  }

  @Test
  fun theBuilderFeeIsApprovedInTenthsOfABasisPointAndRevokedWithZero() = runTest {
    val harness = SetupHarness().withOwner()
    val builder = "0x8c967e73e7b15087c42a10d344cff4c96d877f1d"
    var fee = 0
    harness.funded({ emptyList() }, { fee })

    assertIs<ActionState.Committed>(harness.setup.approveBuilderFee(builder, 10u, prompt))
    var saved = harness.preferences.values.first()
    assertTrue(saved.builderApproved && saved.spotBuilderApproved)
    assertEquals(10, saved.builderFeeBps)

    assertIs<ActionState.Committed>(harness.setup.revokeBuilderFee(builder, prompt))
    saved = harness.preferences.values.first()
    assertFalse(saved.builderApproved || saved.spotBuilderApproved)
    assertEquals(
      listOf(ApproveBuilderFee(builder, "0.1%"), ApproveBuilderFee(builder, "0%")),
      harness.submitter.sent.map { it.first },
    )
    assertFailsWith<IllegalArgumentException> { harness.setup.approveBuilderFee(builder, 11u, prompt) }

    // The saved approval follows what Hyperliquid reports.
    fee = 50
    harness.setup.syncBuilderApproval(builder)
    assertTrue(harness.preferences.values.first().builderApproved)
  }

  @Test
  fun anImportedKeyMustBeAnApprovedAgentOfTheAccount() = runTest {
    val harness = SetupHarness()
    val account = "0x1000000000000000000000000000000000000001"
    harness.wallet.importable["agent-key"] = "0x1000000000000000000000000000000000000009"
    harness.wallet.importable["stranger-key"] = "0x100000000000000000000000000000000000000c"
    harness.answer = { type, body ->
      assertEquals(account, body["user"]?.jsonPrimitive?.content)
      Fixtures.EXTRA_AGENTS.takeIf { type == "extraAgents" }
    }
    harness.now = 1_700_000_000_000L

    assertFailsWith<IllegalStateException> { harness.setup.importTradingKey("stranger-key", account, prompt) }
    assertTrue(harness.preferences.values.first().profiles.isEmpty())

    harness.setup.importTradingKey("agent-key", account, prompt)
    assertEquals(
      "0x1000000000000000000000000000000000000009",
      harness.preferences.values.first().apiWalletAddress,
    )
  }

  @Test
  fun setupActionsAreSentWhileTwapsAndFundMovesStayDisabled() = runTest {
    val harness = SetupHarness().withOwner()
    harness.funded({ emptyList() })
    val trading = HyperliquidTradingRepository(harness.client, harness.actions, harness.setup)
    val builder = "0x8c967e73e7b15087c42a10d344cff4c96d877f1d"
    val twap = TradeAction.CancelTwapOrder(SetupHarness.OWNER, "BTC", "1")

    val cancel = trading.execute(twap, prompt).first()
    val withdraw = trading.execute(TradeAction.Withdraw(SetupHarness.OWNER, "1"), prompt).first()
    val revoke =
      trading.execute(TradeAction.RevokeBuilderFee(SetupHarness.OWNER, builder), prompt)

    assertEquals(TWAP_UNAVAILABLE, assertIs<ActionState.Failed>(cancel).message)
    assertEquals(FUNDS_UNAVAILABLE, assertIs<ActionState.Failed>(withdraw).message)
    assertIs<ActionState.Committed>(revoke.toList().last())
    assertEquals(ApproveBuilderFee.revoke(builder), harness.submitter.sent.single().first)
  }
}
