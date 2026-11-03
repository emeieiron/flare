package xyz.mcxross.flare.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.valentinilk.shimmer.shimmer
import kotlin.math.floor
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.AssetMetadata
import xyz.mcxross.flare.data.WalletTokenBalance
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.data.formatTokenBalance
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.AssetIcon
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareAddressField
import xyz.mcxross.flare.design.FlareAmountField
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareIcons
import xyz.mcxross.flare.design.FlareQrCode
import xyz.mcxross.flare.design.FlareSegmentedControl
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.FlareSkeletonBox
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.LocalTransactionExplorer
import xyz.mcxross.flare.design.LocalVenue
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.Outcome
import xyz.mcxross.flare.design.OutcomeIcon
import xyz.mcxross.flare.design.UnavailableNotice
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.design.emphasizedAddress
import xyz.mcxross.flare.design.fullBleed
import xyz.mcxross.flare.design.rememberFlareShimmer
import xyz.mcxross.flare.design.rememberOutcomeReveal
import xyz.mcxross.flare.design.rememberPreparedFlareQrCodePainter
import xyz.mcxross.flare.design.rememberReducedMotion
import xyz.mcxross.flare.design.resolveAssetIdentity
import xyz.mcxross.flare.design.shortAddress
import xyz.mcxross.flare.feature.onboarding.LocalCredentialParser
import xyz.mcxross.flare.store.opensApp
import xyz.mcxross.flare.store.setupPending
import xyz.mcxross.flare.store.tradingAccountDisplayName
import xyz.mcxross.flare.store.walletDisplayName
import xyz.mcxross.flare.venue.model.ActionKind
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.TradingAccount

private const val COPIED_CONFIRMATION_MS = 1_500L

/** Rows standing in for the trading accounts, sized like them, until the first read comes back. */
@Composable
private fun TradingAccountsSkeleton() {
  val shimmer = rememberFlareShimmer()
  Column(Modifier.fillMaxWidth().shimmer(shimmer)) {
    repeat(2) {
      Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        FlareSkeletonBox(Modifier.padding(top = 2.dp).size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.height(40.dp).padding(top = 4.dp)) {
          FlareSkeletonBox(Modifier.height(16.dp).width(136.dp))
          Spacer(Modifier.height(6.dp))
          FlareSkeletonBox(Modifier.height(12.dp).width(104.dp))
        }
      }
      HorizontalDivider(color = FlareColors.BorderSubtle)
    }
  }
}

/**
 * The wallet's view models, kept above the shell: selecting or creating a trading account starts
 * the shell over, and the Account screen mustn't lose what it was doing for that account.
 */
internal val LocalWalletViewModels = staticCompositionLocalOf<ViewModelStoreOwner?> { null }

@Composable
fun AccountRoute(
  onOpenSettings: () -> Unit,
  onCreateAccount: () -> Unit,
  onImportAccount: () -> Unit,
  onContinueSetup: (String) -> Unit,
  modifier: Modifier = Modifier,
  openReceive: Boolean = false,
  onReceiveOpened: () -> Unit = {},
  viewModel: SettingsViewModel =
    koinViewModel(
      viewModelStoreOwner =
        LocalWalletViewModels.current ?: checkNotNull(LocalViewModelStoreOwner.current),
    ),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val catalog: AssetCatalogRepository = koinInject()
  val assetMetadata by catalog.assets.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(lifecycleOwner, viewModel) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      viewModel.followWalletBalances()
    }
  }
  LaunchedEffect(Unit) {
    viewModel.onIntent(SettingsIntent.LoadSubaccounts)
  }
  AccountScreen(
    state = state,
    onIntent = viewModel::onIntent,
    onOpenSettings = onOpenSettings,
    onCreateAccount = onCreateAccount,
    onImportAccount = onImportAccount,
    onContinueSetup = onContinueSetup,
    modifier = modifier,
    openReceive = openReceive,
    onReceiveOpened = onReceiveOpened,
    assetMetadata = assetMetadata,
  )
}

