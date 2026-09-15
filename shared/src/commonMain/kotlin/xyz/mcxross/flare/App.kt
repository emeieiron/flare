package xyz.mcxross.flare

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PieChartOutline
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.room3.RoomDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.dsl.koinConfiguration
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletRepository
import androidx.compose.foundation.layout.size
import xyz.mcxross.flare.design.FlareSplashScreen
import xyz.mcxross.flare.design.FlareBottomNavigation
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareLogo
import xyz.mcxross.flare.design.FlareNavigationItem
import xyz.mcxross.flare.design.FlareTheme
import xyz.mcxross.flare.design.LocalTransactionExplorer
import xyz.mcxross.flare.design.TransactionExplorer
import xyz.mcxross.flare.di.flareModule
import xyz.mcxross.flare.feature.markets.MarketsRoute
import xyz.mcxross.flare.feature.onboarding.OnboardingRoute
import xyz.mcxross.flare.feature.orders.OrdersRoute
import xyz.mcxross.flare.feature.portfolio.PortfolioRoute
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

@Serializable data object OrdersDestination

@Serializable data object SettingsDestination

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
      LocalTransactionExplorer provides remember(runtimeConfig) {
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
  var forceSetup by rememberSaveable { mutableStateOf(false) }
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

  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = forceSetup,
    onBackCompleted = { forceSetup = false },
  )
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
  val savedScreens = rememberSaveableStateHolder()
  if (persisted == null || walletProfile == null) {
    FlareSplashScreen()
  } else if (hasCredentials && !unlocked) {
    if (unlockError == null) {
      FlareSplashScreen()
    } else {
      Box(
        Modifier.fillMaxSize().background(FlareColors.Canvas),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          FlareLogo(modifier = Modifier.size(64.dp), color = FlareColors.Positive)
          Text("flare", style = MaterialTheme.typography.headlineMedium)
          Text(
            "Confirm it’s you to continue.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
          )
          FlareButton("Continue", { retry++ })
        }
      }
    }
  } else if (
    persisted?.onboardingComplete != true ||
      (walletProfile?.ownerAddress == null && walletProfile?.apiWalletAddress == null) ||
      forceSetup
  ) {
    OnboardingRoute(onCompleted = { forceSetup = false })
  } else {
    key(persisted?.activeProfileId, persisted?.selectedSubaccount) {
      savedScreens.SaveableStateProvider(
        "shell:${persisted?.activeProfileId}:${persisted?.selectedSubaccount}"
      ) {
        FlareShell(onOpenSetup = { forceSetup = true })
      }
    }
  }
}

@Composable
private fun FlareShell(onOpenSetup: () -> Unit) {
  val navController = rememberNavController()
  val backStack by navController.currentBackStackEntryAsState()
  val destination = backStack?.destination
  val isMarketDetail = destination?.hasRoute<TradeDestination>() == true
  val selectedIndex =
    when {
      destination?.hasRoute<PortfolioDestination>() == true -> 1
      destination?.hasRoute<OrdersDestination>() == true -> 2
      destination?.hasRoute<SettingsDestination>() == true -> 3
      else -> 0
    }
  val navigationItems = remember {
    listOf(
      FlareNavigationItem("Markets", Icons.Outlined.Search),
      FlareNavigationItem("Portfolio", Icons.Outlined.PieChartOutline),
      FlareNavigationItem("Activity", Icons.AutoMirrored.Outlined.ListAlt),
      FlareNavigationItem("Account", Icons.Outlined.PersonOutline),
    )
  }

  fun navigateTo(index: Int) {
    val route: Any =
      when (index) {
        0 -> MarketsDestination
        1 -> PortfolioDestination
        2 -> OrdersDestination
        else -> SettingsDestination
      }
    navController.navigate(route) {
      popUpTo(navController.graph.startDestinationId) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    bottomBar = {
      if (!isMarketDetail)
        FlareBottomNavigation(
          items = navigationItems,
          selectedIndex = selectedIndex,
          onSelected = ::navigateTo,
          modifier = Modifier.navigationBarsPadding(),
        )
    },
  ) { contentPadding ->
    NavHost(
      navController = navController,
      startDestination = MarketsDestination,
      modifier = Modifier.padding(contentPadding),
      enterTransition = { fadeIn(tween(180)) },
      exitTransition = { fadeOut(tween(140)) },
      popEnterTransition = { fadeIn(tween(180)) },
      popExitTransition = { fadeOut(tween(140)) },
    ) {
      composable<PortfolioDestination> {
        PortfolioRoute(
          onOpenSetup = onOpenSetup,
          onMarketClick = { marketAddress ->
            navController.navigate(TradeDestination(marketAddress)) { launchSingleTop = true }
          },
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
          backStackEntry.toRoute<TradeDestination>().marketAddress,
          onBack = { navController.popBackStack() },
          onOpenSetup = onOpenSetup,
        )
      }
      composable<OrdersDestination> { OrdersRoute(onOpenSetup) }
      composable<SettingsDestination> { SettingsRoute(onOpenSetup) }
    }
  }
}
