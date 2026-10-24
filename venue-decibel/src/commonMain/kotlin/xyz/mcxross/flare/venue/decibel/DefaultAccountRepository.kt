package xyz.mcxross.flare.venue.decibel

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.SessionStatus
import xyz.mcxross.flare.data.SetupTransactionException
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.AccountOpenOrders
import xyz.mcxross.flare.decibel.api.AccountOverviewTopic
import xyz.mcxross.flare.decibel.api.AccountPositions
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.DecibelStreamData
import xyz.mcxross.flare.decibel.api.DecibelStreamTopic
import xyz.mcxross.flare.decibel.api.OrderUpdates
import xyz.mcxross.flare.decibel.api.StreamEvent
import xyz.mcxross.flare.decibel.api.UserTrades
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.DecimalInput
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.Page
import xyz.mcxross.flare.decibel.model.Position
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.UserCampaigns
import xyz.mcxross.flare.decibel.model.toChainUnits
import xyz.mcxross.flare.data.RewardsRepository
import xyz.mcxross.flare.data.VaultsRepository
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.WithdrawalContinuation
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.Fill
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.ReferralSummary
import xyz.mcxross.flare.venue.model.SeriesPoint
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingAccountStatus
import xyz.mcxross.flare.venue.model.TradingFees
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.VaultCollateral
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.flare.decibel.model.FundMovement as DecibelFundMovement
import xyz.mcxross.flare.decibel.model.FundingPayment as DecibelFundingPayment
import xyz.mcxross.flare.decibel.model.MarketTrade as DecibelMarketTrade
import xyz.mcxross.flare.decibel.model.TradingStreak as DecibelTradingStreak

/**
 * Decibel's account, read through the Flare Worker: REST snapshots refreshed by the account stream,
 * mapped to the neutral models. Setup and funding actions run through [DecibelTrading], signed
 * by the owner or the device trading key.
 */
