package xyz.mcxross.flare.data

import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.decibel.api.TransactionState

class TradingWalletSetupTest {
  @Test
  fun freshSetupCreatesAndDelegatesOnce() = runTest {
    val actions = SetupActions()
    TradingWalletSetup().prepare(actions)
    assertEquals(listOf("create", "delegations", "pending", "delegate", "verify"), actions.events)
  }

  @Test
  fun existingDelegationDoesNotReplaceKeyOrSubmit() = runTest {
    val actions =
      SetupActions().apply {
        address = "api"
        delegated = true
      }
    TradingWalletSetup().prepare(actions)
    assertEquals(listOf("delegations", "verify"), actions.events)
  }

  @Test
  fun unavailableDelegationCheckNeverCreatesAReplacementKey() = runTest {
    val actions =
      SetupActions().apply {
        address = "api"
        verificationFails = true
      }
    assertFailsWith<IllegalStateException> { TradingWalletSetup().prepare(actions) }
    assertEquals(listOf("delegations"), actions.events)
    assertEquals("api", actions.address)
  }

  @Test
  fun delayedIndexingRetriesOnlyVerification() = runTest {
    val actions = SetupActions().apply { verificationFailures = 3 }
    TradingWalletSetup().prepare(actions)
    assertEquals(1, actions.events.count { it == "create" })
    assertEquals(1, actions.events.count { it == "delegate" })
    assertEquals(4, actions.events.count { it == "verify" })
  }

  @Test
  fun retryAfterIndexingFailureReusesKeyAndDelegation() = runTest {
    val actions = SetupActions().apply { verificationFailures = 5 }
    val setup = TradingWalletSetup()
    assertFailsWith<IllegalStateException> { setup.prepare(actions) }
    setup.prepare(actions)
    assertEquals(1, actions.events.count { it == "create" })
    assertEquals(1, actions.events.count { it == "delegate" })
  }

  @Test
  fun uncertainDelegationStopsWithoutVerifyingOrRetrying() = runTest {
    val actions =
      SetupActions().apply {
        result = TransactionState.Failed("timeout", hash = "sponsor:reference")
      }
    val error = assertFailsWith<IllegalStateException> { TradingWalletSetup().prepare(actions) }
    assertTrue(error.message.orEmpty().contains("sponsor:reference"))
    assertEquals(1, actions.events.count { it == "delegate" })
    assertFalse("verify" in actions.events)
    assertEquals("api", actions.address)
  }

  @Test
  fun pendingTransactionBlocksAnotherDelegation() = runTest {
    val actions = SetupActions().apply { pending = true }
    assertFailsWith<IllegalStateException> { TradingWalletSetup().prepare(actions) }
    assertFalse("delegate" in actions.events)
  }

  @Test
  fun cancellationPropagatesAndReleasesSetupLock() = runTest {
    val actions = SetupActions().apply { cancel = true }
    val setup = TradingWalletSetup()
    assertFailsWith<CancellationException> { setup.prepare(actions) }
    actions.cancel = false
    setup.prepare(actions)
    assertEquals(1, actions.events.count { it == "delegate" })
  }
}

private class SetupActions : TradingWalletSetupActions {
  val events = mutableListOf<String>()
  var address: String? = null
  var delegated = false
  var verificationFails = false
  var pending = false
  var cancel = false
  var verificationFailures = 0
  var result: TransactionState = TransactionState.Committed("hash")

  override suspend fun existingWallet() = address

  override suspend fun createWallet(): String {
    events += "create"
    address = "api"
    return "api"
  }

  override suspend fun isDelegated(address: String): Boolean {
    events += "delegations"
    if (verificationFails) error("Unavailable")
    return delegated
  }

  override suspend fun requireNoPendingTransactions() {
    events += "pending"
    check(!pending)
  }

  override suspend fun delegate(): TransactionState {
    events += "delegate"
    delegated = result is TransactionState.Committed
    return result
  }

  override suspend fun verifyTradingKey() {
    events += "verify"
    if (cancel) throw CancellationException()
    if (verificationFailures-- > 0) error("Indexing")
  }
}
