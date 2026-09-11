package xyz.mcxross.flare.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.UnavailableWalletVault
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.account.Ed25519Account

class WithdrawalFlowTest {
  private val prompt = VaultPrompt("Confirm withdrawal", "Confirm your identity")

  @Test
  fun ownerDestinationUsesOneTransaction() = runTest {
    fixture { f ->
      val states = f.accounts.withdrawUsdc("10", null, prompt).toList()
      assertIs<TransactionState.Committed>(states.last())
      assertEquals(1, f.trading.commands.size)
      assertIs<DecibelCommand.Withdraw>(f.trading.commands.single())
      assertNull(f.progress())
    }
  }

  @Test
  fun destinationTransferSharesApprovalAndRecordsBeforeSubmission() = runTest {
    fixture { f ->
      f.accounts.withdrawUsdc("10", "0x3", prompt).toList()
      assertEquals(listOf(true, false), f.trading.prompts.map { it.requireFreshAuthorization })
      assertIs<DecibelCommand.Withdraw>(f.trading.commands[0])
      val transfer = assertIs<DecibelCommand.TransferCollateral>(f.trading.commands[1])
      assertEquals("0x3", transfer.destination)
      assertEquals(10_000_000uL, transfer.amount)
      assertTrue(f.trading.recordedBeforeSubmission.all { it })
      assertNull(f.progress())
    }
  }

  @Test
  fun retryAfterDefiniteTransferFailureDoesNotWithdrawAgain() = runTest {
    fixture { f ->
      f.trading.rejectTransfer = true
      f.accounts.withdrawUsdc("10", "0x3", prompt).toList()
      assertTrue(f.progress()!!.withdrawalCommitted)
      assertNull(f.progress()!!.transferReference)
      f.trading.rejectTransfer = false
      f.accounts.withdrawUsdc("10", "0x3", prompt).toList()
      assertEquals(1, f.trading.commands.count { it is DecibelCommand.Withdraw })
      assertEquals(2, f.trading.commands.count { it is DecibelCommand.TransferCollateral })
      assertTrue(f.trading.prompts.last().requireFreshAuthorization)
      assertNull(f.progress())
    }
  }

  @Test
  fun uncertainWithdrawalReconcilesWithoutResubmissionOrDestinationChange() = runTest {
    fixture { f ->
      f.trading.uncertain = true
      f.accounts.withdrawUsdc("10", "0x3", prompt).toList()
      assertNotNull(f.progress()!!.withdrawalReference)
      assertIs<TransactionState.Pending>(
        f.accounts.withdrawUsdc("10", "0x3", prompt).toList().last()
      )
      assertEquals(1, f.trading.commands.size)
      assertFailsWith<IllegalArgumentException> {
        f.accounts.withdrawUsdc("10", "0x4", prompt).toList()
      }
      f.trading.uncertain = false
      f.trading.resolved = true
      f.accounts.withdrawUsdc("10", "0x3", prompt).toList()
      assertEquals(1, f.trading.commands.count { it is DecibelCommand.Withdraw })
      assertNull(f.progress())
    }
  }

  @Test
  fun insufficientBalanceDoesNotLeaveAnUnchangeableDraft() = runTest {
    fixture { f ->
      assertFailsWith<IllegalArgumentException> {
        f.accounts.withdrawUsdc("101", null, prompt).toList()
      }
      assertTrue(f.trading.commands.isEmpty())
      assertNull(f.progress())
    }
  }

  @Test
  fun anOwnerActionHandsTheTradingSessionBackWithoutAnotherPrompt() = runTest {
    fixture { f ->
      f.preferences.setApiWallet("0x9")

      f.accounts.withdrawUsdc("10", null, prompt).toList()

      assertEquals(listOf(false), f.sessions.tradingPrompts.map { it.requireFreshAuthorization })
    }
  }

  @Test
  fun apiOnlyProfileCannotWithdraw() = runTest {
    fixture { f ->
      f.preferences.registerProfile(
        AccountProfile("api", apiWalletAddress = "0x4", selectedSubaccount = "0x2")
      )
      assertFailsWith<IllegalStateException> {
        f.accounts.withdrawUsdc("10", null, prompt).toList()
      }
      assertTrue(f.trading.commands.isEmpty())
    }
  }
}

