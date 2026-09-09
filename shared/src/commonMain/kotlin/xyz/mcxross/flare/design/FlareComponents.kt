package xyz.mcxross.flare.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class FlareButtonStyle {
  PRIMARY,
  OUTLINE,
  BUY,
  SELL,
}

@Composable
fun FlareSearchField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  leadingIcon: ImageVector,
  modifier: Modifier = Modifier,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.heightIn(min = 52.dp),
    placeholder = { Text(placeholder) },
    leadingIcon = { Icon(leadingIcon, contentDescription = null) },
    trailingIcon = {
      if (value.isNotEmpty())
        IconButton(onClick = { onValueChange("") }) {
          Icon(Icons.Outlined.Close, contentDescription = "Clear search")
        }
    },
    singleLine = true,
    shape = MaterialTheme.shapes.small,
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedContainerColor = FlareColors.Elevated,
        unfocusedContainerColor = FlareColors.Elevated,
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
      ),
  )
}

@Composable
fun PriceDisplay(
  price: String,
  modifier: Modifier = Modifier,
  semanticLabel: String = "Price $price",
) {
  Text(
    price,
    modifier = modifier.semantics { contentDescription = semanticLabel },
    style = MaterialTheme.typography.displaySmall,
  )
}

@Composable
fun FlareButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  style: FlareButtonStyle = FlareButtonStyle.PRIMARY,
) {
  val colors = LocalFlareTradingColors.current
  val shape = CircleShape
  if (style == FlareButtonStyle.PRIMARY) {
    Button(
      onClick = onClick,
      modifier = modifier.heightIn(min = 52.dp),
      enabled = enabled,
      shape = shape,
      colors =
        ButtonDefaults.buttonColors(
          containerColor = colors.positive,
          contentColor = Color.Black,
          disabledContainerColor = FlareColors.Surface,
          disabledContentColor = FlareColors.TextDisabled,
        ),
    ) {
      Text(text, style = MaterialTheme.typography.labelLarge)
    }
  } else {
    val contentColor =
      when (style) {
        FlareButtonStyle.BUY -> colors.positive
        FlareButtonStyle.SELL -> colors.negative
        else -> MaterialTheme.colorScheme.onSurface
      }
    OutlinedButton(
      onClick = onClick,
      modifier = modifier.heightIn(min = 52.dp),
      enabled = enabled,
      shape = shape,
      border = BorderStroke(1.dp, if (enabled) colors.borderDefault else colors.borderSubtle),
      colors =
        ButtonDefaults.outlinedButtonColors(
          contentColor = contentColor,
          disabledContentColor = FlareColors.TextDisabled,
        ),
    ) {
      Text(text, style = MaterialTheme.typography.labelLarge)
    }
  }
}

@Composable
fun FlareChip(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  semanticColor: Color? = null,
  enabled: Boolean = true,
) {
  val shape = CircleShape
  val background by animateColorAsState(if (selected) FlareColors.Elevated else Color.Transparent)
  val foreground by
    animateColorAsState(
      if (!enabled) FlareColors.TextDisabled
      else if (selected) semanticColor ?: FlareColors.TextPrimary else FlareColors.TextSecondary
    )
  Box(
    modifier =
      modifier
        .heightIn(min = 44.dp)
        .clip(shape)
        .background(background)
        .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = foreground,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.Medium,
    )
  }
}

@Composable
fun IndicatorChip(
  text: String,
  selected: Boolean,
  color: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  FlareChip(text, selected, onClick, modifier, semanticColor = color)
}

