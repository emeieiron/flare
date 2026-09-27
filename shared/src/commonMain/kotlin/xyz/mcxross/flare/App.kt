package xyz.mcxross.flare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.room3.RoomDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.dsl.koinConfiguration
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.design.FlareBottomNavigation
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareIcons
import xyz.mcxross.flare.design.FlareNavigationItem
import xyz.mcxross.flare.design.FlareSplashScreen
import xyz.mcxross.flare.design.FlareTheme
import xyz.mcxross.flare.design.LocalTransactionExplorer
import xyz.mcxross.flare.design.SplashMarkSize
import xyz.mcxross.flare.design.TransactionExplorer
import xyz.mcxross.flare.design.pagePopEnter
import xyz.mcxross.flare.design.pagePopExit
import xyz.mcxross.flare.design.pagePredictivePopEnter
import xyz.mcxross.flare.design.pagePredictivePopExit
import xyz.mcxross.flare.design.pagePushEnter
import xyz.mcxross.flare.design.pagePushExit
import xyz.mcxross.flare.design.rememberFlareIgnition
import xyz.mcxross.flare.design.rememberReducedMotion
import xyz.mcxross.flare.di.flareModule
import xyz.mcxross.flare.feature.markets.MarketsRoute
import xyz.mcxross.flare.feature.onboarding.OnboardingRoute
import xyz.mcxross.flare.feature.orders.OrdersRoute
import xyz.mcxross.flare.feature.portfolio.PortfolioRoute
import xyz.mcxross.flare.feature.portfolio.PositionRoute
import xyz.mcxross.flare.feature.settings.AccountRoute
import xyz.mcxross.flare.feature.settings.SettingsRoute
import xyz.mcxross.flare.feature.trade.TradeRoute
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.WalletSecretSlot
import xyz.mcxross.flare.security.WalletVault
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.FlareDatabase

/** Reconnection is automatic and unobtrusive: quick at first, then patient. */
private const val RECONNECT_BASE_DELAY_MS = 2_000L
private const val RECONNECT_MAX_DELAY_MS = 30_000L

@Serializable data object PortfolioDestination

@Serializable data object MarketsDestination

@Serializable data class TradeDestination(val marketAddress: String? = null)

@Serializable data class PositionDestination(val market: String)

@Serializable data object OrdersDestination

@Serializable data object AccountDestination

@Serializable data object SettingsDestination

@Serializable data class AccountSetupDestination(val mode: String = "CREATE", val profileId: String? = null)

@Composable
fun App(
  databaseBuilder: RoomDatabase.Builder<FlareDatabase>,
  preferences: DataStore<Preferences>,
  walletVault: WalletVault,
  runtimeConfig: FlareRuntimeConfig = remember { FlareRuntimeConfig() },
) {
  val foregroundVault = remember(walletVault) { ForegroundWalletVault(walletVault) }
  KoinApplication(
    configuration =
      koinConfiguration {
        modules(flareModule(runtimeConfig, databaseBuilder, preferences, foregroundVault))
      }
  ) {
    CompositionLocalProvider(
      LocalTransactionExplorer provides
        remember(runtimeConfig) {
          TransactionExplorer(runtimeConfig.network)
        }
    ) {
      FlareTheme { FlareAppFlow() }
    }
  }
}