@Composable
fun AccountScreen(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onOpenSettings: () -> Unit,
  onCreateAccount: () -> Unit,
  onImportAccount: () -> Unit,
  onContinueSetup: (String) -> Unit,
  modifier: Modifier = Modifier,
  openReceive: Boolean = false,
  onReceiveOpened: () -> Unit = {},
  assetMetadata: Map<String, AssetMetadata> = emptyMap(),
) {
  var showWallets by remember { mutableStateOf(false) }
  var returnToWallets by remember { mutableStateOf(false) }
  LaunchedEffect(state.nameEditor != null) {
    if (state.nameEditor == null && returnToWallets) {
      returnToWallets = false
      showWallets = true
    }
  }
  var showAddWallet by remember { mutableStateOf(false) }
  var showDeposit by remember { mutableStateOf(false) }
  LaunchedEffect(openReceive) {
    if (openReceive) {
      showDeposit = true
      onReceiveOpened()
    }
  }
  var showWithdraw by remember { mutableStateOf(false) }
  var copied by remember { mutableStateOf(false) }
  var detailTitle by remember { mutableStateOf("Trading account address") }
  var detailAddress by remember { mutableStateOf<String?>(null) }
  val clipboard = LocalClipboardManager.current
  val profiles = state.preferences.profiles
  val active = profiles.firstOrNull { it.id == state.preferences.activeProfileId }
  val walletLabel = active?.walletDisplayName(profiles.indexOf(active).coerceAtLeast(0)) ?: "Wallet"
  val walletAddress = (active?.ownerAddress ?: active?.apiWalletAddress).orEmpty()
  val receiveQrPainter = rememberPreparedFlareQrCodePainter(walletAddress)
  val subaccounts =
    active
      ?.ownerAddress
      ?.let { state.subaccountsByOwner[it] }
      .orEmpty()
      .ifEmpty {
        active
          ?.selectedSubaccount
          ?.let {
            listOf(
              TradingAccount(address = it, owner = active.ownerAddress.orEmpty(), primary = true),
            )
          }
          .orEmpty()
      }
  LaunchedEffect(copied) {
    if (copied) {
      delay(COPIED_CONFIRMATION_MS)
      copied = false
    }
  }
  Column(
    modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
  ) {
    FlareTopBar(
      "Account",
      action = {
        IconButton(
          onClick = onOpenSettings,
          modifier = Modifier.size(48.dp).semantics { contentDescription = "Settings" },
        ) {
          Icon(FlareIcons.Settings, null, tint = FlareColors.TextPrimary)
        }
      },
    )
    state.error?.let { ActionNotice(it, Modifier.padding(bottom = 16.dp), NoticeTone.ALERT) }
    if (active == null) {
      Text("Your wallet", style = MaterialTheme.typography.headlineLarge)
      FlareButton(
        "Add wallet",
        { showAddWallet = true },
        Modifier.fillMaxWidth().padding(top = 24.dp),
      )
    } else {
      Column(
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(20.dp))
          .background(FlareColors.Surface)
          .padding(20.dp),
      ) {
        Text(
          "Wallet",
          style = MaterialTheme.typography.bodySmall,
          color = FlareColors.TextSecondary,
        )
        WalletNameRow(
          walletLabel,
          subtitle =
            if (active.ownerAddress == null) "Trading key · ${shortAddress(walletAddress)}"
            else shortAddress(walletAddress),
          icon = FlareIcons.Wallet,
          onClick = { showWallets = true },
          enabled = !state.busy,
          onClickLabel = "Open wallets",
          onRename = { onIntent(SettingsIntent.RenameWallet(active.id)) },
          showDivider = false,
          showActions = true,
        )
        val balances =
          state.walletBalances
            .takeIf {
              state.walletBalanceProfileId == active.id &&
                state.walletBalanceAddress == walletAddress
            }
            .orEmpty()
        if (balances.isNotEmpty()) WalletBalances(balances, assetMetadata)
        val need = state.setupNeed
        if (active.setupPending && need != null) {
          UnavailableNotice(need.reason, need)
        } else if (!active.onboardingComplete) {
          FlareButton("Finish setup", { onContinueSetup(active.id) }, Modifier.fillMaxWidth())
        }
        if (active.ownerAddress != null) {
          HorizontalDivider(Modifier.padding(top = 20.dp), color = FlareColors.BorderSubtle)
          Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            FlareButton("Receive", { showDeposit = true }, Modifier.weight(1f))
            FlareButton(
              "Withdraw",
              {
                showWithdraw = true
                onIntent(SettingsIntent.OpenWithdraw)
              },
              Modifier.weight(1f),
              style = FlareButtonStyle.OUTLINE,
              enabled =
                active.selectedSubaccount != null &&
                  !state.busy &&
                  state.capabilities.unavailableReason(ActionKind.FUNDS) == null,
            )
          }
          state.capabilities
            .unavailableReason(ActionKind.FUNDS)
            ?.takeIf { it != state.setupNeed?.reason }
            ?.let {
              Text(
                it,
                Modifier.padding(top = 12.dp),
                color = FlareColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
              )
            }
        }
      }
      Text(
        "Trading accounts",
        Modifier.padding(top = 32.dp, bottom = 12.dp),
        style = MaterialTheme.typography.titleMedium,
      )
      val subaccountsLoading =
        state.capabilities.subaccounts &&
          !state.subaccountsLoaded &&
          active.ownerAddress?.let { it !in state.subaccountsByOwner } == true
      val reduceMotion = rememberReducedMotion()
      // The skeleton gives way to the accounts, and an account added later grows into place.
      AnimatedContent(
        subaccountsLoading,
        transitionSpec = {
          if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
          else fadeIn(tween(180)) togetherWith fadeOut(tween(120))
        },
        label = "tradingAccounts",
      ) { loading ->
        if (loading) TradingAccountsSkeleton()
        else
          Column {
            val shownFirst = remember { subaccounts.map { it.address }.toSet() }
            subaccounts.forEachIndexed { index, account ->
              key(account.address) {
                val selected = account.address.equals(active.selectedSubaccount, ignoreCase = true)
                val entrance = remember {
                  MutableTransitionState(reduceMotion || account.address in shownFirst).apply {
                    targetState = true
                  }
                }
                AnimatedVisibility(entrance, enter = expandVertically() + fadeIn()) {
                  Column {
                    Row(
                      Modifier.fillMaxWidth()
                        .combinedClickable(
                          enabled = !state.busy,
                          role = Role.RadioButton,
                          onClickLabel = "Select trading account",
                          onClick = {
                            onIntent(SettingsIntent.SelectSubaccount(active.id, account.address))
                          },
                          onLongClickLabel = "Rename trading account",
                          onLongClick = {
                            onIntent(
                              SettingsIntent.RenameTradingAccount(active.id, account.address),
                            )
                          },
                        )
                        .semantics { this.selected = selected }
                        .padding(vertical = 12.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Icon(
                        FlareIcons.AccountBranch,
                        null,
                        modifier = Modifier.align(Alignment.Top).padding(top = 2.dp).size(24.dp),
                        tint = FlareColors.BorderStrong,
                      )
                      Spacer(Modifier.width(12.dp))
                      Column(Modifier.weight(1f)) {
                        Text(
                          active.tradingAccountDisplayName(account.address, account.label, index),
                          style = MaterialTheme.typography.bodyLarge,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                          shortAddress(account.address),
                          style = MaterialTheme.typography.bodySmall,
                          color = FlareColors.TextSecondary,
                        )
                      }
                      AnimatedVisibility(
                        selected,
                        enter =
                          if (reduceMotion) EnterTransition.None
                          else fadeIn() + expandHorizontally(),
                        exit =
                          if (reduceMotion) ExitTransition.None
                          else fadeOut() + shrinkHorizontally(),
                      ) {
                        Icon(
                          FlareIcons.CheckCircle,
                          "Selected",
                          tint = FlareColors.TextPrimary,
                          modifier = Modifier.size(20.dp),
                        )
                      }
                      IconButton(
                        onClick = {
                          detailTitle = "Trading account address"
                          detailAddress = account.address
                        },
                        modifier =
                          Modifier.semantics {
                            contentDescription = "Show trading account address"
                          },
                      ) {
                        Icon(
                          FlareIcons.Copy,
                          null,
                          tint = FlareColors.TextSecondary,
                          modifier = Modifier.size(18.dp),
                        )
                      }
                      AccountActionsMenu("Trading account", !state.busy) {
                        onIntent(SettingsIntent.RenameTradingAccount(active.id, account.address))
                      }
                    }
                    HorizontalDivider(color = FlareColors.BorderSubtle)
                  }
                }
              }
            }
          }
      }
      if (active.ownerAddress != null && state.capabilities.createSubaccounts) {
        AnimatedContent(
          state.creatingSubaccount,
          transitionSpec = {
            if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(tween(180)) togetherWith fadeOut(tween(120))
          },
          label = "addTradingAccount",
        ) { creating ->
          ActionRow(
            if (creating) "Creating trading account…" else "Add trading account",
            icon = FlareIcons.Add,
            enabled = !state.busy && !creating,
            onClick = { onIntent(SettingsIntent.CreateSubaccountForActiveProfile) },
          )
        }
      }
    }
    Spacer(Modifier.height(32.dp))
  }
  if (showWallets)
    FlareSheet("Wallets", { showWallets = false }) {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        profiles.forEachIndexed { index, profile ->
          WalletNameRow(
            profile.walletDisplayName(index),
            subtitle =
              if (!profile.onboardingComplete) "Finish setup"
              else shortAddress((profile.ownerAddress ?: profile.apiWalletAddress).orEmpty()),
            enabled = !state.busy,
            selected = profile.id == active?.id,
            onRename = {
              showWallets = false
              returnToWallets = true
              onIntent(SettingsIntent.RenameWallet(profile.id))
            },
            onClick = {
              showWallets = false
              if (profile.opensApp) onIntent(SettingsIntent.SelectProfile(profile.id))
              else onContinueSetup(profile.id)
            },
          )
        }
        if (walletAddress.isNotBlank())
          ActionRow(
            "Wallet address",
            subtitle = shortAddress(walletAddress),
            icon = FlareIcons.Copy,
            onClick = {
              showWallets = false
              detailTitle = "Wallet address"
              detailAddress = walletAddress
            },
          )
        ActionRow(
          "Add wallet",
          icon = FlareIcons.Add,
          onClick = {
            showWallets = false
            showAddWallet = true
          },
        )
      }
    }
  state.nameEditor?.let { AccountNameSheet(it, onIntent) }
  if (showAddWallet)
    FlareSheet("Add wallet", { showAddWallet = false }) {
      ActionRow(
        "Create wallet",
        subtitle = "Get a new recovery phrase",
        icon = FlareIcons.Key,
        onClick = {
          showAddWallet = false
          onCreateAccount()
        },
      )
      ActionRow(
        "Import wallet",
        subtitle = "Use a recovery phrase or private key",
        icon = FlareIcons.Wallet,
        onClick = {
          showAddWallet = false
          onImportAccount()
        },
      )
    }
  detailAddress?.let { address ->
    FlareSheet(detailTitle, { detailAddress = null }) {
      SelectionContainer { Text(address, style = MaterialTheme.typography.bodyMedium) }
      Spacer(Modifier.height(24.dp))
      FlareButton(
        if (copied) "Copied" else "Copy address",
        {
          clipboard.setText(AnnotatedString(address))
          copied = true
        },
        Modifier.fillMaxWidth(),
      )
    }
  }
  if (showDeposit && walletAddress.isNotBlank())
    DepositSheet(
      walletLabel,
      walletAddress,
      copied,
      {
        clipboard.setText(AnnotatedString(walletAddress))
        copied = true
      },
      { showDeposit = false },
      receiveQrPainter,
    )
  if (showWithdraw && active?.selectedSubaccount != null)
    WithdrawSheet(
      state,
      onIntent,
      {
        if (!state.withdrawing) {
          showWithdraw = false
          onIntent(SettingsIntent.DismissWithdraw)
        }
      },
    )
}

