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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.PortfolioChartPoint
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.TradingStreak
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSegmentedControl
import xyz.mcxross.flare.design.TimeRangeSelector

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
  onRetry: () -> Unit = {},
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    FlareSegmentedControl(
      PortfolioMetric.entries, metric, onMetricSelect, { it.label })
    TimeRangeSelector(
      PortfolioChartRange.entries, range, { it.label }, onRangeSelect)

    Text("Trading account history", style = MaterialTheme.typography.labelMedium)
    Text(
      if (metric == PortfolioMetric.EQUITY) "Net deposits and realized P&L. Excludes unrealized P&L."
      else "Realized P&L from completed trades.",
      color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
    )
    if (loading) {
      androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
    } else if (error != null) {
      Text(error, color = FlareColors.TextSecondary)
      androidx.compose.material3.TextButton(onRetry) { Text("Retry") }
    } else if (points.size >= 2) {
      val values = points.map { pt ->
        when (metric) {
          PortfolioMetric.EQUITY -> pt.accountValue ?: pt.value
          PortfolioMetric.PNL -> pt.realizedPnl ?: pt.value
        }
      }
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
            bottomAxis = HorizontalAxis.rememberBottom(label = null, tick = null, guideline = null),
            endAxis = VerticalAxis.rememberEnd(label = null, tick = null, guideline = null),
          ),
        model = model,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        modifier = Modifier.fillMaxWidth().height(160.dp),
      )
    } else {
      Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "No history for this period",
          color = FlareColors.TextTertiary,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
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