private suspend fun fixture(block: suspend (WithdrawalFixture) -> Unit) {
  val data =
    object : DataStore<Preferences> {
      override val data = MutableStateFlow(emptyPreferences())

      override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
        transform(data.value).also { data.value = it }
    }
  val preferences = AppPreferences(data)
  preferences.registerProfile(
    AccountProfile("owner", ownerAddress = "0x1", selectedSubaccount = "0x2")
  )
  val http =
    HttpClient(
      MockEngine {
        respond(
          """{"perp_equity_balance":100,"unrealized_pnl":0,"unrealized_funding_cost":0,"cross_margin_ratio":0,"maintenance_margin":0,"total_margin":0,"usdc_cross_withdrawable_balance":100,"usdc_isolated_withdrawable_balance":0}""",
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
    )
  val aptos = Aptos()
  try {
    val trading = WithdrawalTrading(preferences)
    val sessions = WithdrawalSessions()
    val accounts =
      DefaultAccountRepository(
        DecibelClient(http, aptos),
        sessions,
        trading,
        DefaultWalletRepository(ForegroundWalletVault(UnavailableWalletVault()), preferences),
        preferences,
      )
    block(WithdrawalFixture(preferences, trading, sessions, accounts))
  } finally {
    http.close()
    aptos.close()
  }
}

private class WithdrawalFixture(
  val preferences: AppPreferences,
  val trading: WithdrawalTrading,
  val sessions: WithdrawalSessions,
  val accounts: AccountRepository,
) {
  suspend fun progress() = preferences.values.first().profiles.first { it.id == "owner" }.withdrawal
}

private class WithdrawalSessions : SessionRepository {
  val tradingPrompts = mutableListOf<VaultPrompt>()

  override val status =
    MutableStateFlow<SessionStatus?>(SessionStatus(SessionRole.OWNER, "0x1", "0x2", Long.MAX_VALUE))

  override fun <T> bind(status: SessionStatus, operation: Flow<T>): Flow<T> = operation

  override suspend fun accessToken() = "test"

  override suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt): SessionStatus =
    error("Refresh unavailable")

  override suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus =
    error("Refresh unavailable")

  override suspend fun verifyApiCredential(
    account: Ed25519Account,
    subaccount: String,
  ): SessionStatus = error("Not used")

  override suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt): SessionStatus {
    tradingPrompts += prompt
    return SessionStatus(SessionRole.API, "0x9", subaccount, Long.MAX_VALUE)
  }

  override suspend fun useAnonymous(): SessionStatus = error("Not used")

  override suspend fun invalidate() {}
}

private class WithdrawalTrading(val preferences: AppPreferences) : TradingRepository {
  val commands = mutableListOf<DecibelCommand>()
  val prompts = mutableListOf<VaultPrompt>()
  val recordedBeforeSubmission = mutableListOf<Boolean>()
  var rejectTransfer = false
  var uncertain = false
  var resolved = false
  override val pendingTransactions = flowOf(emptyList<PendingTransaction>())

  override fun execute(
    command: DecibelCommand,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    onPrepared: suspend (String) -> Unit,
  ): Flow<TransactionState> = flow {
    commands += command
    prompts += prompt
    val hash = "0x" + commands.size.toString().padStart(64, '0')
    onPrepared(hash)
    val progress = preferences.values.first().profiles.first { it.id == "owner" }.withdrawal!!
    recordedBeforeSubmission +=
      if (command is DecibelCommand.Withdraw) progress.withdrawalReference == hash
      else progress.transferReference == hash
    if (uncertain) emit(TransactionState.Failed("Timeout", hash))
    else if (rejectTransfer && command is DecibelCommand.TransferCollateral)
      emit(TransactionState.Failed("Rejected", hash, definitelyNotSubmitted = true))
    else emit(TransactionState.Committed(hash))
  }

  override suspend fun transactionStatus(reference: String): TransactionState =
    if (resolved) TransactionState.Committed(reference) else TransactionState.Pending(reference)

  override suspend fun apiWalletAptBalance() = 0uL

  override fun topUpApiWallet(amountOctas: ULong, prompt: VaultPrompt): Flow<TransactionState> =
    emptyFlow()

  override suspend fun reconcilePending() = ReconciliationResult(0, 0)
}