@Composable
private fun WalletNameRow(
  name: String,
  subtitle: String,
  enabled: Boolean,
  onRename: () -> Unit,
  onClick: () -> Unit,
  icon: ImageVector? = null,
  selected: Boolean = false,
  showDivider: Boolean = true,
  showActions: Boolean = false,
  onClickLabel: String = "Select wallet",
) {
  Row(
    Modifier.fillMaxWidth()
      .combinedClickable(
        enabled = enabled,
        role = Role.Button,
        onClickLabel = onClickLabel,
        onClick = onClick,
        onLongClickLabel = "Rename wallet",
        onLongClick = onRename,
      )
      .padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    icon?.let {
      Icon(it, null, tint = FlareColors.TextSecondary, modifier = Modifier.size(22.dp))
    }
    Column(Modifier.weight(1f)) {
      Text(
        name,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
    }
    if (selected)
      Icon(
        FlareIcons.CheckCircle,
        "Selected",
        tint = FlareColors.TextPrimary,
        modifier = Modifier.size(20.dp),
      )
    Icon(
      FlareIcons.ChevronRight,
      null,
      tint = FlareColors.TextTertiary,
      modifier = Modifier.size(20.dp),
    )
    if (showActions) AccountActionsMenu("Wallet", enabled, onRename)
  }
  if (showDivider) HorizontalDivider(color = FlareColors.BorderSubtle)
}

@Composable
private fun AccountActionsMenu(label: String, enabled: Boolean, onRename: () -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(
      { expanded = true },
      enabled = enabled,
      modifier = Modifier.size(48.dp).semantics { contentDescription = "$label options" },
    ) {
      Icon(FlareIcons.More, null, Modifier.size(20.dp), tint = FlareColors.TextSecondary)
    }
    DropdownMenu(expanded, { expanded = false }) {
      DropdownMenuItem(
        text = { Text("Rename") },
        onClick = {
          expanded = false
          onRename()
        },
      )
    }
  }
}