@Composable
private fun FlareAppFlow() {
  val appPreferences: AppPreferences = koinInject()
  val vault: ForegroundWalletVault = koinInject()
  val accounts: AccountRepository = koinInject()
  val unlocked by vault.unlocked.collectAsStateWithLifecycle()
  var foreground by remember { mutableStateOf(true) }
  var retry by remember { mutableStateOf(0) }
  var unlockError by remember { mutableStateOf<String?>(null) }
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
    foreground = false
    vault.lock()
  }
  LifecycleEventEffect(Lifecycle.Event.ON_START) { foreground = true }
  val trading: TradingRepository = koinInject()
  val wallets: WalletRepository = koinInject()
  val persisted by appPreferences.values.collectAsStateWithLifecycle(initialValue = null)
  val walletProfile by wallets.profile.collectAsStateWithLifecycle(initialValue = null)
  val hasCredentials = persisted?.profiles?.isNotEmpty() == true
  LaunchedEffect(foreground, hasCredentials, retry) {
    if (foreground && hasCredentials && !vault.unlocked.value) {
      unlockError = null
      try {
        val slots =
          persisted!!.profiles.flatMap { profile ->
            listOfNotNull(
              WalletSecretSlot.OWNER_MNEMONIC.forProfile(profile.id).takeIf {
                profile.ownerAddress != null
              },
              WalletSecretSlot.API_PRIVATE_KEY.forProfile(profile.id).takeIf {
                profile.apiWalletAddress != null
              },
            )
          }
        vault.unlock(slots, VaultPrompt("Open Flare", "Confirm your identity"))
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        unlockError = "Authentication required"
      }
    }
  }
  // Staying current is the app's job: a failed restore retries on its own until the account is
  // live again, so nobody has to notice staleness or press refresh.
  LaunchedEffect(unlocked, persisted?.activeProfileId, persisted?.selectedSubaccount) {
    if (!unlocked) return@LaunchedEffect
    var backoffMs = RECONNECT_BASE_DELAY_MS
    while (true) {
      val restored =
        try {
          accounts.restoreTrading()
          true
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          false
        }
      if (restored) return@LaunchedEffect
      delay(backoffMs)
      backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
    }
  }

  // Submitted work settles without supervision: while anything is outstanding the app keeps
  // checking, and stops the moment the journal is clear.
  LaunchedEffect(unlocked) {
    trading.pendingTransactions
      .map { it.isNotEmpty() }
      .distinctUntilChanged()
      .collectLatest { hasPending ->
        if (!hasPending) return@collectLatest
        var backoffMs = RECONNECT_BASE_DELAY_MS
        while (true) {
          try {
            trading.reconcilePending()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Throwable) {
            // Pending records stay durable; the next pass retries them.
          }
          delay(backoffMs)
          backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        }
      }
  }
  val hasCompletedProfile = persisted?.profiles?.any { it.onboardingComplete } == true
  // The finished wallet the shell shows. While another wallet is being set up it becomes the active
  // one, and the shell keeps showing the wallet it had rather than jumping to another finished one.
  val shownWallet = remember { arrayOf<String?>(null) }
  val lastCompletedProfileId = remember(persisted?.profiles, persisted?.activeProfileId) {
    val activeFinished =
      persisted?.profiles?.firstOrNull { it.id == persisted?.activeProfileId && it.onboardingComplete }?.id
    val stillFinished = shownWallet[0]?.takeIf { id -> persisted?.profiles?.any { it.id == id && it.onboardingComplete } == true }
    (activeFinished ?: stillFinished ?: persisted?.profiles?.firstOrNull { it.onboardingComplete }?.id)
      .also { shownWallet[0] = it }
  }
  val savedScreens = rememberSaveableStateHolder()
  val stage =
    when {
      persisted == null || walletProfile == null -> LaunchStage.LOADING
      hasCredentials && !unlocked -> LaunchStage.LOCKED
      !hasCompletedProfile -> LaunchStage.ONBOARDING
      else -> LaunchStage.READY
    }
  // Whether this is the moment setup finished, worked out as the stage changes so the curtain covers
  // the app in that same frame.
  val lastStage = remember { arrayOf(stage) }
  val finishedSetup =
    remember(stage) {
      (lastStage[0] == LaunchStage.ONBOARDING && stage == LaunchStage.READY).also { lastStage[0] = stage }
    }
  Box(Modifier.fillMaxSize()) {
    when (stage) {
      LaunchStage.ONBOARDING -> {
        // A setup that was interrupted picks up where it stopped instead of starting a second account.
        val unfinished = remember {
          persisted?.profiles?.firstOrNull { it.id == persisted?.activeProfileId && !it.onboardingComplete }?.id
        }
        OnboardingRoute(
          onCompleted = {},
          initialMode = unfinished?.let { "CONTINUE" },
          profileId = unfinished,
        )
      }
      LaunchStage.READY -> {
        // The shell starts over only when a finished wallet or its account changes. A wallet still
        // being set up from inside the shell becomes active along the way, and must not reset it.
        val shellAccount =
          persisted?.profiles?.firstOrNull { it.id == lastCompletedProfileId }?.selectedSubaccount
        key(lastCompletedProfileId, shellAccount) {
          savedScreens.SaveableStateProvider("shell:$lastCompletedProfileId:$shellAccount") {
            FlareShell()
          }
        }
      }
      LaunchStage.LOADING,
      LaunchStage.LOCKED -> Unit
    }
    LaunchCurtain(stage, finishedSetup, locked = unlockError != null, onUnlock = { retry++ })
  }
}

private enum class LaunchStage {
  LOADING,
  LOCKED,
  ONBOARDING,
  READY,
}

