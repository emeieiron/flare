package xyz.mcxross.flare.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareIcons
import xyz.mcxross.flare.design.FlareMark
import xyz.mcxross.flare.design.FlarePageTransition
import xyz.mcxross.flare.design.IGNITION_REST
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.pagePopEnter
import xyz.mcxross.flare.design.pagePopExit
import xyz.mcxross.flare.design.pagePushEnter
import xyz.mcxross.flare.design.pagePushExit
import xyz.mcxross.flare.design.rememberFlareIgnition
import xyz.mcxross.flare.design.rememberReducedMotion
import xyz.mcxross.flare.design.shortAddress

@Composable
fun OnboardingRoute(
  onCompleted: () -> Unit,
  onBack: (() -> Unit)? = null,
  initialMode: String? = null,
  profileId: String? = null,
  modifier: Modifier = Modifier,
  viewModel: OnboardingViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(initialMode, profileId) {
    if (initialMode != null) {
      viewModel.onIntent(OnboardingIntent.InitializeMode(initialMode, profileId))
    }
  }
  LaunchedEffect(viewModel) {
    viewModel.effects.collect { effect ->
      when (effect) {
        OnboardingEffect.Completed -> onCompleted()
        OnboardingEffect.Cancelled -> onBack?.invoke() ?: onCompleted()
      }
    }
  }
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
    viewModel.onIntent(OnboardingIntent.ClearSensitiveState)
  }
  OnboardingScreen(
    state = state,
    onIntent = viewModel::onIntent,
    onBack = onBack,
    initialMode = initialMode,
    modifier = modifier,
  )
}

@Composable
fun OnboardingScreen(
  state: OnboardingUiState,
  onIntent: (OnboardingIntent) -> Unit,
  onBack: (() -> Unit)? = null,
  initialMode: String? = null,
  modifier: Modifier = Modifier,
) {
  // The welcome intro plays once per launch, not again when someone steps back to it.
  var introPending by rememberSaveable { mutableStateOf(true) }
  val goBack = {
    if (!state.busy) {
      if (state.step == OnboardingStep.WELCOME && onBack != null) onBack()
      else onIntent(OnboardingIntent.Back)
    }
  }
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = (state.step != OnboardingStep.WELCOME || onBack != null) && !state.pastReturn,
    onBackCompleted = goBack,
  )
  AnimatedContent(
    targetState = state.step == OnboardingStep.WELCOME && initialMode == null,
    modifier = modifier.fillMaxSize(),
    transitionSpec = {
      val move =
        if (targetState) pagePopEnter() togetherWith pagePopExit()
        else pagePushEnter() togetherWith pagePushExit()
      move.apply { targetContentZIndex = if (targetState) 0f else 1f }.using(null)
    },
    label = "Welcome and setup",
  ) { welcome ->
    if (welcome) {
      WelcomeScreen(
        intro = introPending,
        onIntroShown = { introPending = false },
        busy = state.busy,
        onCreate = { onIntent(OnboardingIntent.CreateOwner) },
        onImport = { onIntent(OnboardingIntent.ShowImport) },
        onBack = onBack,
      )
    } else {
      SetupFlow(state, onIntent, goBack)
    }
  }
}

@Composable
private fun SetupFlow(
  state: OnboardingUiState,
  onIntent: (OnboardingIntent) -> Unit,
  goBack: () -> Unit,
) {
  Column(Modifier.fillMaxSize().background(FlareColors.Canvas).safeDrawingPadding().imePadding()) {
    Box(Modifier.fillMaxWidth().height(56.dp)) {
      val canGoBack = !state.busy && !state.pastReturn
      IconButton(goBack, Modifier.align(Alignment.CenterStart).padding(start = 4.dp), enabled = canGoBack) {
        Icon(
          FlareIcons.ArrowBack,
          "Back",
          tint = if (canGoBack) FlareColors.TextPrimary else FlareColors.TextDisabled,
        )
      }
      SetupProgress(state.stage, Modifier.align(Alignment.Center).size(28.dp))
    }
    FlarePageTransition(state.step, depth = { it.order }, modifier = Modifier.weight(1f)) { step ->
      when (step) {
        // A setup opened from the account screen shows this only while it finds where to begin.
        OnboardingStep.WELCOME ->
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
          }
        OnboardingStep.IMPORT -> ImportStep(state, onIntent)
        OnboardingStep.SHOW_BACKUP -> BackupStep(state, onIntent)
        OnboardingStep.CONFIRM_BACKUP -> ConfirmBackupStep(state, onIntent)
        OnboardingStep.SUBACCOUNT -> SubaccountStep(state, onIntent)
        OnboardingStep.OPEN_ACCOUNT -> OpenAccountStep(state, onIntent)
        OnboardingStep.ENABLE_TRADING -> EnableTradingStep(state, onIntent)
      }
    }
  }
}