@Composable
private fun WalletBalances(
  balances: List<WalletTokenBalance>,
  assetMetadata: Map<String, AssetMetadata>,
) {
  val fontScale = LocalDensity.current.fontScale
  BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
    val columns =
      if (maxWidth < 280.dp || fontScale > 1.3f || balances.any { it.displayAmount.length > 8 }) 1
      else 2
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
      balances.chunked(columns).forEach { group ->
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          group.forEachIndexed { index, balance ->
            if (index > 0) VerticalDivider(Modifier.height(44.dp), color = FlareColors.BorderSubtle)
            Row(
              Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              val metadata = assetMetadata[balance.symbol]
              AssetIcon(resolveAssetIdentity(balance.symbol, balance.symbol, metadata), 28.dp)
              Column {
                Text(
                  balance.symbol,
                  style = MaterialTheme.typography.bodySmall,
                  color = FlareColors.TextSecondary,
                )
                Text(
                  balance.displayAmount,
                  style = MaterialTheme.typography.titleLarge,
                  color = FlareColors.TextPrimary,
                )
              }
            }
          }
          if (group.size < columns) Spacer(Modifier.weight(1f))
        }
      }
      if (balances.any { it.unavailable }) {
        Text(
          if (balances.any { it.unavailable && it.amount == null }) "Balance unavailable"
          else "Updated earlier",
          style = MaterialTheme.typography.bodySmall,
          color = FlareColors.TextSecondary,
        )
      }
    }
  }
}

