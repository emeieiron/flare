package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.model.ActionKind
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.TradeAction

class HyperliquidVenueTest {
  @Test
  fun describesHyperliquidOnEachNetwork() {
    val testnet = HyperliquidVenue().descriptor
    val mainnet = HyperliquidVenue(Network.MAINNET).descriptor

    assertEquals("hyperliquid", testnet.id)
    assertEquals("Hyperliquid", testnet.displayName)
    assertEquals(Network.TESTNET, testnet.network)
    assertEquals(
      "https://app.hyperliquid-testnet.xyz/explorer/tx/$TX_HASH",
      testnet.explorer.transactionUrl(TX_HASH),
    )
    assertEquals("https://app.hyperliquid.xyz/explorer/tx/$TX_HASH", mainnet.explorer.transactionUrl(TX_HASH))
    // Order ids and nonces aren't transactions, so they get no link.
    assertNull(mainnet.explorer.transactionUrl("123456789"))
    assertNull(mainnet.explorer.transactionUrl("1784361909247"))
    assertNull(mainnet.explorer.transactionUrl("0x0123456789abcdef0123456789abcdef"))
    assertEquals(listOf("USDC"), testnet.chain.depositAssets)
    assertEquals(10, testnet.maxBuilderFeeBps)
    assertNull(testnet.vaultMinimums)
    assertNull(testnet.proxyUrl)
  }

  @Test
  fun setupEnablesTradingAndOffersTheBuilderFeeOnlyWithABuilder() {
    val bare = HyperliquidVenue().descriptor
    val withBuilder = HyperliquidVenue(builderAddress = "0x8c967e73e7b15087c42a10d344cff4c96d877f1d").descriptor

    assertFalse(bare.setup.tradingAccounts || bare.setup.openTradingAccount)
    assertTrue(bare.setup.enableTrading)
    assertFalse(bare.setup.builderFee)
    assertNull(bare.builderAddress)
    assertTrue(withBuilder.setup.builderFee)
    assertFalse(withBuilder.setup.separateSpotBuilderApproval)
    assertEquals(5, withBuilder.builderFeeBps)
    assertFailsWith<IllegalArgumentException> { HyperliquidVenue(builderAddress = "0x1234") }
  }

  @Test
  fun offersOnlyWhatHyperliquidHas() {
    val capabilities = HyperliquidVenue.Capabilities

    assertTrue(capabilities.spot && capabilities.twap && capabilities.vaults)
    assertFalse(capabilities.rewards || capabilities.sponsoredFees || capabilities.networkFeeTopUp)
    assertFalse(capabilities.inAppDeposit || capabilities.subaccounts || capabilities.perpSpotTransfer)
    assertTrue(capabilities.batchCancel)
    assertNull(capabilities.unavailableReason(ActionKind.TRADING))
    assertEquals(TWAP_UNAVAILABLE, capabilities.unavailableReason(ActionKind.TWAP))
    assertEquals(FUNDS_UNAVAILABLE, capabilities.unavailableReason(ActionKind.FUNDS))
    assertNull(capabilities.unavailableReason(ActionKind.SETUP))
  }

  @Test
  fun explainsHowToFundTheAccount() {
    val testnet = HyperliquidVenue().descriptor.receive!!
    val mainnet = HyperliquidVenue(Network.MAINNET).descriptor.receive!!

    assertTrue("Hyperliquid" in testnet.summary && "bridge" in testnet.summary)
    assertEquals("https://app.hyperliquid-testnet.xyz/drip", testnet.faucet?.url)
    assertTrue("mainnet" in testnet.faucet?.note.orEmpty())
    assertNull(mainnet.faucet)
    assertEquals(testnet.warning, mainnet.warning)
  }

  @Test
  fun theWalletsOwnAddressIsItsAccountAndNothingIsSent() = runTest {
    val preferences = memoryPreferences()
    val client = testClient(MarketResponses)
    val accounts = HyperliquidAccountRepository(client, preferences, unusedSetup(client, preferences))
    val owner = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
    preferences.registerProfile(AccountProfile("owner_$owner", ownerAddress = owner))

    accounts.selectTradingAccount(owner, VaultPrompt("Test", "Test"))

    assertEquals(owner, preferences.values.first().selectedSubaccount)
    assertEquals(owner, accounts.snapshot.value.account)
    assertEquals(listOf(owner), accounts.subaccounts(owner).map { it.address })
    val harness = SetupHarness()
    val trading = HyperliquidTradingRepository(client, harness.actions, harness.setup)
    val result =
      trading.execute(TradeAction.Withdraw(owner, "1"), VaultPrompt("Test", "Test")).first()
    assertTrue(assertIs<ActionState.Failed>(result).definitelyNotSubmitted)
    assertTrue(trading.pendingTransactions.first().isEmpty())
  }

  private companion object {
    const val TX_HASH = "0x99ec2abc3983011c9b650424daff7000002742a1d4861fee3db4d60ef886db07"
  }
}
