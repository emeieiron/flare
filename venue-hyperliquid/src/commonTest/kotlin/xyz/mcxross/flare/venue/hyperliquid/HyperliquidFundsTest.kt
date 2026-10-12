package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.CancelStatus
import xyz.mcxross.flare.hyperliquid.model.ExchangeOutcome
import xyz.mcxross.flare.hyperliquid.model.ExchangeResponse
import xyz.mcxross.flare.hyperliquid.model.L1Action
import xyz.mcxross.flare.hyperliquid.model.LedgerUpdate
import xyz.mcxross.flare.hyperliquid.model.OrderStatus
import xyz.mcxross.flare.hyperliquid.model.TwapHistoryEntry
import xyz.mcxross.flare.hyperliquid.model.TwapOrderStatus
import xyz.mcxross.flare.hyperliquid.model.UsdClassTransfer
import xyz.mcxross.flare.hyperliquid.model.UsdSend
import xyz.mcxross.flare.hyperliquid.model.VaultTransfer
import xyz.mcxross.flare.hyperliquid.model.Withdraw
import xyz.mcxross.flare.hyperliquid.model.toHlDecimal
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.operationName
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.TwapSchedule

/**
 * Fund moves, TWAPs and sub-account actions as sent, and how each is followed to an outcome: an
 * answer settles it, otherwise the ledger or TWAP history does once it lists it, and an action
 * past its lapse that isn't listed failed.
 */
class HyperliquidFundsTest {
  private val prompt = VaultPrompt("Move funds", "Confirm your identity")

  @Test
  fun aWithdrawalGoesToTheOwnerOnArbitrumAndTakesItsLedgerHash() = runTest {
    val harness = harness()
    var journaled: String? = null
    harness.submitter.beforeSigning = { journaled = harness.journal.find(it.toString())?.operationName }
    harness.ledger = { nonce -> """[${withdrawal(nonce * 1_000, "5.5")}]""" }

    val states = harness.trading.execute(TradeAction.Withdraw(OWNER, "5.5"), prompt).toList()

    assertEquals(Withdraw(OWNER, "5.5".toHlDecimal()), harness.submitter.sent.single().first)
    val record = assertIs<JournaledAction.Withdraw>(JournaledAction.decode(checkNotNull(journaled)))
    assertTrue(record.destination.sameAs(OWNER))
    assertEquals("5.5", record.amount)
    assertEquals(harness.nonce() + HyperliquidActions.NONCE_WINDOW_MS, record.lapsesAtMs)
    assertEquals(listOf(ActionState.AwaitingAuthorization, ActionState.Committed(HASH)), states)
    assertTrue(harness.wallet.ownerPrompts.single().requireFreshAuthorization)
    assertTrue(harness.journal.entries.value.isEmpty())
  }

  @Test
  fun aWithdrawalTheLedgerDoesntListYetKeepsItsNonce() = runTest {
    val harness = harness()

    val result = harness.funds.withdraw(OWNER, DESTINATION, "2", prompt)

    assertEquals(Withdraw(DESTINATION, "2".toHlDecimal()), harness.submitter.sent.single().first)
    assertEquals(ActionState.Committed(harness.nonce().toString()), result)
  }

  @Test
  fun withdrawalsAreCheckedBeforeAnythingIsSigned() = runTest {
    val harness = harness()

    val atTheFee = harness.funds.withdraw(OWNER, null, "1", prompt)
    val badAddress = harness.funds.withdraw(OWNER, "0x1234", "5", prompt)
    val tooPrecise = harness.funds.withdraw(OWNER, null, "5.0000001", prompt)

    assertEquals("A withdrawal must be more than the 1 USDC fee", assertIs<ActionState.Failed>(atTheFee).message)
    assertEquals("Enter a valid Arbitrum address", assertIs<ActionState.Failed>(badAddress).message)
    assertTrue(assertIs<ActionState.Failed>(tooPrecise).definitelyNotSubmitted)
    assertTrue(harness.submitter.sent.isEmpty())
    assertTrue(harness.wallet.ownerPrompts.isEmpty())
  }