/**
 * The splash, held over the app until it can be used. It looks exactly like the system's launch
 * splash, so the app takes over without a visible seam. When the account unlocks, the mark ignites
 * and the curtain lifts off the app already drawn beneath it. Onboarding takes the mark over at the
 * same spot, so there the curtain simply goes, and when setup finishes it returns: the mark lights up
 * in full, once, before the app is revealed. If the unlock is dismissed, the mark stays where it is
 * and the way back in appears beneath it.
 */
@Composable
private fun LaunchCurtain(
  stage: LaunchStage,
  finishedSetup: Boolean,
  locked: Boolean,
  onUnlock: () -> Unit,
) {
  val reduceMotion = rememberReducedMotion()
  val haptics = LocalHapticFeedback.current
  val ignition = rememberFlareIgnition()
  val opacity = remember { Animatable(1f) }
  var covering by remember { mutableStateOf(true) }
  var celebrated by remember { mutableStateOf(false) }
  val celebrating = finishedSetup && !celebrated
  LaunchedEffect(stage, celebrating) {
    when (stage) {
      LaunchStage.LOADING,
      LaunchStage.LOCKED -> {
        ignition.snapTo(0f)
        opacity.snapTo(1f)
        covering = true
      }
      LaunchStage.ONBOARDING -> {
        ignition.snapTo(0f)
        opacity.snapTo(1f)
        covering = false
      }
      LaunchStage.READY -> {
        if (celebrating) {
          coroutineScope {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            launch { ignition.ignite(reduceMotion, rest = 1f) }
            delay(if (reduceMotion) 700 else 1_500)
            opacity.animateTo(0f, tween(if (reduceMotion) 120 else 320, easing = LinearEasing))
          }
          celebrated = true
          covering = false
          return@LaunchedEffect
        }
        if (!covering) return@LaunchedEffect
        coroutineScope {
          launch { ignition.ignite(reduceMotion, rest = 1f, staggerMs = 50, flareMs = 150, settleMs = 300) }
          // The curtain starts lifting as the last bar flares, so unlocking never waits on the light.
          delay(if (reduceMotion) 0 else 200)
          opacity.animateTo(0f, tween(if (reduceMotion) 120 else 240, easing = LinearEasing))
        }
        covering = false
      }
    }
  }
  if (!covering && !celebrating) return
  Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }) {
    FlareSplashScreen(glow = ignition::level)
    AnimatedVisibility(
      visible = celebrating,
      modifier = Modifier.align(Alignment.Center).padding(top = SplashMarkSize + 88.dp),
      enter = fadeIn(tween(400, delayMillis = 350)) + slideInVertically(tween(500, 350, FastOutSlowInEasing)) { it / 2 },
      exit = fadeOut(tween(120)),
    ) {
      Text("You’re ready to trade.", style = MaterialTheme.typography.titleMedium)
    }
    AnimatedVisibility(
      visible = stage == LaunchStage.LOCKED && locked,
      modifier = Modifier.align(Alignment.BottomCenter),
      enter = fadeIn(tween(220)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 6 },
      exit = fadeOut(tween(120)),
    ) {
      Column(
        Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text("Flare is locked", style = MaterialTheme.typography.titleLarge)
        Text(
          "Confirm it’s you to open your account.",
          Modifier.padding(top = 8.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = FlareColors.TextSecondary,
        )
        FlareButton("Unlock", onUnlock, Modifier.fillMaxWidth().padding(top = 28.dp))
      }
    }
  }
}

