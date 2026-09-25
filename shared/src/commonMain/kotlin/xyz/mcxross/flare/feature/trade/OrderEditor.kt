package xyz.mcxross.flare.feature.trade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.valentinilk.shimmer.shimmer
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.ExitPriceFields
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSkeletonBox
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.rememberFlareShimmer

@Composable
internal fun OrderEditor(
  state: TradeUiState,
  side: OrderSide,
  onSideChange: (OrderSide) -> Unit,
  onIntent: (TradeIntent) -> Unit,
  exitsOpen: Boolean,
  onExitsOpenChange: (Boolean) -> Unit,
  minHeight: Dp,
) {
  val quote = state.quote ?: return
  val isSpot = quote.market.assetType == AssetType.SPOT
  val estimate = state.orderEstimate(side)
  val inputError = state.orderInputError(side)
  val hasExits = state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank()
  val showNotices =
    (state.sizeInput.isNotBlank() && inputError != null) || state.orderError != null || !state.tradingEnabled
  SpacedGroups(minHeight) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OrderSide.entries.forEach { value ->
          FlareChip(
            if (isSpot) {
              if (value == OrderSide.BUY) "Buy" else "Sell"
            } else {
              if (value == OrderSide.BUY) "Buy / Long" else "Sell / Short"
            },
            side == value,
            { onSideChange(value) },
            Modifier.weight(1f),
            semanticColor =
              if (value == OrderSide.BUY) FlareColors.Positive else FlareColors.Negative,
            enabled = !state.orderBusy,
          )
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val orderTypes =
          if (isSpot) listOf(OrderType.MARKET, OrderType.LIMIT)
          else listOf(OrderType.MARKET, OrderType.LIMIT, OrderType.TWAP)
        orderTypes.forEach { type ->
          FlareChip(
            text = if (type == OrderType.TWAP) "TWAP" else type.name.lowercase().replaceFirstChar(Char::uppercase),
            selected = state.orderType == type,
            onClick = { onIntent(TradeIntent.SetOrderType(type)) },
            modifier = Modifier.weight(1f),
            enabled = !state.orderBusy,
          )
        }
      }
    }
    Column {
      OrderAmountField(
        "Size",
        quote.market.symbol,
        state.sizeInput,
        { onIntent(TradeIntent.SetSize(it)) },
        Modifier.fillMaxWidth(),
        enabled = !state.orderBusy,
        valueHint = "≈ " + when {
          state.sizeInput.isBlank() -> formatBalance(0.0)
          else -> estimate?.value?.let(::formatBalance) ?: "—"
        },
      )
      Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          "Min ${quote.market.minSize.toDecimalString(quote.market.sizeDecimals)} ${quote.market.symbol}",
          Modifier.weight(1f, fill = false).padding(end = 12.dp),
          style = MaterialTheme.typography.labelSmall,
          color = FlareColors.TextSecondary,
          maxLines = 1,
        )
        state.availableDisplay(side)?.let { available ->
          Text(
            buildAnnotatedString {
              append("Available ")
              withStyle(SpanStyle(color = FlareColors.TextPrimary)) { append(available) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextSecondary,
            maxLines = 1,
          )
        }
      }
      if (state.orderType == OrderType.LIMIT) {
        OrderAmountField(
          "Limit price",
          "USDC",
          state.limitPriceInput,
          { onIntent(TradeIntent.SetLimitPrice(it)) },
          Modifier.fillMaxWidth().padding(top = 12.dp),
          enabled = !state.orderBusy,
        )
      }
      if (state.orderType == OrderType.TWAP) {
        Row(
          Modifier.fillMaxWidth().padding(top = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OrderAmountField(
            "Duration",
            "min",
            state.twapDurationMinutesInput,
            { onIntent(TradeIntent.SetTwapDurationMinutes(it)) },
            Modifier.weight(1f),
            enabled = !state.orderBusy,
          )
          OrderAmountField(
            "Interval",
            "min",
            state.twapFrequencyMinutesInput,
            { onIntent(TradeIntent.SetTwapFrequencyMinutes(it)) },
            Modifier.weight(1f),
            enabled = !state.orderBusy,
          )
        }
        Text(
          "TWAP divides the order into regular slices across the duration.",
          style = MaterialTheme.typography.bodySmall,
          color = FlareColors.TextSecondary,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
    }
    if (!isSpot) {
      LeverageControl(state, estimate?.margin) { onIntent(TradeIntent.SetLeverage(it)) }
    }
    if (!isSpot && state.orderType != OrderType.TWAP) {
      Column {
        ExitsDisclosure(exitsOpen, hasExits, !state.orderBusy) { onExitsOpenChange(!exitsOpen) }
        AnimatedVisibility(
          exitsOpen,
          enter = expandVertically() + fadeIn(),
          exit = shrinkVertically() + fadeOut(),
        ) {
          ExitPriceFields(
            state.takeProfitInput, state.stopLossInput,
            { onIntent(TradeIntent.SetTakeProfit(it)) },
            { onIntent(TradeIntent.SetStopLoss(it)) },
            Modifier.fillMaxWidth().padding(top = 12.dp),
            enabled = !state.orderBusy,
          )
        }
      }
    }
    if (showNotices) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.sizeInput.isNotBlank() && inputError != null) {
          ActionNotice(inputError, tone = NoticeTone.ALERT)
        }
        state.orderError?.let { ActionNotice(it, tone = NoticeTone.ALERT) }
        if (!state.tradingEnabled) {
          OrderConnectionNotice()
        }
      }
    }
  }
}

