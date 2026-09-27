package xyz.mcxross.flare.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.mcxross.flare.data.CredentialFormat
import xyz.mcxross.flare.data.WalletCredential
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.design.FlareAddressField
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareIcons
import xyz.mcxross.flare.design.FlareSegmentedControl
import xyz.mcxross.flare.design.FlareSkeletonBox
import xyz.mcxross.flare.design.ProtectFromScreenCapture
import xyz.mcxross.flare.design.SwitchRow
import xyz.mcxross.flare.design.rememberFlareShimmer
import xyz.mcxross.flare.design.rememberReducedMotion
import xyz.mcxross.flare.design.shortAddress
import com.valentinilk.shimmer.shimmer

private val Whitespace = Regex("\\s+")

@Composable
internal fun ImportStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  ProtectFromScreenCapture()
  val format = remember(state.input) { WalletCredential.detect(state.input) }
  val words = remember(state.input) { state.input.trim().split(Whitespace).count(String::isNotEmpty) }
  val phraseAsTradingKey = state.apiImport && format == CredentialFormat.RECOVERY_PHRASE
  val ready =
    format != CredentialFormat.UNKNOWN &&
      !phraseAsTradingKey &&
      (!state.apiImport || state.tradingAccountInput.isNotBlank()) &&
      (!state.apiImport || state.tradingAccountCheck.usable)
  StepLayout(
    title = "Import your account",
    subtitle = "Enter the recovery phrase or private key of the account you want to use.",
    actions = {
      SetupActions(state, onIntent) {
        FlareButton(
          "Import",
          { onIntent(OnboardingIntent.ImportCredential) },
          Modifier.fillMaxWidth(),
          enabled = ready,
          working = state.busy,
        )
      }
    },
  ) {
    SecretField(
      value = state.input,
      onValueChange = { onIntent(OnboardingIntent.ChangeInput(it)) },
      recognized = format != CredentialFormat.UNKNOWN && !phraseAsTradingKey,
      enabled = !state.busy,
    )
    CredentialHint(
      when {
        state.input.isBlank() -> null
        phraseAsTradingKey -> Hint("A trading key is a private key, not a recovery phrase.", HintTone.ALERT)
        format == CredentialFormat.RECOVERY_PHRASE -> Hint("$words-word recovery phrase", HintTone.RECOGNIZED)
        format == CredentialFormat.PRIVATE_KEY -> Hint("Private key", HintTone.RECOGNIZED)
        else -> Hint("Enter 12 or 24 words, or a private key.", HintTone.NEUTRAL)
      }
    )
    AnimatedVisibility(format == CredentialFormat.PRIVATE_KEY || state.apiImport) {
      Column(Modifier.padding(top = 20.dp)) {
        FlareSegmentedControl(
          listOf(false, true),
          state.apiImport,
          { onIntent(OnboardingIntent.SetApiImport(it)) },
          { if (it) "Trading key" else "Owner key" },
          Modifier.fillMaxWidth(),
          enabled = !state.busy,
        )
        Text(
          if (state.apiImport) {
            "A trading key can only trade for one account. Deposits and withdrawals stay with its owner."
          } else {
            "The key that owns the account. It can trade, deposit and withdraw."
          },
          Modifier.padding(top = 10.dp),
          style = MaterialTheme.typography.bodySmall,
          color = FlareColors.TextSecondary,
        )
        if (state.apiImport) {
          FlareAddressField(
            state.tradingAccountInput,
            { onIntent(OnboardingIntent.ChangeTradingAccount(it)) },
            Modifier.fillMaxWidth().padding(top = 16.dp),
            label = "Trading account",
            enabled = !state.busy,
          )
          CredentialHint(tradingAccountHint(state.tradingAccountCheck))
        }
      }
    }
  }
}

private val TradingAccountCheck.usable: Boolean
  get() = this == TradingAccountCheck.UNKNOWN || this == TradingAccountCheck.TRADING_ACCOUNT

private fun tradingAccountHint(check: TradingAccountCheck): Hint? =
  when (check) {
    TradingAccountCheck.UNKNOWN -> null
    TradingAccountCheck.TRADING_ACCOUNT -> Hint("Active trading account", HintTone.RECOGNIZED)
    TradingAccountCheck.CLOSED -> Hint("This trading account is closed.", HintTone.ALERT)
    TradingAccountCheck.KEY_ADDRESS ->
      Hint("That’s this key’s own address. Enter the account it trades for.", HintTone.ALERT)
    TradingAccountCheck.WALLET -> Hint("That’s a wallet, not a trading account.", HintTone.ALERT)
  }