/** Once a new wallet's trading account is open on chain, setup only goes forward. */
private val OnboardingUiState.pastReturn: Boolean
  get() = step == OnboardingStep.OPEN_ACCOUNT && accountOpened

/**
 * Setup has three stages, one for each bar of the mark: securing the account, its trading account,
 * trading. A new wallet opens its account and enables trading on one screen, which moves on to the
 * last stage as soon as the account is open.
 */
private val OnboardingUiState.stage: Int
  get() =
    when (step) {
      OnboardingStep.WELCOME,
      OnboardingStep.IMPORT,
      OnboardingStep.SHOW_BACKUP,
      OnboardingStep.CONFIRM_BACKUP -> 0
      OnboardingStep.SUBACCOUNT -> 1
      OnboardingStep.OPEN_ACCOUNT -> if (accountOpened) 2 else 1
      OnboardingStep.ENABLE_TRADING -> 2
    }

private val OnboardingStep.order: Int
  get() =
    when (this) {
      OnboardingStep.WELCOME -> 0
      OnboardingStep.IMPORT,
      OnboardingStep.SHOW_BACKUP -> 1
      OnboardingStep.CONFIRM_BACKUP -> 2
      OnboardingStep.SUBACCOUNT,
      OnboardingStep.OPEN_ACCOUNT -> 3
      OnboardingStep.ENABLE_TRADING -> 4
    }

/**
 * Setup's progress, drawn as the mark. The bars stand for the stages; the current one is lit, the ones
 * to come are grey, and a finished stage's bar flares the moment it's done and keeps a soft glow.
 */
@Composable
private fun SetupProgress(stage: Int, modifier: Modifier = Modifier) {
  val reduceMotion = rememberReducedMotion()
  val haptics = LocalHapticFeedback.current
  val ignition = rememberFlareIgnition()
  val colors =
    List(3) { bar ->
      animateColorAsState(
        if (bar <= stage) FlareColors.Positive else FlareColors.BorderStrong,
        tween(320),
        label = "Setup stage $bar",
      )
    }
  var shown by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(stage) {
    val previous = shown
    shown = stage
    if (previous != null && stage > previous) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    for (bar in 0..2) {
      launch {
        val level = if (bar < stage) IGNITION_REST else 0f
        // A stage already done when setup opens is shown lit; one finished just now flares.
        ignition.light(bar, level, reduceMotion || previous == null || bar < previous)
      }
    }
  }
  FlareMark(
    modifier.semantics { contentDescription = "Step ${stage + 1} of 3" },
    barColor = { colors[it].value },
    glow = ignition::level,
  )
}

/**
 * A setup step: its heading and body scroll, and its actions stay at the bottom, above the keyboard.
 */
@Composable
internal fun StepLayout(
  title: String,
  subtitle: String?,
  actions: @Composable ColumnScope.() -> Unit,
  content: @Composable ColumnScope.() -> Unit = {},
) {
  Column(Modifier.fillMaxSize()) {
    Column(
      Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
    ) {
      Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineLarge)
      if (subtitle != null) {
        Text(
          subtitle,
          Modifier.padding(top = 12.dp),
          style = MaterialTheme.typography.bodyLarge,
          color = FlareColors.TextSecondary,
        )
      }
      Spacer(Modifier.height(28.dp))
      content()
      Spacer(Modifier.height(24.dp))
    }
    Column(
      Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      content = actions,
    )
  }
}