@Composable
private fun DepositSheet(
  accountLabel: String,
  address: String,
  copied: Boolean,
  onCopy: () -> Unit,
  onDismiss: () -> Unit,
  qrPainter: Painter?,
) {
  val chain = LocalVenue.current.chain
  val receive = LocalVenue.current.receive
  // Keep an immediate-open fallback for this sheet rather than replacing its warm draw cache
  // when background preparation finishes. A different address always selects a new painter.
  val painter = remember(address) { qrPainter }
  FlareSheet("Receive funds", onDismiss, heightFraction = 0.9f) {
    Text(
      receive?.summary
        ?: "Send ${chain.depositAssets.joinToString(" or ")} on the ${chain.name} network to $accountLabel.",
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
    // The code takes whatever room the sheet can spare, up to a size that scans from arm's length.
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      val codeSize = minOf(maxWidth * 0.84f, maxHeight - 112.dp, 320.dp).coerceAtLeast(168.dp)
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (painter != null) {
          FlareQrCode(painter, "QR code for your ${chain.name} address", Modifier.size(codeSize))
        } else {
          // An immediate tap can precede background preparation; keep Receive fully functional.
          FlareQrCode(address, "QR code for your ${chain.name} address", Modifier.size(codeSize))
        }
        ReceiveAddress(address, onCopy, Modifier.padding(top = 20.dp))
      }
    }
    ActionNotice(
      receive?.warning
        ?: ("Only send ${chain.name}-native ${chain.depositAssets.joinToString(" and ")}. Assets sent " +
          "from other networks can be lost for good."),
      tone = NoticeTone.INFO,
    )
    FlareButton(
      text = if (copied) "Copied" else "Copy address",
      onClick = onCopy,
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
  }
}

/** The address to share, emphasised at both ends. Tapping it copies. */
@Composable
private fun ReceiveAddress(address: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
  Text(
    emphasizedAddress(address),
    modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(role = Role.Button, onClickLabel = "Copy address", onClick = onCopy)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    style = MaterialTheme.typography.bodyMedium,
    textAlign = TextAlign.Center,
  )
}

/** Where a withdrawal is: being set up, checked, done, or stopped. */
private enum class WithdrawStage {
  FORM,
  REVIEW,
  SENT,
  FAILED,
}

/**
 * Withdrawing USDC from the trading account to any address on the venue's chain. Every stage keeps
 * its action at the bottom. An interrupted withdrawal reopens at its review, and a failure says
 * where the funds are.
 */