/**
 * Where a recovery phrase or key goes. It stays masked unless the person asks to see it, and a secret
 * pasted in is cleared from the clipboard, where any app could read it.
 */
@Composable
private fun SecretField(
  value: String,
  onValueChange: (String) -> Unit,
  recognized: Boolean,
  enabled: Boolean,
) {
  var visible by rememberSaveable { mutableStateOf(false) }
  val clipboardText = LocalClipboardManager.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val border by
    animateColorAsState(
      when {
        recognized -> FlareColors.Positive.copy(alpha = 0.6f)
        focused -> FlareColors.BorderStrong
        else -> FlareColors.BorderSubtle
      },
      label = "Secret field border",
    )
  val shape = RoundedCornerShape(18.dp)
  val style = MaterialTheme.typography.bodyLarge
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape)
        .background(FlareColors.Surface)
        .border(1.dp, border, shape)
        .padding(horizontal = 16.dp, vertical = 14.dp),
    enabled = enabled,
    singleLine = false,
    minLines = 4,
    keyboardOptions =
      KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
      ),
    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
    interactionSource = interaction,
    textStyle = style.copy(color = if (enabled) FlareColors.TextPrimary else FlareColors.TextDisabled),
    cursorBrush = SolidColor(FlareColors.Positive),
    decorationBox = { field ->
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(
            "Recovery phrase or private key",
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextTertiary,
          )
          if (value.isEmpty()) {
            Box(
              Modifier.clip(RoundedCornerShape(8.dp))
                .border(1.dp, FlareColors.BorderDefault, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Paste") {
                  val pasted = clipboardText.getText()?.text?.trim().orEmpty()
                  if (pasted.isNotEmpty()) {
                    onValueChange(pasted)
                    if (WalletCredential.detect(pasted) != CredentialFormat.UNKNOWN) {
                      scope.launch { clipboard.setClipEntry(null) }
                    }
                  }
                }
                .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
              Text("Paste", style = MaterialTheme.typography.labelMedium, color = FlareColors.Positive)
            }
          } else {
            FieldAction(if (visible) FlareIcons.Hide else FlareIcons.Show, if (visible) "Hide" else "Show", enabled) {
              visible = !visible
            }
            FieldAction(FlareIcons.Close, "Clear", enabled) { onValueChange("") }
          }
        }
        Box {
          if (value.isEmpty()) {
            Text("12 or 24 words, or a private key", style = style, color = FlareColors.TextDisabled)
          }
          field()
        }
      }
    },
  )
}

@Composable
private fun FieldAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.padding(start = 6.dp)
      .size(32.dp)
      .clip(CircleShape)
      .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = label, Modifier.size(18.dp), tint = FlareColors.TextSecondary)
  }
}

private enum class HintTone {
  NEUTRAL,
  RECOGNIZED,
  ALERT,
}

private data class Hint(val message: String, val tone: HintTone)

/** What the field holds, confirmed as it's typed or pasted, so no one has to guess before importing. */
@Composable
private fun CredentialHint(hint: Hint?) {
  AnimatedContent(
    targetState = hint,
    modifier = Modifier.padding(top = 10.dp).heightIn(min = 20.dp),
    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
    label = "Credential hint",
  ) { shown ->
    if (shown == null) {
      Spacer(Modifier.fillMaxWidth())
    } else {
      val color =
        when (shown.tone) {
          HintTone.RECOGNIZED -> FlareColors.Positive
          HintTone.ALERT -> FlareColors.Negative
          HintTone.NEUTRAL -> FlareColors.TextTertiary
        }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (shown.tone == HintTone.RECOGNIZED) {
          Icon(FlareIcons.Check, contentDescription = null, Modifier.size(16.dp), tint = color)
        }
        Text(shown.message, style = MaterialTheme.typography.bodySmall, color = color)
      }
    }
  }
}