  @Test
  fun aTransferGoesToAnotherHyperliquidAccount() = runTest {
    val harness = harness()
    harness.ledger = { nonce -> """[${send(nonce, "12.5")}]""" }

    val result = harness.funds.send(OWNER, DESTINATION, "12.5", prompt)
    val toSelf = harness.funds.send(OWNER, OWNER.lowercase(), "1", prompt)

    assertEquals(UsdSend(DESTINATION, "12.5".toHlDecimal()), harness.submitter.sent.single().first)
    assertEquals(ActionState.Committed(HASH), result)
    assertEquals("That’s this account’s own address", assertIs<ActionState.Failed>(toSelf).message)
  }

  @Test
  fun usdcMovesBetweenPerpsAndSpotOnlyWhereTheyAreApart() = runTest {
    val harness = harness()

    val toSpot = harness.trading.execute(TradeAction.MovePerpSpot(OWNER, "3", toSpot = true), prompt).toList().last()
    harness.abstraction = Fixtures.USER_ABSTRACTION_UNIFIED
    val unified = harness.funds.movePerpSpot(OWNER, "3", toPerp = true, prompt)

    assertEquals(UsdClassTransfer("3".toHlDecimal(), toPerp = false), harness.submitter.sent.single().first)
    assertIs<ActionState.Committed>(toSpot)
    assertEquals(
      "This account shares one balance between perps and spot, so there’s nothing to move",
      assertIs<ActionState.Failed>(unified).message,
    )
  }

  @Test
  fun aSubAccountsMovesNameItAndItsOtherFundMovesStayWithTheMainAccount() = runTest {
    val harness = harness()

    val moved = harness.funds.movePerpSpot(SUB_ACCOUNT, "2.5", toPerp = true, prompt)
    val withdrawal = harness.funds.withdraw(SUB_ACCOUNT, null, "5", prompt)
    val vault = harness.funds.vault(SUB_ACCOUNT, Fixtures.HLP_VAULT, true, "5", prompt)

    assertIs<ActionState.Committed>(moved)
    assertEquals(
      UsdClassTransfer("2.5".toHlDecimal(), toPerp = true, subAccount = SUB_ACCOUNT),
      harness.submitter.sent.single().first,
    )
    assertEquals(
      "Withdrawals go from your main account. Select it first.",
      assertIs<ActionState.Failed>(withdrawal).message,
    )
    assertTrue(assertIs<ActionState.Failed>(vault).definitelyNotSubmitted)
  }

  @Test
  fun vaultDepositsAreSignedByTheAgentAndRedemptionsWaitForTheLockup() = runTest {
    val harness = harness()
    harness.ledger = { _ -> """[${vaultDeposit("5")}]""" }

    val deposit = harness.trading.execute(TradeAction.VaultDeposit(OWNER, Fixtures.HLP_VAULT, "5"), prompt).toList().last()
    harness.equities = """[{"vaultAddress":"${Fixtures.HLP_VAULT}","equity":"5.0","lockedUntilTimestamp":${harness.now + 3_600_000}}]"""
    val locked = harness.funds.vault(OWNER, Fixtures.HLP_VAULT, false, "5", prompt)
    harness.equities = """[{"vaultAddress":"${Fixtures.HLP_VAULT}","equity":"5.0","lockedUntilTimestamp":${harness.now - 1}}]"""
    val redeemed = harness.funds.vault(OWNER, Fixtures.HLP_VAULT, false, "5", prompt)

    assertEquals(
      listOf(
        VaultTransfer.ofUsdc(Fixtures.HLP_VAULT, isDeposit = true, "5".toHlDecimal()),
        VaultTransfer.ofUsdc(Fixtures.HLP_VAULT, isDeposit = false, "5".toHlDecimal()),
      ),
      harness.submitter.sent.map { it.first },
    )
    assertEquals(listOf<String?>(null, null), harness.submitter.vaults)
    assertTrue(harness.wallet.ownerPrompts.isEmpty())
    assertEquals(ActionState.Committed(HASH), deposit)
    assertTrue("locked until" in assertIs<ActionState.Failed>(locked).message)
    assertIs<ActionState.Committed>(redeemed)
  }