@Composable
fun <T> TimeRangeSelector(
  values: List<T>,
  selected: T,
  label: (T) -> String,
  onSelected: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    values.forEach { value ->
      val active = value == selected
      Box(
        Modifier.heightIn(min = 48.dp).weight(1f).clip(MaterialTheme.shapes.small).selectable(
          active,
          role = Role.Tab,
        ) {
          onSelected(value)
        },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          label(value),
          Modifier.background(
              if (active) FlareColors.Positive else Color.Transparent,
              MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
          color = if (active) FlareColors.Canvas else FlareColors.TextSecondary,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
  }
}

@Composable
fun CompactActionButton(
  text: String,
  positive: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val colors = LocalFlareTradingColors.current
  val foreground = if (positive) colors.positive else colors.negative
  val background = if (positive) colors.positiveMuted else colors.negativeMuted
  Box(
    modifier =
      modifier
        .heightIn(min = 44.dp)
        .background(if (enabled) background else FlareColors.Surface, CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      color = if (enabled) foreground else FlareColors.TextDisabled,
      style = MaterialTheme.typography.labelMedium,
    )
  }
}

@Composable
fun QuantitySelector(
  quantity: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  OutlinedButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = 52.dp),
    enabled = enabled,
    shape = CircleShape,
    border = BorderStroke(1.dp, FlareColors.BorderDefault),
  ) {
    Text(quantity, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
fun DeltaLabel(
  value: String,
  positive: Boolean,
  modifier: Modifier = Modifier,
) {
  val colors = LocalFlareTradingColors.current
  Text(
    text = if (positive) "▲ $value" else "▼ $value",
    color = if (positive) colors.positive else colors.negative,
    style = MaterialTheme.typography.labelMedium,
    modifier =
      modifier.semantics {
        contentDescription = if (positive) "Up $value" else "Down $value"
      },
  )
}

@Composable
fun FlareTopBar(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  action: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp).heightIn(min = 52.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.headlineLarge)
      subtitle?.let {
        Text(
          it,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    action?.invoke()
  }
}

@Composable
fun AssetHeader(
  symbol: String,
  name: String,
  price: String,
  delta: String,
  positive: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(assetDisplayName(symbol, name), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    PriceDisplay(price)
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      DeltaLabel(delta, positive)
      Text(
        "Past 24 hours",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.labelMedium,
      )
    }
  }
}

@Composable
fun MarketListRow(
  symbol: String,
  name: String,
  price: String,
  delta: String,
  positive: Boolean,
  favorite: Boolean,
  onClick: () -> Unit,
  onFavorite: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = LocalFlareTradingColors.current
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier.size(40.dp).background(FlareColors.Elevated, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Text(assetMonogram(symbol), style = MaterialTheme.typography.titleLarge)
    }
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(symbol, style = MaterialTheme.typography.labelLarge)
      Text(
        assetDisplayName(symbol, name),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(price, style = MaterialTheme.typography.labelLarge)
      DeltaLabel(delta, positive)
    }
    Spacer(Modifier.width(8.dp))
    IconButton(onClick = onFavorite) {
      Icon(
        if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
        contentDescription =
          if (favorite) "Remove $symbol from watchlist" else "Add $symbol to watchlist",
        tint = if (favorite) colors.positive else colors.textTertiary,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
fun EmptyState(
  title: String,
  message: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: () -> Unit = {},
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(
      message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    actionLabel?.let {
      Spacer(Modifier.height(4.dp))
      FlareButton(text = it, onClick = onAction, style = FlareButtonStyle.OUTLINE)
    }
  }
}

data class FlareNavigationItem(
  val label: String,
  val icon: ImageVector,
)

@Composable
fun FlareBottomNavigation(
  items: List<FlareNavigationItem>,
  selectedIndex: Int,
  onSelected: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().height(72.dp).background(FlareColors.Canvas),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    items.forEachIndexed { index, item ->
      val selected = index == selectedIndex
      val color = if (selected) FlareColors.TextPrimary else FlareColors.TextTertiary
      Column(
        modifier =
          Modifier.weight(1f).heightIn(min = 52.dp).selectable(
            selected = selected,
            role = Role.Tab,
          ) {
            onSelected(index)
          },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(5.dp))
        Text(item.label, color = color, style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}

@Composable
fun BottomTradeDock(
  quantity: String,
  enabled: Boolean,
  onBuy: () -> Unit,
  onSell: () -> Unit,
  onQuantity: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .border(
          1.dp,
          FlareColors.BorderSubtle,
          RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        )
        .background(FlareColors.Canvas)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FlareButton("Buy", onBuy, Modifier.weight(1f), enabled, FlareButtonStyle.BUY)
    QuantitySelector(quantity, onQuantity, Modifier.weight(1f), enabled)
    FlareButton("Sell", onSell, Modifier.weight(1f), enabled, FlareButtonStyle.SELL)
  }
}