@Composable
internal fun BackupStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  ProtectFromScreenCapture()
  val count = state.backupWords.size.takeIf { it > 0 } ?: 12
  StepLayout(
    title = "Your recovery phrase",
    subtitle =
      "These $count words are the only way to restore your account. Write them down in order and " +
        "keep them offline.",
    actions = {
      SetupActions(state, onIntent) {
        FlareButton(
          "I’ve written it down",
          { onIntent(OnboardingIntent.ReviewBackup) },
          Modifier.fillMaxWidth(),
          enabled = state.backupRevealed && state.backupWords.isNotEmpty() && !state.busy,
        )
      }
    },
  ) {
    PhraseCard(
      words = state.backupWords,
      count = count,
      revealed = state.backupRevealed,
      working = state.busy,
      onReveal = { onIntent(OnboardingIntent.RevealBackup) },
    )
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(FlareIcons.Shield, contentDescription = null, Modifier.size(18.dp), tint = FlareColors.TextTertiary)
      Text(
        "Anyone with these words controls your account. Flare will never ask for them.",
        style = MaterialTheme.typography.bodySmall,
        color = FlareColors.TextSecondary,
      )
    }
  }
}

/**
 * The recovery phrase, numbered down two columns the way it's written on paper. It stays covered until
 * the person asks to see it, then the words surface one after another, in the order they're written.
 */
@Composable
private fun PhraseCard(
  words: List<String>,
  count: Int,
  revealed: Boolean,
  working: Boolean,
  onReveal: () -> Unit,
) {
  val reduceMotion = rememberReducedMotion()
  val shown = revealed && words.isNotEmpty()
  val reveal = remember { Animatable(if (shown) 1f else 0f) }
  LaunchedEffect(shown) {
    when {
      !shown -> reveal.snapTo(0f)
      reduceMotion -> reveal.snapTo(1f)
      else -> reveal.animateTo(1f, tween(1_100, easing = LinearEasing))
    }
  }
  val shape = RoundedCornerShape(18.dp)
  Box(
    Modifier.fillMaxWidth()
      .clip(shape)
      .background(FlareColors.Surface)
      .border(1.dp, FlareColors.BorderSubtle, shape)
  ) {
    val rows = (count + 1) / 2
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
      for (column in 0..1) {
        Column(Modifier.weight(1f)) {
          for (row in 0 until rows) {
            val index = column * rows + row
            if (index < count) PhraseWord(index, words.getOrNull(index)) { wordReveal(reveal.value, index, count) }
          }
        }
      }
    }
    if (!shown) {
      Box(
        Modifier.matchParentSize()
          .background(FlareColors.Surface.copy(alpha = 0.86f))
          .clickable(enabled = !working, role = Role.Button, onClickLabel = "Reveal recovery phrase", onClick = onReveal),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Box(
            Modifier.size(52.dp).clip(CircleShape).background(FlareColors.Elevated),
            contentAlignment = Alignment.Center,
          ) {
            if (working) {
              CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
              Icon(FlareIcons.Show, contentDescription = null, Modifier.size(22.dp), tint = FlareColors.Positive)
            }
          }
          Text("Tap to reveal", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
          Text(
            "Make sure no one can see your screen.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = FlareColors.TextTertiary,
          )
        }
      }
    }
  }
}

/** How far word [index] has surfaced as the reveal runs from 0 to 1. */
private fun wordReveal(progress: Float, index: Int, count: Int): Float {
  val start = index.toFloat() / count * 0.55f
  return FastOutSlowInEasing.transform(((progress - start) / 0.45f).coerceIn(0f, 1f))
}

// Word-like lengths for the covered placeholders, so the card looks like a phrase before it's shown.
private val PlaceholderWidths = listOf(56, 72, 48, 64, 80, 52, 68, 44, 76, 60, 50, 70)

@Composable
private fun PhraseWord(index: Int, word: String?, reveal: () -> Float) {
  Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
      (index + 1).toString().padStart(2, '0'),
      Modifier.width(30.dp),
      style = MaterialTheme.typography.labelMedium,
      color = FlareColors.TextTertiary,
    )
    Box(contentAlignment = Alignment.CenterStart) {
      Box(
        Modifier.width(PlaceholderWidths[index % PlaceholderWidths.size].dp)
          .height(10.dp)
          .graphicsLayer { alpha = 1f - reveal() }
          .background(FlareColors.Elevated, RoundedCornerShape(5.dp))
      )
      if (word != null) {
        Text(
          word,
          Modifier.graphicsLayer {
            val shown = reveal()
            alpha = shown
            translationY = (1f - shown) * 6.dp.toPx()
          },
          style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
      }
    }
  }
}