@Composable
fun WithdrawSheet(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onDismiss: () -> Unit,
  startInReview: Boolean = state.pendingWithdrawal != null,
) {
  var reviewing by rememberSaveable { mutableStateOf(startInReview) }
  // Progress is saved as soon as any withdrawal starts; only one found on opening is being resumed.
  val resuming = rememberSaveable { state.pendingWithdrawal != null }
  val transaction = state.withdrawTransaction
  val stage =
    when {
      transaction is ActionState.Committed -> WithdrawStage.SENT
      !state.withdrawing &&
        transaction != null &&
        (transaction is ActionState.Failed || state.withdrawError != null) -> WithdrawStage.FAILED
      reviewing || state.withdrawing -> WithdrawStage.REVIEW
      else -> WithdrawStage.FORM
    }
  FlareSheet(
    title =
      when (stage) {
        WithdrawStage.FORM -> "Withdraw"
        WithdrawStage.REVIEW -> "Review withdrawal"
        WithdrawStage.SENT,
        WithdrawStage.FAILED -> null
      },
    onDismiss = onDismiss,
    dismissible = !state.withdrawing,
  ) {
    // The height animates between stages. Its clip spans the full sheet, not just the text column,
    // so
    // anything that draws into the gutter, like the outcome icon's halo, isn't cut at the margin.
    Column(Modifier.fullBleed().animateContentSize().padding(horizontal = 24.dp)) {
      when (stage) {
        WithdrawStage.FORM -> WithdrawForm(state, onIntent) { reviewing = true }
        WithdrawStage.REVIEW -> WithdrawReview(state, onIntent, resuming) { reviewing = false }
        WithdrawStage.SENT ->
          WithdrawSent(state, (transaction as ActionState.Committed).reference, onDismiss)
        WithdrawStage.FAILED ->
          WithdrawFailed(state, onIntent) {
            onIntent(SettingsIntent.EditWithdraw)
            reviewing = false
          }
      }
    }
  }
}

@Composable
private fun WithdrawForm(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onReview: () -> Unit,
) {
  val venue = LocalVenue.current
  val terms = venue.withdrawal
  val chainName =
    if (state.withdrawOnVenue) venue.chain.name else terms?.network ?: venue.chain.name
  val credentials = LocalCredentialParser.current
  val destination = state.withdrawDestination.trim()
  val addressInvalid = destination.isNotEmpty() && !credentials.isAddress(destination)
  val amount = state.withdrawAmount.toDoubleOrNull()
  val available = state.withdrawable
  // Guard at the field: an amount above the balance is flagged before anything is signed.
  val exceeds = amount != null && available != null && amount > available + BALANCE_EPSILON
  val fee = withdrawalFee(state)
  val coversFee = fee == null || (amount != null && amount > fee)
  val canReview =
    credentials.isAddress(destination) && amount != null && amount > 0.0 && !exceeds && coversFee
  if (terms?.internalTransfers == true) {
    FlareSegmentedControl(
      listOf(false, true),
      state.withdrawOnVenue,
      { onIntent(SettingsIntent.SetWithdrawOnVenue(it)) },
      { if (it) "On ${venue.chain.name}" else "To ${terms.network}" },
      Modifier.fillMaxWidth().padding(bottom = 16.dp),
    )
  }
  Text(
    if (state.withdrawOnVenue) "Send USDC from your trading account to another $chainName account."
    else "Send USDC from your trading account to any $chainName address.",
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodyMedium,
  )
  FlareAddressField(
    state.withdrawDestination,
    { onIntent(SettingsIntent.ChangeWithdrawDestination(it)) },
    Modifier.fillMaxWidth().padding(top = 20.dp),
    label = "Recipient",
    placeholder = "$chainName address",
    isError = addressInvalid,
    supportingText = if (addressInvalid) "Enter a valid $chainName address." else null,
  )
  FlareAmountField(
    value = state.withdrawAmount,
    onValueChange = { onIntent(SettingsIntent.ChangeWithdrawAmount(it)) },
    label = "Amount",
    unit = "USDC",
    availableText = available?.let { "Available ${formatTokenBalance(it)} USDC" },
    onMaxClick =
      available
        ?.takeIf { it > 0.0 }
        ?.let { max ->
          { onIntent(SettingsIntent.ChangeWithdrawAmount(maxAmountInput(max), immediate = true)) }
        },
    isError = exceeds || !coversFee,
    supportingText =
      when {
        exceeds && available != null ->
          "You can withdraw up to ${formatTokenBalance(available)} USDC."
        fee != null -> "${formatQuantity(fee, 6)} USDC of it pays the withdrawal fee."
        else -> null
      },
    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
  )
  state.withdrawError?.let {
    ActionNotice(
      actionFailure(it, WITHDRAWAL_FAILED),
      Modifier.padding(top = 12.dp),
      NoticeTone.ALERT,
    )
  }
  FlareButton(
    "Review withdrawal",
    onReview,
    Modifier.fillMaxWidth().padding(top = 24.dp),
    enabled = canReview,
  )
}

