package xyz.mcxross.flare.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import com.valentinilk.shimmer.shimmer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent

enum class FlareButtonStyle {
  PRIMARY,
  OUTLINE,
  BUY,
  SELL,
}

/**
 * A uniform, elevated input field matching Flare's dark-mode fintech design system.
 * Eliminates generic Android outline styling and floating notch cuts.
 */
@Composable
fun FlareTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  label: String? = null,
  placeholder: String? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  supportingText: String? = null,
  isError: Boolean = false,
  enabled: Boolean = true,
  readOnly: Boolean = false,
  singleLine: Boolean = true,
  minLines: Int = 1,
  maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
  val focused by interactionSource.collectIsFocusedAsState()
  val borderColor by animateColorAsState(
    when {
      !enabled -> FlareColors.BorderSubtle.copy(alpha = 0.5f)
      isError -> FlareColors.Negative
      focused -> FlareColors.Positive
      else -> FlareColors.BorderSubtle
    }
  )
  val containerColor = if (enabled) FlareColors.Elevated else FlareColors.Surface

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (label != null) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = when {
          !enabled -> FlareColors.TextDisabled
          isError -> FlareColors.Negative
          focused -> FlareColors.TextPrimary
          else -> FlareColors.TextSecondary
        },
      )
    }
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(containerColor)
        .border(1.dp, borderColor, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = if (singleLine) 14.dp else 12.dp),
      enabled = enabled,
      readOnly = readOnly,
      singleLine = singleLine,
      minLines = minLines,
      maxLines = maxLines,
      keyboardOptions = keyboardOptions,
      keyboardActions = keyboardActions,
      visualTransformation = visualTransformation,
      interactionSource = interactionSource,
      textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = if (enabled) FlareColors.TextPrimary else FlareColors.TextDisabled,
      ),
      cursorBrush = SolidColor(if (isError) FlareColors.Negative else FlareColors.Positive),
      decorationBox = { innerTextField ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
          if (leadingIcon != null) {
            Box(modifier = Modifier.padding(end = 10.dp), contentAlignment = Alignment.Center) {
              leadingIcon()
            }
          }
          Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder != null) {
              Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = FlareColors.TextTertiary,
              )
            }
            innerTextField()
          }
          if (trailingIcon != null) {
            Box(modifier = Modifier.padding(start = 10.dp), contentAlignment = Alignment.Center) {
              trailingIcon()
            }
          }
        }
      },
    )
    if (supportingText != null) {
      Text(
        text = supportingText,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) FlareColors.Negative else FlareColors.TextSecondary,
      )
    }
  }
}

/**
 * A dedicated financial amount input matching Flare's trading card aesthetic.
 * Integrates label, available balance, numeric field, unit badge, and optional MAX button.
 */
@Composable
fun FlareAmountField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  label: String = "Amount",
  unit: String? = null,
  availableText: String? = null,
  onMaxClick: (() -> Unit)? = null,
  placeholder: String = "0.00",
  supportingText: String? = null,
  isError: Boolean = false,
  enabled: Boolean = true,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
  val focused by interactionSource.collectIsFocusedAsState()
  val borderColor by animateColorAsState(
    when {
      !enabled -> FlareColors.BorderSubtle.copy(alpha = 0.5f)
      isError -> FlareColors.Negative
      focused -> FlareColors.Positive
      else -> FlareColors.BorderSubtle
    }
  )
  val containerColor = if (enabled) FlareColors.Elevated else FlareColors.Surface

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(containerColor)
        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
        .padding(16.dp),
      enabled = enabled,
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      interactionSource = interactionSource,
      textStyle = MaterialTheme.typography.titleLarge.copy(
        color = if (enabled) FlareColors.TextPrimary else FlareColors.TextDisabled,
      ),
      cursorBrush = SolidColor(if (isError) FlareColors.Negative else FlareColors.Positive),
      decorationBox = { innerTextField ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          // Top metadata row
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = label,
              style = MaterialTheme.typography.labelSmall,
              color = when {
                !enabled -> FlareColors.TextDisabled
                isError -> FlareColors.Negative
                focused -> FlareColors.TextPrimary
                else -> FlareColors.TextSecondary
              },
            )
            if (availableText != null) {
              Text(
                text = availableText,
                style = MaterialTheme.typography.labelSmall,
                color = FlareColors.Positive,
                modifier =
                  if (onMaxClick != null) {
                    Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onMaxClick)
                  } else {
                    Modifier
                  },
              )
            }
          }
          // Main input row
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(modifier = Modifier.weight(1f)) {
              if (value.isBlank()) {
                Text(
                  text = placeholder,
                  style = MaterialTheme.typography.titleLarge,
                  color = FlareColors.TextTertiary,
                )
              }
              innerTextField()
            }
            if (onMaxClick != null || unit != null) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp),
              ) {
                if (onMaxClick != null) {
                  Box(
                    modifier = Modifier
                      .clip(RoundedCornerShape(8.dp))
                      .background(FlareColors.Surface)
                      .border(1.dp, FlareColors.BorderDefault, RoundedCornerShape(8.dp))
                      .clickable(enabled = enabled, onClick = onMaxClick)
                      .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                  ) {
                    Text(
                      text = "MAX",
                      style = MaterialTheme.typography.labelMedium,
                      color = FlareColors.Positive,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                }
                if (unit != null) {
                  Text(
                    text = unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = FlareColors.TextSecondary,
                    fontWeight = FontWeight.Medium,
                  )
                }
              }
            }
          }
        }
      },
    )
    if (supportingText != null) {
      Text(
        text = supportingText,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) FlareColors.Negative else FlareColors.TextSecondary,
      )
    }
  }
}