@Composable
internal fun ConfirmBackupStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  ProtectFromScreenCapture()
  val confirmed =
    state.confirmationIndices.isNotEmpty() &&
      state.confirmationIndices.all { state.confirmations[it] == state.backupWords.getOrNull(it) }
  StepLayout(
    title = "Confirm your phrase",
    subtitle = "Tap the right word for each position.",
    actions = {
      SetupActions(state, onIntent) {
        FlareButton(
          "Confirm",
          { onIntent(OnboardingIntent.ConfirmBackup) },
          Modifier.fillMaxWidth(),
          enabled = confirmed,
          working = state.busy,
        )
      }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
      for (index in state.confirmationIndices) {
        WordQuestion(
          position = index + 1,
          options = state.confirmationOptions[index].orEmpty(),
          answer = state.backupWords.getOrNull(index),
          chosen = state.confirmations[index],
          enabled = !state.busy,
        ) {
          onIntent(OnboardingIntent.ChangeConfirmation(index, it))
        }
      }
    }
  }
}

/**
 * One position to confirm, with three words to choose from. The right one locks in; a wrong one shakes
 * and clears, so there's nothing to type and no typo to trip on.
 */
@Composable
private fun WordQuestion(
  position: Int,
  options: List<String>,
  answer: String?,
  chosen: String?,
  enabled: Boolean,
  onChoose: (String) -> Unit,
) {
  val haptics = LocalHapticFeedback.current
  var missed by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(missed) {
    if (missed != null) {
      delay(700)
      missed = null
    }
  }
  Column {
    Text("Word $position", style = MaterialTheme.typography.labelMedium, color = FlareColors.TextSecondary)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (option in options) {
        WordChoice(
          word = option,
          correct = chosen == option,
          missed = missed == option,
          faded = chosen != null && chosen != option,
          enabled = enabled && chosen == null,
          modifier = Modifier.weight(1f),
        ) {
          if (option == answer) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onChoose(option)
          } else {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            missed = option
          }
        }
      }
    }
  }
}

@Composable
private fun WordChoice(
  word: String,
  correct: Boolean,
  missed: Boolean,
  faded: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val reduceMotion = rememberReducedMotion()
  val shake = remember { Animatable(0f) }
  LaunchedEffect(missed) {
    if (missed && !reduceMotion) {
      shake.animateTo(
        0f,
        keyframes {
          durationMillis = 360
          -8f at 50
          8f at 110
          -6f at 170
          5f at 230
          -2f at 290
        },
      )
    }
  }
  val container by
    animateColorAsState(
      when {
        correct -> FlareColors.PositiveMuted
        missed -> FlareColors.NegativeMuted
        else -> FlareColors.Surface
      },
      label = "Word container",
    )
  val border by
    animateColorAsState(
      when {
        correct -> FlareColors.Positive
        missed -> FlareColors.Negative
        else -> FlareColors.BorderDefault
      },
      label = "Word border",
    )
  val content by
    animateColorAsState(
      when {
        correct -> FlareColors.Positive
        missed -> FlareColors.Negative
        else -> FlareColors.TextPrimary
      },
      label = "Word text",
    )
  val shape = RoundedCornerShape(14.dp)
  Box(
    modifier
      .height(52.dp)
      .graphicsLayer {
        translationX = shake.value.dp.toPx()
        alpha = if (faded) 0.4f else 1f
      }
      .clip(shape)
      .background(container)
      .border(1.dp, border, shape)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      if (correct) Icon(FlareIcons.Check, contentDescription = null, Modifier.size(16.dp), tint = content)
      Text(word, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
  }
}

@Composable
internal fun SubaccountStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  val ownerMode = state.profile.ownerAddress != null
  when {
    !ownerMode ->
      StepLayout(
        title = "Your trading account",
        subtitle = "Enter the trading account this key can trade for.",
        actions = {
          SetupActions(state, onIntent) {
            FlareButton(
              "Continue",
              { onIntent(OnboardingIntent.ContinueSubaccount) },
              Modifier.fillMaxWidth(),
              enabled = state.input.isNotBlank(),
              working = state.busy,
            )
          }
        },
      ) {
        FlareAddressField(
          state.input,
          { onIntent(OnboardingIntent.ChangeInput(it)) },
          Modifier.fillMaxWidth(),
          label = "Trading account",
          enabled = !state.busy,
        )
      }
    state.subaccounts.isNotEmpty() ->
      StepLayout(
        title = "Choose an account",
        subtitle = "Pick the trading account to use on this phone.",
        actions = {
          SetupActions(state, onIntent) {
            FlareButton(
              "Continue",
              { onIntent(OnboardingIntent.ContinueSubaccount) },
              Modifier.fillMaxWidth(),
              enabled = state.selectedSubaccount != null,
              working = state.busy,
            )
          }
        },
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          state.subaccounts.forEachIndexed { position, subaccount ->
            AccountOption(
              subaccount,
              position,
              selected = subaccount.address == state.selectedSubaccount,
              enabled = !state.busy,
            ) {
              onIntent(OnboardingIntent.SelectSubaccount(subaccount.address))
            }
          }
        }
      }
    state.subaccountsLoaded ->
      StepLayout(
        title = "Create your trading account",
        subtitle =
          "This wallet doesn’t have a Decibel trading account yet. Flare will open one and enable " +
            "trading on this phone. You’ll confirm twice.",
        actions = {
          SetupActions(state, onIntent) {
            FlareButton(
              if (state.error != null) "Try again" else "Create account",
              { onIntent(OnboardingIntent.CreateSubaccount) },
              Modifier.fillMaxWidth(),
              working = state.busy,
            )
            FlareButton(
              "Check again",
              { onIntent(OnboardingIntent.DiscoverSubaccounts) },
              Modifier.fillMaxWidth(),
              enabled = !state.busy,
              style = FlareButtonStyle.OUTLINE,
            )
          }
        },
      ) {
        NetworkFeeLine("Network fees", covered = state.setupFee == null)
      }
    state.busy ->
      StepLayout(
        title = "Finding your account",
        subtitle = "Looking for trading accounts that belong to this wallet.",
        actions = {},
      ) {
        val shimmer = rememberFlareShimmer()
        Column(Modifier.shimmer(shimmer), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          repeat(2) {
            FlareSkeletonBox(
              Modifier.fillMaxWidth().height(72.dp),
              RoundedCornerShape(16.dp),
              FlareColors.Surface,
            )
          }
        }
      }
    else ->
      StepLayout(
        title = "Find your account",
        subtitle = "Flare couldn’t look up your trading accounts. Check your connection and try again.",
        actions = {
          SetupActions(state, onIntent) {
            FlareButton(
              "Try again",
              { onIntent(OnboardingIntent.DiscoverSubaccounts) },
              Modifier.fillMaxWidth(),
            )
          }
        },
      )
  }
}

