package xyz.mcxross.flare.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

private val PillHeight = 32.dp
private val ChipPadding = 14.dp

/**
 * Filters one level below a [FlareSegmentedControl]. The parent selects with a solid pill on a
 * track; these select with a single hairline pill that glides between chips and takes each chip's
 * width, so the two levels read as related but clearly ranked.
 *
 * [indicatorPosition] lets a pager drive the pill mid-swipe (fractional chip index); without it the
 * pill springs to [selectedIndex].
 */
@Composable
fun FlareFilterChips(
  labels: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  indicatorPosition: (() -> Float)? = null,
  scrollState: ScrollState = rememberScrollState(),
) {
  val density = LocalDensity.current
  // Chip bounds in the row's content space, captured as each chip is placed.
  val starts = remember(labels) { mutableStateListOf(*Array(labels.size) { 0f }) }
  val widths = remember(labels) { mutableStateListOf(*Array(labels.size) { 0f }) }
  val settled by animateFloatAsState(
    selectedIndex.toFloat(),
    spring(stiffness = Spring.StiffnessMediumLow),
    label = "filterPill",
  )
  LaunchedEffect(selectedIndex, labels) {
    val start = starts.getOrNull(selectedIndex) ?: return@LaunchedEffect
    val gutter = with(density) { 48.dp.toPx() }
    scrollState.animateScrollTo((start - gutter).toInt().coerceAtLeast(0))
  }
  Row(
    modifier
      .fillMaxWidth()
      .horizontalScroll(scrollState)
      .padding(contentPadding)
      .drawBehind {
        if (labels.isEmpty() || widths.all { it == 0f }) return@drawBehind
        val position = (indicatorPosition?.invoke() ?: settled).coerceIn(0f, labels.lastIndex.toFloat())
        val from = floor(position).toInt()
        val to = (from + 1).coerceAtMost(labels.lastIndex)
        val fraction = position - from
        val x = starts[from] + (starts[to] - starts[from]) * fraction
        val width = widths[from] + (widths[to] - widths[from]) * fraction
        val height = PillHeight.toPx()
        val stroke = 1.dp.toPx()
        drawRoundRect(
          FlareColors.BorderDefault,
          topLeft = Offset(x + stroke / 2, (size.height - height) / 2 + stroke / 2),
          size = Size(width - stroke, height - stroke),
          cornerRadius = CornerRadius(height / 2),
          style = Stroke(stroke),
        )
      },
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    labels.forEachIndexed { index, label ->
      val selected = index == selectedIndex
      val color by animateColorAsState(if (selected) FlareColors.TextPrimary else FlareColors.TextTertiary)
      Box(
        Modifier.onPlaced {
          starts[index] = it.positionInParent().x
          widths[index] = it.size.width.toFloat()
        }
          .heightIn(min = 48.dp)
          .selectable(selected = selected, role = Role.Tab) { onSelect(index) }
          .padding(horizontal = ChipPadding),
        contentAlignment = Alignment.Center,
      ) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}