  @Test
  fun anUnansweredMoveIsReconciledFromTheLedgerAndFailsOnlyOnceItLapsed() = runTest {
    val harness = harness()
    harness.submitter.outcome = { ExchangeOutcome.Unknown("timed out") }

    val first = harness.funds.movePerpSpot(OWNER, "3", toPerp = false, prompt)
    val nonce = harness.nonce()
    val second = harness.funds.send(OWNER, DESTINATION, "1", prompt)

    assertEquals(ActionState.Pending(nonce.toString()), first)
    assertIs<ActionState.Pending>(second)
    assertEquals(2, harness.actions.reconcile().unresolved)
    harness.ledger = { _ -> """[${classTransfer("3", toPerp = false)}]""" }
    assertEquals(1, harness.actions.reconcile().finalized)
    harness.now += HyperliquidActions.NONCE_WINDOW_MS + 60_000
    assertIs<ActionState.Failed>(harness.trading.transactionStatus(harness.journal.entries.value.keys.single()))
    assertTrue(harness.journal.entries.value.isEmpty())
  }

  @Test
  fun aTwapRunsForItsMinutesFromTheAgent() = runTest {
    val harness = harness()
    harness.submitter.outcome = { ExchangeOutcome.Accepted(ExchangeResponse.TwapPlaced(TwapOrderStatus.Running(42L))) }

    val placed = harness.placeTwap(OWNER, "0.01", minutes = 60)
    harness.submitter.outcome = { ExchangeOutcome.Accepted(ExchangeResponse.TwapPlaced(TwapOrderStatus.Error("Too small"))) }
    val refused = harness.placeTwap(OWNER, "0.01", minutes = 60)

    assertEquals("""{"type":"twapOrder","twap":{"a":0,"b":true,"s":"0.01","r":false,"m":60,"t":false}}""", harness.wires().first())
    assertEquals("Your TWAP is running.", assertIs<ActionState.Committed>(placed).detail)
    val failed = assertIs<ActionState.Failed>(refused)
    assertEquals("Too small", failed.message)
    assertTrue(failed.committed)
    assertTrue(harness.wallet.ownerPrompts.isEmpty())
  }

  @Test
  fun aTwapIsCanceledByItsId() = runTest {
    val harness = harness()
    harness.submitter.outcome = { ExchangeOutcome.Accepted(ExchangeResponse.TwapCanceled(CancelStatus.Success)) }

    val result = harness.trading.execute(TradeAction.CancelTwapOrder(OWNER, "BTC", "42"), prompt).toList().last()

    assertEquals("""{"type":"twapCancel","a":0,"t":42}""", harness.wires().single())
    assertIs<ActionState.Committed>(result)
  }

  @Test
  fun anUnansweredTwapIsReconciledFromItsHistory() = runTest {
    val harness = harness()
    harness.submitter.outcome = { ExchangeOutcome.Unknown("timed out") }

    assertIs<ActionState.Pending>(harness.placeTwap(OWNER, "0.01", minutes = 60))
    assertIs<ActionState.Pending>(
      harness.trading.execute(TradeAction.CancelTwapOrder(OWNER, "BTC", "7"), prompt).toList().last()
    )
    assertEquals(2, harness.actions.reconcile().unresolved)
    harness.twaps = "[${twap(null, "activated")},${twap(7, "terminated")}]"
    assertEquals(2, harness.actions.reconcile().finalized)
  }

  @Test
  fun aSubAccountsTradingSignsWithItsVaultAddress() = runTest {
    val harness = harness()
    harness.submitter.outcome = { action ->
      when (action.type) {
        "order" -> ExchangeOutcome.Accepted(ExchangeResponse.Orders(listOf(OrderStatus.Resting(5L))))
        "twapOrder" -> ExchangeOutcome.Accepted(ExchangeResponse.TwapPlaced(TwapOrderStatus.Running(1L)))
        else -> ExchangeOutcome.Accepted(ExchangeResponse.Cancels(listOf(CancelStatus.Success)))
      }
    }
    val order = OrderRequest("BTC", OrderSide.BUY, OrderType.LIMIT, "0.001", price = "65000")

    harness.trading.execute(TradeAction.PlaceOrder(SUB_ACCOUNT, BTC, order), prompt).toList()
    harness.trading.execute(TradeAction.CancelOrder(SUB_ACCOUNT, "BTC", "5"), prompt).toList()
    harness.placeTwap(SUB_ACCOUNT, "0.01", minutes = 30)
    harness.trading.execute(TradeAction.PlaceOrder(OWNER, BTC, order), prompt).toList()

    assertEquals(listOf(SUB_ACCOUNT, SUB_ACCOUNT, SUB_ACCOUNT, null), harness.submitter.vaults)
  }