@Composable
private fun AccountOption(
  subaccount: Subaccount,
  position: Int,
  selected: Boolean,
  enabled: Boolean,
  onSelect: () -> Unit,
) {
  val shape = RoundedCornerShape(16.dp)
  val border by
    animateColorAsState(if (selected) FlareColors.Positive else FlareColors.BorderSubtle, label = "Account border")
  Row(
    Modifier.fillMaxWidth()
      .clip(shape)
      .background(FlareColors.Surface)
      .border(1.dp, border, shape)
      .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        subaccount.name.ifBlank { if (subaccount.isPrimary) "Primary account" else "Account ${position + 1}" },
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        shortAddress(subaccount.address),
        Modifier.padding(top = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = FlareColors.TextSecondary,
      )
    }
    val ring by
      animateColorAsState(if (selected) FlareColors.Positive else FlareColors.BorderStrong, label = "Account ring")
    Box(Modifier.size(22.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
      if (selected) Box(Modifier.size(10.dp).background(FlareColors.Positive, CircleShape))
    }
  }
}

/**
 * A new wallet's last step: Flare opens its trading account and enables trading, all sponsored. What
 * has gone through stays checked off, so a retry after a failure or an interruption only does what's
 * left.
 */
@Composable
internal fun OpenAccountStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  val stalled = !state.busy && (state.error != null || state.setupFee != null)
  StepLayout(
    title = "Open your trading account",
    subtitle =
      "Flare opens a Decibel trading account for your new wallet and adds a trading key to this " +
        "phone, so your orders sign instantly. The key can trade, but it can’t move funds out.",
    actions = {
      SetupActions(state, onIntent) {
        FlareButton(
          when {
            stalled -> "Try again"
            state.accountOpened -> "Enable trading"
            else -> "Open account"
          },
          { onIntent(OnboardingIntent.OpenAccount) },
          Modifier.fillMaxWidth(),
          working = state.busy,
        )
      }
    },
  ) {
    Column(
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .background(FlareColors.Surface)
        .padding(horizontal = 16.dp)
    ) {
      SetupTask(
        "Trading account",
        when {
          state.accountOpened -> TaskStatus.DONE
          state.busy -> TaskStatus.WORKING
          stalled -> TaskStatus.STALLED
          else -> TaskStatus.WAITING
        },
        waiting = "A Decibel account for your new wallet",
        working = "Opening…",
        done = "Open",
        stalled = "Not opened yet",
      )
      HorizontalDivider(color = FlareColors.BorderSubtle)
      SetupTask(
        "Trading on this phone",
        when {
          !state.accountOpened -> TaskStatus.WAITING
          state.busy -> TaskStatus.WORKING
          stalled -> TaskStatus.STALLED
          else -> TaskStatus.WAITING
        },
        waiting = if (state.accountOpened) "Ready to enable" else "Once the account is open",
        working = "Enabling…",
        done = "Enabled",
        stalled = "Not enabled yet",
      )
    }
    Box(
      Modifier.fillMaxWidth()
        .padding(top = 12.dp)
        .clip(RoundedCornerShape(18.dp))
        .background(FlareColors.Surface)
        .padding(horizontal = 16.dp)
    ) {
      SwitchRow(
        "Support Flare",
        "Adds 0.05% to your trades to fund Flare’s development. Change it anytime in Settings.",
        state.builderOptIn,
        { if (!state.busy) onIntent(OnboardingIntent.SetBuilderOptIn(it)) },
      )
    }
    NetworkFeeLine("Network fees", covered = state.setupFee == null, Modifier.padding(top = 16.dp))
  }
}