@Composable
fun FlareSearchField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  leadingIcon: ImageVector,
  modifier: Modifier = Modifier,
) {
  FlareTextField(
    value = value,
    onValueChange = onValueChange,
    placeholder = placeholder,
    leadingIcon = {
      Icon(leadingIcon, contentDescription = null, tint = FlareColors.TextSecondary, modifier = Modifier.size(20.dp))
    },
    trailingIcon = {
      if (value.isNotEmpty()) {
        IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(24.dp)) {
          Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = FlareColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
      }
    },
    singleLine = true,
    modifier = modifier,
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

/**
 * [working] turns the button itself into the progress indicator, so an action in flight never needs
 * a separate status line beside it.
 */
@Composable
fun FlareButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  style: FlareButtonStyle = FlareButtonStyle.PRIMARY,
  working: Boolean = false,
) {
  val colors = LocalFlareTradingColors.current
  val shape = CircleShape
  if (style == FlareButtonStyle.PRIMARY) {
    Button(
      onClick = onClick,
      modifier = modifier.heightIn(min = 52.dp).workingShimmer(working),
      enabled = enabled && !working,
      shape = shape,
      colors =
        ButtonDefaults.buttonColors(
          containerColor = colors.positive,
          contentColor = Color.Black,
          // A pending action keeps its identity; only interaction is withheld.
          disabledContainerColor = if (working) colors.positive else FlareColors.Surface,
          disabledContentColor = if (working) Color.Black else FlareColors.TextDisabled,
        ),
    ) {
      ButtonContent(text)
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
      modifier = modifier.heightIn(min = 52.dp).workingShimmer(working),
      enabled = enabled && !working,
      shape = shape,
      border = BorderStroke(1.dp, if (enabled) colors.borderDefault else colors.borderSubtle),
      colors =
        ButtonDefaults.outlinedButtonColors(
          contentColor = contentColor,
          disabledContentColor = if (working) contentColor else FlareColors.TextDisabled,
        ),
    ) {
      ButtonContent(text)
    }
  }
}

@Composable
private fun ButtonContent(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun Modifier.workingShimmer(working: Boolean): Modifier =
  if (working) shimmer(rememberFlareShimmer()) else this

/** Tone of an inline notice; the app speaks in outcomes, never in transaction states. */
enum class NoticeTone {
  PROGRESS,
  INFO,
  ALERT,
}

/** One quiet line about what the app is doing, or why an action did not happen. */
@Composable
fun ActionNotice(
  message: String,
  modifier: Modifier = Modifier,
  tone: NoticeTone = NoticeTone.INFO,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(FlareColors.Elevated, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (tone == NoticeTone.PROGRESS) {
      CircularProgressIndicator(
        modifier = Modifier.size(14.dp),
        color = FlareColors.TextSecondary,
        strokeWidth = 2.dp,
      )
    }
    Text(
      message,
      style = MaterialTheme.typography.bodySmall,
      color =
        if (tone == NoticeTone.ALERT) MaterialTheme.colorScheme.error
        else FlareColors.TextSecondary,
    )
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
        .heightIn(min = 48.dp)
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
fun <T> FlareSegmentedControl(
  options: List<T>,
  selectedOption: T,
  onOptionSelected: (T) -> Unit,
  label: (T) -> String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(FlareColors.Elevated)
        .border(BorderStroke(1.dp, FlareColors.BorderSubtle), RoundedCornerShape(12.dp))
        .padding(3.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    options.forEach { option ->
      val selected = option == selectedOption
      val background by animateColorAsState(if (selected) FlareColors.Canvas else Color.Transparent)
      val textColor by
        animateColorAsState(if (selected) FlareColors.TextPrimary else FlareColors.TextSecondary)
      Box(
        modifier =
          Modifier.weight(1f)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab) { onOptionSelected(option) },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label(option),
          color = textColor,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
      }
    }
  }
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
              if (active) FlareColors.Elevated else Color.Transparent,
              MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
          color = if (active) FlareColors.TextPrimary else FlareColors.TextSecondary,
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
        .heightIn(min = 48.dp)
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
fun InstrumentBadge(
  text: String,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(4.dp))
        .background(FlareColors.Elevated)
        .border(BorderStroke(1.dp, FlareColors.BorderSubtle), RoundedCornerShape(4.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style =
        MaterialTheme.typography.labelSmall.copy(
          fontSize = 10.sp,
          fontWeight = FontWeight.SemiBold,
        ),
      color = FlareColors.TextSecondary,
    )
  }
}

@Composable
fun AssetHeader(
  asset: AssetIdentity,
  price: String,
  delta: String,
  positive: Boolean,
  modifier: Modifier = Modifier,
  badgeText: String? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AssetIcon(asset, Modifier.size(48.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(asset.name, style = MaterialTheme.typography.titleLarge)
        if (badgeText != null) {
          InstrumentBadge(badgeText)
        }
      }
      Text(
        asset.kind?.replaceFirstChar(Char::titlecase) ?: asset.symbol,
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.labelMedium,
      )
    }
  }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
  asset: AssetIdentity,
  price: String,
  delta: String,
  positive: Boolean,
  favorite: Boolean,
  onClick: () -> Unit,
  onFavorite: () -> Unit,
  modifier: Modifier = Modifier,
  badgeText: String? = null,
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
      AssetIcon(asset, Modifier.size(40.dp))
    }
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(asset.symbol, style = MaterialTheme.typography.labelLarge)
        if (badgeText != null) {
          Spacer(Modifier.width(6.dp))
          Box(
            modifier =
              Modifier.clip(RoundedCornerShape(4.dp))
                .background(FlareColors.Elevated)
                .border(BorderStroke(1.dp, FlareColors.BorderSubtle), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = badgeText,
              color = FlareColors.TextSecondary,
              style = MaterialTheme.typography.labelSmall,
              fontSize = 9.sp,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }
      Text(
        asset.detailLabel(),
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
          if (favorite) "Remove ${asset.symbol} from watchlist"
          else "Add ${asset.symbol} to watchlist",
        tint = if (favorite) colors.positive else colors.textTertiary,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
internal fun AssetIcon(asset: AssetIdentity, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.clip(CircleShape).background(FlareColors.Elevated),
    contentAlignment = Alignment.Center,
  ) {
    if (asset.iconUrl == null) {
      Text(assetMonogram(asset.symbol), style = MaterialTheme.typography.titleLarge)
    } else {
      SubcomposeAsyncImage(
        model = asset.iconUrl,
        contentDescription = "${asset.name} icon",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        loading = {
          Text(assetMonogram(asset.symbol), style = MaterialTheme.typography.titleLarge)
        },
        error = { Text(assetMonogram(asset.symbol), style = MaterialTheme.typography.titleLarge) },
        success = { SubcomposeAsyncImageContent() },
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

/**
 * Flare's authentic brand logo mark: three ascending slanted bars representing market momentum and
 * perpetual trading signals.
 */
@Composable
fun FlareLogo(
  modifier: Modifier = Modifier,
  color: Color = FlareColors.Positive,
) {
  Canvas(modifier = modifier) {
    val stroke = size.width * 0.105f
    for (i in 0..2) {
      val x = size.width * (0.15f + i * 0.26f)
      drawLine(
        color = color,
        start = Offset(x, size.height * 0.82f),
        end = Offset(x + size.width * 0.24f, size.height * (0.36f - i * 0.10f)),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Premium branded splash screen displayed during app cold start and while awaiting
 * biometric/passcode authentication when an account is locked.
 */
@Composable
fun FlareSplashScreen(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.fillMaxSize().background(FlareColors.Canvas),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      FlareLogo(
        modifier = Modifier.size(72.dp),
        color = FlareColors.Positive,
      )
      Spacer(Modifier.height(24.dp))
      Text(
        text = "flare",
        style =
          MaterialTheme.typography.displaySmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
          ),
        color = FlareColors.TextPrimary,
      )
      Spacer(Modifier.height(8.dp))
      Text(
        text = "DECIBEL PERPETUALS",
        style =
          MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.5.sp,
          ),
        color = FlareColors.TextTertiary,
      )
    }
  }
}