  @Test
  fun theLedgerTellsEachMoveApart() {
    val since = 1_000L
    fun update(json: String) = HyperliquidClient.DefaultJson.decodeFromString<LedgerUpdate>(json)

    val withdraw = JournaledAction.Withdraw(OWNER, DESTINATION, "5.5", 0L)
    assertTrue(HyperliquidLedger.matches(update(withdrawal(7_000, "5.5", time = 2_000)), withdraw, 7L, since))
    assertTrue(HyperliquidLedger.matches(update(withdrawal(7, "4.5", time = 2_000)), withdraw, 7L, since))
    assertFalse(HyperliquidLedger.matches(update(withdrawal(8, "5.5", time = 2_000)), withdraw, 7L, since))
    assertFalse(HyperliquidLedger.matches(update(withdrawal(7, "5.5", time = 999)), withdraw, 7L, since))

    val sent = JournaledAction.SendUsdc(OWNER, DESTINATION, "10", 0L)
    val internal =
      """{"time":2000,"hash":"$HASH","delta":{"type":"internalTransfer","usdc":"9.0","user":"${OWNER.lowercase()}","destination":"$DESTINATION","fee":"1.0"}}"""
    assertTrue(HyperliquidLedger.matches(update(internal), sent, 7L, since))
    assertFalse(HyperliquidLedger.matches(update(internal.replace("9.0", "8.0")), sent, 7L, since))

    val move = JournaledAction.MovePerpSpot(OWNER, "3", toPerp = false, 0L)
    assertTrue(HyperliquidLedger.matches(update(classTransfer("3.0", toPerp = false, time = 2_000)), move, 7L, since))
    assertFalse(HyperliquidLedger.matches(update(classTransfer("3.0", toPerp = true, time = 2_000)), move, 7L, since))

    val redeem = JournaledAction.VaultTransfer(OWNER, Fixtures.HLP_VAULT, isDeposit = false, "5", 0L)
    val redeemed =
      """{"time":2000,"hash":"$HASH","delta":{"type":"vaultWithdraw","vault":"${Fixtures.HLP_VAULT}","user":"${OWNER.lowercase()}","requestedUsd":"5.0","commission":"0.0","closingCost":"0.0","basis":"4.98","netWithdrawnUsd":"5.0"}}"""
    assertTrue(HyperliquidLedger.matches(update(redeemed), redeem, 7L, since))
    assertFalse(HyperliquidLedger.matches(update(vaultDeposit("5", time = 2_000)), redeem, 7L, since))
  }

  @Test
  fun newJournaledActionsSurviveARestart() {
    val actions =
      listOf(
        JournaledAction.Withdraw(OWNER, DESTINATION, "5.5", 1L),
        JournaledAction.SendUsdc(OWNER, DESTINATION, "12.345678", 2L),
        JournaledAction.MovePerpSpot(SUB_ACCOUNT, "3", toPerp = true, 3L),
        JournaledAction.VaultTransfer(OWNER, Fixtures.HLP_VAULT, isDeposit = true, "5", 4L),
        JournaledAction.VaultTransfer(OWNER, Fixtures.HLP_VAULT, isDeposit = false, "5", 5L),
        JournaledAction.PlaceTwap(OWNER, "xyz:ABC", isBuy = false, "0.5", 30, 6L),
        JournaledAction.CancelTwap(OWNER, 42L, 7L),
      )

    actions.forEach { assertEquals(it, JournaledAction.decode(JournaledAction.encode(it))) }
    assertNull(JournaledAction.decode("PLACE_TWAP_ORDER:$OWNER:1"))
  }

  private class Harness(val setup: SetupHarness) {
    val submitter get() = setup.submitter
    val journal get() = setup.journal
    val actions get() = setup.actions
    val wallet get() = setup.wallet
    var now: Long
      get() = setup.now
      set(value) {
        setup.now = value
      }
    /** `userNonFundingLedgerUpdates`, given the nonce last sent. */
    var ledger: (nonce: Long) -> String = { "[]" }
    var abstraction = Fixtures.USER_ABSTRACTION
    var equities = "[]"
    var twaps = "[]"
    val funds = HyperliquidFunds(setup.client, setup.actions, setup.preferences) { setup.now }
    val trading =
      HyperliquidTradingRepository(setup.client, setup.actions, setup.setup, funds, HyperliquidAssets(setup.client))

