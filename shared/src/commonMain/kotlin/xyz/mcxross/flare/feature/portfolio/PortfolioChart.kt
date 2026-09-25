package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.valentinilk.shimmer.shimmer
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.PortfolioChartPoint
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.TradingStreak
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSkeletonBox
import xyz.mcxross.flare.design.TimeRangeSelector
import xyz.mcxross.flare.design.rememberFlareShimmer

@Composable
fun PortfolioPerformanceChart(
  points: List<PortfolioChartPoint>,
  range: PortfolioChartRange,
  metric: PortfolioMetric,
  onRangeSelect: (PortfolioChartRange) -> Unit,
  onMetricSelect: (PortfolioMetric) -> Unit,
  modifier: Modifier = Modifier,
  loading: Boolean = false,
  error: String? = null,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    var choosingMetric by remember { mutableStateOf(false) }
    val values = points.map { point ->
      point.dataPoints ?: when (metric) {
        PortfolioMetric.EQUITY -> point.accountValue ?: point.value
        PortfolioMetric.PNL -> point.realizedPnl ?: point.value
      }
    }
    Box {
      TextButton({ choosingMetric = true }) { Text("${metric.label} ▾") }
      DropdownMenu(choosingMetric, { choosingMetric = false }) {
        PortfolioMetric.entries.forEach { option ->
          DropdownMenuItem(text = { Text(option.label) }, onClick = {
            choosingMetric = false
            onMetricSelect(option)
          })
        }
      }
    }
    if (values.isNotEmpty()) Text(formatBalance(values.last()), style = MaterialTheme.typography.displaySmall)
    if (points.isEmpty() && loading && error == null) {
      Column(Modifier.fillMaxWidth().height(240.dp).shimmer(rememberFlareShimmer()),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        FlareSkeletonBox(Modifier.fillMaxWidth(0.45f).height(40.dp))
        FlareSkeletonBox(Modifier.fillMaxWidth().height(160.dp))
      }
    } else if (points.size >= 2) {
      val isPositive = values.last() >= values.first()
      val lineColor = if (isPositive) FlareColors.Positive else FlareColors.Negative

      val model =
        remember(points, metric) {
          CartesianChartModel(
            LineCartesianLayerModel.build {
              series(x = points.indices.toList(), y = values)
            }
          )
        }

      val marker =
        rememberDefaultCartesianMarker(
          label =
            rememberTextComponent(
              style = MaterialTheme.typography.bodySmall.copy(color = FlareColors.TextPrimary)
            ),
          guideline = rememberLineComponent(fill = Fill(FlareColors.BorderStrong), thickness = 1.dp),
        )

      CartesianChartHost(
        chart =
          rememberCartesianChart(
            rememberLineCartesianLayer(
              lineProvider =
                LineCartesianLayer.LineProvider.series(
                  LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(lineColor)))
                )
            ),
            marker = marker,
            bottomAxis = HorizontalAxis.rememberBottom(label = null, tick = null, guideline = null, line = null),
            endAxis = VerticalAxis.rememberEnd(label = null, tick = null, guideline = null, line = null),
          ),
        model = model,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        modifier = Modifier.fillMaxWidth().height(240.dp),
      )
    } else {
      Box(
        modifier = Modifier.fillMaxWidth().height(240.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          when {
            error != null -> "History is temporarily unavailable"
            points.size == 1 -> "More history will appear as your account updates"
            else -> "No history for this period"
          },
          color = FlareColors.TextTertiary,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    TimeRangeSelector(PortfolioChartRange.entries, range, { it.label }, onRangeSelect)
    if (error != null) Text(
      if (points.isEmpty()) "Reconnecting automatically" else "Updating · showing your last history",
      color = FlareColors.TextTertiary, style = MaterialTheme.typography.bodySmall,
    )
    Text(
      if (metric == PortfolioMetric.EQUITY) "Net deposits and realized P&L. Excludes unrealized P&L."
      else "Realized P&L from completed trades.",
      Modifier.padding(top = 12.dp), color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
fun TradingRewardsCard(
  streak: TradingStreak?,
  amps: AmpsBreakdown?,
  tier: TierInfo?,
  modifier: Modifier = Modifier,
) {
  if (streak == null && amps == null && tier == null) return

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(FlareColors.Elevated, RoundedCornerShape(14.dp))
        .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        val streakCount = streak?.streakCount ?: 0
        Text(
          text = if (streakCount > 0) "$streakCount-day streak" else "No active streak",
          style = MaterialTheme.typography.labelLarge,
          color = if (streakCount > 0) FlareColors.Positive else FlareColors.TextPrimary,
        )
        streak?.graceDaysRemaining?.takeIf { it > 0 }?.let { grace ->
          Text(
            "· $grace grace left",
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextSecondary,
          )
        }
      }
      Text(
        text = tier?.let { "${it.tier} tier" } ?: "Trading rewards",
        style = MaterialTheme.typography.bodySmall,
        color = FlareColors.TextSecondary,
      )
    }

    amps?.let {
      Column(horizontalAlignment = Alignment.End) {
        Text(
          text = "${formatQuantity(it.totalAmps, 0)} Amps",
          style = MaterialTheme.typography.labelLarge,
          color = FlareColors.Positive,
        )
        it.rank?.let { rank ->
          Text(
            text = "Rank #$rank",
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextTertiary,
          )
        }
      }
    }
  }
}