private val MinGroupGap = 16.dp
private val MaxGroupGap = 28.dp

/**
 * Stacks the form's groups with gaps that open up to [MaxGroupGap] when the viewport has room
 * and close to [MinGroupGap] as the form grows. A gap also leads the first group.
 */
@Composable
internal fun SpacedGroups(minHeight: Dp, content: @Composable () -> Unit) {
  Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
    val placeables = measurables.map {
      it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    }
    val gaps = placeables.size
    val used = placeables.sumOf { it.height }
    val gap =
      if (gaps == 0) 0
      else ((minHeight.roundToPx() - used) / gaps).coerceIn(MinGroupGap.roundToPx(), MaxGroupGap.roundToPx())
    layout(constraints.maxWidth, used + gap * gaps) {
      var y = gap
      placeables.forEach {
        it.placeRelative(0, y)
        y += it.height + gap
      }
    }
  }
}

@Composable
internal fun OrderReviewStatus(
  state: TradeUiState,
  side: OrderSide,
  onIntent: (TradeIntent) -> Unit,
  minHeight: Dp,
) {
  val inputError = state.orderInputError(side)
  val selfPayEstimate = (state.transaction as? TransactionState.Failed)?.selfPayEstimateOctas
  val topUp = state.suggestedTopUpOctas?.takeIf { state.apiWalletNeedsTopUp && selfPayEstimate != null }
  SpacedGroups(minHeight) {
    if (inputError != null || state.orderError != null || selfPayEstimate != null) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        inputError?.let { ActionNotice(it, tone = NoticeTone.ALERT) }
        state.orderError?.let { ActionNotice(it, tone = NoticeTone.ALERT) }
        selfPayEstimate?.let { estimate ->
          ActionNotice(
            "Flare can’t cover the network fee right now. Confirming pays about " +
              "${estimate.toDecimalString(8)} APT from your wallet.",
          )
        }
        topUp?.let { amount ->
          ActionRow(
            "Send APT from your wallet",
            subtitle = "This device needs ${amount.toDecimalString(8)} APT to pay the fee itself.",
            enabled = !state.orderBusy,
            onClick = { onIntent(TradeIntent.TopUpApiWallet) },
          )
        }
      }
    }
    ReviewHeading(state, side)
    ReviewDetails(state, side)
    ReviewExits(state, side)
    ReviewFootnote(state, side)
    if (!state.tradingEnabled) OrderConnectionNotice()
  }
}

/** Optional exits stay one quiet row until the trader asks for them. */
@Composable
private fun ExitsDisclosure(expanded: Boolean, added: Boolean, enabled: Boolean, onToggle: () -> Unit) {
  val rotation by animateFloatAsState(if (expanded) 45f else 0f, label = "exitsDisclosure")
  Column(Modifier.fillMaxWidth()) {
    HorizontalDivider(color = FlareColors.BorderSubtle)
    Row(
      Modifier.fillMaxWidth()
        .heightIn(min = 56.dp)
        .clickable(
          enabled = enabled,
          role = Role.Button,
          onClickLabel = if (expanded) "Hide take profit and stop loss" else "Show take profit and stop loss",
          onClick = onToggle,
        )
        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        Modifier.size(28.dp).background(FlareColors.Elevated, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Outlined.Add,
          contentDescription = null,
          modifier = Modifier.size(16.dp).rotate(rotation),
          tint = if (enabled) FlareColors.Positive else FlareColors.TextDisabled,
        )
      }
      Text(
        "Take profit and stop loss",
        Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) FlareColors.TextPrimary else FlareColors.TextDisabled,
      )
      Text(
        if (added && !expanded) "Added" else "Optional",
        style = MaterialTheme.typography.labelSmall,
        color = if (added && !expanded) FlareColors.TextPrimary else FlareColors.TextTertiary,
      )
    }
    HorizontalDivider(color = FlareColors.BorderSubtle)
  }
}

@Composable
private fun OrderConnectionNotice() {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FlareSkeletonBox(Modifier.width(40.dp).height(3.dp).shimmer(rememberFlareShimmer()))
    Text("Reconnecting to live prices. Trading resumes automatically.",
      color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
  }
}
