package xyz.mcxross.flare

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.serialization.Serializable
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.dsl.koinConfiguration
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.design.FlareBottomNavigation
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareNavigationItem
import xyz.mcxross.flare.design.FlareTheme
import xyz.mcxross.flare.di.flareModule
import xyz.mcxross.flare.feature.markets.MarketsRoute
import xyz.mcxross.flare.feature.onboarding.OnboardingRoute
import xyz.mcxross.flare.feature.orders.OrdersRoute
import xyz.mcxross.flare.feature.portfolio.PortfolioRoute
import xyz.mcxross.flare.feature.settings.SettingsRoute
import xyz.mcxross.flare.feature.trade.TradeRoute
import xyz.mcxross.flare.security.WalletVault
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.FlareDatabase

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
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { walletVault.lock() }
  KoinApplication(
    configuration =
      koinConfiguration {
        modules(flareModule(runtimeConfig, databaseBuilder, preferences, walletVault))
      }
  ) {
    FlareTheme { FlareAppFlow() }
  }
}

@Composable
private fun FlareAppFlow() {
  val appPreferences: AppPreferences = koinInject()
  val sessions: SessionRepository = koinInject()
  val trading: TradingRepository = koinInject()
  val persisted by appPreferences.values.collectAsStateWithLifecycle(initialValue = null)
  var forceSetup by rememberSaveable { mutableStateOf(false) }
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = forceSetup,
    onBackCompleted = { forceSetup = false },
  )
  LaunchedEffect(Unit) {
    try {
      sessions.useAnonymous()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      // Public repositories surface their own offline state.
    }
    try {
      trading.reconcilePending()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      // Pending records remain durable and are retried after wallet authentication.
    }
  }
  val savedScreens = rememberSaveableStateHolder()
  if (persisted == null) {
    Box(
      Modifier.fillMaxSize().background(FlareColors.Canvas),
      contentAlignment = Alignment.Center,
    ) {
      CircularProgressIndicator()
    }
  } else if (persisted?.onboardingComplete != true || forceSetup) {
    OnboardingRoute(onCompleted = { forceSetup = false })
  } else {
    savedScreens.SaveableStateProvider("shell") { FlareShell(onOpenSetup = { forceSetup = true }) }
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
      composable<PortfolioDestination> { PortfolioRoute(onOpenSetup) }
      composable<MarketsDestination> {
        MarketsRoute(
          onMarketClick = { marketAddress ->
            navController.navigate(TradeDestination(marketAddress)) {
              launchSingleTop = true
            }
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