    fun nonce(): Long = submitter.sent.last().second

    fun wires(): List<String> = submitter.sent.map { (it.first as L1Action).toWire().toString() }

    suspend fun placeTwap(account: String, size: String, minutes: Long): ActionState {
      val order =
        OrderRequest("BTC", OrderSide.BUY, OrderType.TWAP, size, twap = TwapSchedule(30, minutes * 60))
      return trading.execute(TradeAction.PlaceOrder(account, BTC, order), VaultPrompt("TWAP", "")).toList().last()
    }

    @Suppress("UNUSED_PARAMETER")
    fun answer(type: String, body: JsonObject): String? =
      when (type) {
        "meta" -> META
        "spotMeta" -> SPOT_META
        "userNonFundingLedgerUpdates" -> ledger(submitter.sent.lastOrNull()?.second ?: 0L)
        "userAbstraction" -> abstraction
        "userVaultEquities" -> equities
        "twapHistory" -> twaps
        else -> null
      }
  }

  private suspend fun harness(): Harness {
    val setup = SetupHarness().withOwner()
    setup.preferences.setApiWallet(AGENT)
    return Harness(setup).also { harness -> setup.answer = harness::answer }
  }

  private fun withdrawal(nonce: Long, usdc: String, time: Long = 1_800_000_000_100L) =
    """{"time":$time,"hash":"$HASH","delta":{"type":"withdraw","usdc":"$usdc","nonce":$nonce,"fee":"1.0"}}"""

  private fun send(nonce: Long, amount: String) =
    """{"time":1800000000100,"hash":"$HASH","delta":{"type":"send","user":"${OWNER.lowercase()}","destination":"$DESTINATION","sourceDex":"","destinationDex":"","token":"USDC","amount":"$amount","usdcValue":"$amount","fee":"0.0","nativeTokenFee":"0.0","nonce":$nonce,"feeToken":""}}"""

  private fun classTransfer(usdc: String, toPerp: Boolean, time: Long = 1_800_000_000_100L) =
    """{"time":$time,"hash":"$HASH","delta":{"type":"accountClassTransfer","usdc":"$usdc","toPerp":$toPerp}}"""

  private fun vaultDeposit(usdc: String, time: Long = 1_800_000_000_100L) =
    """{"time":$time,"hash":"$HASH","delta":{"type":"vaultDeposit","vault":"${Fixtures.HLP_VAULT}","usdc":"$usdc"}}"""

  /** A history entry of the TWAP [placeTwap] sends, started now; [id] null is from before TWAP ids. */
  private fun twap(id: Long?, status: String) =
    """{"time":1800000001,"state":{"coin":"BTC","user":"${OWNER.lowercase()}","side":"B","sz":"0.01","executedSz":"0.0","executedNtl":"0.0","minutes":60,"reduceOnly":false,"randomize":false,"timestamp":1800000000500},"status":{"status":"$status"}${id?.let { ",\"twapId\":$it" } ?: ""}}"""

  private companion object {
    const val OWNER = SetupHarness.OWNER
    const val AGENT = "0x1000000000000000000000000000000000000009"
    const val SUB_ACCOUNT = "0x100000000000000000000000000000000000000d"
    const val DESTINATION = "0x5e9ee1089755c3435139848e47e6635505d5a13a"
    const val HASH = "0x99ec2abc3983011c9b650424daff7000002742a1d4861fee3db4d60ef886db07"
    const val META = """{"universe":[{"szDecimals":5,"name":"BTC","maxLeverage":40}]}"""
    const val SPOT_META = """{"universe":[],"tokens":[]}"""

    val BTC: Market =
      HyperliquidOrderRules("BTC", "BTC", MarketKind.PERP, 5).let { rules ->
        Market("BTC", "BTC", "BTC/USD", MarketKind.PERP, "crypto", rules.maxPriceDecimals, 5, 40, false, rules)
      }
  }
}