/**
 * A step's actions, after anything that went wrong. When Flare couldn't cover the network fee, the way
 * forward depends on the wallet: it can pay (and the person says so first), it needs funding, or its
 * balance couldn't be checked. Nothing is ever charged to it without that choice.
 */
@Composable
internal fun ColumnScope.SetupActions(
  state: OnboardingUiState,
  onIntent: (OnboardingIntent) -> Unit,
  primary: @Composable ColumnScope.() -> Unit,
) {
  when (val fee = state.setupFee) {
    null -> {
      state.error?.let { ActionNotice(it, tone = NoticeTone.ALERT) }
      primary()
    }
    is SetupFee.WalletCanPay -> {
      ActionNotice(
        "Flare couldn’t cover this step’s network fee. Your wallet can pay it: about " +
          "${aptFee(fee.estimateOctas)}, from its ${aptBalance(fee.balanceOctas)}."
      )
      FlareButton(
        "Pay ${aptFee(fee.estimateOctas)} and continue",
        { onIntent(OnboardingIntent.ConfirmSetupSelfPay) },
        Modifier.fillMaxWidth(),
        working = state.busy,
      )
      FlareButton(
        "Try again without paying",
        { onIntent(OnboardingIntent.RetrySetup) },
        Modifier.fillMaxWidth(),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    is SetupFee.WalletNeedsFunds -> {
      ActionNotice(
        "Flare couldn’t cover this step’s network fee, and your wallet can’t pay it yet: the network " +
          "sets aside ${aptFee(fee.reserveOctas)} before it runs, and your wallet has " +
          "${aptBalance(fee.balanceOctas)}. Add APT to it, or try again later.",
        tone = NoticeTone.ALERT,
      )
      FundingAddress(fee.address)
      FlareButton(
        "Try again",
        { onIntent(OnboardingIntent.RetrySetup) },
        Modifier.fillMaxWidth(),
        working = state.busy,
      )
    }
    is SetupFee.AwaitingSponsor -> {
      ActionNotice(
        "Flare couldn’t cover the network fee just now. A new wallet has no APT, so nothing was " +
          "charged, and everything that went through is saved. Try again in a moment."
      )
      FlareButton(
        "Try again",
        { onIntent(OnboardingIntent.RetrySetup) },
        Modifier.fillMaxWidth(),
        working = state.busy,
      )
    }
    is SetupFee.Unchecked -> {
      ActionNotice(
        "Flare couldn’t cover this step’s network fee just now. Nothing was charged, and your " +
          "progress is saved."
      )
      FlareButton(
        "Try again",
        { onIntent(OnboardingIntent.RetrySetup) },
        Modifier.fillMaxWidth(),
        working = state.busy,
      )
    }
  }
}

/** Where to send APT so the wallet can pay the fee, one tap from the clipboard. */
@Composable
private fun FundingAddress(address: String) {
  val clipboard = LocalClipboardManager.current
  val shape = RoundedCornerShape(14.dp)
  Row(
    Modifier.fillMaxWidth().clip(shape).background(FlareColors.Surface).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text("Your wallet", style = MaterialTheme.typography.labelSmall, color = FlareColors.TextTertiary)
      Text(shortAddress(address), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium)
    }
    Text(
      "Copy",
      Modifier.clip(RoundedCornerShape(8.dp))
        .border(1.dp, FlareColors.BorderDefault, RoundedCornerShape(8.dp))
        .clickable(role = Role.Button, onClickLabel = "Copy wallet address") {
          clipboard.setText(AnnotatedString(address))
        }
        .padding(horizontal = 10.dp, vertical = 5.dp),
      style = MaterialTheme.typography.labelMedium,
      color = FlareColors.Positive,
    )
  }
}

/** A fee in APT to four decimals, rounded up so it's never understated. */
private fun aptFee(octas: ULong): String = "${((octas + 9_999uL) / 10_000uL).toDecimalString(4)} APT"

/** A balance in APT to four decimals, rounded down so it's never overstated. */
private fun aptBalance(octas: ULong): String = "${(octas / 10_000uL).toDecimalString(4)} APT"