@Composable
private fun WithdrawReview(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  resuming: Boolean,
  onEdit: () -> Unit,
) {
  // An unfinished withdrawal can only be completed as it was started, so it can't be edited.
  val pending = state.pendingWithdrawal?.takeIf { resuming || !state.withdrawing }
  // A withdrawal to the owner's own wallet is a single step, with nothing to send on.
  val addresses = LocalCredentialParser.current
  val sendsOn =
    state.profile.ownerAddress?.let { owner ->
      !addresses.sameAddress(state.withdrawDestination.trim(), owner)
    } ?: true
  pending?.let {
    ActionNotice(
      if (it.withdrawalCommitted)
        "Your USDC reached your wallet but wasn’t sent on yet. Confirm to finish sending it."
      else "Your last withdrawal didn’t finish. Confirm to pick it up where it stopped.",
      Modifier.padding(bottom = 20.dp),
    )
  }
  Text("${withdrawAmountText(state)} USDC", style = MaterialTheme.typography.displaySmall)
  Text(
    "From your trading account",
    Modifier.padding(top = 4.dp),
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodyMedium,
  )
  Text(
    "To",
    Modifier.padding(top = 24.dp),
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.labelMedium,
  )
  Text(
    emphasizedAddress(state.withdrawDestination.trim()),
    Modifier.padding(top = 6.dp),
    style = MaterialTheme.typography.bodyLarge,
  )
  HorizontalDivider(Modifier.padding(top = 16.dp), color = FlareColors.BorderSubtle)
  val venue = LocalVenue.current
  val chain = venue.chain
  DetailRow(
    "Network",
    if (state.withdrawOnVenue) chain.name else venue.withdrawal?.network ?: chain.name,
  )
  withdrawalFee(state)?.let { fee ->
    DetailRow("Withdrawal fee", "${formatQuantity(fee, 6)} USDC")
    state.withdrawAmount.toDoubleOrNull()?.let {
      DetailRow("Recipient gets", "${formatQuantity((it - fee).coerceAtLeast(0.0), 6)} USDC")
    }
  }
  if (state.capabilities.sponsoredFees)
    DetailRow(
      "Fees",
      state.withdrawNetworkFee?.let { fee ->
        if (fee.covered) "Covered by Flare" else "≈ ${chain.feeAmount(fee.estimate)}"
      } ?: "—",
    )
  if (state.withdrawOnVenue)
    Text(
      "${chain.name} may charge a one-time fee to activate an address that has never used it.",
      Modifier.padding(top = 12.dp),
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodySmall,
    )
  Text(
    "Withdrawals can’t be reversed. Check the address before you confirm.",
    Modifier.padding(top = 12.dp),
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodySmall,
  )
  // Problems found before anything was sent stay here, next to what they're about.
  (state.withdrawError ?: state.withdrawFeeError)
    ?.takeIf { state.withdrawTransaction == null }
    ?.let {
      ActionNotice(
        actionFailure(it, WITHDRAWAL_FAILED),
        Modifier.padding(top = 16.dp),
        NoticeTone.ALERT,
      )
    }
  FlareButton(
    when {
      state.withdrawing && sendsOn && state.pendingWithdrawal?.withdrawalCommitted == true ->
        "Sending to recipient…"
      state.withdrawing -> "Withdrawing…"
      pending != null -> "Finish withdrawal"
      else -> "Confirm withdrawal"
    },
    { onIntent(SettingsIntent.SubmitWithdraw) },
    Modifier.fillMaxWidth().padding(top = 24.dp),
    working = state.withdrawing,
  )
  // An interrupted withdrawal can only be finished as it was started.
  if (pending == null) QuietAction("Edit", onEdit, enabled = !state.withdrawing)
}