private enum class TaskStatus {
  WAITING,
  WORKING,
  DONE,
  STALLED,
}

@Composable
private fun SetupTask(
  title: String,
  status: TaskStatus,
  waiting: String,
  working: String,
  done: String,
  stalled: String,
) {
  Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
      when (status) {
        TaskStatus.WAITING -> Box(Modifier.size(18.dp).border(1.5.dp, FlareColors.BorderStrong, CircleShape))
        TaskStatus.WORKING ->
          CircularProgressIndicator(Modifier.size(18.dp), color = FlareColors.Positive, strokeWidth = 2.dp)
        TaskStatus.DONE ->
          Icon(FlareIcons.CheckCircle, contentDescription = null, Modifier.size(22.dp), tint = FlareColors.Positive)
        TaskStatus.STALLED -> Box(Modifier.size(18.dp).border(1.5.dp, FlareColors.Warning, CircleShape))
      }
    }
    Column(Modifier.weight(1f).padding(start = 14.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        when (status) {
          TaskStatus.WAITING -> waiting
          TaskStatus.WORKING -> working
          TaskStatus.DONE -> done
          TaskStatus.STALLED -> stalled
        },
        Modifier.padding(top = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color =
          when (status) {
            TaskStatus.DONE -> FlareColors.Positive
            TaskStatus.STALLED -> FlareColors.Warning
            else -> FlareColors.TextSecondary
          },
      )
    }
  }
}

@Composable
internal fun EnableTradingStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  val account = state.selectedSubaccount?.let(::shortAddress)
  StepLayout(
    title = "Enable trading",
    subtitle =
      "Flare adds a trading key to this phone so your orders sign instantly. It can trade" +
        (account?.let { " for $it" } ?: "") +
        ", but it can’t move funds out.",
    actions = {
      SetupActions(state, onIntent) {
        FlareButton(
          if (state.error != null) "Try again" else "Enable trading",
          { onIntent(OnboardingIntent.EnableTrading) },
          Modifier.fillMaxWidth(),
          working = state.busy,
        )
      }
    },
  ) {
    Box(
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .background(FlareColors.Surface)
        .padding(horizontal = 16.dp)
    ) {
      // Held as chosen while trading is being enabled, rather than greyed out to look switched off.
      SwitchRow(
        "Support Flare",
        "Adds 0.05% to your trades to fund Flare’s development. Change it anytime in Settings.",
        state.builderOptIn,
        { if (!state.busy) onIntent(OnboardingIntent.SetBuilderOptIn(it)) },
      )
    }
    NetworkFeeLine("Network fee", covered = state.setupFee == null, Modifier.padding(top = 16.dp))
  }
}

/**
 * Says up front who pays for an on-chain step, before the person is asked to approve anything, and
 * stops claiming Flare covers it once Flare couldn't.
 */
@Composable
private fun NetworkFeeLine(label: String, covered: Boolean, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = FlareColors.TextSecondary)
    if (covered) {
      Icon(FlareIcons.CheckCircle, contentDescription = null, Modifier.size(16.dp), tint = FlareColors.Positive)
      Text("Covered by Flare", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
    } else {
      Text("Not covered right now", style = MaterialTheme.typography.bodyMedium, color = FlareColors.Warning)
    }
  }
}