class DefaultAccountRepository(
  private val client: DecibelClient,
  private val sessions: AptosSessionRepository,
  private val trading: DecibelTrading,
  private val wallets: AptosSigningWallet,
  private val preferences: AppPreferences,
) : AccountRepository, VaultsRepository, RewardsRepository {
  private val mutableSnapshot = MutableStateFlow(AccountSnapshot())
  private val mutableHistory = MutableStateFlow(AccountHistorySnapshot())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val refreshMutex = Mutex()
  private val readSessionMutex = Mutex()
  private val historyMutex = Mutex()
  // Scoped to the reviewed create-and-enable operation, never a general owner unlock.
  private var setupApproval: Triple<String, Long, Long>? = null
  private val withdrawalMutex = Mutex()
  private val tradingWalletSetup = TradingWalletSetup()
  private var streamJob: Job? = null
  // Positions as Decibel last sent them, so exits read from open orders can be joined to them again.
  private var venuePositions: List<Position> = emptyList()
  private var streamingAccount: String? = null
  override val snapshot: StateFlow<AccountSnapshot> = mutableSnapshot.asStateFlow()
  override val history: StateFlow<AccountHistorySnapshot> = mutableHistory.asStateFlow()

  override suspend fun delegations(): List<TradingKey> {
    val account = preferences.values.first().selectedSubaccount ?: error("Select a trading account")
    val onChain = runSuspendCatching { client.subaccounts.delegations(account) }.getOrNull()
    if (onChain is AptosResult.Success) return onChain.value.toTradingKeys()
    ensureReadSession(account)
    return client.accounts.delegations(account).toTradingKeys()
  }

  override suspend fun revokeDelegation(address: String, prompt: VaultPrompt): ActionState {
    val account = preferences.values.first().selectedSubaccount ?: error("Select a trading account")
    var result: ActionState = ActionState.Failed("Revocation failed")
    trading
      .executeCommand(
        DecibelCommand.RevokeDelegation(account, address),
        prompt.copy(requireFreshAuthorization = true),
      )
      .collect { result = it }
    return result
  }

  override suspend fun restoreTrading() =
    restoreTrading(VaultPrompt("Open Flare", "Confirm your identity"))

  private suspend fun restoreTrading(prompt: VaultPrompt) {
    val saved = preferences.values.first()
    val subaccount = saved.selectedSubaccount
    if (subaccount == null) {
      streamJob?.cancel()
      streamingAccount = null
      venuePositions = emptyList()
      mutableSnapshot.value = AccountSnapshot()
      mutableHistory.value = AccountHistorySnapshot()
      return
    }
    if (mutableSnapshot.value.account != subaccount) {
      streamJob?.cancel()
      streamingAccount = null
      mutableSnapshot.value = AccountSnapshot(account = subaccount)
      mutableHistory.value = AccountHistorySnapshot(account = subaccount)
    }
    try {
      ensureReadSession(subaccount)
      refresh()
      refreshHistory()
      startLive()
    } catch (error: Exception) {
      if (error is kotlinx.coroutines.CancellationException) throw error
      val message = "Account access could not be restored. Check the selected wallet and trading account."
      mutableSnapshot.update { it.copy(account = subaccount, loading = false, stale = true, error = message) }
      mutableHistory.update { it.copy(account = subaccount, loading = false, stale = true, error = message) }
      throw error
    }
  }

  override suspend fun importTradingKey(key: String, subaccount: String, prompt: VaultPrompt) {
    require(subaccount.isNotBlank()) { "Enter a trading-account address" }
    wallets.importAptosApiWallet(key, prompt) { account ->
      sessions.verifyApiCredential(account, subaccount)
    }
    selectTradingAccount(subaccount, prompt)
  }

  override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<TradingAccount> {
    val owner =
      wallets.profile.first().ownerAddress
        ?: error("An owner wallet is required to discover subaccounts")
    sessions.authenticateOwner(subaccount = null, prompt = prompt)
    return subaccounts(owner)
  }

  override suspend fun subaccounts(owner: String): List<TradingAccount> =
    client.accounts.subaccounts(owner).filter(Subaccount::isActive).map { it.toTradingAccount() }

  override suspend fun tradingAccountStatus(address: String): TradingAccountStatus? =
    runSuspendCatching { (client.subaccounts.status(address) as? AptosResult.Success)?.value }
      .getOrNull()
      ?.toTradingAccountStatus()

  override suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt) {
    require(subaccount.isNotBlank()) { "Select a trading account" }
    preferences.setSelectedSubaccount(subaccount)
    trading.reconcilePending()
    restoreTrading(prompt)
  }

  /** Confirms the device trading key can act for [subaccount] before setup reports success. */
  private suspend fun verifyTradingKey(subaccount: String, prompt: VaultPrompt) {
    sessions.authenticateApi(subaccount = subaccount, prompt = prompt)
    refresh()
    refreshHistory()
    startLive()
  }

  override suspend fun createSubaccount(
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): ActionState {
    val saved = preferences.values.first()
    val owner = saved.ownerAddress ?: error("An owner key is required")
    sessions.authenticateOwner(null, prompt.copy(requireFreshAuthorization = false))
    val reference = saved.profiles.first { it.id == saved.activeProfileId }.creationReference
    if (reference != null) {
      // An earlier attempt may have gone through after its journal entry settled, so the chain has
      // the last word before anything is created again.
      val resolved = trading.transactionStatus(reference)
      val isPending = trading.pendingTransactions.first().any { it.operation == "CREATE_SUBACCOUNT" }
      when {
        resolved is ActionState.Committed -> {
          preferences.setTradingAccountOpened(saved.activeProfileId)
          preferences.setCreationReference(saved.activeProfileId, null)
          return resolved
        }
        resolved is ActionState.Failed && resolved.committed ->
          preferences.setCreationReference(saved.activeProfileId, null)
        isPending -> return resolved
        else -> preferences.setCreationReference(saved.activeProfileId, null)
      }
    }
    trading.reconcilePending()
    check(trading.pendingTransactions.first().none { it.operation == "CREATE_SUBACCOUNT" }) {
      "Account creation is pending. Try again after confirmation."
    }
    val approvalStartedAt = Clock.System.now().toEpochMilliseconds()
    var terminal: ActionState = ActionState.Failed("Subaccount transaction did not start")
    trading
      .executeCommand(
        DecibelCommand.CreateSubaccount,
        prompt.copy(requireFreshAuthorization = !insideConfirmedSetup()),
        feePayment = feePayment,
        onPrepared = { preferences.setCreationReference(saved.activeProfileId, it) },
      )
      .collect { state ->
        terminal = state
        if (state is ActionState.Failed && (state.definitelyNotSubmitted || state.committed)) {
          preferences.setCreationReference(saved.activeProfileId, null)
        }
        if (state is ActionState.Committed) {
          preferences.setTradingAccountOpened(saved.activeProfileId)
          preferences.setCreationReference(saved.activeProfileId, null)
          setupApproval =
            Triple(saved.activeProfileId, wallets.authorizationGeneration, approvalStartedAt)
        }
      }
    return terminal
  }

  override suspend fun <T> confirmedSetup(prompt: VaultPrompt, block: suspend () -> T): T {
    if (!insideConfirmedSetup()) wallets.confirmIdentity(prompt)
    return withContext(ConfirmedSetup()) { block() }
  }

  private suspend fun insideConfirmedSetup(): Boolean = currentCoroutineContext()[ConfirmedSetup] != null

  override suspend fun openTradingAccount(prompt: VaultPrompt): String {
    val saved = preferences.values.first()
    saved.selectedSubaccount?.let { return it }
    val owner = saved.ownerAddress ?: error("An owner key is required")
    val profile = saved.profiles.first { it.id == saved.activeProfileId }
    // A wallet Flare made can't have an account it didn't open, so an account on chain is the one an
    // earlier attempt opened. A first attempt has nothing to look for.
    if (profile.tradingAccountOpened || profile.creationReference != null) {
      subaccounts(owner).firstOrNull()?.let { return it.address }
    }
    if (!profile.tradingAccountOpened) {
      val result = createSubaccount(prompt, FeePayment.SPONSORED)
      if (result !is ActionState.Committed) throw SetupTransactionException(result)
    }
    return awaitSubaccount(owner)
  }

  /** Decibel lists a new account a moment after it's opened; this waits, patiently, for it to appear. */
  private suspend fun awaitSubaccount(owner: String): String {
    var wait = 1_000L
    repeat(OPENED_ACCOUNT_CHECKS) {
      runSuspendCatching { subaccounts(owner).firstOrNull()?.address }.getOrNull()?.let { return it }
      delay(wait)
      wait = (wait * 2).coerceAtMost(8_000L)
    }
    error("Your trading account is open but isn’t showing yet. Try again in a moment.")
  }

  override suspend fun delegateApiWallet(
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): ActionState {
    val saved = preferences.values.first()
    val subaccount =
      saved.selectedSubaccount ?: error("Select a trading account before enabling trading")
    val apiAddress = saved.apiWalletAddress ?: error("Generate a device trading key first")
    val approval = setupApproval
    setupApproval = null
    val reviewedSetup =
      approval != null &&
        approval.first == saved.activeProfileId &&
        approval.second == wallets.authorizationGeneration &&
        Clock.System.now().toEpochMilliseconds() - approval.third < SETUP_APPROVAL_WINDOW_MS
    var terminal: ActionState = ActionState.Failed("Delegation transaction did not start")
    trading
      .executeCommand(
        command = DecibelCommand.DelegateTrading(subaccount, apiAddress),
        prompt = prompt.copy(requireFreshAuthorization = !reviewedSetup && !insideConfirmedSetup()),
        feePayment = feePayment,
      )
      .collect { state -> terminal = state }
    return terminal
  }

  override suspend fun prepareTradingWallet(prompt: VaultPrompt, feePayment: FeePayment) {
    val subaccount =
      preferences.values.first().selectedSubaccount
        ?: error("Select a trading account before enabling trading")
    tradingWalletSetup.prepare(
      object : TradingWalletSetupActions {
        override suspend fun existingWallet() = wallets.profile.first().apiWalletAddress

        override suspend fun createWallet() = wallets.createApiWallet(prompt)

        override suspend fun isDelegated(address: String): Boolean {
          val delegations = client.accounts.delegations(subaccount)
          val now = Clock.System.now().epochSeconds
          return delegations.any {
            it.delegate.sameAptosAddress(address) &&
              (it.canTradeAllPerpMarkets || it.canTradeAllSpotMarkets) &&
              (it.expirationTimeSeconds?.let { expiry -> expiry == 0L || expiry > now } ?: true)
          }
        }

        override suspend fun requireNoPendingTransactions() {
          trading.reconcilePending()
          check(trading.pendingTransactions.first().isEmpty()) {
            "Resolve pending transactions before submitting another delegation"
          }
        }

        override suspend fun delegate() = delegateApiWallet(prompt, feePayment)

        override suspend fun verifyTradingKey() =
          this@DefaultAccountRepository.verifyTradingKey(subaccount, prompt)
      }
    )
  }

  private suspend fun resolveBuilderAddress(builderAddress: String): String {
    val trimmed = builderAddress.trim()
    if (trimmed.isBlank()) return trimmed
    return runCatching {
      val subaccounts = client.accounts.subaccounts(trimmed)
      subaccounts.firstOrNull { it.isPrimary && it.isActive }?.address
        ?: subaccounts.firstOrNull { it.isActive }?.address
        ?: subaccounts.firstOrNull()?.address
        ?: trimmed
    }.getOrDefault(trimmed)
  }

  override suspend fun approveBuilderFee(
    builderAddress: String,
    feeBps: UInt,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    product: MarketKind,
  ): ActionState {
    require(feeBps in 1u..10u) { "Builder fee must be between 1 and 10 bps (up to 0.10%)" }
    val saved = preferences.values.first()
    val subaccount = saved.selectedSubaccount ?: error("Select a trading account")
    val resolvedBuilder = resolveBuilderAddress(builderAddress)
    val units = feeBps.toULong() * 100uL
    val command =
      when (product) {
        MarketKind.PERP -> DecibelCommand.ApproveMaxBuilderFee(subaccount, resolvedBuilder, units)
        MarketKind.SPOT -> DecibelCommand.ApproveMaxSpotBuilderFee(subaccount, resolvedBuilder, units)
      }
    var terminal: ActionState = ActionState.Failed("Approval transaction did not start")
    trading
      .executeCommand(
        command = command,
        prompt = prompt.copy(requireFreshAuthorization = !insideConfirmedSetup()),
        feePayment = feePayment,
      )
      .collect { state -> terminal = state }
    if (terminal is ActionState.Committed) {
      when (product) {
        MarketKind.PERP ->
          preferences.setBuilderSupport(
            builderAddress = resolvedBuilder,
            builderFeeBps = feeBps.toInt(),
            builderApproved = true,
          )
        MarketKind.SPOT -> preferences.setSpotBuilderApproved(true, resolvedBuilder)
      }
    }
    return terminal
  }

  override suspend fun revokeBuilderFee(
    builderAddress: String,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    product: MarketKind,
  ): ActionState {
    val saved = preferences.values.first()
    val subaccount = saved.selectedSubaccount ?: error("Select a trading account")
    val resolvedBuilder = resolveBuilderAddress(builderAddress)
    val command =
      when (product) {
        MarketKind.PERP -> DecibelCommand.RevokeMaxBuilderFee(subaccount, resolvedBuilder)
        MarketKind.SPOT -> DecibelCommand.RevokeMaxSpotBuilderFee(subaccount, resolvedBuilder)
      }
    var terminal: ActionState = ActionState.Failed("Revocation transaction did not start")
    trading
      .executeCommand(
        command = command,
        prompt = prompt.copy(requireFreshAuthorization = true),
        feePayment = feePayment,
      )
      .collect { state -> terminal = state }
    // The rate stays: it still applies to the other product, and returns if this one is approved again.
    if (terminal is ActionState.Committed) {
      when (product) {
        MarketKind.PERP -> preferences.setBuilderApproved(false)
        MarketKind.SPOT -> preferences.setSpotBuilderApproved(false, resolvedBuilder)
      }
    }
    return terminal
  }

  override fun depositUsdc(
    amount: String,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<ActionState> = flow {
    val subaccount =
      preferences.values.first().selectedSubaccount ?: error("Select a trading account")
    val units = DecimalInput(amount).toChainUnits("USDC amount", USDC_DECIMALS).getOrThrow()
    require(units > 0uL) { "Enter an amount greater than zero" }
    try {
      emitAll(
        trading.executeCommand(
          command =
            DecibelCommand.Deposit(
              subaccount = subaccount,
              assetMetadata = client.config.deployment.usdcMetadataAddress,
              amount = units,
            ),
          prompt = prompt.copy(requireFreshAuthorization = true),
          feePayment = feePayment,
        )
      )
    } finally {
      runSuspendCatching { restoreTrading() }
    }
  }

  override fun withdrawUsdc(
    amount: String,
    destination: String?,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<ActionState> = flow {
    withdrawalMutex.withLock {
      val saved = preferences.values.first()
      val owner = saved.ownerAddress ?: error("Use a device that holds the owner key")
      val subaccount = saved.selectedSubaccount ?: error("Select a trading account")
      val target = destination?.takeIf(String::isNotBlank) ?: owner
      AccountAddress.fromString(target)
      val units = DecimalInput(amount).toChainUnits("USDC amount", USDC_DECIMALS).getOrThrow()
      require(units > 0uL) { "Enter an amount greater than zero" }
      val pending = saved.profiles.first { it.id == saved.activeProfileId }.withdrawal
      require(
        pending == null ||
          (pending.subaccount.sameAptosAddress(subaccount) &&
            pending.destination.sameAptosAddress(target) &&
            pending.amount == amount)
      ) {
        "Continue the previous withdrawal before starting another"
      }
      var progress = pending ?: WithdrawalContinuation(subaccount, target, amount)
      suspend fun save() {
        preferences.setWithdrawal(saved.activeProfileId, progress)
      }
      suspend fun verifySelection() {
        val current = preferences.values.first()
        check(
          current.activeProfileId == saved.activeProfileId &&
            current.selectedSubaccount == subaccount
        ) {
          "The selected account changed. Review the withdrawal again."
        }
      }
      save()
      val approvalGeneration = wallets.authorizationGeneration
      var approvedThisVisit = false
      try {
        val submitted = progress.withdrawalReference
        if (!progress.withdrawalCommitted && submitted != null) {
          // An interrupted withdrawal is resolved on chain before anything is sent again.
          when (val resolved = ownerTransactionStatus(subaccount, submitted, prompt)) {
            is ActionState.Committed -> {
              progress =
                progress.copy(withdrawalCommitted = true, withdrawalReference = resolved.reference)
              save()
            }
            is ActionState.Failed -> {
              if (resolved.committed) preferences.setWithdrawal(saved.activeProfileId, null)
              emit(resolved)
              return@withLock
            }
            else -> {
              emit(resolved)
              return@withLock
            }
          }
        }
        if (!progress.withdrawalCommitted) {
          verifySelection()
          ensureReadSession(subaccount)
          val balance = client.accounts.overview(subaccount).crossWithdrawableBalance
          require(balance.isFinite() && balance >= 0 && units <= (balance * 1_000_000).toULong()) {
            "Amount exceeds the withdrawable balance"
          }
          var terminal: ActionState? = null
          trading
            .executeCommand(
              DecibelCommand.Withdraw(
                subaccount,
                client.config.deployment.usdcMetadataAddress,
                units,
              ),
              prompt.copy(requireFreshAuthorization = true),
              feePayment,
              onPrepared = {
                progress = progress.copy(withdrawalReference = it)
                save()
              },
            )
            .collect { state ->
              terminal = state
              if (state is ActionState.Committed) {
                progress =
                  progress.copy(withdrawalCommitted = true, withdrawalReference = state.reference)
                save()
              } else emit(state)
            }
          if (terminal !is ActionState.Committed) {
            if (
              (terminal as? ActionState.Failed)?.let {
                it.definitelyNotSubmitted || it.committed || progress.withdrawalReference == null
              } == true
            ) {
              preferences.setWithdrawal(saved.activeProfileId, null)
            }
            return@withLock
          }
          approvedThisVisit = true
        }
        val withdrawn = checkNotNull(progress.withdrawalReference)
        if (target.sameAptosAddress(owner)) {
          preferences.setWithdrawal(saved.activeProfileId, null)
          emit(ActionState.Committed(withdrawn))
          return@withLock
        }
        val transferred = progress.transferReference
        if (transferred != null) {
          val resolved = ownerTransactionStatus(subaccount, transferred, prompt)
          if (resolved is ActionState.Committed)
            preferences.setWithdrawal(saved.activeProfileId, null)
          if (resolved is ActionState.Failed && resolved.committed) {
            progress = progress.copy(transferReference = null)
            save()
          }
          emit(resolved)
          return@withLock
        }
        verifySelection()
        trading
          .executeCommand(
            DecibelCommand.TransferCollateral(
              subaccount,
              client.config.deployment.usdcMetadataAddress,
              target,
              units,
            ),
            prompt.copy(
              requireFreshAuthorization =
                !approvedThisVisit || wallets.authorizationGeneration != approvalGeneration
            ),
            feePayment,
            onPrepared = {
              progress = progress.copy(transferReference = it)
              save()
            },
          )
          .collect { state ->
            if (state is ActionState.Committed)
              preferences.setWithdrawal(saved.activeProfileId, null)
            if (
              state is ActionState.Failed && (state.definitelyNotSubmitted || state.committed)
            ) {
              progress = progress.copy(transferReference = null)
              save()
            }
            emit(state)
          }
      } catch (error: Throwable) {
        if (!progress.withdrawalCommitted && progress.withdrawalReference == null) {
          preferences.setWithdrawal(saved.activeProfileId, null)
        }
        throw error
      } finally {
        runSuspendCatching { restoreTrading() }
      }
    }
  }

  private suspend fun ownerTransactionStatus(
    subaccount: String,
    reference: String,
    prompt: VaultPrompt,
  ): ActionState {
    val current = sessions.status.value
    val owner = wallets.profile.first().ownerAddress
    val status =
      if (
        current?.role == SessionRole.OWNER &&
          current.walletAddress?.sameAptosAddress(owner ?: "0x0") == true &&
          current.subaccount?.sameAptosAddress(subaccount) == true &&
          current.expiresAt > Clock.System.now().toEpochMilliseconds() + SESSION_RENEWAL_MARGIN_MS
      )
        current
      else sessions.authenticateOwner(subaccount, prompt.copy(requireFreshAuthorization = false))
    return sessions.bind(status, flow { emit(trading.transactionStatus(reference)) }).first()
  }

  private suspend fun ensureReadSession(account: String): SessionStatus = readSessionMutex.withLock {
    val saved = preferences.values.first()
    val current = sessions.status.value
    // Reading an owned account must not depend on a device trading key's delegation.
    val expectedRole = if (saved.ownerAddress != null) SessionRole.OWNER else SessionRole.API
    val expected = saved.ownerAddress ?: saved.apiWalletAddress
    if (
      expected != null && current?.role == expectedRole &&
        current.walletAddress?.sameAptosAddress(expected) == true &&
        current.subaccount?.sameAptosAddress(account) == true &&
        current.expiresAt > Clock.System.now().toEpochMilliseconds() + SESSION_RENEWAL_MARGIN_MS
    )
      return@withLock current
    wallets.requireAuthorization(wallets.authorizationGeneration)
    val prompt = VaultPrompt("Open Flare", "Confirm your identity")
    if (saved.ownerAddress != null) sessions.authenticateOwner(account, prompt)
    else sessions.ensureTrading(account, prompt)
  }

  private suspend fun <T> readAccount(account: String, request: suspend () -> T): T {
    val session = ensureReadSession(account)
    return sessions.bind(session, flow { emit(request()) }).first()
  }

  override suspend fun refresh() = refreshMutex.withLock {
    val account = preferences.values.first().selectedSubaccount
    if (account == null) {
      mutableSnapshot.value = AccountSnapshot(error = "No trading account is selected")
      return@withLock
    }
    val readSession = runSuspendCatching { ensureReadSession(account) }.getOrNull()
    if (readSession == null) {
      mutableSnapshot.update {
        (if (it.account == account) it else AccountSnapshot(account = account)).copy(
          loading = false, stale = true,
          error = "Account access is unavailable. Check the selected wallet and trading account.",
        )
      }
      return@withLock
    }
    mutableSnapshot.update { it.copy(account = account, loading = true, error = null) }
    runSuspendCatching {
      sessions.bind(readSession, flow {
        emit(coroutineScope {
          val overview = async { client.accounts.overview(account) }
          val positions = async { client.accounts.positions(account) }
          val orders = async { client.accounts.openOrders(account) }
          Triple(overview.await(), positions.await(), orders.await())
        })
      }).first()
    }
      .onSuccess { (overview, positions, orders) ->
        if (preferences.values.first().selectedSubaccount != account) return@onSuccess
        venuePositions = positions.filterNot(Position::isDeleted)
        val openOrders = orders.items.map { it.toOpenOrder() }.withWholePositionExits(venuePositions)
        mutableSnapshot.value =
          AccountSnapshot(
            account = account,
            overview = overview.toAccountOverview(),
            positions = venuePositions.map { it.toPosition(openOrders) },
            openOrders = openOrders,
            stale = false,
          )
      }
      .onFailure { error ->
        mutableSnapshot.update {
          it.copy(
            loading = false,
            stale = true,
            error = error.message ?: "Account data is unavailable",
          )
        }
      }
  }

  override suspend fun refreshHistory() = historyMutex.withLock {
    val account = preferences.values.first().selectedSubaccount
    if (account == null) {
      mutableHistory.value = AccountHistorySnapshot(error = "No trading account is selected")
      return@withLock
    }
    val readSession = runSuspendCatching { ensureReadSession(account) }.getOrNull()
    if (readSession == null) {
      mutableHistory.update {
        (if (it.account == account) it else AccountHistorySnapshot(account = account)).copy(
          loading = false, stale = true,
          error = "Account access is unavailable. Check the selected wallet and trading account.",
        )
      }
      return@withLock
    }
    mutableHistory.update { it.copy(account = account, loading = true, error = null) }
    runSuspendCatching {
      sessions.bind(readSession, flow {
        emit(coroutineScope {
          val orders = async { client.accounts.orderHistory(account, HISTORY_PAGE_SIZE, 0) }
          val trades = async { client.accounts.tradeHistory(account, HISTORY_PAGE_SIZE, 0) }
          val funding = async { client.accounts.fundingHistory(account, HISTORY_PAGE_SIZE, 0) }
          val transfers = async { client.accounts.fundHistory(account, HISTORY_PAGE_SIZE, 0) }
          object {
            val orders = orders
            val trades = trades
            val funding = funding
            val transfers = transfers
          }
        })
      }).first()
    }
      .onSuccess { data ->
        val orders = data.orders.await()
        val trades = data.trades.await()
        val funding = data.funding.await()
        val transfers = data.transfers.await()
        if (preferences.values.first().selectedSubaccount != account) return@onSuccess
        mutableHistory.value =
          AccountHistorySnapshot(
            account = account,
            orders = orders.items.map { it.toOrderHistoryEntry() },
            trades = trades.items.map { it.toFill() },
            funding = funding.items.map { it.toFundingPayment() },
            transfers = transfers.items.map { it.toFundMovement() },
            ordersHasMore = orders.hasMore(0),
            tradesHasMore = trades.hasMore(0),
            fundingHasMore = funding.hasMore(0),
            transfersHasMore = transfers.hasMore(0),
            stale = false,
          )
      }
      .onFailure { error ->
        mutableHistory.update {
          it.copy(
            loading = false,
            stale = true,
            error = error.message ?: "Account history is unavailable",
          )
        }
      }
  }

  override suspend fun loadMoreHistory(kind: AccountHistoryKind) = historyMutex.withLock {
    val current = mutableHistory.value
    val account = current.account ?: return@withLock
    val shouldLoad =
      when (kind) {
        AccountHistoryKind.ORDERS -> current.ordersHasMore
        AccountHistoryKind.TRADES -> current.tradesHasMore
        AccountHistoryKind.FUNDING -> current.fundingHasMore
        AccountHistoryKind.TRANSFERS -> current.transfersHasMore
      }
    if (!shouldLoad || current.loading || current.loadingMore) return@withLock
    mutableHistory.update { it.copy(loadingMore = true, error = null) }
    runSuspendCatching {
      when (kind) {
        AccountHistoryKind.ORDERS ->
          HistoryResult.Orders(
            client.accounts.orderHistory(account, HISTORY_PAGE_SIZE, current.orders.size)
          )
        AccountHistoryKind.TRADES ->
          HistoryResult.Trades(
            client.accounts.tradeHistory(account, HISTORY_PAGE_SIZE, current.trades.size)
          )
        AccountHistoryKind.FUNDING ->
          HistoryResult.Funding(
            client.accounts.fundingHistory(account, HISTORY_PAGE_SIZE, current.funding.size)
          )
        AccountHistoryKind.TRANSFERS ->
          HistoryResult.Transfers(
            client.accounts.fundHistory(account, HISTORY_PAGE_SIZE, current.transfers.size)
          )
      }
    }
      .onSuccess { result ->
        mutableHistory.update { state ->
          when (result) {
            is HistoryResult.Orders ->
              state.copy(
                orders = state.orders + result.page.items.map { it.toOrderHistoryEntry() },
                ordersHasMore = result.page.hasMore(state.orders.size),
                loadingMore = false,
              )
            is HistoryResult.Trades ->
              state.copy(
                trades = state.trades + result.page.items.map { it.toFill() },
                tradesHasMore = result.page.hasMore(state.trades.size),
                loadingMore = false,
              )
            is HistoryResult.Funding ->
              state.copy(
                funding = state.funding + result.page.items.map { it.toFundingPayment() },
                fundingHasMore = result.page.hasMore(state.funding.size),
                loadingMore = false,
              )
            is HistoryResult.Transfers ->
              state.copy(
                transfers = state.transfers + result.page.items.map { it.toFundMovement() },
                transfersHasMore = result.page.hasMore(state.transfers.size),
                loadingMore = false,
              )
          }
        }
      }
      .onFailure { error ->
        mutableHistory.update {
          it.copy(
            loadingMore = false,
            error = error.message ?: "Unable to load more account history",
          )
        }
      }
  }

  override fun startLive() {
    val account = mutableSnapshot.value.account ?: return
    if (
      sessions.status.value?.role == null || sessions.status.value?.role == SessionRole.ANONYMOUS
    ) {
      return
    }
    if (streamJob?.isActive == true && streamingAccount == account) return
    streamJob?.cancel()
    streamingAccount = account
    streamJob = scope.launch { collectAccountStream(account) }
  }

  private suspend fun collectAccountStream(account: String) {
    venuePositions = emptyList()
    var lastBackfillAtMs = 0L
    var lastHistoryBackfillAtMs = 0L
    val userTrades = UserTrades(account)
    val topics: Set<DecibelStreamTopic> =
      setOf(
        AccountOverviewTopic(account),
        AccountPositions(account),
        AccountOpenOrders(account),
        OrderUpdates(account),
        userTrades,
      )
    client.stream.subscribe(topics).collect { event ->
      when (event) {
        is StreamEvent.Connected -> {
          refresh()
          refreshHistory()
          lastBackfillAtMs = Clock.System.now().toEpochMilliseconds()
          lastHistoryBackfillAtMs = lastBackfillAtMs
        }
        is StreamEvent.Message -> {
          val now = Clock.System.now().toEpochMilliseconds()
          when (val data = event.data) {
            is DecibelStreamData.AccountOverviewValue ->
              mutableSnapshot.update {
                it.copy(overview = data.value.toAccountOverview(), stale = false, error = null)
              }
            is DecibelStreamData.AccountPositionsValue -> {
              venuePositions = data.values.filterNot(Position::isDeleted)
              mutableSnapshot.update { snapshot ->
                val openOrders = snapshot.openOrders.withWholePositionExits(venuePositions)
                snapshot.copy(
                  positions = venuePositions.map { it.toPosition(openOrders) },
                  openOrders = openOrders,
                  stale = false,
                  error = null,
                )
              }
            }
            is DecibelStreamData.AccountOpenOrdersValue -> {
              val openOrders = data.values.map { it.toOpenOrder() }.withWholePositionExits(venuePositions)
              mutableSnapshot.update {
                it.copy(
                  positions = venuePositions.map { position -> position.toPosition(openOrders) },
                  openOrders = openOrders,
                  stale = false,
                  error = null,
                )
              }
            }
            is DecibelStreamData.MarketTrades ->
              if (event.topic == userTrades) {
                mutableHistory.update { current ->
                  current.copy(
                    trades =
                      (data.values.map { it.toFill() } + current.trades)
                        .distinctBy(Fill::id)
                        .sortedByDescending(Fill::timeMs)
                        .take(HISTORY_PAGE_SIZE),
                    stale = false,
                    error = null,
                  )
                }
              }
            else -> Unit
          }
          if (
            (event.topic is OrderUpdates || event.data is DecibelStreamData.Malformed) &&
              now - lastBackfillAtMs >= BACKFILL_INTERVAL_MS
          ) {
            refresh()
            lastBackfillAtMs = now
          }
          if (
            event.topic is OrderUpdates && now - lastHistoryBackfillAtMs >= BACKFILL_INTERVAL_MS
          ) {
            refreshHistory()
            lastHistoryBackfillAtMs = now
          }
        }
        is StreamEvent.SequenceGap -> {
          refresh()
          refreshHistory()
          lastBackfillAtMs = Clock.System.now().toEpochMilliseconds()
          lastHistoryBackfillAtMs = lastBackfillAtMs
        }
        is StreamEvent.Rejected -> {
          mutableSnapshot.update { it.copy(stale = true, error = event.reason) }
          mutableHistory.update { it.copy(stale = true, error = event.reason) }
        }
        is StreamEvent.Disconnected ->
          (event.reason ?: "Account stream disconnected").let { reason ->
            mutableSnapshot.update { it.copy(stale = true, error = reason) }
            mutableHistory.update { it.copy(stale = true, error = reason) }
          }
      }
    }
  }

  override suspend fun portfolioChart(
    range: PortfolioChartRange,
    metric: PortfolioMetric,
  ): List<SeriesPoint> {
    val account = preferences.values.first().selectedSubaccount ?: return emptyList()
    return readAccount(account) {
        client.accounts.portfolioChart(account, range.decibelWireValue, metric.decibelWireValue)
      }
      .map { it.toPortfolioPoint(metric) }
  }

  override suspend fun fees(): TradingFees? {
    val account = preferences.values.first().selectedSubaccount ?: return null
    return readAccount(account) { client.accounts.fees(account) }?.toTradingFees()
  }

  override suspend fun rewards(): Rewards {
    val streak = runSuspendCatching { tradingStreak() }
    val amps = runSuspendCatching { ampsBreakdown() }
    val tier = runSuspendCatching { tierInfo() }
    val campaigns = runSuspendCatching { accountCampaigns() }
    val referral = runSuspendCatching { referralSummary() }
    // Public season totals; a failure here only costs the rank its context.
    val global = runSuspendCatching { client.accounts.globalPoints() }.getOrNull()
    return decibelRewards(streak, amps, tier, campaigns, referral, global = global)
  }

  override suspend fun pointsHistory(): List<SeriesPoint> =
    readOwner { client.accounts.dailyAmps(it).toPointsHistory() }.orEmpty()

  private suspend fun accountCampaigns(): UserCampaigns? {
    val account = preferences.values.first().selectedSubaccount ?: return null
    return readAccount(account) { client.accounts.campaigns(account) }
  }

  /**
   * Runs a read the venue scopes by `owner`: points, the streak and the tier are the wallet's,
   * not a subaccount's, and need the wallet's own session to pass the proxy's scope check.
   */
  private suspend fun <T> readOwner(block: suspend (owner: String) -> T): T? {
    val owner = wallets.profile.first().ownerAddress ?: preferences.values.first().ownerAddress ?: return null
    val account = preferences.values.first().selectedSubaccount
    val current = sessions.status.value
    val session = if (current?.role == SessionRole.OWNER &&
      current.walletAddress?.sameAptosAddress(owner) == true &&
      current.subaccount == account &&
      current.expiresAt > Clock.System.now().toEpochMilliseconds() + SESSION_RENEWAL_MARGIN_MS
    ) current else sessions.authenticateOwner(
      account, VaultPrompt("Open Flare", "Confirm your identity", requireFreshAuthorization = false),
    )
    return sessions.bind(session, flow { emit(block(owner)) }).first()
  }

  private suspend fun tradingStreak(): DecibelTradingStreak? =
    readOwner { client.accounts.streak(it) }

  private suspend fun ampsBreakdown(): AmpsBreakdown? = readOwner { client.accounts.amps(it) }

  private suspend fun tierInfo(): TierInfo? = readOwner { client.accounts.tier(it) }

  private suspend fun referralSummary(): ReferralSummary? =
    readOwner { decibelReferral(client.accounts.referrerStats(it), client.accounts.accountReferral(it)) }

  override suspend fun redeemReferralCode(code: String): Boolean {
    val account = preferences.values.first().selectedSubaccount ?: return false
    return runCatching { client.accounts.redeemReferralCode(account, code).success }.getOrDefault(false)
  }

  override suspend fun activeTwaps(): List<TwapOrder> {
    val account = preferences.values.first().selectedSubaccount ?: return emptyList()
    return readAccount(account) { client.accounts.activeTwaps(account) }.map { it.toTwapOrder() }
  }

  override suspend fun twapHistory(limit: Int): List<TwapOrder> {
    val account = preferences.values.first().selectedSubaccount ?: return emptyList()
    return readAccount(account) { client.accounts.twapHistory(account, limit) }
      .map { it.toTwapOrder() }
  }

  override suspend fun vaults(limit: Int): List<VaultSummary> {
    return client.accounts.vaults(limit).map { it.toVaultSummary() }
  }

  override suspend fun vaultPositions(): List<VaultPosition> {
    val account = preferences.values.first().selectedSubaccount ?: return emptyList()
    return readAccount(account) { client.accounts.accountVaultPerformance(account) }
      .map { it.toVaultPosition() }
  }

  override fun contribute(
    vaultAddress: String,
    amount: String,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<ActionState> = flow {
    val subaccount =
      preferences.values.first().selectedSubaccount ?: error("Select a trading account")
    val units = DecimalInput(amount).toChainUnits("Contribution amount", USDC_DECIMALS).getOrThrow()
    require(units > 0uL) { "Enter an amount greater than zero" }
    try {
      emitAll(
        trading.executeCommand(
          command =
            DecibelCommand.ContributeToVault(
              subaccount = subaccount,
              vaultAddress = vaultAddress,
              assetMetadata = client.config.deployment.usdcMetadataAddress,
              amount = units,
            ),
          prompt = prompt.copy(requireFreshAuthorization = true),
          feePayment = feePayment,
        )
      )
    } finally {
      runSuspendCatching { restoreTrading() }
    }
  }

  override fun redeem(
    vaultAddress: String,
    shares: String,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<ActionState> = flow {
    val subaccount =
      preferences.values.first().selectedSubaccount ?: error("Select a trading account")
    val units = DecimalInput(shares).toChainUnits("Redemption shares", USDC_DECIMALS).getOrThrow()
    require(units > 0uL) { "Enter a share count greater than zero" }
    try {
      emitAll(
        trading.executeCommand(
          command =
            DecibelCommand.RedeemFromVault(
              subaccount = subaccount,
              vaultAddress = vaultAddress,
              shares = units,
            ),
          prompt = prompt.copy(requireFreshAuthorization = true),
          feePayment = feePayment,
        )
      )
    } finally {
      runSuspendCatching { restoreTrading() }
    }
  }

  override suspend fun collateral(vaultAddress: String): VaultCollateral? {
    val subaccount = preferences.values.first().selectedSubaccount ?: return null
    val shares =
      when (val result = client.vaultCollateral.shareCollateral(vaultAddress, subaccount)) {
        is AptosResult.Success -> result.value
        is AptosResult.Failure -> return null
      }
    // The credit comes from the overview, which reports it per secondary asset.
    val credit = snapshot.value.overview?.secondaryCollateral?.firstOrNull()?.creditPercent
    return VaultCollateral(
      pledgeable = shares.pledgeable.toShareAmount(),
      pledged = shares.pledged.toShareAmount(),
      creditPercent = credit,
    )
  }

  override fun setCollateral(
    vaultAddress: String,
    shares: String,
    pledge: Boolean,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<ActionState> = flow {
    val subaccount =
      preferences.values.first().selectedSubaccount ?: error("Select a trading account")
    val units = DecimalInput(shares).toChainUnits("Share amount", SHARE_DECIMALS).getOrThrow()
    require(units > 0uL) { "Enter an amount greater than zero" }
    require(units <= Long.MAX_VALUE.toULong()) { "That is more shares than the vault holds" }
    val asset =
      when (val result = client.vaultCollateral.shareCollateral(vaultAddress, subaccount)) {
        is AptosResult.Success -> result.value.assetMetadata
        is AptosResult.Failure -> error("Flare couldn't read this vault's shares")
      }
    try {
      emitAll(
        trading.executeCommand(
          command =
            DecibelCommand.PledgeCollateral(
              subaccount = subaccount,
              assetMetadata = asset,
              // The chain takes one signed amount: positive pledges, negative releases.
              shares = units.toLong().let { if (pledge) it else -it },
            ),
          prompt = prompt.copy(requireFreshAuthorization = true),
          feePayment = feePayment,
        )
      )
    } finally {
      runSuspendCatching { restoreTrading() }
    }
  }

  /** Decibel stores shares with six decimals, as the vault performance reader also assumes. */
  private fun ULong.toShareAmount(): Double = toDouble() / 1_000_000.0

  private companion object {
    /** One reviewed "create account and enable trading" may span its two transactions. */
    const val SETUP_APPROVAL_WINDOW_MS = 60_000L
    const val BACKFILL_INTERVAL_MS = 2_000L
    const val USDC_DECIMALS = 6
    const val SHARE_DECIMALS = 6
    const val HISTORY_PAGE_SIZE = 50
  }
}

private sealed interface HistoryResult {
  data class Orders(val page: Page<Order>) : HistoryResult

  data class Trades(val page: Page<DecibelMarketTrade>) : HistoryResult

  data class Funding(val page: Page<DecibelFundingPayment>) : HistoryResult

  data class Transfers(val page: Page<DecibelFundMovement>) : HistoryResult
}

private fun <T> Page<T>.hasMore(offset: Int): Boolean {
  val knownTotal = totalCount
  return items.size == 50 && (knownTotal == null || (offset + items.size).toLong() < knownTotal)
}

private const val OPENED_ACCOUNT_CHECKS = 8

/** Marks the setup steps a person confirmed as one action; see [AccountRepository.confirmedSetup]. */
private class ConfirmedSetup : AbstractCoroutineContextElement(ConfirmedSetup) {
  companion object Key : CoroutineContext.Key<ConfirmedSetup>
}