@Composable
private fun FlareShell() {
  val navController = rememberNavController()
  val backStack by navController.currentBackStackEntryAsState()
  val destination = backStack?.destination
  val isDetailDestination = destination?.isDetailPage() == true
  val selectedIndex =
    when {
      destination?.hasRoute<PortfolioDestination>() == true -> 1
      destination?.hasRoute<OrdersDestination>() == true -> 2
      destination?.hasRoute<AccountDestination>() == true ||
        destination?.hasRoute<SettingsDestination>() == true ||
        destination?.hasRoute<AccountSetupDestination>() == true -> 3
      else -> 0
    }
  val navigationItems = remember {
    listOf(
      FlareNavigationItem("Markets", FlareIcons.Markets, FlareIcons.MarketsFilled),
      FlareNavigationItem("Portfolio", FlareIcons.Portfolio, FlareIcons.PortfolioFilled),
      FlareNavigationItem("Activity", FlareIcons.Activity, FlareIcons.ActivityFilled),
      FlareNavigationItem("Account", FlareIcons.Account, FlareIcons.AccountFilled),
    )
  }

  /**
   * Every move to a tab goes through here, including links from inside a screen. A plain navigate
   * would stack the tab on top of Markets, and the next tap on Markets would save that stack as
   * Markets' own state and restore it straight back.
   */
  fun navigateTo(index: Int) {
    val route: Any =
      when (index) {
        0 -> MarketsDestination
        1 -> PortfolioDestination
        2 -> OrdersDestination
        else -> AccountDestination
      }
    navController.navigate(route) {
      popUpTo(navController.graph.startDestinationId) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  val onOpenSetup = { navigateTo(3) }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    bottomBar = {
      if (!isDetailDestination)
        FlareBottomNavigation(
          items = navigationItems,
          selectedIndex = selectedIndex,
          onSelected = ::navigateTo,
          modifier = Modifier.navigationBarsPadding(),
        )
    },
  ) { contentPadding ->
    // No background of its own: the scaffold paints the same black beneath, and each detail page paints
    // its own as it slides over, so another full-screen layer here would only be overdraw.
    NavHost(
      navController = navController,
      startDestination = MarketsDestination,
      modifier =
        Modifier.fillMaxSize()
          .padding(contentPadding)
          .consumeWindowInsets(contentPadding)
          .clipToBounds(),
      enterTransition = {
        if (targetState.destination.isDetailPage()) pagePushEnter() else EnterTransition.None
      },
      exitTransition = {
        if (targetState.destination.isDetailPage()) pagePushExit() else ExitTransition.None
      },
      popEnterTransition = {
        if (initialState.destination.isDetailPage()) pagePopEnter() else EnterTransition.None
      },
      popExitTransition = {
        if (initialState.destination.isDetailPage()) pagePopExit() else ExitTransition.None
      },
      predictivePopEnterTransition = { edge ->
        if (initialState.destination.isDetailPage()) pagePredictivePopEnter(edge) else EnterTransition.None
      },
      predictivePopExitTransition = { edge ->
        if (initialState.destination.isDetailPage()) pagePredictivePopExit(edge) else ExitTransition.None
      },
      sizeTransform = { null },
    ) {
      composable<PortfolioDestination> {
        PortfolioRoute(
          onOpenSetup = onOpenSetup,
          onMarketClick = { marketAddress ->
            navController.navigate(TradeDestination(marketAddress)) { launchSingleTop = true }
          },
          onPositionClick = { market ->
            navController.navigate(PositionDestination(market)) { launchSingleTop = true }
          },
        )
      }
      composable<PositionDestination> { backStackEntry ->
        // Shares the portfolio's view model, so the list and the position never disagree.
        val portfolioEntry =
          remember(backStackEntry) { navController.getBackStackEntry<PortfolioDestination>() }
        PositionRoute(
          market = backStackEntry.toRoute<PositionDestination>().market,
          onBack = { navController.popBackStack() },
          viewModel = koinViewModel(viewModelStoreOwner = portfolioEntry),
        )
      }
      composable<MarketsDestination> {
        MarketsRoute(
          onMarketClick = { marketAddress ->
            navController.navigate(TradeDestination(marketAddress)) { launchSingleTop = true }
          }
        )
      }
      composable<TradeDestination> { backStackEntry ->
        TradeRoute(
          onOpenActivity = {
            // The finished order isn't kept: returning to Markets shows the list, not the receipt.
            navController.popBackStack()
            navigateTo(2)
          },
          marketAddress = backStackEntry.toRoute<TradeDestination>().marketAddress,
          onBack = { navController.popBackStack() },
          onOpenSetup = onOpenSetup,
        )
      }
      composable<OrdersDestination> { OrdersRoute(onOpenSetup) }
      composable<AccountDestination> {
        AccountRoute(
          onOpenSettings = { navController.navigate(SettingsDestination) },
          onCreateAccount = { navController.navigate(AccountSetupDestination("CREATE")) },
          onImportAccount = { navController.navigate(AccountSetupDestination("IMPORT")) },
          onContinueSetup = { id -> navController.navigate(AccountSetupDestination("CONTINUE", id)) },
        )
      }
      composable<SettingsDestination> {
        SettingsRoute(
          onBack = { navController.popBackStack() },
        )
      }
      composable<AccountSetupDestination> { backStackEntry ->
        val setup = backStackEntry.toRoute<AccountSetupDestination>()
        OnboardingRoute(
          initialMode = setup.mode,
          profileId = setup.profileId,
          onCompleted = { navController.popBackStack() },
          onBack = { navController.popBackStack() },
        )
      }
    }
  }
}

private fun NavDestination.isDetailPage(): Boolean =
  hasRoute<TradeDestination>() || hasRoute<PositionDestination>() || hasRoute<SettingsDestination>() ||
    hasRoute<AccountSetupDestination>()
