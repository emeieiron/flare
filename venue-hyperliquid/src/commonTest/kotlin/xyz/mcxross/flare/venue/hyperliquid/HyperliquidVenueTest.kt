package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.venue.Network
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
      "https://app.hyperliquid-testnet.xyz/explorer/tx/0xabc",
      testnet.explorer.transactionUrl("0xabc"),
    )
    assertEquals("https://app.hyperliquid.xyz/explorer/tx/0xabc", mainnet.explorer.transactionUrl("0xabc"))
    assertEquals(listOf("USDC"), testnet.chain.depositAssets)
    assertEquals(10, testnet.maxBuilderFeeBps)
    assertNull(testnet.vaultMinimums)
    assertNull(testnet.proxyUrl)
  }

  @Test
  fun setupEndsOnceTheWalletExists() {
    val setup = HyperliquidVenue().descriptor.setup

    assertFalse(setup.tradingAccounts)
    assertFalse(setup.openTradingAccount)
    assertFalse(setup.builderFee)
    assertFalse(setup.enableTrading)
  }

  @Test
  fun offersOnlyWhatHyperliquidHas() {
    val capabilities = HyperliquidVenue.Capabilities

    assertTrue(capabilities.spot && capabilities.twap && capabilities.vaults)
    assertFalse(capabilities.rewards || capabilities.sponsoredFees || capabilities.networkFeeTopUp)
    assertFalse(capabilities.inAppDeposit || capabilities.subaccounts || capabilities.perpSpotTransfer)
  }

  @Test
  fun theWalletsOwnAddressIsItsAccountAndNothingIsSent() = runTest {
    val preferences = memoryPreferences()
    val client = testClient(MarketResponses)
    val accounts = HyperliquidAccountRepository(client, preferences)
    val owner = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
    preferences.registerProfile(AccountProfile("owner_$owner", ownerAddress = owner))

    accounts.selectTradingAccount(owner, VaultPrompt("Test", "Test"))

    assertEquals(owner, preferences.values.first().selectedSubaccount)
    assertEquals(owner, accounts.snapshot.value.account)
    assertEquals(listOf(owner), accounts.subaccounts(owner).map { it.address })
    val trading = HyperliquidTradingRepository(client)
    val result =
      trading.execute(TradeAction.Withdraw(owner, "1"), VaultPrompt("Test", "Test")).first()
    assertTrue(assertIs<ActionState.Failed>(result).definitelyNotSubmitted)
    assertTrue(trading.pendingTransactions.first().isEmpty())
  }
}