@Composable
private fun WithdrawSent(state: SettingsUiState, hash: String, onDone: () -> Unit) {
  val explorer = LocalTransactionExplorer.current
  val browser = LocalUriHandler.current
  val revealed = rememberOutcomeReveal()
  val amount = withdrawAmountText(state)
  val recipient = shortAddress(state.withdrawDestination.trim())
  OutcomeIcon(Outcome.SUCCESS)
  Column(revealed) {
    Text(
      "Withdrawal complete",
      Modifier.padding(top = 16.dp),
      style = MaterialTheme.typography.headlineSmall,
    )
    Text(
      "$amount USDC was sent to $recipient.",
      Modifier.padding(top = 6.dp),
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  Column(Modifier.padding(top = 16.dp).then(revealed)) {
    DetailRow("Amount", "$amount USDC")
    DetailRow("To", recipient)
    explorer.url(hash)?.let { url ->
      ActionRow(
        explorer.label,
        icon = FlareIcons.External,
        onClick = { runCatching { browser.openUri(url) } },
      )
    }
  }
  FlareButton("Done", onDone, Modifier.fillMaxWidth().padding(top = 24.dp))
}

@Composable
private fun WithdrawFailed(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onEdit: () -> Unit,
) {
  val pending = state.pendingWithdrawal
  val revealed = rememberOutcomeReveal()
  val raw = state.withdrawError ?: (state.withdrawTransaction as? ActionState.Failed)?.message
  val cause =
    actionFailure(raw, "")
      .trim()
      .takeIf { it.isNotEmpty() }
      ?.let { if (it.endsWith('.')) it else "$it." }
  // Say where the money is: that is what someone needs to know after a failure.
  val funds =
    when {
      pending?.withdrawalCommitted == true ->
        "Your USDC is in your wallet and wasn’t sent on. Try again to finish."
      pending?.withdrawalReference != null ->
        "It isn’t clear yet whether it went through. Trying again checks first, so nothing is sent twice."
      else -> "Your USDC is still in your trading account."
    }
  OutcomeIcon(Outcome.FAILURE)
  Column(revealed) {
    Text(
      "Withdrawal didn’t go through",
      Modifier.padding(top = 16.dp),
      style = MaterialTheme.typography.headlineSmall,
    )
    Text(
      listOfNotNull(cause, funds).joinToString(" "),
      Modifier.padding(top = 6.dp),
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  Column(Modifier.padding(top = 16.dp).then(revealed)) {
    DetailRow("Amount", "${withdrawAmountText(state)} USDC")
    DetailRow("To", shortAddress(state.withdrawDestination.trim()))
  }
  FlareButton(
    "Try again",
    { onIntent(SettingsIntent.SubmitWithdraw) },
    Modifier.fillMaxWidth().padding(top = 24.dp),
    working = state.withdrawing,
  )
  if (pending == null) QuietAction("Edit withdrawal", onEdit, enabled = !state.withdrawing)
}

/** The quiet way out under a primary action, as in every confirmation. */
@Composable
private fun QuietAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
  TextButton(
    onClick,
    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 4.dp),
    enabled = enabled,
  ) {
    Text(
      label,
      color = if (enabled) FlareColors.TextSecondary else FlareColors.TextDisabled,
      style = MaterialTheme.typography.labelLarge,
    )
  }
}

/** What the venue keeps from a withdrawal leaving it; null for a send on the venue, or no fee. */
@Composable
private fun withdrawalFee(state: SettingsUiState): Double? =
  LocalVenue.current.withdrawal?.feeUsdc?.toDoubleOrNull()?.takeIf {
    !state.withdrawOnVenue && it > 0.0
  }

private fun withdrawAmountText(state: SettingsUiState): String =
  state.withdrawAmount.toDoubleOrNull()?.let { formatQuantity(it, 6) } ?: state.withdrawAmount

/** The whole balance, rounded down to USDC's precision so it never reads as more than there is. */
private fun maxAmountInput(balance: Double): String {
  val micros = floor(balance * 1_000_000).toLong().coerceAtLeast(0)
  val fraction = (micros % 1_000_000).toString().padStart(6, '0').trimEnd('0')
  return (micros / 1_000_000).toString() + if (fraction.isEmpty()) "" else ".$fraction"
}

private const val WITHDRAWAL_FAILED = "Your withdrawal didn’t go through."

/** Tolerance for comparing a typed amount with a balance read as a double. */
private const val BALANCE_EPSILON = 1e-9
